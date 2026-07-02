package com.bannerbound.core.api.settlement;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-home appeal scorer. Parallel to {@link ChunkAppealData} but operating on the union of
 * a home's {@link com.bannerbound.core.api.world.BlockSelection.Kind#HOME} selections instead
 * of a 16×16 chunk grid.
 *
 * <p>Reuses the stateless math in {@link AppealResolver} — every block contributes
 * {@code appealOf(block) · (1 - 0.9^count) · 10} — so the diminishing-returns curve and
 * culture-style overrides apply identically to the chunk path. The difference is scope: counts
 * accumulate <i>per home</i>, not per chunk, so eight identical paintings inside one home
 * diminish each other but eight paintings spread one-per-home don't.
 *
 * <p>Stateless — call {@link #scoreOf(ServerLevel, Settlement, Home)} and the result lands
 * back on the home via {@link Home#setCachedScore}. No internal cache lives here.
 */
@ApiStatus.Internal
public final class HouseAppealData {
    /** Returned by {@link #scoreOf} so the caller can act on both the raw score and the
     *  derived beauty tier without computing the tier twice. */
    public record AppealSnapshot(double score, ChunkBeauty beauty, int blockCount) {}

    private HouseAppealData() {}

    /**
     * Scores the home's appeal-region (the union of every {@link BlockSelection.Kind#HOME}
     * selection registered for {@code home.id()}). The score also updates the home's cached
     * score / beauty / lastScoredTick fields as a side effect, so the caller doesn't have to
     * do the write itself.
     *
     * @return a fresh {@link AppealSnapshot} — never null. A home with no selections (or all
     *         selections empty) scores 0 / BLAND with {@code blockCount = 0}.
     */
    public static AppealSnapshot scoreOf(ServerLevel level, Settlement settlement, Home home) {
        AppealSnapshot snapshot = scoreUnion(level, settlement,
            BlockSelectionRegistry.get(level).findByHome(home.id()));
        home.setCachedScore(snapshot.score(), snapshot.beauty(), level.getGameTime());
        return snapshot;
    }

    /**
     * The GENERIC selection-union scorer — pure (no side effects), shared by homes and workshops
     * (and any future building kind). Scores the union of {@code boxes} with the settlement's
     * current styles/palettes: dedupe positions, count per block type once per anchor half, sum
     * {@link AppealResolver#typeContribution} per type.
     */
    public static AppealSnapshot scoreUnion(ServerLevel level, Settlement settlement,
                                            List<BlockSelection> boxes) {
        // Step 1: union. Walk every box's volume into a single Set<BlockPos> so overlapping
        // boxes dedupe to one count per position. Iteration is O(sum of box volumes) — the
        // 4096-per-box and per-building box caps bound it; typical buildings are a fraction.
        Set<BlockPos> union = new HashSet<>();
        for (BlockSelection s : boxes) {
            for (int x = s.minX(); x <= s.maxX(); x++) {
                for (int y = s.minY(); y <= s.maxY(); y++) {
                    for (int z = s.minZ(); z <= s.maxZ(); z++) {
                        union.add(new BlockPos(x, y, z));
                    }
                }
            }
        }

        // Step 2: group by Block type, skipping air (no contribution, no tint, no count).
        Map<Block, Integer> counts = new HashMap<>();
        for (BlockPos pos : union) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            // Count two-tall plants/doors once: skip the UPPER half so a single rose bush / peony /
            // tall grass doesn't score double (its lower half is the anchor that counts).
            if (AppealResolver.isAppealDuplicateHalf(state)) continue;
            Block b = state.getBlock();
            counts.merge(b, 1, Integer::sum);
        }

        // Step 3: sum AppealResolver's per-type contribution across every (block, count) pair.
        List<String> styles = settlement.cultureStyles();
        List<String> palettes = settlement.activePalettes();
        double score = 0.0;
        int contributingBlocks = 0;
        for (Map.Entry<Block, Integer> e : counts.entrySet()) {
            float appeal = AppealResolver.appealOf(e.getKey(), styles, palettes);
            if (appeal == 0f) continue;
            score += AppealResolver.typeContribution(appeal, e.getValue());
            contributingBlocks += e.getValue();
        }

        // Step 4: non-block appeal — face decorations (Antiquity plaster/trim) and any other
        // registered contributors. Summed per position in the union (cheap fast-path when none).
        if (AppealContributors.hasAny()) {
            for (BlockPos pos : union) {
                score += AppealContributors.extra(level, pos);
            }
        }
        return new AppealSnapshot(score, ChunkBeauty.fromScore(score), contributingBlocks);
    }

    /** True iff any of the home's selection boxes contains {@code pos}. Used by the block-edit
     *  invalidation listener to decide whether a player's place/break warrants a rescore. */
    public static boolean unionContains(ServerLevel level, Home home, BlockPos pos) {
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(level);
        for (BlockSelection s : registry.findByHome(home.id())) {
            if (s.contains(pos)) return true;
        }
        return false;
    }

    /**
     * Per-home analog of {@link ChunkAppealData#queuePositionOf(BlockPos)}. Returns the 1-based
     * slot of {@code target} among the home's union positions that share the same block type,
     * or 0 if the target isn't in the union (or its block is air).
     *
     * <p>The home has no scan order — unlike a chunk's beauty scan, selection boxes don't run a
     * sweep when committed. So we synthesise a stable order by sorting same-type positions by
     * their packed long. The displayed marginal value ({@code appeal · 0.9^(slot-1)}) is then
     * deterministic for any given union; adding/removing same-type blocks renumbers slots the
     * way the chunk scan would on a rescan.
     */
    public static int queuePositionOf(ServerLevel level, Home home, BlockPos target) {
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(level);
        List<BlockSelection> boxes = registry.findByHome(home.id());
        if (boxes.isEmpty()) return 0;
        BlockState targetState = level.getBlockState(target);
        if (targetState.isAir()) return 0;
        // Multi-block objects are tallied at one anchor half (see scoreOf). If the overlay queries
        // the other half (upper plant/door, bed foot), resolve to the anchor so the marginal value
        // matches the score.
        if (AppealResolver.isAppealDuplicateHalf(targetState)) {
            target = AppealResolver.appealAnchor(targetState, target);
            targetState = level.getBlockState(target);
            if (targetState.isAir()) return 0;
        }
        Block targetBlock = targetState.getBlock();

        // Union — same shape as scoreOf, kept inline so the queue lookup doesn't depend on a
        // separate union cache that could drift.
        Set<BlockPos> union = new HashSet<>();
        for (BlockSelection s : boxes) {
            for (int x = s.minX(); x <= s.maxX(); x++) {
                for (int y = s.minY(); y <= s.maxY(); y++) {
                    for (int z = s.minZ(); z <= s.maxZ(); z++) {
                        union.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        if (!union.contains(target)) return 0;

        // Collect same-type positions as packed longs and sort — stable, position-only order.
        List<Long> sameType = new java.util.ArrayList<>();
        long targetKey = target.asLong();
        for (BlockPos p : union) {
            BlockState state = level.getBlockState(p);
            if (state.isAir()) continue;
            if (AppealResolver.isAppealDuplicateHalf(state)) continue;  // count plants once, at the lower half
            if (state.getBlock() != targetBlock) continue;
            sameType.add(p.asLong());
        }
        java.util.Collections.sort(sameType);
        int idx = sameType.indexOf(targetKey);
        return idx < 0 ? 0 : idx + 1;
    }
}
