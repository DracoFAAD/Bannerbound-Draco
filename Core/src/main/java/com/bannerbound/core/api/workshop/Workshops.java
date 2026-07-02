package com.bannerbound.core.api.workshop;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.bannerbound.core.api.settlement.HouseAppealData;
import com.bannerbound.core.api.settlement.Homes;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-side service for crafter Workshops (see {@code CRAFTER_PLAN.md}): validation against the
 * box union (reusing the housing flood-fill/connectivity code in {@link com.bannerbound.core.api.settlement.Homes}), type
 * derivation from contained work blocks, and position → workshop lookup. Validation runs on commit,
 * on menu open, and when a crafter looks for work — workshops have no anchor block entity to tick.
 */
public final class Workshops {
    /** Blocks that count as workshop storage (chest/barrel; expansions extend via the tag). */
    public static final TagKey<Block> STORAGE_TAG = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("bannerbound", "workshop_storage"));

    private Workshops() {
    }

    /**
     * Full validation of {@code workshop} against its current selection union: connectivity,
     * ≥1 work block, ≥1 storage block, and <b>reachability</b> — citizens must be able to walk
     * from outside the marking to a work block and to storage (doors/gates/non-colliding blocks
     * pass; a floating or walled-off workshop fails). Workshops deliberately do NOT require
     * enclosure: open-air smithy-porch builds are legitimate (unlike houses, which keep their
     * walls-and-roof rule). Only REACHABLE work/storage blocks are cached — capacity counts
     * stations a citizen can actually stand at. Updates status/caches/derived type in place.
     */
    public static Workshop.Status validate(ServerLevel sl, Workshop workshop) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id());
        if (boxes.isEmpty()) {
            workshop.applyValidation(Workshop.Status.UNMARKED, List.of(), List.of(),
                WorkBlockRegistry.TYPE_NONE);
            workshop.setCachedAppeal(0.0, null);
            return workshop.status();
        }

        // Workplace appeal: score the box union under the owning settlement's styles (the same
        // generic scorer homes use). Cached on the workshop so the crafter XP multiplier, menu
        // and floating labels read it without re-walking blocks — refreshed at the cadence the
        // status caches already pay for (commit / menu open / crafter scan / revalidator sweep).
        Settlement owner = SettlementData.get(sl.getServer().overworld())
            .getById(boxes.get(0).settlementId());
        if (owner != null) {
            HouseAppealData.AppealSnapshot snap = HouseAppealData.scoreUnion(sl, owner, boxes);
            workshop.setCachedAppeal(snap.score(), snap.beauty());
        }

        Set<BlockPos> marked = Homes.collectMarkedSolids(sl, boxes);

        // Scan the marked solids once for work blocks + storage blocks + the distinct type set.
        List<BlockPos> work = new ArrayList<>();
        List<BlockPos> storage = new ArrayList<>();
        Set<String> typeIds = new LinkedHashSet<>();
        for (BlockPos p : marked) {
            BlockState state = sl.getBlockState(p);
            WorkBlockRegistry.WorkBlockDef def = WorkBlockRegistry.of(state);
            if (def != null) {
                // Multiblock stations count once: only the anchor/master cell adds a work-slot, the
                // shell cells are recognized as the station but contribute no capacity (and aren't
                // storage). Single-block stations have no anchorTest and always count.
                if (def.countsAt(state)) {
                    work.add(p.immutable());
                    typeIds.add(def.workshopTypeId());
                }
            } else if (state.is(STORAGE_TAG)) {
                storage.add(p.immutable());
            }
        }
        String derived = typeIds.isEmpty() ? WorkBlockRegistry.TYPE_NONE
            : typeIds.size() == 1 ? typeIds.iterator().next()
            : WorkBlockRegistry.TYPE_MIXED;

        if (marked.isEmpty()) {
            workshop.applyValidation(Workshop.Status.UNMARKED, work, storage, derived);
            return workshop.status();
        }
        if (!Homes.isConnected(marked)) {
            workshop.applyValidation(Workshop.Status.DISCONNECTED, work, storage, derived);
            return workshop.status();
        }
        if (work.isEmpty()) {
            workshop.applyValidation(Workshop.Status.NO_WORK_BLOCK, work, storage, derived);
            return workshop.status();
        }
        if (storage.isEmpty()) {
            workshop.applyValidation(Workshop.Status.NO_STORAGE, work, storage, derived);
            return workshop.status();
        }

        // Reachability: walk the standable-cell graph from outside the marking and keep only the
        // work/storage blocks a citizen can stand next to.
        Set<BlockPos> reached = reachableCells(sl, marked);
        List<BlockPos> reachableWork = filterAdjacent(work, reached);
        List<BlockPos> reachableStorage = filterAdjacent(storage, reached);
        if (reachableWork.isEmpty()) {
            // Distinguish "roof too low" (standing spots exist but lack head clearance — the
            // playtest's confusion) from a genuinely blocked-off / floating work block.
            Workshop.Status reason = lacksHeadroomOnly(sl, work)
                ? Workshop.Status.NO_HEADROOM
                : Workshop.Status.WORK_BLOCK_UNREACHABLE;
            workshop.applyValidation(reason, reachableWork, reachableStorage, derived);
            return workshop.status();
        }
        if (reachableStorage.isEmpty()) {
            workshop.applyValidation(Workshop.Status.STORAGE_UNREACHABLE, reachableWork,
                reachableStorage, derived);
            return workshop.status();
        }
        Workshop.Status extraRequirement = WorkBlockRegistry.validateRequirements(
            typeIds, sl, workshop, marked, reachableWork, reachableStorage);
        if (extraRequirement != null) {
            workshop.applyValidation(extraRequirement, reachableWork, reachableStorage, derived);
            return workshop.status();
        }
        workshop.applyValidation(Workshop.Status.VALID, reachableWork, reachableStorage, derived);
        return workshop.status();
    }

    // ─── Reachability (standable-cell BFS) ──────────────────────────────────────────────────────

    /**
     * All walkable feet-cells reachable from OUTSIDE the marking. Seeds are standable cells on the
     * side ring just outside the marked bbox (the connection to the world — a floating workshop
     * has no standable ring, so nothing is reachable); steps go to the 4 horizontal neighbours at
     * dy −1/0/+1 (walk, step up, step down). "Standable" = feet+head passable with support below;
     * doors and (rope) fence gates count as passable so citizens path through them.
     */
    private static Set<BlockPos> reachableCells(ServerLevel sl, Set<BlockPos> marked) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : marked) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        // Search region: bbox + 1 ring on the sides, + 1 below / above for step-down/step-up room.
        int rMinX = minX - 1, rMaxX = maxX + 1;
        int rMinY = minY - 1, rMaxY = maxY + 1;
        int rMinZ = minZ - 1, rMaxZ = maxZ + 1;

        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        Set<BlockPos> reached = new java.util.HashSet<>();
        // Seeds: standable cells on the side ring (x or z exactly one outside the marked bbox).
        for (int y = rMinY; y <= rMaxY; y++) {
            for (int x = rMinX; x <= rMaxX; x++) {
                seed(sl, new BlockPos(x, y, rMinZ), queue, reached);
                seed(sl, new BlockPos(x, y, rMaxZ), queue, reached);
            }
            for (int z = rMinZ; z <= rMaxZ; z++) {
                seed(sl, new BlockPos(rMinX, y, z), queue, reached);
                seed(sl, new BlockPos(rMaxX, y, z), queue, reached);
            }
        }
        while (!queue.isEmpty()) {
            BlockPos c = queue.poll();
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos n = c.relative(dir).above(dy);
                    if (n.getX() < rMinX || n.getX() > rMaxX
                        || n.getY() < rMinY || n.getY() > rMaxY
                        || n.getZ() < rMinZ || n.getZ() > rMaxZ) continue;
                    if (reached.contains(n) || !standable(sl, n)) continue;
                    reached.add(n);
                    queue.add(n);
                }
            }
        }
        return reached;
    }

    private static void seed(ServerLevel sl, BlockPos p, java.util.ArrayDeque<BlockPos> queue,
                             Set<BlockPos> reached) {
        if (!reached.contains(p) && standable(sl, p)) {
            reached.add(p);
            queue.add(p);
        }
    }

    /** The targets with at least one reached cell horizontally adjacent at dy −1/0/+1 (a citizen
     *  standing there can use the block). */
    private static List<BlockPos> filterAdjacent(List<BlockPos> targets, Set<BlockPos> reached) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos t : targets) {
            boolean ok = false;
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1 && !ok; dy++) {
                    if (reached.contains(t.relative(dir).above(dy))) ok = true;
                }
                if (ok) break;
            }
            if (ok) out.add(t);
        }
        return out;
    }

    /** True when some standing spot beside a work block fails ONLY on head clearance (feet clear,
     *  floor solid, ceiling in the face) — i.e. the roof is too low rather than the path blocked. */
    private static boolean lacksHeadroomOnly(ServerLevel sl, List<BlockPos> work) {
        for (BlockPos t : work) {
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos feet = t.relative(dir).above(dy);
                    if (passable(sl, feet) && !passable(sl, feet.above()) && !passable(sl, feet.below())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Feet-cell test: feet + head passable, support below. Doors and fence gates (incl. rope
     *  gates via the tag — never instanceof) count as passable; other collision blocks don't. */
    private static boolean standable(ServerLevel sl, BlockPos feet) {
        return passable(sl, feet) && passable(sl, feet.above()) && !passable(sl, feet.below());
    }

    private static boolean passable(ServerLevel sl, BlockPos p) {
        BlockState s = sl.getBlockState(p);
        if (s.is(net.minecraft.tags.BlockTags.DOORS) || s.is(net.minecraft.tags.BlockTags.FENCE_GATES)) {
            return true;
        }
        return s.getCollisionShape(sl, p).isEmpty();
    }

    /** Throttled validation for hot paths (the crafter goal's work scan): re-runs the full
     *  validation (incl. the reachability BFS) at most every {@code maxAgeTicks}, otherwise
     *  returns the cached status. Menu-open and rod commits still validate eagerly. */
    public static Workshop.Status validateCached(ServerLevel sl, Workshop workshop, long maxAgeTicks) {
        long now = sl.getGameTime();
        if (now - workshop.lastValidatedTick() < maxAgeTicks) {
            return workshop.status();
        }
        workshop.setLastValidatedTick(now);
        return validate(sl, workshop);
    }

    /**
     * The min-stock governor (CRAFTER_PLAN.md Phase 3): only outputs with a positive min-stock row
     * and a settlement-wide deficit (census &lt; min, quality ignored) are wanted. Explicit player
     * orders and chain-derived auto-orders are handled separately by {@link #orderedItems}.
     */
    public static boolean wantedByMinStock(ServerLevel sl, Settlement settlement, Workshop w,
                                           net.minecraft.world.item.ItemStack result) {
        if (w.minStock().isEmpty()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(result.getItem()).toString();
        Integer min = w.minStock().get(id);
        if (min == null || min <= 0) return false;
        return SettlementItemCensus.count(sl, settlement, result.getItem()) < min;
    }

    /**
     * How many MORE units of {@code result} the min-stock governor wants right now: the positive
     * gap between this workshop's min-stock row and the settlement census, or 0 when there is no
     * row or the census already meets it. Where {@link #wantedByMinStock} answers yes/no, this is
     * the true COUNT — used to size demand for CRAFTED intermediates so a single wanted final
     * product doesn't pull a whole rolling input-buffer's worth of sub-assemblies (the "1 sword
     * ordered 4 blades" overproduction).
     */
    public static int minStockDeficit(ServerLevel sl, Settlement settlement, Workshop w,
                                      net.minecraft.world.item.ItemStack result) {
        if (w.minStock().isEmpty()) return 0;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(result.getItem()).toString();
        Integer min = w.minStock().get(id);
        if (min == null || min <= 0) return 0;
        return Math.max(0, min - SettlementItemCensus.count(sl, settlement, result.getItem()));
    }

    /**
     * Whether the workshop wants <b>another</b> unit of {@code result} <b>started</b> right now,
     * net of units already committed at a WAITING stage ({@code inProgress}).
     *
     * <p><b>The waiting-stage contract (read this before adding a crafter with an unattended bake/
     * dry/cure/smelt/ferment step).</b> A "waiting stage" is anywhere a committed unit sits and
     * finishes on its own clock while the worker walks away (a hide drying on a rack, dough proving,
     * ore in a non-tended furnace). An order is only decremented when the <i>finished</i> item is
     * produced, so during the wait the order still reads "unfulfilled" — and a naive demand check
     * (plain {@link #orderedCraftCount}/{@link #wantedByMinStock}) will happily START a second unit
     * for a single order. That is the overproduction bug. Two rules avoid it:
     * <ol>
     *   <li><b>Collect ungated.</b> The step that COLLECTS a finished waiting unit must run
     *       regardless of current demand — the unit is already made; stranding it also jams the
     *       station. (i.e. don't put the collect step behind this gate.)</li>
     *   <li><b>Start gated by NET demand.</b> The step that STARTS a new final unit must check this
     *       method, passing {@code inProgress} = units currently in waiting stages (committed but not
     *       yet finished). Orders and min-stock deficits both shrink by what's already in flight.</li>
     * </ol>
     *
     * <p>Crafters whose final step BLOCKS the worker for its whole duration (e.g. the Potter tending
     * a kiln via {@code externallyComplete}, or any instant station) have no unattended wait and can
     * pass {@code inProgress = 0} — the worker simply can't start a duplicate while occupied. Only
     * walk-away waits (the Tannery's drying rack) need a real {@code inProgress} count. FUTURE
     * crafters with a walk-away wait (smith quench/temper, baker proving, fermenter, kiln if ever
     * made non-blocking) MUST honour both rules — see {@code CRAFTER_PLAN.md}.
     */
    public static boolean wantsAnother(ServerLevel sl, Settlement settlement, Workshop w,
                                       net.minecraft.world.item.ItemStack result, int inProgress) {
        int slack = Math.max(0, inProgress);
        if (orderedCraftCount(w, result.getItem()) - slack > 0) return true;
        if (w.minStock().isEmpty()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(result.getItem()).toString();
        Integer min = w.minStock().get(id);
        if (min == null || min <= 0) return false;
        int deficit = min - SettlementItemCensus.count(sl, settlement, result.getItem());
        return deficit - slack > 0;
    }

    /**
     * The workshop's order queue resolved to {@link net.minecraft.world.item.Item}s, in queue
     * order — PLAYER orders first, then the production chain's derived auto orders (see
     * {@code StockerTasks}). Executors try these FIRST in {@code chooseCraft} — an order
     * outranks (and ignores) the min-stock governor; an order whose ingredients are missing is
     * skipped, never blocking the rest of the queue. Ids whose item no longer exists drop out.
     */
    public static List<net.minecraft.world.item.Item> orderedItems(Workshop w) {
        if (w.orders().isEmpty() && w.autoOrders().isEmpty()) return List.of();
        List<net.minecraft.world.item.Item> out =
            new ArrayList<>(w.orders().size() + w.autoOrders().size());
        appendOrderItems(w.orders().keySet(), out);
        appendOrderItems(w.autoOrders().keySet(), out);
        return out;
    }

    /** Outstanding queued craft count for {@code item}, combining player and chain-derived orders. */
    public static int orderedCraftCount(Workshop w, net.minecraft.world.item.Item item) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        return Math.max(0, w.orders().getOrDefault(id, 0))
            + Math.max(0, w.autoOrders().getOrDefault(id, 0));
    }

    private static void appendOrderItems(java.util.Collection<String> ids,
                                         List<net.minecraft.world.item.Item> out) {
        for (String id : ids) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) continue;
            net.minecraft.world.item.Item item =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
            if (item != net.minecraft.world.item.Items.AIR && !out.contains(item)) out.add(item);
        }
    }

    /** A workshop + its owning settlement, resolved from a block position. */
    public record Hit(Settlement settlement, Workshop workshop) {
    }

    /** The workshop whose box union contains {@code pos}, or null. Walks the selection registry
     *  (kind WORKSHOP) then resolves owner settlement + workshop record. */
    @Nullable
    public static Hit findAt(ServerLevel sl, BlockPos pos) {
        for (BlockSelection s : BlockSelectionRegistry.get(sl).getAll()) {
            if (s.kind() != BlockSelection.Kind.WORKSHOP || !s.contains(pos)) continue;
            Settlement owner = SettlementData.get(sl.getServer().overworld()).getById(s.settlementId());
            if (owner == null) continue;
            Workshop w = owner.getWorkshop(s.homeId());
            if (w != null) return new Hit(owner, w);
        }
        return null;
    }

    /** Resolves a workshop by id across all settlements (the rod stores only the id). */
    @Nullable
    public static Hit findById(ServerLevel sl, UUID workshopId) {
        if (workshopId == null) return null;
        for (Settlement s : SettlementData.get(sl.getServer().overworld()).all()) {
            Workshop w = s.getWorkshop(workshopId);
            if (w != null) return new Hit(s, w);
        }
        return null;
    }
}
