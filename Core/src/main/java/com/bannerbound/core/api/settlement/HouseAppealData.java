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
 * Per-home appeal scorer - parallel to {@link ChunkAppealData} but scoped to the union of a
 * home's HOME {@link BlockSelection}s instead of a 16x16 chunk. Reuses the stateless math in
 * {@link AppealResolver} (each block type contributes appealOf(block) * (1 - 0.9^count) * 10),
 * so the diminishing-returns curve and culture-style overrides match the chunk path exactly;
 * only the scope differs - counts accumulate per home, so eight identical paintings inside one
 * home diminish each other but one painting per home does not. Stateless: {@link #scoreOf}
 * writes its result back onto the {@link Home} cache fields as a side effect, and
 * {@link #scoreUnion} is the pure generic union scorer shared with workshops (and any future
 * building kind); no cache lives here. Overlapping boxes dedupe by position; air contributes
 * nothing; two-tall blocks (tall plants, doors, beds) count once at their anchor half via
 * {@link AppealResolver#isAppealDuplicateHalf}. Union iteration is O(sum of box
 * volumes), bounded by the per-box and per-building caps. {@link AppealContributors#extra} adds
 * non-block appeal (e.g. Antiquity plaster/trim face decorations) per union position, and
 * {@link #unionContains} lets the block-edit listener decide whether an edit warrants a rescore.
 */
@ApiStatus.Internal
public final class HouseAppealData {
    public record AppealSnapshot(double score, ChunkBeauty beauty, int blockCount) {}

    private HouseAppealData() {}

    public static AppealSnapshot scoreOf(ServerLevel level, Settlement settlement, Home home) {
        AppealSnapshot snapshot = scoreUnion(level, settlement,
            BlockSelectionRegistry.get(level).findByHome(home.id()));
        home.setCachedScore(snapshot.score(), snapshot.beauty(), level.getGameTime());
        return snapshot;
    }

    public static AppealSnapshot scoreUnion(ServerLevel level, Settlement settlement,
                                            List<BlockSelection> boxes) {
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

        Map<Block, Integer> counts = new HashMap<>();
        for (BlockPos pos : union) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            if (AppealResolver.isAppealDuplicateHalf(state)) continue;
            Block b = state.getBlock();
            counts.merge(b, 1, Integer::sum);
        }

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

        if (AppealContributors.hasAny()) {
            for (BlockPos pos : union) {
                score += AppealContributors.extra(level, pos);
            }
        }
        return new AppealSnapshot(score, ChunkBeauty.fromScore(score), contributingBlocks);
    }

    public static boolean unionContains(ServerLevel level, Home home, BlockPos pos) {
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(level);
        for (BlockSelection s : registry.findByHome(home.id())) {
            if (s.contains(pos)) return true;
        }
        return false;
    }

}
