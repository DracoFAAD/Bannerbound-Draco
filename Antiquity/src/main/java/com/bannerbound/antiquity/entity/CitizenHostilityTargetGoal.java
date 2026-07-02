package com.bannerbound.antiquity.entity;

import java.util.function.Predicate;

import com.bannerbound.antiquity.Config;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;

/**
 * Makes a predator target settlement {@link CitizenEntity citizens}, the citizen-facing counterpart
 * to {@link PlayerHostilityTargetGoal}. A thin {@link NearestAttackableTargetGoal} subclass so it can
 * be {@code instanceof}-matched for dedup (the way the repo dedups on its own goal classes). The
 * mob's existing melee goal (wolf {@code MeleeAttackGoal}) does the actual attacking once a target is
 * set. Unlike the player goal there's no food/bone pacify path — citizens can't be relied on to hold
 * a calming item the way a player can.
 */
public class CitizenHostilityTargetGoal extends NearestAttackableTargetGoal<CitizenEntity> {
    public CitizenHostilityTargetGoal(Mob mob) {
        super(mob, CitizenEntity.class, 10, true, false,
            (Predicate<LivingEntity>) le -> isHuntableCitizen(le));
    }

    private static boolean isHuntableCitizen(LivingEntity le) {
        if (!Config.HUNTING_ENABLED.get()) {
            return false;
        }
        return le instanceof CitizenEntity citizen && citizen.isAlive();
    }
}
