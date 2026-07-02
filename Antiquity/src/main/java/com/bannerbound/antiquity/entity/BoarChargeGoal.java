package com.bannerbound.antiquity.entity;

import java.util.EnumSet;

import com.bannerbound.antiquity.Config;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The boar charge: a scared, herd-elected pig pauses (~0.1 s wind-up), eyes the player, then dashes
 * forward — committed but slightly homing so it's dodgeable — and on impact deals damage and then
 * flees. Only the pig holding a live {@code BOAR_CHARGE_CLAIM} (see {@link HuntingFear#electBoarCharger})
 * charges; the rest of the herd flees. Registered at priority 1 BEFORE the flee goals so the charger
 * wins MOVE/LOOK; once it's done it drops the claim and falls through to fleeing.
 */
public class BoarChargeGoal extends Goal {
    private enum Phase { WINDUP, CHARGE, DONE }

    private final Pig pig;
    private Player target;
    private Phase phase = Phase.DONE;
    private int windupLeft;
    private int chargeLeft;
    private Vec3 chargeDir = Vec3.ZERO;
    private boolean dealtHit;

    public BoarChargeGoal(Pig pig) {
        this.pig = pig;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!Config.HUNTING_ENABLED.get() || HuntingFear.isTamed(pig)
                || !HuntingFear.isScared(pig) || !HuntingFear.hasChargeClaim(pig)) {
            return false;
        }
        if (pig.isBaby() && !Config.BABIES_CHARGE.get()) {
            return false;
        }
        if (pig.getControllingPassenger() != null) {
            return false; // don't fight a rider
        }
        this.target = findTarget();
        return this.target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.DONE && target != null && target.isAlive();
    }

    @Override
    public void start() {
        this.phase = Phase.WINDUP;
        this.windupLeft = Config.BOAR_WINDUP_TICKS.get();
        this.chargeLeft = Config.BOAR_CHARGE_TICKS.get();
        this.dealtHit = false;
        pig.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (target == null) {
            return;
        }
        pig.getLookControl().setLookAt(target, 30.0F, 30.0F);
        switch (phase) {
            case WINDUP -> {
                pig.getNavigation().stop();
                faceDir(horizontalToward(target)); // turn to square up on the player before lunging
                if (--windupLeft <= 0) {
                    this.chargeDir = horizontalToward(target);
                    this.phase = Phase.CHARGE;
                }
            }
            case CHARGE -> {
                // Slightly track the player so a standing target is hit but a dodge works.
                double k = Config.BOAR_CHARGE_REDIRECT.get();
                this.chargeDir = chargeDir.scale(1.0 - k).add(horizontalToward(target).scale(k)).normalize();
                faceDir(chargeDir); // body leads the charge — head-first, not a sideways butt-slam
                double speed = Config.BOAR_CHARGE_SPEED.get();
                pig.setDeltaMovement(chargeDir.x * speed, pig.getDeltaMovement().y, chargeDir.z * speed);
                pig.hasImpulse = true;
                double reach = Config.BOAR_IMPACT_REACH.get();
                if (!dealtHit && pig.distanceToSqr(target) <= reach * reach) {
                    target.hurt(pig.damageSources().mobAttack(pig), (float) (double) Config.BOAR_CHARGE_DAMAGE.get());
                    this.dealtHit = true;
                    this.phase = Phase.DONE;
                } else if (--chargeLeft <= 0) {
                    this.phase = Phase.DONE; // missed
                }
            }
            default -> { }
        }
    }

    @Override
    public void stop() {
        this.phase = Phase.DONE;
        this.target = null;
        // After charging, the pig is spooked-but-no-longer-the-charger → next tick it flees.
        HuntingFear.scare(pig, Config.SCARED_DURATION_TICKS.get());
        HuntingFear.clearChargeClaim(pig);
        pig.getNavigation().stop();
    }

    /**
     * Snap the pig's body (and head) yaw to a horizontal direction. The charge drives movement via
     * {@code setDeltaMovement} rather than the navigator, so the body-rotation controller never turns
     * the pig to face where it's going — without this it lunges sideways/rear-first ("butt-slam").
     * Setting the {@code *O} (previous-tick) fields too avoids a one-tick interpolation snap.
     */
    private void faceDir(Vec3 dir) {
        if (dir.lengthSqr() < 1.0e-6) {
            return;
        }
        float yaw = (float) (Mth.atan2(dir.z, dir.x) * (180.0 / Math.PI)) - 90.0F;
        pig.setYRot(yaw);
        pig.yBodyRot = yaw;
        pig.yBodyRotO = yaw;
        pig.yHeadRot = yaw;
        pig.yHeadRotO = yaw;
    }

    private Vec3 horizontalToward(Player p) {
        Vec3 d = new Vec3(p.getX() - pig.getX(), 0.0, p.getZ() - pig.getZ());
        return d.lengthSqr() < 1.0e-6 ? new Vec3(0.0, 0.0, 1.0) : d.normalize();
    }

    private Player findTarget() {
        double range = Config.FLEE_RANGE.get();
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : pig.level().getEntitiesOfClass(Player.class, pig.getBoundingBox().inflate(range),
                pl -> pl.isAlive() && !pl.isCreative() && !pl.isSpectator())) {
            double d = pig.distanceToSqr(p);
            if (d > range * range) {
                continue;
            }
            if (Config.REQUIRE_LINE_OF_SIGHT.get() && !pig.getSensing().hasLineOfSight(p)) {
                continue;
            }
            if (Config.LURE_FOOD_CANCELS.get()
                    && (pig.isFood(p.getMainHandItem()) || pig.isFood(p.getOffhandItem()))) {
                continue;
            }
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }
}
