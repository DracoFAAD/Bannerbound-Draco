package com.bannerbound.core.api.walls;

import java.util.ArrayList;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.walls.DefaultWallDesigns.WallDesignSet;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The walls system's server-side verbs, shared by the {@code /bannerbound walls} commands and
 * the wall-preview screen's payload handlers — one code path for layout, construct, cancel and
 * gate toggling (WALLS_PLAN.md Phase 2/3). All methods assume the caller already resolved the
 * player's settlement.
 */
public final class WallService {

    /** Construct outcome: {@code error} is null on success. */
    public record ConstructResult(@Nullable String error, @Nullable WallLayoutEngine.LayoutResult layout) {
        public boolean ok() {
            return error == null;
        }
    }

    private WallService() {
    }

    /**
     * The settlement's ACTIVE design set: per kind, the library design the player saved as
     * active, falling back to the built-in default. The returned set's {@code byId} also
     * resolves library ids, so frozen plans keep expanding correctly after later edits.
     */
    public static WallDesignSet designs(ServerLevel level, Settlement settlement) {
        WallData walls = WallData.get(level);
        return new WallDesignSet(
            activeOrDefault(walls, settlement, WallDesign.Kind.SEGMENT, DefaultWallDesigns.set().wall()),
            activeOrDefault(walls, settlement, WallDesign.Kind.CORNER, DefaultWallDesigns.set().corner()),
            activeOrDefault(walls, settlement, WallDesign.Kind.GATE, DefaultWallDesigns.set().gate()));
    }

    private static WallDesign activeOrDefault(WallData walls, Settlement settlement,
                                              WallDesign.Kind kind, WallDesign fallback) {
        String id = walls.activeId(settlement.id(), kind);
        if (id == null) return fallback;
        WallDesign design = walls.libraryDesign(settlement.id(), id);
        return design == null ? fallback : design;
    }

    /**
     * Design resolver for expanding a settlement's plans/blueprints: library first, then the
     * built-in defaults. Use THIS (not {@code DefaultWallDesigns::byId}) wherever a plan that
     * may reference custom designs gets expanded.
     */
    public static java.util.function.Function<String, WallDesign> resolver(ServerLevel level,
                                                                           Settlement settlement) {
        WallData walls = WallData.get(level);
        // VARIANT-AWARE: <id>#steps / <id>#steps_r resolve to auto-derived step variants of
        // the base design (per-piece overrides, playtest 2026-06-12).
        return id -> WallVariants.resolve(id, baseId -> {
            WallDesign design = walls.libraryDesign(settlement.id(), baseId);
            return design != null ? design : DefaultWallDesigns.byId(baseId);
        });
    }

    /** Computes the current layout WITHOUT committing: preview, dump command, gate validation. */
    public static WallLayoutEngine.LayoutResult computeLayout(ServerLevel level, Settlement settlement) {
        WallDesignSet designs = designs(level, settlement);
        WallData walls = WallData.get(level);
        walls.reconcile(level, settlement.id(), resolver(level, settlement));
        return computeRefined(level, settlement, designs, walls);
    }

    /**
     * The auto layout with the player's REFINEMENTS applied (Phase 5.5): per-slot wall-top
     * overrides replace the chain's choice — the auto chain is only the first draft. Anchors
     * that no longer match a slot are silently ignored (gate-anchor stability rule).
     */
    private static WallLayoutEngine.LayoutResult computeRefined(ServerLevel level, Settlement settlement,
                                                                WallDesignSet designs, WallData walls) {
        WallLayoutEngine.LayoutResult result = WallLayoutEngine.compute(level, settlement, designs,
            committedWallPositions(level, settlement, designs),
            walls.gateAnchors(settlement.id()));
        java.util.Map<Long, Integer> tops = walls.topOverrides(settlement.id());
        java.util.Map<Long, String> variants = walls.variantOverrides(settlement.id());
        it.unimi.dsi.fastutil.longs.LongSet fndOff = walls.foundationOff(settlement.id());
        if (tops.isEmpty() && variants.isEmpty() && fndOff.isEmpty()) return result;
        java.util.function.Function<String, WallDesign> resolver = resolver(level, settlement);
        java.util.List<WallPiece> refined = new ArrayList<>(result.plan().pieces().size());
        for (WallPiece piece : result.plan().pieces()) {
            // KIND-AWARE key (y = kind + 1): a corner and a segment can share a start column.
            long key = BlockPos.asLong(piece.startX(), piece.kind().ordinal() + 1, piece.startZ());
            WallPiece out = piece;
            if (!piece.waterGap()) {
                // 1. Design variant — a PLAYER-SAVED library design of the same kind and
                //    footprint (run tiling stays intact). Footprint mismatches (the variant
                //    or active design changed since) are silently ignored.
                String variantId = piece.kind() == WallDesign.Kind.GATE ? null : variants.get(key);
                if (variantId != null) {
                    WallDesign variant = resolver.apply(variantId);
                    WallDesign base = resolver.apply(piece.designId());
                    if (variant != null && base != null && variant.kind() == piece.kind()
                        && variant.length() == base.length() && variant.depth() == base.depth()) {
                        out = out.withDesignId(variantId);
                    }
                }
                // 2. Wall-top override (absolute Y).
                Integer topY = tops.get(key);
                if (topY != null) {
                    WallDesign design = resolver.apply(out.designId());
                    if (design != null) {
                        out = out.withBaseY(topY - design.height() + 1);
                    }
                }
                // 3. Foundation suppression.
                if (fndOff.contains(key)) {
                    out = out.withNoFoundation();
                }
            }
            refined.add(out);
        }
        return new WallLayoutEngine.LayoutResult(
            new WallPlan(refined, result.plan().obsolete()), result.stats(), result.warnings());
    }

