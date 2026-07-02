package com.bannerbound.antiquity.entity;

import java.util.EnumSet;
import java.util.List;

import com.bannerbound.antiquity.Config;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * A prey animal flees the player: it runs when a player is within range (24 blocks, or 12 if the
 * player sneaks, ×1.5 if sprinting — the "noise" rule) AND has line of sight AND isn't holding the
 * animal's favourite food (the lure cancel). On the not-scared→scared edge it alarms the herd (and,
 * for pigs, elects a boar-charger). It keeps fleeing for the whole scared window so a chased herd
 * stays spooked. Cows use small speed multipliers (catchable); everything else outruns the player.
 *
 * <p>Plain {@link Goal} (not {@code AvoidEntityGoal}) for full control over the dynamic range,
 * sneak/LoS/food gating, stamina, and bleed slow — using the vanilla {@link DefaultRandomPos} away-
 * path the way AvoidEntityGoal does.
 */
public class FleeFromPlayerGoal extends Goal {
    private final PathfinderMob mob;
    private final double walkSpeed;
    private final double sprintSpeed;
    private Player threat;
    private Vec3 fleeTo;

    public FleeFromPlayerGoal(PathfinderMob mob, double walkSpeed, double sprintSpeed) {
        this.mob = mob;
        this.walkSpeed = walkSpeed;
        this.sprintSpeed = sprintSpeed;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!Config.HUNTING_ENABLED.get()) {
            return false;
        }
        if (HuntingFear.isTamed(mob)) {
            return false; // fed/domesticated → behaves like vanilla livestock, never flees
        }
        if (mob.isBaby() && !Config.BABIES_FLEE.get()) {
            return false;
        }
        Player t = findThreat();
        if (t == null) {
            return false;
        }
        Vec3 away = DefaultRandomPos.getPosAway(mob, 16, 7, t.position());
        if (away == null || t.distanceToSqr(away) < t.distanceToSqr(mob)) {
            return false; // no escape route, or it would run toward the player
        }
        this.threat = t;
        this.fleeTo = away;
        // First-scared edge: spook the herd once (and elect the pig that charges).
        if (!HuntingFear.isScared(mob)) {
            HuntingFear.alarmHerd(mob, Config.HERD_ALARM_RADIUS.get(), Config.SCARED_DURATION_TICKS.get());
            if (mob instanceof Pig pig) {
                HuntingFear.electBoarCharger(pig, Config.HERD_ALARM_RADIUS.get(),
                    Config.BOAR_CHARGE_CLAIM_TICKS.get(), Config.BOAR_CHARGE_CHANCE.get(), mob.getRandom());
            }
        }
        HuntingFear.scare(mob, Config.SCARED_DURATION_TICKS.get());
        return true;
    }

    @Override
    public void start() {
        mob.getNavigation().moveTo(fleeTo.x, fleeTo.y, fleeTo.z, walkSpeed);
    }

    @Override
    public boolean canContinueToUse() {
        // Keep fleeing while we're still moving, or while spooked AND a threat remains visible
        // (tick repaths). Releases (lets the herd-flee/wander resume) once calm with no threat.
        return !mob.getNavigation().isDone() || (HuntingFear.isScared(mob) && findThreat() != null);
    }

    @Override
    public void tick() {
        Player t = this.threat != null ? this.threat : findThreat();
        if (t != null) {
            mob.getNavigation().setSpeedModifier(speedFor(t));
            HuntingFear.scare(mob, Config.SCARED_DURATION_TICKS.get()); // stay alarmed while chased
        }
        if (mob.getNavigation().isDone() && t != null) {
            Vec3 away = DefaultRandomPos.getPosAway(mob, 16, 7, t.position());
            if (away != null && t.distanceToSqr(away) >= t.distanceToSqr(mob)) {
                this.threat = t;
                mob.getNavigation().moveTo(away.x, away.y, away.z, walkSpeed);
            }
        }
    }

    @Override
    public void stop() {
        this.threat = null;
        this.fleeTo = null;
        mob.getNavigation().stop();
    }

    /** Sprint when the player is close, walk otherwise; tired/bleeding animals slow (Parts 5 & 7). */
    private double speedFor(Player t) {
        double base = mob.distanceToSqr(t) < 49.0 ? sprintSpeed : walkSpeed;
        if (HuntingFear.isTired(mob)) {
            base *= Config.TIRED_SPEED_MULT.get();
        }
        if (HuntingFear.isBleeding(mob)) {
            base *= Config.BLEED_SPEED_MULT.get();
        }
        return base;
    }

    private Player findThreat() {
        double maxRange = Config.FLEE_RANGE.get() * Config.RANGE_SPRINT_MULT.get();
        List<Player> players = mob.level().getEntitiesOfClass(Player.class,
            mob.getBoundingBox().inflate(maxRange),
            p -> p.isAlive() && !p.isCreative() && !p.isSpectator());
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : players) {
            double range = effectiveRange(p);
            double d = mob.distanceToSqr(p);
            if (d > range * range) {
                continue;
            }
            if (Config.REQUIRE_LINE_OF_SIGHT.get() && !mob.getSensing().hasLineOfSight(p)) {
                continue;
            }
            if (isLured(p)) {
                continue;
            }
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        return nearest;
    }

    private double effectiveRange(Player p) {
        double r = p.isCrouching() ? Config.FLEE_RANGE_SNEAK.get() : Config.FLEE_RANGE.get();
        if (p.isSprinting()) {
            r *= Config.RANGE_SPRINT_MULT.get();
        }
        return r;
    }

    /** Holding an item the animal likes (wheat→cow, seeds→chicken, berries→fox, …) cancels fear. */
    private boolean isLured(Player p) {
        if (!Config.LURE_FOOD_CANCELS.get() || !(mob instanceof Animal animal)) {
            return false;
        }
        return animal.isFood(p.getMainHandItem()) || animal.isFood(p.getOffhandItem());
    }
}
