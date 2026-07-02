package com.bannerbound.core.api.walls;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * {@link SavedData} for the walls system: each settlement's frozen {@link WallPlan}, its
 * design library (Phase 5), and — crucially — its <b>built-wall set</b>: every position where
 * a wall block was actually PLACED into the blueprint (by hand via the place-event hook in
 * {@code WallEvents}, by builders inline in Phase 4). This is the authoritative "this block IS
 * settlement wall" memory: it survives plan cancellation and re-layout, so a wall designed
 * out of terrain blocks (dirt!) can never be mistaken for a hill by the ground walk, no
 * matter what happened to the plan since. Positions leave the set when their block breaks
 * (event) or is found to be air during {@link #reconcile} (explosions, pre-event edits).
 *
 * <p>Kept separate from {@code SettlementData} so the piece arrays, design palettes and
 * position sets don't bloat the frequently-saved settlement blob — same split as
 * {@code ChunkBeautyData}.
 */
@ApiStatus.Internal
public class WallData extends SavedData {
    private static final String DATA_NAME = "bannerbound_walls";

    private final Map<UUID, WallPlan> plans = new HashMap<>();
    /** Player-authored designs per settlement (Phase 5 fills this; defaults never live here). */
    private final Map<UUID, List<WallDesign>> libraries = new HashMap<>();
    /** Positions where wall blocks were actually placed and still stand. */
    private final Map<UUID, LongOpenHashSet> builtWalls = new HashMap<>();
    /** Player-marked gate slots: packed (x, 0, z) segment-slot starts from the preview. Input
     *  to the layout engine; anchors that stop matching a slot after expansion are ignored. */
    private final Map<UUID, LongOpenHashSet> gateAnchors = new HashMap<>();
    /** Transient pos→expected-state cache per settlement, invalidated on plan change. Lets the
     *  per-block-place event hook check membership in O(1) instead of re-expanding the plan. */
    private final transient Map<UUID, Long2ObjectMap<BlockState>> blueprintCache = new HashMap<>();

    public WallData() {
    }

    public static WallData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<WallData> factory() {
        return new Factory<>(WallData::new, WallData::load);
    }

    @Nullable
    public WallPlan plan(UUID settlementId) {
        return plans.get(settlementId);
    }

    public void setPlan(UUID settlementId, @Nullable WallPlan plan) {
        if (plan == null) {
            plans.remove(settlementId);
        } else {
            plans.put(settlementId, plan);
        }
        blueprintCache.remove(settlementId);
        setDirty();
    }

    /** Cached pos → expected-state expansion of the committed plan (empty map if no plan).
     *  Connections are BAKED (WallConnectivity, design-only neighbors) so the expected states
     *  are the EXACT final wall states — builders place them verbatim, ghosts show them
     *  verbatim, player placements snap to them (playtest 2026-06-12). */
    public Long2ObjectMap<BlockState> blueprint(ServerLevel level, UUID settlementId,
                                                Function<String, WallDesign> designs) {
        return blueprintCache.computeIfAbsent(settlementId, id -> {
            WallPlan plan = plans.get(id);
            if (plan == null) return new Long2ObjectOpenHashMap<>();
            return WallConnectivity.bake(plan.buildBlueprint(designs), level);
        });
    }

    // ─── Built-wall tracking ────────────────────────────────────────────────────────────────

    /** Live set of positions whose blocks ARE settlement wall (placed, still standing). */
    public LongSet builtWall(UUID settlementId) {
        return builtWalls.computeIfAbsent(settlementId, k -> new LongOpenHashSet());
    }

    public void markBuilt(UUID settlementId, long packedPos) {
        if (builtWalls.computeIfAbsent(settlementId, k -> new LongOpenHashSet()).add(packedPos)) {
            setDirty();
        }
    }

    public void clearBuilt(UUID settlementId, long packedPos) {
        LongOpenHashSet set = builtWalls.get(settlementId);
        if (set != null && set.remove(packedPos)) {
            setDirty();
        }
    }

    /**
     * Re-syncs the built-wall set against the world: blueprint positions whose block already
     * matches are marked built (backfills walls that predate placement tracking; self-heals
     * anything a hook missed), and built positions that are now air are dropped (explosions,
     * non-event removals). Cheap — a few thousand block reads — and run on construct/status,
     * never on a tick path.
     */
    public void reconcile(ServerLevel level, UUID settlementId, Function<String, WallDesign> designs) {
        Long2ObjectMap<BlockState> blueprint = blueprint(level, settlementId, designs);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Long2ObjectMap.Entry<BlockState> entry : blueprint.long2ObjectEntrySet()) {
            long packed = entry.getLongKey();
            cursor.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            if (level.getBlockState(cursor).is(entry.getValue().getBlock())) {
                markBuilt(settlementId, packed);
            }
        }
        LongOpenHashSet built = builtWalls.get(settlementId);
        if (built != null) {
            LongIterator iterator = built.iterator();
            boolean changed = false;
            while (iterator.hasNext()) {
                long packed = iterator.nextLong();
                cursor.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
                if (level.getBlockState(cursor).isAir()) {
                    iterator.remove();
                    changed = true;
                }
            }
            if (changed) {
                setDirty();
            }
        }
    }

    /** Live set of player-marked gate anchors (packed (x, 0, z) slot starts). */
    public LongSet gateAnchors(UUID settlementId) {
        return gateAnchors.computeIfAbsent(settlementId, k -> new LongOpenHashSet());
    }

    /** Player wall-top refinements (Phase 5.5): packed slot-start anchor → ABSOLUTE top Y.
     *  Applied after the auto top-alignment chain; anchors that stop matching a slot after
     *  expansion are silently ignored (same stability rule as gates). */
    private final Map<UUID, Map<Long, Integer>> topOverrides = new HashMap<>();

    public Map<Long, Integer> topOverrides(UUID settlementId) {
        return topOverrides.computeIfAbsent(settlementId, k -> new HashMap<>());
    }

    public void setTopOverride(UUID settlementId, long packedAnchor, @Nullable Integer topY) {
        Map<Long, Integer> map = topOverrides(settlementId);
        if (topY == null) map.remove(packedAnchor);
        else map.put(packedAnchor, topY);
        blueprintCache.remove(settlementId);
        setDirty();
    }

    /** Per-piece design VARIANT overrides (kind-aware refine anchor → LIBRARY design id).
     *  Variants are PLAYER-SAVED designs of the same kind + footprint ("variants shouldn't
     *  be auto-derived", playtest 2026-06-12); absent = the active (base) design. */
    private final Map<UUID, Map<Long, String>> variantOverrides = new HashMap<>();

    public Map<Long, String> variantOverrides(UUID settlementId) {
        return variantOverrides.computeIfAbsent(settlementId, k -> new HashMap<>());
    }

    public void setVariantOverride(UUID settlementId, long packedAnchor, @Nullable String designId) {
        Map<Long, String> map = variantOverrides(settlementId);
        if (designId == null || designId.isEmpty()) map.remove(packedAnchor);
        else map.put(packedAnchor, designId);
        blueprintCache.remove(settlementId);
        setDirty();
    }

    /** Per-piece "no foundation" refinements: pieces whose bottom-course continuation is
     *  suppressed (kind-aware refine anchors, playtest 2026-06-12). */
    private final Map<UUID, LongOpenHashSet> foundationOff = new HashMap<>();

    public LongSet foundationOff(UUID settlementId) {
        return foundationOff.computeIfAbsent(settlementId, k -> new LongOpenHashSet());
    }

    /** Toggles; returns true if continuation is now OFF for that piece. */
    public boolean toggleFoundationOff(UUID settlementId, long packedAnchor) {
        LongOpenHashSet set = foundationOff.computeIfAbsent(settlementId, k -> new LongOpenHashSet());
        boolean nowOff = set.add(packedAnchor);
        if (!nowOff) {
            set.remove(packedAnchor);
        }
        blueprintCache.remove(settlementId);
        setDirty();
        return nowOff;
    }

    /** Toggles a gate anchor; returns true if it is now SET. */
    public boolean toggleGateAnchor(UUID settlementId, long packedAnchor) {
        LongOpenHashSet set = gateAnchors.computeIfAbsent(settlementId, k -> new LongOpenHashSet());
        boolean added = set.add(packedAnchor);
        if (!added) {
            set.remove(packedAnchor);
        }
        setDirty();
        return added;
    }

    public List<WallDesign> library(UUID settlementId) {
        return libraries.computeIfAbsent(settlementId, k -> new ArrayList<>());
    }

    /** Active design id per kind (key = settlementId + kind). Null/absent = built-in default. */
    private final Map<UUID, Map<WallDesign.Kind, String>> activeIds = new HashMap<>();

    @Nullable
    public String activeId(UUID settlementId, WallDesign.Kind kind) {
        Map<WallDesign.Kind, String> map = activeIds.get(settlementId);
        return map == null ? null : map.get(kind);
    }

    public void setActiveId(UUID settlementId, WallDesign.Kind kind, @Nullable String designId) {
        Map<WallDesign.Kind, String> map = activeIds.computeIfAbsent(settlementId,
            k -> new java.util.EnumMap<>(WallDesign.Kind.class));
        if (designId == null) map.remove(kind);
        else map.put(kind, designId);
        blueprintCache.remove(settlementId);
        setDirty();
    }

    /** Adds or replaces (by id) a design in the settlement's library. */
    public void upsertDesign(UUID settlementId, WallDesign design) {
        List<WallDesign> library = library(settlementId);
        library.removeIf(d -> d.id().equals(design.id()));
        library.add(design);
        blueprintCache.remove(settlementId);
        setDirty();
    }

    /** Removes a library design; clears the active pointer if it pointed at it. */
    public void removeDesign(UUID settlementId, String designId) {
        boolean removed = library(settlementId).removeIf(d -> d.id().equals(designId));
        if (removed) {
            Map<WallDesign.Kind, String> active = activeIds.get(settlementId);
            if (active != null) {
                active.values().removeIf(designId::equals);
            }
            blueprintCache.remove(settlementId);
            setDirty();
        }
    }

    /** Library design by id, or null (caller falls back to the built-in defaults). */
    @Nullable
    public WallDesign libraryDesign(UUID settlementId, String designId) {
        for (WallDesign design : library(settlementId)) {
            if (design.id().equals(designId)) return design;
        }
        return null;
    }

    /** Designer working copies, one per kind — autosaved when the designer closes so Escape
     *  never loses work (playtest 2026-06-12). Never activated, never enter the resolver. */
    private final Map<UUID, Map<WallDesign.Kind, WallDesign>> drafts = new HashMap<>();

    @Nullable
    public WallDesign draft(UUID settlementId, WallDesign.Kind kind) {
        Map<WallDesign.Kind, WallDesign> map = drafts.get(settlementId);
        return map == null ? null : map.get(kind);
    }

    public void setDraft(UUID settlementId, WallDesign design) {
        drafts.computeIfAbsent(settlementId, k -> new java.util.EnumMap<>(WallDesign.Kind.class))
            .put(design.kind(), design);
        setDirty();
    }

    /** Settlement dissolved — drop everything walls-related. */
    public void remove(UUID settlementId) {
        boolean removed = plans.remove(settlementId) != null;
        removed |= libraries.remove(settlementId) != null;
        removed |= builtWalls.remove(settlementId) != null;
        removed |= gateAnchors.remove(settlementId) != null;
        removed |= activeIds.remove(settlementId) != null;
        removed |= topOverrides.remove(settlementId) != null;
        removed |= variantOverrides.remove(settlementId) != null;
        removed |= foundationOff.remove(settlementId) != null;
        removed |= drafts.remove(settlementId) != null;
        blueprintCache.remove(settlementId);
        if (removed) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        Set<UUID> ids = new HashSet<>();
        ids.addAll(plans.keySet());
        ids.addAll(libraries.keySet());
        ids.addAll(builtWalls.keySet());
        ids.addAll(gateAnchors.keySet());
        ids.addAll(drafts.keySet());
        ListTag list = new ListTag();
        for (UUID id : ids) {
            CompoundTag c = new CompoundTag();
            c.putUUID("Settlement", id);
            WallPlan plan = plans.get(id);
            if (plan != null) {
                c.put("Plan", plan.save());
            }
            List<WallDesign> library = libraries.get(id);
            if (library != null && !library.isEmpty()) {
                ListTag designs = new ListTag();
                for (WallDesign design : library) {
                    designs.add(design.save());
                }
                c.put("Library", designs);
            }
            LongOpenHashSet built = builtWalls.get(id);
            if (built != null && !built.isEmpty()) {
                c.putLongArray("Built", built.toLongArray());
            }
            LongOpenHashSet gates = gateAnchors.get(id);
            if (gates != null && !gates.isEmpty()) {
                c.putLongArray("Gates", gates.toLongArray());
            }
            Map<WallDesign.Kind, String> active = activeIds.get(id);
            if (active != null) {
                for (Map.Entry<WallDesign.Kind, String> e : active.entrySet()) {
                    c.putString("Active" + e.getKey().name(), e.getValue());
                }
            }
            Map<Long, Integer> tops = topOverrides.get(id);
            if (tops != null && !tops.isEmpty()) {
                long[] anchors = new long[tops.size()];
                int[] values = new int[tops.size()];
                int i = 0;
                for (Map.Entry<Long, Integer> e : tops.entrySet()) {
                    anchors[i] = e.getKey();
                    values[i] = e.getValue();
                    i++;
                }
                c.putLongArray("TopAnchors", anchors);
                c.putIntArray("TopValues", values);
            }
            Map<Long, String> variants = variantOverrides.get(id);
            if (variants != null && !variants.isEmpty()) {
                long[] anchors = new long[variants.size()];
                ListTag variantIds = new ListTag();
                int i = 0;
                for (Map.Entry<Long, String> e : variants.entrySet()) {
                    anchors[i] = e.getKey();
                    variantIds.add(net.minecraft.nbt.StringTag.valueOf(e.getValue()));
                    i++;
                }
                c.putLongArray("VariantAnchors", anchors);
                c.put("VariantIds", variantIds);
            }
            LongOpenHashSet noFnd = foundationOff.get(id);
            if (noFnd != null && !noFnd.isEmpty()) {
                c.putLongArray("FoundationOff", noFnd.toLongArray());
            }
            Map<WallDesign.Kind, WallDesign> draftMap = drafts.get(id);
            if (draftMap != null && !draftMap.isEmpty()) {
                ListTag draftList = new ListTag();
                for (WallDesign draft : draftMap.values()) {
                    draftList.add(draft.save());
                }
                c.put("Drafts", draftList);
            }
            list.add(c);
        }
        tag.put("Settlements", list);
        return tag;
    }

    public static WallData load(CompoundTag tag, HolderLookup.Provider provider) {
        WallData data = new WallData();
        HolderGetter<Block> blocks = provider.lookupOrThrow(Registries.BLOCK);
        ListTag list = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            UUID settlementId = c.getUUID("Settlement");
            if (c.contains("Plan", Tag.TAG_COMPOUND)) {
                data.plans.put(settlementId, WallPlan.load(c.getCompound("Plan")));
            }
            if (c.contains("Library", Tag.TAG_LIST)) {
                ListTag designs = c.getList("Library", Tag.TAG_COMPOUND);
                List<WallDesign> library = new ArrayList<>();
                for (int j = 0; j < designs.size(); j++) {
                    library.add(WallDesign.load(designs.getCompound(j), blocks));
                }
                data.libraries.put(settlementId, library);
            }
            if (c.contains("Built")) {
                data.builtWalls.put(settlementId, new LongOpenHashSet(c.getLongArray("Built")));
            }
            if (c.contains("Gates")) {
                data.gateAnchors.put(settlementId, new LongOpenHashSet(c.getLongArray("Gates")));
            }
            for (WallDesign.Kind kind : WallDesign.Kind.values()) {
                String key = "Active" + kind.name();
                if (c.contains(key)) {
                    data.activeIds.computeIfAbsent(settlementId,
                        k -> new java.util.EnumMap<>(WallDesign.Kind.class))
                        .put(kind, c.getString(key));
                }
            }
            if (c.contains("TopAnchors")) {
                long[] anchors = c.getLongArray("TopAnchors");
                int[] values = c.getIntArray("TopValues");
                Map<Long, Integer> tops = new HashMap<>();
                for (int j = 0; j < anchors.length && j < values.length; j++) {
                    tops.put(anchors[j], values[j]);
                }
                data.topOverrides.put(settlementId, tops);
            }
            // (Legacy "VariantValues" int overrides from the auto-derived era are dropped.)
            if (c.contains("VariantAnchors") && c.contains("VariantIds", Tag.TAG_LIST)) {
                long[] anchors = c.getLongArray("VariantAnchors");
                ListTag variantIds = c.getList("VariantIds", Tag.TAG_STRING);
                Map<Long, String> variants = new HashMap<>();
                for (int j = 0; j < anchors.length && j < variantIds.size(); j++) {
                    variants.put(anchors[j], variantIds.getString(j));
                }
                data.variantOverrides.put(settlementId, variants);
            }
            if (c.contains("FoundationOff")) {
                data.foundationOff.put(settlementId,
                    new LongOpenHashSet(c.getLongArray("FoundationOff")));
            }
            if (c.contains("Drafts", Tag.TAG_LIST)) {
                ListTag draftList = c.getList("Drafts", Tag.TAG_COMPOUND);
                Map<WallDesign.Kind, WallDesign> draftMap =
                    new java.util.EnumMap<>(WallDesign.Kind.class);
                for (int j = 0; j < draftList.size(); j++) {
                    WallDesign draft = WallDesign.load(draftList.getCompound(j), blocks);
                    draftMap.put(draft.kind(), draft);
                }
                data.drafts.put(settlementId, draftMap);
            }
        }
        return data;
    }
}