    /** The kind-aware per-piece refinement key (matches PieceLite.refineAnchor()). */
    private static long refineKey(WallPiece piece) {
        return BlockPos.asLong(piece.startX(), piece.kind().ordinal() + 1, piece.startZ());
    }

    /**
     * Cycles the selected piece's design among the PLAYER-SAVED library designs of the same
     * kind and footprint (active design = "Default"; variants are editable designs the player
     * authored in the Designer — 2 steps, 3 steps, 10 steps, whatever they saved). Returns
     * the NEW design's name prefixed with "ok:", else an error reason.
     */
    public static String cycleVariant(ServerLevel level, Settlement settlement, long anchor) {
        WallData walls = WallData.get(level);
        WallLayoutEngine.LayoutResult result = computeLayout(level, settlement);
        for (WallPiece piece : result.plan().pieces()) {
            if (refineKey(piece) != anchor) continue;
            if (piece.waterGap()) return "Water gaps have no blocks to vary.";
            if (piece.kind() == WallDesign.Kind.GATE) return "Gates have no variants.";
            // The slot was tiled with the ACTIVE design's footprint — candidates must match.
            WallDesignSet designs = designs(level, settlement);
            WallDesign active = piece.kind() == WallDesign.Kind.CORNER
                ? designs.corner() : designs.wall();
            java.util.List<String> candidates = new ArrayList<>();
            candidates.add(""); // "" = base / no override
            for (WallDesign d : walls.library(settlement.id())) {
                if (d.kind() == piece.kind() && !d.id().equals(active.id())
                    && d.length() == active.length() && d.depth() == active.depth()) {
                    candidates.add(d.id());
                }
            }
            if (candidates.size() == 1) {
                return "No variants saved for this size — save same-footprint designs ("
                    + active.length() + "×" + active.depth() + ") in the Designer first.";
            }
            String current = walls.variantOverrides(settlement.id()).getOrDefault(anchor, "");
            int index = candidates.indexOf(current);
            String next = candidates.get((Math.max(0, index) + 1) % candidates.size());
            walls.setVariantOverride(settlement.id(), anchor, next.isEmpty() ? null : next);
            if (next.isEmpty()) {
                return "ok:" + active.name() + " (default)";
            }
            WallDesign nextDesign = resolver(level, settlement).apply(next);
            return "ok:" + (nextDesign == null ? next : nextDesign.name());
        }
        return "That wall piece no longer exists — reopen the preview.";
    }

    /**
     * Toggles the selected piece's bottom-course continuation. Returns "ok:on"/"ok:off" on
     * success, else an error reason.
     */
    public static String toggleFoundation(ServerLevel level, Settlement settlement, long anchor) {
        WallData walls = WallData.get(level);
        WallLayoutEngine.LayoutResult result = computeLayout(level, settlement);
        for (WallPiece piece : result.plan().pieces()) {
            if (refineKey(piece) != anchor) continue;
            if (piece.waterGap()) return "Water gaps have no foundation.";
            boolean nowOff = walls.toggleFoundationOff(settlement.id(), anchor);
            return nowOff ? "ok:off" : "ok:on";
        }
        return "That wall piece no longer exists — reopen the preview.";
    }

    /**
     * Moves one slot's wall top by {@code delta} (0 = reset to the auto height). Returns null
     * on success, else a player-facing reason. Clamped: ≥1 course above the slot's crest,
     * ≤8 foundation courses below its lowest ground.
     */
    @Nullable
    public static String refineTop(ServerLevel level, Settlement settlement, long anchor, int delta) {
        WallData walls = WallData.get(level);
        if (delta == 0) {
            walls.setTopOverride(settlement.id(), anchor, null);
            return null;
        }
        WallLayoutEngine.LayoutResult result = computeLayout(level, settlement);
        for (WallPiece piece : result.plan().pieces()) {
            if (piece.waterGap()) continue;
            if (BlockPos.asLong(piece.startX(), piece.kind().ordinal() + 1, piece.startZ()) != anchor) continue;
            // Variant-aware lookup — the piece may carry a #steps designId by now.
            WallDesign design = resolver(level, settlement).apply(piece.designId());
            if (design == null) return "Unknown design.";
            int currentTop = piece.baseY() + design.height() - 1;
            int newTop = currentTop + delta;
            if (newTop < piece.maxGround() + 1) return "The wall top can't sink below the terrain crest.";
            if (newTop > piece.minGround() + design.height() + 8) return "That needs more than 8 foundation courses.";
            walls.setTopOverride(settlement.id(), anchor, newTop);
            return null;
        }
        return "That wall piece no longer exists — reopen the preview.";
    }

