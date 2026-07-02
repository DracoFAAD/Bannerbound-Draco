package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.BoarChargeGoal;
import com.bannerbound.antiquity.entity.FleeFromPlayerGoal;
import com.bannerbound.antiquity.entity.HerdFleeGoal;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Injects immersive-hunting AI into vanilla animals as they spawn/load (the
 * {@code AntiquityEvents.onCitizenJoinLevel} pattern: server-only, dedup before adding so chunk
 * reloads don't stack goals). Passive animals get flee + herd goals (pigs also get the boar charge);
 * wild wolves and ocelots become hostile.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class HuntingAiInjectionEvents {
    private HuntingAiInjectionEvents() {}

    @SubscribeEvent
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !Config.HUNTING_ENABLED.get()) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof Wolf || entity instanceof Ocelot) {
            // Predators (hostility) are handled in HuntingPredatorEvents (Part 4).
            return;
        }
        if (entity instanceof Animal animal) {
            double[] speed = speedFor(animal);
            // Pigs: boar charge at priority 0 so the elected charger PREEMPTS the flee goal (same
            // priority wouldn't — flee grabs MOVE on the scared edge and holds it, so the pig would
            // just flee). Non-chargers have no claim, so the charge yields and they flee.
            if (animal instanceof Pig pig) {
                addGoalOnce(pig.goalSelector, 0, new BoarChargeGoal(pig), BoarChargeGoal.class);
            }
            addGoalOnce(animal.goalSelector, 1,
                new FleeFromPlayerGoal(animal, speed[0], speed[1]), FleeFromPlayerGoal.class);
            addGoalOnce(animal.goalSelector, 1,
                new com.bannerbound.antiquity.entity.FleeFromHunterGoal(animal, speed[0], speed[1]),
                com.bannerbound.antiquity.entity.FleeFromHunterGoal.class);
            addGoalOnce(animal.goalSelector, 1,
                new HerdFleeGoal(animal, speed[1]), HerdFleeGoal.class);
        }
    }

    /** {walk, sprint} flee multipliers per species — cow slow (catchable), others outrun the player. */
    private static double[] speedFor(Animal animal) {
        if (animal instanceof Cow) {
            return new double[] {Config.COW_WALK_SPEED.get(), Config.COW_SPRINT_SPEED.get()};
        }
        if (animal instanceof AbstractHorse) {
            return new double[] {Config.HORSE_WALK_SPEED.get(), Config.HORSE_SPRINT_SPEED.get()};
        }
        if (animal instanceof Rabbit || animal instanceof Fox) {
            return new double[] {Config.FAST_WALK_SPEED.get(), Config.FAST_SPRINT_SPEED.get()};
        }
        return new double[] {Config.PREY_WALK_SPEED.get(), Config.PREY_SPRINT_SPEED.get()};
    }

    /** Add a goal only if one of its class isn't already present (idempotent across reloads). */
    static void addGoalOnce(GoalSelector selector, int priority, Goal goal, Class<? extends Goal> type) {
        if (selector.getAvailableGoals().stream().noneMatch(w -> type.isInstance(w.getGoal()))) {
            selector.addGoal(priority, goal);
        }
    }
}
