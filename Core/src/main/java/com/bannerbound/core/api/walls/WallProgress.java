package com.bannerbound.core.api.walls;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Blueprint-vs-world completeness scan (WALLS_PLAN.md §A "completeness %"). A position counts
 * as satisfied when the world block MATCHES THE EXPECTED BLOCK — deliberately forgiving on
 * state (an open gate, a differently-shaped wall connection or rotated stair still counts;
 * tightening to exact-state is a later refinement if it ever matters). Water-gap pieces emit
 * no blueprint positions, so they're inherently excluded — water IS the wall.
 *
 * <p>Used on demand (status command / future walls tab + stakes); throttled callers piggyback
 * the settlement upkeep tick, never per-block ticking.
 */
public final class WallProgress {

    public record Progress(int total, int matching, int missing, int mismatched) {
        public int percent() {
            return total == 0 ? 100 : Math.round(100.0f * matching / total);
        }
    }

    private WallProgress() {
    }

    public static Progress scan(ServerLevel level, WallPlan plan, Function<String, WallDesign> designs) {
        Long2ObjectMap<BlockState> blueprint = plan.buildBlueprint(designs);
        int matching = 0;
        int missing = 0;
        int mismatched = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Long2ObjectMap.Entry<BlockState> entry : blueprint.long2ObjectEntrySet()) {
            long packed = entry.getLongKey();
            cursor.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            BlockState actual = level.getBlockState(cursor);
            if (actual.is(entry.getValue().getBlock())) {
                matching++;
            } else if (actual.isAir() || actual.canBeReplaced()) {
                missing++;
            } else {
                mismatched++;
            }
        }
        return new Progress(blueprint.size(), matching, missing, mismatched);
    }

    /** Items still needed for unsatisfied blueprint positions, largest counts first. */
    public static Map<Item, Integer> remainingItems(ServerLevel level, WallPlan plan,
                                                    Function<String, WallDesign> designs) {
        Long2ObjectMap<BlockState> blueprint = plan.buildBlueprint(designs);
        Map<Item, Integer> remaining = new LinkedHashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Long2ObjectMap.Entry<BlockState> entry : blueprint.long2ObjectEntrySet()) {
            long packed = entry.getLongKey();
            cursor.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            BlockState expected = entry.getValue();
            if (!level.getBlockState(cursor).is(expected.getBlock())) {
                remaining.merge(expected.getBlock().asItem(), 1, Integer::sum);
            }
        }
        return WallLayoutEngine.sortedRequired(remaining);
    }
}
