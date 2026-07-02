package com.bannerbound.core.building;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Enclosure scanner for an <b>animal pen</b> — the Herder's workplace. It mirrors
 * {@link StockpileEnclosure}'s 2D flood (fences / walls / gates are the only perimeter; everything
 * else floods as interior) but is <b>roofless</b> (animals graze under the sky). The only validity
 * requirement is an enclosing ring with a <b>gate</b> to walk through — the interior terrain (grass vs
 * sand/gravel) and nearby water no longer gate validity, they scale BREEDING effectiveness instead (see
 * {@link com.bannerbound.core.entity.BreedingEvents}). Supports any closed rope-fence shape, not just rectangles.
 *
 * <p>Seed the scan from a block <i>inside</i> the pen (the spot the player marked with the Foreman's
 * Rod). An un-fenced/gapped side leaks the flood past {@link #MAX_SPAN} → {@code TOO_LARGE}.
 */
@ApiStatus.Internal
public final class PenEnclosure {
    public static final int MAX_SPAN = 48;       // pens can be larger than a stockpile shed
    public static final int MAX_INTERIOR = 2048;
    private static final int PROJECT_DOWN = 2; // fence-ring projection band (terraced pens), as Stockpile
    private static final int PROJECT_UP = 2;

    public enum FailReason { TOO_LARGE, NO_GATE, NO_WATER, NO_GRASS }

    public record Result(boolean valid, FailReason reason, BlockPos failPos, Set<BlockPos> interior,
                         BlockPos min, BlockPos max) {
        /** Axis-aligned box covering the interior plane (1 block tall at the flood Y) — inflate for use. */
        public AABB bounds() {
            return new AABB(min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1);
        }
    }

    /** Husbandry stats of a pen's interior: walkable land cells, of which grass, and water-source cells. */
    public record PenStats(int land, int grass, int water) {
        /** Size-units the pen affords: one per land cell, ×(config water multiplier, default 1.5) if it
         *  has any water source. */
        public double sizeUnits() {
            return land * (water > 0 ? com.bannerbound.core.Config.HERDER_PEN_WATER_MULTIPLIER.get() : 1.0);
        }

        /** Animal capacity for an animal of the given SIZE cost (chicken 1, cow/horse 2, …): the pen's
         *  size-units divided by that cost. Minimum 2 so a breeding pair always fits. E.g. 14 units →
         *  14 chickens, or 7 horses. */
        public int capacity(int animalSize) {
            return Math.max(2, (int) (sizeUnits() / Math.max(1, animalSize)));
        }
    }

    /** Count the interior's walkable land cells (solid floor + air above), how many of those are grass,
     *  and how many cells are water sources — the inputs to {@link PenStats#capacity()}/{@code breedBonus()}. */
    public static PenStats stats(Level level, Result r) {
        int land = 0, grass = 0, water = 0;
        for (BlockPos c : r.interior()) {
            BlockState floor = level.getBlockState(c);
            if (floor.getFluidState().isSource()) {
                water++;
                continue;
            }
            if (floor.blocksMotion() && !level.getBlockState(c.above()).blocksMotion()) {
                land++;
                if (floor.is(Blocks.GRASS_BLOCK)) grass++;
            }
        }
        return new PenStats(land, grass, water);
    }

    private PenEnclosure() {
    }

    /** Like {@link StockpileEnclosure#scan} the floor may sit below an elevated marker, so try the
     *  seed plane then successively lower planes; the first that yields a valid pen wins. */
    public static Result scan(Level level, BlockPos seed) {
        Result first = null;
        for (int drop = 0; drop <= 8; drop++) {
            Result r = scanAt(level, new BlockPos(seed.getX(), seed.getY() - drop, seed.getZ()));
            if (r.valid()) return r;
            if (first == null) first = r;
        }
        return first;
    }

    private static Result scanAt(Level level, BlockPos seed) {
        Set<BlockPos> interior = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = seed.immutable();
        interior.add(start);
        queue.add(start);

        int minX = start.getX(), maxX = start.getX();
        int minZ = start.getZ(), maxZ = start.getZ();
        boolean hasGate = false;

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos n = cur.relative(dir);
                if (interior.contains(n)) continue;
                int boundary = boundaryColumn(level, n.getX(), n.getZ(), seed.getY());
                if (boundary > 0) {
                    if (boundary == 2) hasGate = true;
                    continue;
                }
                interior.add(n);
                queue.add(n);
                minX = Math.min(minX, n.getX());
                maxX = Math.max(maxX, n.getX());
                minZ = Math.min(minZ, n.getZ());
                maxZ = Math.max(maxZ, n.getZ());
                if (maxX - minX + 1 > MAX_SPAN || maxZ - minZ + 1 > MAX_SPAN
                        || interior.size() > MAX_INTERIOR) {
                    return new Result(false, FailReason.TOO_LARGE, n, interior,
                        new BlockPos(minX, seed.getY(), minZ), new BlockPos(maxX, seed.getY(), maxZ));
                }
            }
        }

        BlockPos min = new BlockPos(minX, seed.getY(), minZ);
        BlockPos max = new BlockPos(maxX, seed.getY(), maxZ);
        if (!hasGate) return new Result(false, FailReason.NO_GATE, seed, interior, min, max);

        // Enclosed with a gate → valid. Interior terrain (grass / sand / water) no longer gates validity; it
        // scales breeding effectiveness via BreedingEvents (an infertile pen breeds poorly, it isn't blocked).
        return new Result(true, null, null, interior, min, max);
    }

    private static boolean isFenceLike(BlockState state) {
        return state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.WALLS);
    }

    /** {@code 0} = not boundary, {@code 1} = fence/wall, {@code 2} = includes a gate. Projected across a
     *  small vertical band so a ring that steps with terraced terrain reads as one continuous wall. */
    private static int boundaryColumn(Level level, int x, int z, int scanY) {
        int code = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -PROJECT_DOWN; dy <= PROJECT_UP; dy++) {
            BlockState s = level.getBlockState(p.set(x, scanY + dy, z));
            if (isFenceLike(s)) {
                if (s.is(BlockTags.FENCE_GATES)) return 2;
                code = 1;
            }
        }
        return code;
    }
}
