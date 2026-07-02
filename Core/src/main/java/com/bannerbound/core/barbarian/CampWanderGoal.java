package com.bannerbound.core.barbarian;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Keeps a barbarian camp member milling about its camp: it strolls to a random spot within
 * {@code radius} of the camp center on a relaxed cadence, and is pulled back whenever it drifts
 * outside that radius. This gives camps a living ecosystem feel and doubles as a soft leash so
 * members don't wander off into the wild (replacing the default free random-stroll).
 *
 * <p>Combat/defense behaviour (Phase 5) sits at a higher priority and pre-empts this.
 */
public class CampWanderGoal extends Goal {
    private final PathfinderMob mob;
    private final BlockPos center;
    private final int radius;
    private final double speed;
    private double tx, ty, tz;

    public CampWanderGoal(PathfinderMob mob, BlockPos center, int radius, double speed) {
        this.mob = mob;
        this.center = center;
        this.radius = radius;
        this.speed = speed;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        double dcx = mob.getX() - (center.getX() + 0.5);
        double dcz = mob.getZ() - (center.getZ() + 0.5);
        boolean outside = dcx * dcx + dcz * dcz > (double) radius * radius;
        // Always head back if it has drifted out; otherwise pick a new spot only occasionally so it
        // pauses and idles between strolls.
        if (!outside && mob.getRandom().nextInt(50) != 0) return false;
        pickTarget(outside);
        return true;
    }

    private void pickTarget(boolean homeward) {
        if (homeward) {
            tx = center.getX() + 0.5;
            tz = center.getZ() + 0.5;
        } else {
            double ang = mob.getRandom().nextDouble() * Math.PI * 2.0;
            double r = mob.getRandom().nextDouble() * radius;
            tx = center.getX() + 0.5 + Math.cos(ang) * r;
            tz = center.getZ() + 0.5 + Math.sin(ang) * r;
        }
        ty = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (int) Math.floor(tx), (int) Math.floor(tz));
    }

    @Override
    public boolean canContinueToUse() {
        return !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        mob.getNavigation().moveTo(tx, ty, tz, speed);
    }
}
