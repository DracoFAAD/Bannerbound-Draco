package com.bannerbound.core.api.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-side registry of every active {@link BlockSelection} (across all settlements). Persisted
 * as {@link SavedData} on the overworld so jobs survive restarts. Lookup is by rod UUID; overlap
 * checks scan all selections (fine for typical settlement counts).
 * <p>
 * Mutators always call {@link #setDirty()} so Minecraft persists on the next save tick.
 */
public class BlockSelectionRegistry extends SavedData {
    private static final String DATA_NAME = "bannerbound_block_selections";

    // LinkedHashMap preserves insertion order — diggers process selections in queue order
    // (oldest commit first), so the player's first marked area gets cleared before later ones.
    private final Map<UUID, BlockSelection> selections = new LinkedHashMap<>();
    /** Monotonic counter bumped on every register/unregister/markCompleted. Workers compare their
     *  last-seen version to detect "did anything change?" without diffing the whole map. Used to
     *  force-rescan when a fresh selection is committed mid-cooldown — otherwise the digger
     *  might walk all the way back to the campfire before noticing the new job. */
    private long version = 0L;

    public long version() { return version; }

    public BlockSelectionRegistry() {
    }

    public static BlockSelectionRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<BlockSelectionRegistry> factory() {
        return new Factory<>(BlockSelectionRegistry::new, BlockSelectionRegistry::load);
    }

    // ─── Mutators ────────────────────────────────────────────────────────────────────────────

    /** Register or replace the selection for {@code selection.rodId}. */
    public void register(BlockSelection selection) {
        selections.put(selection.rodId(), selection);
        version++;
        setDirty();
    }

    /** Remove the selection for {@code rodId}, returning what was removed (or null). */
    public BlockSelection unregister(UUID rodId) {
        BlockSelection removed = selections.remove(rodId);
        if (removed != null) {
            version++;
            setDirty();
        }
        return removed;
    }

    /** Mark the selection for {@code rodId} as completed. No-op if absent. */
    public void markCompleted(UUID rodId) {
        BlockSelection s = selections.get(rodId);
        if (s == null || s.completed()) return;
        selections.put(rodId, s.withCompleted(true));
        version++;
        setDirty();
    }

    // ─── Queries ─────────────────────────────────────────────────────────────────────────────

    public BlockSelection get(UUID rodId) {
        return selections.get(rodId);
    }

    public Collection<BlockSelection> getAll() {
        return Collections.unmodifiableCollection(selections.values());
    }

    /** Selections owned by {@code settlementId} that contain {@code pos}. Used by the
     *  shift-left-click delete gesture so the player can wipe an overlay by clicking inside it. */
    public List<BlockSelection> findContaining(net.minecraft.core.BlockPos pos, UUID settlementId) {
        List<BlockSelection> out = new ArrayList<>();
        for (BlockSelection s : selections.values()) {
            if (!s.settlementId().equals(settlementId)) continue;
            if (s.contains(pos)) out.add(s);
        }
        return out;
    }

    /** Selections owned by a given settlement (used by work goals to find their jobs). */
    public List<BlockSelection> getForSettlement(UUID settlementId) {
        List<BlockSelection> out = new ArrayList<>();
        for (BlockSelection s : selections.values()) {
            if (s.settlementId().equals(settlementId)) out.add(s);
        }
        return out;
    }

    /**
     * True if {@code candidate} overlaps any existing selection other than the one identified by
     * {@code excludeRodId}. Used at B-click to reject overlapping new selections (rod allowed to
     * "re-place" its own selection without false-rejecting itself). This is the strict variant
     * used by the Foreman's Rod — every overlap counts as a conflict, regardless of kind.
     */
    public boolean anyOverlapExcluding(BlockSelection candidate, UUID excludeRodId) {
        return anyOverlapExcludingAll(candidate, java.util.Set.of(excludeRodId));
    }

    /**
     * Set-excluding variant of {@link #anyOverlapExcluding}: true if {@code candidate} overlaps any
     * existing selection whose rod id is NOT in {@code excludeRodIds}. Used by the Foreman's Rod
     * "join overlapping fields" flow, where the grown union legitimately covers several existing
     * fields (the ones being merged) and must only be tested against the OTHER selections.
     */
    public boolean anyOverlapExcludingAll(BlockSelection candidate, java.util.Set<UUID> excludeRodIds) {
        for (BlockSelection s : selections.values()) {
            if (excludeRodIds.contains(s.rodId())) continue;
            // Completed selections are invisible (the renderer skips them) and finished — they must
            // not block a fresh selection in the same spot, or the player gets a phantom "overlap".
            if (s.completed()) continue;
            if (s.intersects(candidate)) return true;
        }
        return false;
    }

    /**
     * Returns the first existing selection that conflicts with {@code candidate}, or {@code null}
     * if the candidate is free to commit. Conflict rules:
     * <ul>
     *   <li>Selections sharing the {@code excludeRodId} are ignored (you can re-place yourself).</li>
     *   <li>Two HOME selections sharing the same {@code homeId} are NOT a conflict — overlap is
     *       allowed within a single home (the "Super Glue" union-of-boxes behaviour).</li>
     *   <li>Any other intersection IS a conflict.</li>
     * </ul>
     * The caller (Home Marker Rod) uses the returned conflicting selection to name the offending
     * home / workstation in the user-facing reject message.
     */
    public BlockSelection firstConflictingOverlap(BlockSelection candidate, UUID excludeRodId) {
        for (BlockSelection s : selections.values()) {
            if (s.rodId().equals(excludeRodId)) continue;
            if (!s.intersects(candidate)) continue;
            if (candidate.sameHomeAs(s)) continue;     // allowed: same-home overlap
            if (candidate.sameWorkshopAs(s)) continue; // allowed: same-workshop overlap
            return s;
        }
        return null;
    }

    /** Every selection that belongs to the given workshop (the workshop twin of
     *  {@link #findByHome}; the workshop id rides in the {@code homeId} slot). */
    public List<BlockSelection> findByWorkshop(UUID workshopId) {
        if (workshopId == null || BlockSelection.NO_HOME.equals(workshopId)) return Collections.emptyList();
        List<BlockSelection> out = new ArrayList<>();
        for (BlockSelection s : selections.values()) {
            if (s.kind() == BlockSelection.Kind.WORKSHOP && workshopId.equals(s.homeId())) {
                out.add(s);
            }
        }
        return out;
    }

    /** Removes every selection belonging to the given workshop in one batch (workshop deleted). */
    public int removeAllByWorkshop(UUID workshopId) {
        if (workshopId == null || BlockSelection.NO_HOME.equals(workshopId)) return 0;
        int removed = 0;
        var it = selections.entrySet().iterator();
        while (it.hasNext()) {
            BlockSelection s = it.next().getValue();
            if (s.kind() == BlockSelection.Kind.WORKSHOP && workshopId.equals(s.homeId())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            version++;
            setDirty();
        }
        return removed;
    }

    /** Every selection that belongs to the given home. Used by {@code HouseAppealData} to walk
     *  the union of boxes when scoring, and by the rod's shift-left-click box-removal flow to
     *  enumerate which boxes a clicked position belongs to. */
    public List<BlockSelection> findByHome(UUID homeId) {
        if (homeId == null || BlockSelection.NO_HOME.equals(homeId)) return Collections.emptyList();
        List<BlockSelection> out = new ArrayList<>();
        for (BlockSelection s : selections.values()) {
            if (s.kind() == BlockSelection.Kind.HOME && homeId.equals(s.homeId())) {
                out.add(s);
            }
        }
        return out;
    }

    /** Removes every selection belonging to the given home in one batch. Called when a House
     *  Block is broken so the home's box set doesn't leak. Cheap — single pass. */
    public int removeAllByHome(UUID homeId) {
        if (homeId == null || BlockSelection.NO_HOME.equals(homeId)) return 0;
        int removed = 0;
        var it = selections.entrySet().iterator();
        while (it.hasNext()) {
            BlockSelection s = it.next().getValue();
            if (s.kind() == BlockSelection.Kind.HOME && homeId.equals(s.homeId())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            version++;
            setDirty();
        }
        return removed;
    }

    // ─── SavedData ────────────────────────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (BlockSelection s : selections.values()) {
            list.add(s.save());
        }
        tag.put("Selections", list);
        return tag;
    }

    public static BlockSelectionRegistry load(CompoundTag tag, HolderLookup.Provider provider) {
        BlockSelectionRegistry reg = new BlockSelectionRegistry();
        ListTag list = tag.getList("Selections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            BlockSelection s = BlockSelection.load(list.getCompound(i));
            reg.selections.put(s.rodId(), s);
        }
        return reg;
    }
}
