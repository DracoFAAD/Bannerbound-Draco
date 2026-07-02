package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;

/**
 * Ground navigation for citizens. Identical to {@link GroundPathNavigation} except it uses a
 * {@link CitizenNodeEvaluator}, which makes closed fence gates routable.
 */
@ApiStatus.Internal
public class CitizenGroundNavigation extends GroundPathNavigation {
    public CitizenGroundNavigation(Mob mob, Level level) {
        super(mob, level);
        // Give A* a bigger node budget so long, winding land routes resolve in ONE path computation
        // instead of returning a partial path to the nearest reachable tile. Without this, a fisher
        // forced around water (the direct route is blocked) would walk to the shore by the water, run
        // out of budget there, stop, then re-path the rest — looking like it "tried to go through the
        // water." The default multiplier is 1.0; the extra exploration only costs more on genuinely
        // long detours, since short direct paths terminate early regardless of the cap.
        this.setMaxVisitedNodesMultiplier(4.0F);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new CitizenNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }
}
