package com.bannerbound.antiquity;

import java.lang.reflect.Field;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.CitizenHostilityTargetGoal;
import com.bannerbound.antiquity.entity.PlayerHostilityTargetGoal;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Makes wild wolves and ocelots hostile to the player. Wolves: skip tamed/owned ones. Ocelots are
 * always wild, but vanilla makes them flee the player — so we strip that avoid goal before adding
 * the attack target. Both rely on their existing melee goals once a target is set.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class HuntingPredatorEvents {
    private static final ResourceLocation HOSTILE_SPEED_ID =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "hostile_speed");
    private static final ResourceLocation HOSTILE_ATTACK_ID =
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "hostile_attack");

    private static Field avoidClassField;
    private static boolean avoidClassFieldResolved;

    private HuntingPredatorEvents() {}

    @SubscribeEvent
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !Config.HUNTING_ENABLED.get()) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof Wolf wolf && Config.WILD_WOLVES_HOSTILE.get()) {
            if (wolf.isTame() || wolf.getOwnerUUID() != null) {
                return; // tamed/owned wolves stay friendly
            }
            // Wild wolves hunt both players and citizens. Player goal sits at the higher priority
            // (lower number) so a player in range is preferred; citizens are taken otherwise.
            HuntingAiInjectionEvents.addGoalOnce(wolf.targetSelector, 2,
                new PlayerHostilityTargetGoal(wolf), PlayerHostilityTargetGoal.class);
            HuntingAiInjectionEvents.addGoalOnce(wolf.targetSelector, 3,
                new CitizenHostilityTargetGoal(wolf), CitizenHostilityTargetGoal.class);
            buffPredator(wolf);
        } else if (entity instanceof Ocelot ocelot && Config.OCELOTS_HOSTILE.get()) {
            removeOcelotAvoidPlayer(ocelot);
            // Ocelots hunt both players and citizens — player at the higher priority (lower number)
            // so a player in range is preferred; citizens are taken otherwise.
            HuntingAiInjectionEvents.addGoalOnce(ocelot.targetSelector, 1,
                new PlayerHostilityTargetGoal(ocelot), PlayerHostilityTargetGoal.class);
            HuntingAiInjectionEvents.addGoalOnce(ocelot.targetSelector, 2,
                new CitizenHostilityTargetGoal(ocelot), CitizenHostilityTargetGoal.class);
            buffPredator(ocelot);
        }
    }

    /** Hostile predators hit harder and move faster. Transient modifiers (re-applied each join via
     *  the id-presence guard; not persisted, so disabling hunting and reloading reverts them). */
    private static void buffPredator(Mob mob) {
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(HOSTILE_SPEED_ID) == null) {
            speed.addTransientModifier(new AttributeModifier(HOSTILE_SPEED_ID,
                Config.HOSTILE_SPEED_MULT.get() - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        AttributeInstance attack = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null && attack.getModifier(HOSTILE_ATTACK_ID) == null) {
            attack.addTransientModifier(new AttributeModifier(HOSTILE_ATTACK_ID,
                Config.HOSTILE_ATTACK_BONUS.get(), AttributeModifier.Operation.ADD_VALUE));
        }
    }

    /** Strip the vanilla ocelot's "flee the player" avoid goal so it can attack instead. */
    private static void removeOcelotAvoidPlayer(Ocelot ocelot) {
        for (WrappedGoal wrapped : List.copyOf(ocelot.goalSelector.getAvailableGoals())) {
            if (wrapped.getGoal() instanceof AvoidEntityGoal<?> avoid && avoidsPlayers(avoid)) {
                ocelot.goalSelector.removeGoal(wrapped.getGoal());
            }
        }
    }

    /** True if the avoid goal targets players. Reads the protected {@code avoidClass} field
     *  reflectively (NeoForge runs Mojang mappings in production, so the name is stable); if that
     *  ever fails, fall back to true — the ocelot's only vanilla avoid goal is the player one. */
    private static boolean avoidsPlayers(AvoidEntityGoal<?> avoid) {
        try {
            if (!avoidClassFieldResolved) {
                avoidClassField = AvoidEntityGoal.class.getDeclaredField("avoidClass");
                avoidClassField.setAccessible(true);
                avoidClassFieldResolved = true;
            }
            if (avoidClassField != null) {
                return avoidClassField.get(avoid) == Player.class;
            }
        } catch (ReflectiveOperationException ignored) {
            avoidClassFieldResolved = true;
        }
        return true;
    }
}
