package com.bannerbound.antiquity.entity;

import java.util.function.Predicate;

import com.bannerbound.antiquity.Config;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

/**
 * Makes a predator target the player. A thin {@link NearestAttackableTargetGoal} subclass so it can
 * be {@code instanceof}-matched for dedup (the way the repo dedups on its own goal classes). The
 * mob's existing melee goal (wolf {@code MeleeAttackGoal}, ocelot {@code OcelotAttackGoal}) does the
 * actual attacking once a target is set. Skips creative/spectator players and — if configured — a
 * player holding the predator's food (so it can still be pacified/lured, like prey).
 */
public class PlayerHostilityTargetGoal extends NearestAttackableTargetGoal<Player> {
    public PlayerHostilityTargetGoal(Mob mob) {
        super(mob, Player.class, 10, true, false,
            (Predicate<LivingEntity>) le -> isHuntablePlayer(mob, le));
    }

    private static boolean isHuntablePlayer(Mob mob, LivingEntity le) {
        if (!Config.HUNTING_ENABLED.get()) {
            return false;
        }
        if (!(le instanceof Player player) || player.isCreative() || player.isSpectator()) {
            return false;
        }
        // A held bone calms a wolf (it reads as taming intent), even though a bone isn't wolf "food".
        if (mob instanceof Wolf
                && (player.getMainHandItem().is(Items.BONE) || player.getOffhandItem().is(Items.BONE))) {
            return false;
        }
        if (Config.PREDATORS_PACIFIED_BY_FOOD.get() && mob instanceof Animal animal
                && (animal.isFood(player.getMainHandItem()) || animal.isFood(player.getOffhandItem()))) {
            return false;
        }
        return true;
    }
}
