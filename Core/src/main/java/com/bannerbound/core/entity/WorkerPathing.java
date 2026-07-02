package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/**
 * Shared stand-position checks for citizen work goals (farmer, digger, fisher, forester).
 * <p>
 * "Walkable" here means "a tile a worker can occupy to reach an adjacent work block" — which is
 * not the same as "empty". A worker walks straight through crops, grass tufts and flowers (blocks
 * with no collision shape), so those count as passable. Treating them as obstacles — the old
 * {@code isAir()} test — left work blocks fully ringed by grown crops with no eligible stand
 * position, so they were silently skipped.
 * <p>
 * Liquids are deliberately <i>not</i> passable: a no-collision water/lava block would otherwise
 * read as standable, and these land workers should keep their feet on solid ground.
 */
@ApiStatus.Internal
public final class WorkerPathing {
    private WorkerPathing() {
    }

    /** True if a worker can stand at {@code p} to work an adjacent block: {@code p} and the head
     *  space above it are passable, and the tile below has a collision shape to stand on. */
    public static boolean isWalkable(BlockGetter level, BlockPos p) {
        if (!isPassable(level, p)) return false;
        if (!isPassable(level, p.above())) return false;
        return hasFloor(level, p.below());
    }

    /** True if an entity can occupy {@code p}: the block has no collision shape (air, crops,
     *  grass, flowers, …) and holds no fluid. */
    public static boolean isPassable(BlockGetter level, BlockPos p) {
        if (!level.getFluidState(p).isEmpty()) return false;
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** True if {@code p} has a collision shape solid enough to stand on (full block, slab,
     *  farmland, …). Crops, grass and liquids have no collision and fail this. */
    public static boolean hasFloor(BlockGetter level, BlockPos p) {
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }
}