    /**
     * Recomputes the layout, enforces the ≥1-gate rule, freezes the plan (carrying obsolete
     * wall blocks forward) and pushes blueprints to online members.
     */
    public static ConstructResult construct(ServerLevel level, Settlement settlement) {
        WallDesignSet designs = designs(level, settlement);
        WallData walls = WallData.get(level);
        walls.reconcile(level, settlement.id(), resolver(level, settlement));
        WallLayoutEngine.LayoutResult result = computeRefined(level, settlement, designs, walls);
        if (result.plan().pieces().isEmpty()) {
            return new ConstructResult("No wall layout — claim territory first.", null);
        }
        if (result.stats().gates() < 1) {
            return new ConstructResult(
                "Mark at least one gate on the wall preview first — a sealed wall traps your citizens.",
                null);
        }
        LongOpenHashSet newPositions = new LongOpenHashSet(
            result.plan().buildBlueprint(resolver(level, settlement)).keySet());
        LongSet carried = carryObsolete(level, walls.plan(settlement.id()),
            walls.builtWall(settlement.id()), resolver(level, settlement), newPositions);
        walls.setPlan(settlement.id(), new WallPlan(result.plan().pieces(), carried));
        WallSync.syncSettlement(level, settlement);
        return new ConstructResult(null, result);
    }

    /**
     * Forgets the PLAN, never the BLOCKS: standing wall retires into the obsolete demolition
     * queue and the placement memory persists. Returns the leftover-block count, or -1 when
     * there was no plan to cancel.
     */
    public static int cancel(ServerLevel level, Settlement settlement) {
        WallData walls = WallData.get(level);
        WallPlan previous = walls.plan(settlement.id());
        if (previous == null) {
            return -1;
        }
        walls.reconcile(level, settlement.id(), resolver(level, settlement));
        LongSet leftovers = carryObsolete(level, previous,
            walls.builtWall(settlement.id()), resolver(level, settlement), LongSets.EMPTY_SET);
        walls.setPlan(settlement.id(), leftovers.isEmpty()
            ? null
            : new WallPlan(new ArrayList<>(), leftovers));
        WallSync.syncSettlement(level, settlement);
        return leftovers.size();
    }

    /**
     * Toggles a gate anchor if it lands on a valid slot. Validated by recomputing the layout
     * with the toggle applied: the anchor must match an emitted GATE piece start (set) or have
     * matched one before (unset). Returns null on success, else a player-facing reason.
     */
    @Nullable
    public static String toggleGate(ServerLevel level, Settlement settlement, long packedAnchor) {
        WallData walls = WallData.get(level);
        boolean nowSet = walls.toggleGateAnchor(settlement.id(), packedAnchor);
        if (nowSet) {
            WallLayoutEngine.LayoutResult result = computeLayout(level, settlement);
            boolean matched = result.plan().pieces().stream().anyMatch(p ->
                p.kind() == WallDesign.Kind.GATE
                && BlockPos.asLong(p.startX(), 0, p.startZ()) == packedAnchor);
            if (!matched) {
                walls.toggleGateAnchor(settlement.id(), packedAnchor); // revert
                return "That spot can't hold a gate — click a wall segment on a straight run.";
            }
        }
        return null;
    }

    /** Every position the settlement considers WALL — blueprint ∪ obsolete ∪ builtWall. */
    public static LongSet committedWallPositions(ServerLevel level, Settlement settlement,
                                                 WallDesignSet designs) {
        WallData walls = WallData.get(level);
        LongOpenHashSet positions = new LongOpenHashSet(walls.builtWall(settlement.id()));
        WallPlan committed = walls.plan(settlement.id());
        if (committed != null) {
            positions.addAll(walls.blueprint(level, settlement.id(), resolver(level, settlement)).keySet());
            positions.addAll(committed.obsolete());
        }
        return positions;
    }

    /**
     * The wall-block memory carried across plan changes: (previous blueprint ∪ previous
     * obsolete ∪ builtWall) − the new blueprint (reused positions are live wall again) −
     * world-air positions (nothing left to demolish). This is the Phase 4 demolition queue.
     */
    public static LongSet carryObsolete(ServerLevel level, @Nullable WallPlan previous,
                                        LongSet builtWall,
                                        java.util.function.Function<String, WallDesign> resolver,
                                        LongSet newBlueprintPositions) {
        LongOpenHashSet carried = new LongOpenHashSet(builtWall);
        if (previous != null) {
            carried.addAll(previous.obsolete());
            carried.addAll(previous.buildBlueprint(resolver).keySet());
        }
        carried.removeAll(newBlueprintPositions);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        LongIterator iterator = carried.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            cursor.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            if (level.getBlockState(cursor).isAir()) {
                iterator.remove();
            }
        }
        return carried;
    }
}
