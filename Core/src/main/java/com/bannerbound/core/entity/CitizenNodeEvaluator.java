package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Walk evaluator with two citizen-specific tweaks over vanilla:
 * <ul>
 *   <li><b>Fence gates</b> — vanilla classifies a closed fence gate as {@link PathType#FENCE}
 *       (impassable, malus -1). This reclassifies it as {@link PathType#DOOR_WOOD_CLOSED} so —
 *       with {@code canOpenDoors} set — the pathfinder routes through it; {@link OpenFenceGateGoal}
 *       opens it on arrival, mirroring {@code OpenDoorGoal} for real doors.</li>
 *   <li><b>Dirt path preference</b> — every walkable tile <i>not</i> standing on a dirt path
 *       gets a small extra cost, so the pathfinder routes over {@code dirt_path} infrastructure
 *       when a path is reasonably close instead of cutting straight across the terrain. The
 *       malus stays positive (negative is the pathfinder's "blocked" sentinel). This preference
 *       is gated on the <b>Roads policy</b> being active — without it citizens path normally.</li>
 * </ul>
 */
@ApiStatus.Internal
public class CitizenNodeEvaluator extends WalkNodeEvaluator {
    /** Extra path cost for a walkable tile that isn't on a dirt path. Tuned so citizens take a
     *  path detour up to ~2× the straight-line distance, without distorting long cross-country
     *  routes enough to starve the pathfinder's node budget. */
    private static final float OFF_PATH_MALUS = 1.0F;

    /** Cached once per path build in {@link #prepare}: whether the pathing citizen's settlement
     *  has the Roads policy active. Avoids a SettlementData lookup per evaluated node. */
    private boolean roadsActive = false;
    /** Cached once per path build: whether the pathing citizen is a fisher that must keep out of the
     *  water (walk the shore/pier rather than swim a shortcut). See {@link CitizenEntity#isAvoidWaterPathing}. */
    private boolean avoidWater = false;

    @Override
    public void prepare(net.minecraft.world.level.PathNavigationRegion region,
                        net.minecraft.world.entity.Mob mob) {
        super.prepare(region, mob);
        roadsActive = false;
        avoidWater = false;
        if (mob instanceof CitizenEntity c) {
            com.bannerbound.core.api.settlement.Settlement s = c.getSettlement();
            // Road-building stockers (outpost haul legs) prefer paths regardless of the policy —
            // the first trip tramples the road, this preference makes every later trip follow it.
            roadsActive = (s != null && s.hasPolicy(
                com.bannerbound.core.api.settlement.PolicyRegistry.ROADS))
                || c.isRoadBuilding();
            avoidWater = c.isAvoidWaterPathing();
        }
    }

    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        PathType base = super.getPathType(context, x, y, z);
        BlockState state = context.getBlockState(new BlockPos(x, y, z));
        // Note: Antiquity's partial-collision workstation blocks (mortar, basket, crafting stone, etc.)
        // declare isPathfindable=false on the block itself, so vanilla already classifies them BLOCKED
        // here — no id special-case needed. (They used to be matched by registry id in this method.)
        // A fisher walking to its spot must not swim: hard-block actual water so the only route is over
        // land (the shore and the pier). A high malus wasn't enough — a short diagonal swim could still
        // out-cost the long way round — so we make water impassable for it outright. WATER_BORDER (the
        // water-adjacent LAND the pier is built from) stays walkable, so the pier remains routable.
        if (avoidWater && base == PathType.WATER) {
            return PathType.BLOCKED;
        }
        // Gate-like blocks identified by the fence_gates tag (vanilla AND the modded rope gate): a CLOSED
        // gate is a barrier the pathfinder won't cross — FENCE for vanilla, BLOCKED for the rope gate (its
        // isPathfindable is false when closed). Reclassify to a routable closed door so A* paths through it;
        // OpenFenceGateGoal opens it on arrival. Open gates keep their normal (walkable) type.
        if (state.is(BlockTags.FENCE_GATES) && state.hasProperty(BlockStateProperties.OPEN)
                && !state.getValue(BlockStateProperties.OPEN)) {
            return PathType.DOOR_WOOD_CLOSED;
        }
        return base;
    }

    @Override
    protected Node findAcceptedNode(int x, int y, int z, int verticalDeltaLimit, double nodeFloorLevel,
                                    Direction direction, PathType pathType) {
        Node node = super.findAcceptedNode(x, y, z, verticalDeltaLimit, nodeFloorLevel, direction, pathType);
        if (node == null) {
            // An OPEN fence gate's cell is rejected by vanilla as a node (its non-empty render shape fails
            // the node's floor/occupancy check) even though it's physically passable — so A* can't route
            // THROUGH the open gate (the herder was only getting inside via its stuck-teleport). Force-accept
            // an OPEN gate as a plain walkable node so it's a real connector. CLOSED gates are handled by the
            // getPathType DOOR_WOOD_CLOSED reclass + canOpenDoors; they aren't forced here.
            BlockState g = this.currentContext.getBlockState(new BlockPos(x, y, z));
            if (g.is(BlockTags.FENCE_GATES) && g.hasProperty(BlockStateProperties.OPEN)
                    && g.getValue(BlockStateProperties.OPEN)) {
                Node n = this.getNode(x, y, z);
                n.type = PathType.OPEN;
                n.costMalus = Math.max(n.costMalus, 0.0F);
                return n;
            }
            return null;
        }
        if (roadsActive && node.type == PathType.WALKABLE && node.costMalus >= 0.0F) {
            BlockState ground = this.currentContext.getBlockState(
                new BlockPos(node.x, node.y - 1, node.z));
            if (!ground.is(Blocks.DIRT_PATH)) {
                node.costMalus += OFF_PATH_MALUS;
            }
        }
        return node;
    }
}
