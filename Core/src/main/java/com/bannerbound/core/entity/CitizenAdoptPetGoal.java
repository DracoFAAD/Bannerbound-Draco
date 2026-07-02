package com.bannerbound.core.entity;

import java.util.EnumSet;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.PolicyRegistry;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.phys.AABB;

/**
 * Domestication policy: a citizen occasionally walks up to a nearby untamed {@link Wolf} and
 * bonds it to the settlement (see {@link PetBonding}). v1 covers wolves only.
 *
 * <p>Sparse like {@link AnarchyWorkGoal}: a low canUse roll + long cooldown, so the settlement
 * accumulates a handful of pets over time rather than every citizen grabbing one at once. Only
 * fires while the Domestication policy is active.
 */
@ApiStatus.Internal
public class CitizenAdoptPetGoal extends Goal {
    private static final double ADOPTION_RADIUS = 10.0;
    private static final int BOND_TICKS = 20;
    private static final int TRIP_TICKS_MAX = 300;
    private static final int COOLDOWN_MIN = 6_000;
    private static final int COOLDOWN_MAX = 12_000;
    private static final float CANUSE_CHANCE = 0.05f;
    private static final double REACH_SQ = 4.0;

    private final CitizenEntity citizen;
    private final double speedModifier;

    private int cooldown = 0;
    private int tripTicks = 0;
    private int bondTicks = 0;
    @Nullable private Wolf target;

    public CitizenAdoptPetGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) { cooldown--; return false; }
        if (!(citizen.level() instanceof ServerLevel)) return false;
        Settlement s = citizen.getSettlement();
        if (s == null || !s.hasPolicy(PolicyRegistry.DOMESTICATION)) return false;
        if (citizen.isPregnant() || citizen.isChild() || citizen.isStaminaExhausted()) return false;
        if (citizen.getRandom().nextFloat() >= CANUSE_CHANCE) return false;
        Wolf w = findUntamedWolf();
        if (w == null) return false;
        this.target = w;
        return true;
    }

    @Override
    public void start() {
        tripTicks = TRIP_TICKS_MAX;
        bondTicks = 0;
        if (target != null) {
            citizen.getNavigation().moveTo(target, speedModifier);
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (tripTicks <= 0) return false;
        if (target == null || !target.isAlive() || target.isTame()) return false;
        Settlement s = citizen.getSettlement();
        return s != null && s.hasPolicy(PolicyRegistry.DOMESTICATION);
    }

    @Override
    public void tick() {
        tripTicks--;
        if (target == null) return;
        citizen.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (citizen.distanceToSqr(target) <= REACH_SQ) {
            bondTicks++;
            citizen.getNavigation().stop();
            if (bondTicks >= BOND_TICKS) {
                Settlement s = citizen.getSettlement();
                if (s != null && !target.isTame()) {
                    PetBonding.bond(target, citizen, s);
                }
                tripTicks = 0; // done
            }
        } else if (citizen.getNavigation().isDone()) {
            citizen.getNavigation().moveTo(target, speedModifier);
        }
    }

    @Override
    public void stop() {
        RandomSource rng = citizen.getRandom();
        cooldown = COOLDOWN_MIN + rng.nextInt(Math.max(1, COOLDOWN_MAX - COOLDOWN_MIN));
        target = null;
        bondTicks = 0;
        tripTicks = 0;
        citizen.getNavigation().stop();
    }

    @Nullable
    private Wolf findUntamedWolf() {
        AABB box = citizen.getBoundingBox().inflate(ADOPTION_RADIUS);
        List<Wolf> wolves = citizen.level().getEntitiesOfClass(Wolf.class, box,
            w -> w.isAlive() && !w.isTame() && !w.isBaby());
        Wolf nearest = null;
        double best = Double.MAX_VALUE;
        for (Wolf w : wolves) {
            double d = citizen.distanceToSqr(w);
            if (d < best) { best = d; nearest = w; }
        }
        return nearest;
    }
}
