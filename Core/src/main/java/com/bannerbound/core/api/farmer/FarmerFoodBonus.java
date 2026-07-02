package com.bannerbound.core.api.farmer;

import com.bannerbound.core.api.settlement.ImmigrationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Computes a per-source food RATE estimate for a settlement's farmer selections. Each "valid
 * farmable block" inside an active farmer selection contributes {@value #FOOD_PER_BLOCK} food/sec.
 * <p><b>Currently orphaned.</b> Since the COOKING_PLAN larder rewrite, real farmer food reaches the
 * settlement anonymously through the storage scan ({@code LarderService} â†’ {@code storedFoodPerSecond}),
 * NOT through this number, and {@link #refresh} is no longer called from the immigration tick. Per-source
 * accounting now lives in {@link Settlement#addFoodProduced}/{@link Settlement#foodProducedFrom} (a
 * cumulative production statistic credited at harvest), which is what crisis objectives read. Kept for
 * reference / possible reuse as an instantaneous-rate estimate; it was never part of
 * {@link Settlement#effectiveFoodPerSecond()}.
 * <p>
 * "Valid farmable block" means one of: an existing farmland tile, a {@link CropBlock} that's
 * already planted, or a tile the farmer could till (dirt / grass_block / dirt_path with
 * vanilla farmland hydration). The {@code +1 Y} scan above the selection AABB picks up the
 * crop blocks that sit on top of the farmland the player actually selected.
 * <p>
 * The result is cached per-settlement and refreshed by {@code ImmigrationManager} on its 1Hz
 * broadcast tick — scanning every tile every tick would be wasteful, but a 1-second staleness
 * on the food rate is invisible at gameplay scale.
 */
public final class FarmerFoodBonus {
    public static final double FOOD_PER_BLOCK = 0.01;

    private static final Map<UUID, Double> CACHE = new HashMap<>();

    private FarmerFoodBonus() {
    }

    /** Last-cached bonus for {@code settlementId}, or 0 if nothing's been computed yet. Cheap
     *  lookup used in the per-tick food accumulation. */
    public static double get(UUID settlementId) {
        return CACHE.getOrDefault(settlementId, 0.0);
    }

    /** Recomputes the bonus for {@code s} and stores it in the cache. Called once per second
     *  from the immigration tick — bounded cost (scan all the settlement's farmer selections
     *  once). */
    public static double refresh(ServerLevel level, Settlement s) {
        double bonus = compute(level, s);
        CACHE.put(s.id(), bonus);
        s.setPassiveFoodSourceRate("farming", bonus);
        return bonus;
    }

    /** Drop the cache entry for a settlement that's been disbanded — no-op if absent. */
    public static void forget(UUID settlementId) {
        CACHE.remove(settlementId);
    }

    private static double compute(ServerLevel level, Settlement s) {
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(level);
        long count = 0;
        for (BlockSelection sel : registry.getForSettlement(s.id())) {
            if (sel.completed()) continue;
            if (!"farmer".equals(sel.workstationType())) continue;
            count += countQualifyingTiles(level, sel);
        }
        return count * FOOD_PER_BLOCK;
    }

    private static long countQualifyingTiles(ServerLevel level, BlockSelection sel) {
        long count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // Scan +1 Y above the selection's top so crop blocks sitting on top of the player's
        // selected farmland row are included.
        for (int y = sel.minY(); y <= sel.maxY() + 1; y++) {
            for (int x = sel.minX(); x <= sel.maxX(); x++) {
                for (int z = sel.minZ(); z <= sel.maxZ(); z++) {
                    cursor.set(x, y, z);
                    // Skip unloaded chunks — getBlockState would force a load otherwise.
                    if (!level.isLoaded(cursor)) continue;
                    if (isQualifying(level, cursor)) count++;
                }
            }
        }
        return count;
    }

    /** Qualifying = a crop block sitting on top of a tile of FARMLAND whose moisture is &gt; 0
     *  (i.e., actively watered). Bare farmland or unplanted dirt no longer counts — the bonus
     *  only kicks in once something is actually growing. */
    private static boolean isQualifying(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CropBlock)) return false;
        BlockState below = level.getBlockState(pos.below());
        if (!below.is(Blocks.FARMLAND)) return false;
        return below.getValue(FarmBlock.MOISTURE) > 0;
    }
}
