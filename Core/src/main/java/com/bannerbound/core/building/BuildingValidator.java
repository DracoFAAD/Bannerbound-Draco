package com.bannerbound.core.building;

import com.bannerbound.core.api.settlement.ImmigrationManager;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;

/**
 * Tektopia-style enclosure validator. Starting from the air blocks adjacent to a marker block
 * (e.g., a Town Hall), flood-fills through air, requires every visited air block to have a non-air
 * block above it within the height cap (a "roof"), and stops at non-air blocks (walls/floor).
 *
 * <p>Caps the total volume so an open structure can't run away across the world. The flood will
 * tend to hit either the no-roof check or the volume cap if the structure has a gap or no roof.
 */
@ApiStatus.Internal
public final class BuildingValidator {
    public static final int MAX_X = 32;
    public static final int MAX_Z = 32;
    public static final int MAX_Y = 16;
    public static final int MAX_BLOCKS = 4096;

    private BuildingValidator() {
    }

    public enum FailReason {
        NO_INTERIOR,
        NO_ROOF,
        TOO_LARGE
    }

    /**
     * How strict the building check is. Ancient-era workstations only need a roof block somewhere
     * above them; later-era workstations need a full enclosed interior with walls + roof.
     */
    public enum BuildingTier {
        ROOF_ONLY,
        ENCLOSED
    }

    public record Result(boolean valid, FailReason reason, BlockPos failPos, Set<BlockPos> interior) {
        public static Result success(Set<BlockPos> interior) {
            return new Result(true, null, null, interior);
        }

        public static Result fail(FailReason reason, BlockPos pos) {
            return new Result(false, reason, pos, Collections.emptySet());
        }
    }

    /** Default ENCLOSED validation — preserved so the existing call sites in
     *  {@code ImmigrationManager.revalidateWorkstationBuildings} keep working without changes. */
    public static Result validate(BlockGetter level, BlockPos markerPos) {
        return validate(level, markerPos, BuildingTier.ENCLOSED);
    }

    /**
     * Run the validator at the given tier.
     * <ul>
     *   <li>{@link BuildingTier#ROOF_ONLY}: scan straight up from {@code markerPos}; success on
     *       the first non-air block found within {@link #MAX_Y} blocks. No flood fill, no walls.
     *       Returns an empty interior set (the one-per-building check is skipped for this tier).</li>
     *   <li>{@link BuildingTier#ENCLOSED}: full flood-fill + roof scan, as before.</li>
     * </ul>
     */
    public static Result validate(BlockGetter level, BlockPos markerPos, BuildingTier tier) {
        if (tier == BuildingTier.ROOF_ONLY) {
            BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
            for (int dy = 1; dy <= MAX_Y; dy++) {
                scan.set(markerPos.getX(), markerPos.getY() + dy, markerPos.getZ());
                if (!level.getBlockState(scan).isAir()) {
                    return Result.success(Collections.emptySet());
                }
            }
            return Result.fail(FailReason.NO_ROOF,
                new BlockPos(markerPos.getX(), markerPos.getY() + MAX_Y, markerPos.getZ()));
        }
        return validateEnclosed(level, markerPos);
    }

    private static Result validateEnclosed(BlockGetter level, BlockPos markerPos) {
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = markerPos.relative(dir);
            if (level.getBlockState(neighbor).isAir()) {
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        if (queue.isEmpty()) {
            return Result.fail(FailReason.NO_INTERIOR, markerPos);
        }

        // Pass 1: flood-fill, check only the bounding box. If the flood escapes via a wall gap
        // (or missing roof), it'll keep expanding outdoors until it breaks the size cap. This
        // makes "TOO_LARGE" mean "not enclosed" and frees us to report a meaningful NO_ROOF
        // only when the structure is genuinely closed off.
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();

            minX = Math.min(minX, current.getX()); maxX = Math.max(maxX, current.getX());
            minY = Math.min(minY, current.getY()); maxY = Math.max(maxY, current.getY());
            minZ = Math.min(minZ, current.getZ()); maxZ = Math.max(maxZ, current.getZ());
            if (maxX - minX + 1 > MAX_X || maxZ - minZ + 1 > MAX_Z || maxY - minY + 1 > MAX_Y
                    || visited.size() > MAX_BLOCKS) {
                return Result.fail(FailReason.TOO_LARGE, current);
            }

            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (visited.contains(neighbor)) {
                    continue;
                }
                if (level.getBlockState(neighbor).isAir()) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        // Pass 2: every interior block must have a non-air ceiling within MAX_Y above it.
        // Only reachable when the flood stayed within the cap, so any failure here is a
        // genuine roof gap rather than a wall gap.
        BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
        for (BlockPos pos : visited) {
            boolean hasRoof = false;
            for (int dy = 1; dy <= MAX_Y; dy++) {
                scan.set(pos.getX(), pos.getY() + dy, pos.getZ());
                if (!level.getBlockState(scan).isAir()) {
                    hasRoof = true;
                    break;
                }
            }
            if (!hasRoof) {
                return Result.fail(FailReason.NO_ROOF, pos);
            }
        }

        return Result.success(visited);
    }
}
