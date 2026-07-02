package com.bannerbound.antiquity.entity;

import java.util.EnumSet;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The herd half of fear propagation: an animal that's been alarmed (its {@code SCARED_UNTIL} set by
 * a fleeing/hurt herd-mate) but has NO line of sight to the player bolts to a random away-spot —
 * so the whole herd scatters when one panics, not just the ones that can see you. Runs at the same
 * priority as {@link FleeFromPlayerGoal}; that goal (registered first) wins MOVE when a player is
 * actually visible, and this one covers the spooked-but-blind herd-mates.
 */
public class HerdFleeGoal extends Goal {
    private final PathfinderMob mob;
    private final double speed;
    private Vec3 fleeTo;

    public HerdFleeGoal(PathfinderMob mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (HuntingFear.isTamed(mob) || !HuntingFear.isScared(mob)) {
            return false;
        }
        // Flee away from the nearest player if one's around (even without LoS), else just scatter.
        Player p = mob.level().getNearestPlayer(mob, 32.0);
        Vec3 away = p != null
            ? DefaultRandomPos.getPosAway(mob, 16, 7, p.position())
            : LandRandomPos.getPos(mob, 16, 7);
        if (away == null) {
            return false;
        }
        this.fleeTo = away;
        return true;
    }

    @Override
    public void start() {
        mob.getNavigation().moveTo(fleeTo.x, fleeTo.y, fleeTo.z, speed);
    }

    @Override
    public boolean canContinueToUse() {
        return HuntingFear.isScared(mob) && !mob.getNavigation().isDone();
    }

    @Override
    public void stop() {
        this.fleeTo = null;
        mob.getNavigation().stop();
    }
}
