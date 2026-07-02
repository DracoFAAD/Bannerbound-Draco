package com.bannerbound.core.territory;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.SiteWarning;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Cheap, one-time terrain check run the moment a player opens the founding screen, so the screen
 * can warn them off obviously poor sites (a desert with no water, etc.) before they commit. Samples
 * a coarse grid of surface columns over the initial-claim footprint (3×3 chunks around the town
 * hall) and derives {@link SiteWarning}s from the mix of water vs. arable ground.
 * <p>
 * Sampling is deliberately forgiving: an unloaded column is skipped, and if the whole area is
 * unloaded (shouldn't happen — the player is standing in it) we raise no warnings rather than a
 * false alarm.
 */
@ApiStatus.Internal
public final class SettlementSiteAssessor {
    /** Footprint sampled, in chunks out from the town-hall chunk. One wider than the initial
     *  claim radius (1) — a settlement can expand to own more than its starting chunks, so the
     *  assessment looks at the land it could plausibly reach, not just the chunks it claims today. */
    private static final int RADIUS_CHUNKS = 2;
    /** Grid spacing in blocks — every 4th column, ~400 samples over the 5×5-chunk area. */
    private static final int STEP = 4;
    /** Blocks scanned downward from the surface to find true ground beneath foliage/snow. */
    private static final int FOLIAGE_DEPTH = 12;
    /** Below this fraction of land columns being fertile, the ground reads as barren. */
    private static final double MIN_GRASS_FRACTION = 0.15;

    private SettlementSiteAssessor() {
    }

    /** Bitmask form of {@link #assess}, ready to drop onto the open-screen payload. */
    public static int assessMask(ServerLevel level, BlockPos center) {
        return SiteWarning.toMask(assess(level, center));
    }

    public static EnumSet<SiteWarning> assess(ServerLevel level, BlockPos center) {
        ChunkPos centerChunk = new ChunkPos(center);
        int minX = (centerChunk.x - RADIUS_CHUNKS) << 4;
        int minZ = (centerChunk.z - RADIUS_CHUNKS) << 4;
        int span = (RADIUS_CHUNKS * 2 + 1) * 16;

        int sampled = 0;
        int water = 0;
        int fertile = 0;
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = 0; dx < span; dx += STEP) {
            for (int dz = 0; dz < span; dz += STEP) {
                int x = minX + dx;
                int z = minZ + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                sampled++;
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);

                // The topmost non-air block: water here means an open-water column.
                cursor.set(x, top - 1, z);
                if (level.getBlockState(cursor).getFluidState().is(FluidTags.WATER)) {
                    water++;
                    continue;
                }

                // Otherwise dig past tree canopy / tall grass / snow to the real ground block and
                // judge whether it can grow anything.
                for (int y = top - 1; y >= top - FOLIAGE_DEPTH && y > minY; y--) {
                    cursor.set(x, y, z);
                    BlockState s = level.getBlockState(cursor);
                    if (s.isAir() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)
                            || s.is(BlockTags.REPLACEABLE) || s.is(Blocks.SNOW)) {
                        continue;
                    }
                    if (isFertile(s)) {
                        fertile++;
                    }
                    break;
                }
            }
        }

        EnumSet<SiteWarning> warnings = EnumSet.noneOf(SiteWarning.class);
        if (sampled == 0) {
            return warnings;
        }
        if (water == 0) {
            warnings.add(SiteWarning.NO_WATER);
        }
        int land = sampled - water;
        double grassFraction = land <= 0 ? 1.0 : (double) fertile / land;
        if (grassFraction < MIN_GRASS_FRACTION) {
            warnings.add(SiteWarning.POOR_SOIL);
        }
        return warnings;
    }

    /** Ground that can support grass/crops. {@code BlockTags.DIRT} covers grass block, dirt,
     *  podzol, coarse dirt, mycelium, rooted dirt, moss and mud. */
    private static boolean isFertile(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(Blocks.FARMLAND) || s.is(Blocks.MOSS_BLOCK);
    }
}
