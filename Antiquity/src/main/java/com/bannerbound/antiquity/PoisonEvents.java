package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.item.AntidoteItem;
import com.bannerbound.antiquity.item.PoisonedFoodData;
import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.antiquity.poison.Poisons;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Drives the poison lifecycle for EVERY living entity — player, wild animal, or citizen — so a single
 * shared path handles escalation, damage-over-time and each poison's signature effect (vanilla's
 * {@link HuntingCombatEvents} bleed handler is {@code Animal}-only, hence a separate subscriber here).
 *
 * <p>Hot path: this fires for every {@link LivingEntity} every tick, so the not-poisoned case is a
 * single attachment read returning the {@code NONE} sentinel and an immediate bail with no allocation.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class PoisonEvents {
    private PoisonEvents() {}

    @SubscribeEvent
    static void onLivingPoisonTick(EntityTickEvent.Post event) {
        if (!Config.POISON_ENABLED.get() || !(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        if (!(living.level() instanceof ServerLevel level)) {
            return;
        }
        tickFoodPoison(living, level); // a delayed dose from laced food may now be due
        if (Poisons.isPoisoned(living)) {
            Poisons.tickPoison(living, level);
        }
    }

    /** Apply a pending food-poison dose once its delay elapses (set when the victim ate laced food). */
    private static void tickFoodPoison(LivingEntity living, ServerLevel level) {
        long applyAt = living.getData(BannerboundAntiquity.POISON_FOOD_APPLY_AT.get());
        if (applyAt <= 0L || level.getGameTime() < applyAt) {
            return;
        }
        PoisonType type = PoisonType.fromId(living.getData(BannerboundAntiquity.POISON_FOOD_TYPE.get()));
        int stage = living.getData(BannerboundAntiquity.POISON_FOOD_STAGE.get());
        living.setData(BannerboundAntiquity.POISON_FOOD_APPLY_AT.get(), 0L);
        living.setData(BannerboundAntiquity.POISON_FOOD_TYPE.get(), "");
        living.setData(BannerboundAntiquity.POISON_FOOD_STAGE.get(), 0);
        if (type != null && stage > 0) {
            Poisons.applyPoisonAtStage(living, type, stage); // dose → starting stage
        }
    }

    /** Eating laced food schedules its poison a short while later (so the meal isn't the obvious cause).
     *  Fires for any entity that finishes eating; only laced stacks carry the {@code POISONED_FOOD}
     *  component, so the clean-food case is one component read. */
    @SubscribeEvent
    static void onFinishEating(LivingEntityUseItemEvent.Finish event) {
        if (!Config.POISON_ENABLED.get() || event.getEntity().level().isClientSide) {
            return;
        }
        PoisonedFoodData laced = event.getItem().get(BannerboundAntiquity.POISONED_FOOD.get());
        if (laced == null || PoisonType.fromId(laced.poisonId()) == null) {
            return;
        }
        LivingEntity eater = event.getEntity();
        eater.setData(BannerboundAntiquity.POISON_FOOD_APPLY_AT.get(),
            eater.level().getGameTime() + Config.POISON_FOOD_DELAY_TICKS.get());
        eater.setData(BannerboundAntiquity.POISON_FOOD_TYPE.get(), laced.poisonId());
        eater.setData(BannerboundAntiquity.POISON_FOOD_STAGE.get(), laced.dose());
    }

    /** Oleander attacks the healing system: while it's active, ALL healing is blocked — natural regen,
     *  golden apples, regen potions, everything — so any damage taken sticks while its cardiac clock
     *  runs down. Fires for every healing entity, so the not-oleander case is one attachment read. */
    @SubscribeEvent
    static void onLivingHeal(LivingHealEvent event) {
        if (Config.POISON_ENABLED.get() && Poisons.blocksHealing(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** Shift-right-click any poisoned creature (mob, player, or citizen) WITH an antidote to cure THEM
     *  of that antidote's poison. Handled on {@code EntityInteract}, which fires BEFORE the target's own
     *  interaction — so it works even on entities that open a menu on right-click (citizens). Only fires
     *  when the target actually has the matching poison; otherwise it passes through (no wasted antidote,
     *  normal interaction still happens). */
    @SubscribeEvent
    static void onAntidoteOnEntity(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || !Config.POISON_ENABLED.get()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof AntidoteItem antidote)
            || !(event.getTarget() instanceof LivingEntity target)
            || Poisons.getPoison(target).type() != antidote.cures()) {
            return;
        }
        if (!player.level().isClientSide) {
            Poisons.cure(target, antidote.cures()); // clears only this antidote's poison + plays the heal cue
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(player.level().isClientSide));
    }

    /** A curare-unconscious player can't act — cancel their own attacks and interactions (the dragger
     *  is never unconscious, so the kidnap grab / antidote-on-target are unaffected). NeoForge forbids
     *  subscribing to the abstract {@link PlayerInteractEvent} base, so each concrete right/left-click
     *  subclass gets a thin handler delegating to {@link #cancelIfUnconscious}. */
    @SubscribeEvent
    static void onUnconsciousRightClickBlock(PlayerInteractEvent.RightClickBlock event) { cancelIfUnconscious(event); }

    @SubscribeEvent
    static void onUnconsciousRightClickItem(PlayerInteractEvent.RightClickItem event) { cancelIfUnconscious(event); }

    @SubscribeEvent
    static void onUnconsciousLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) { cancelIfUnconscious(event); }

    @SubscribeEvent
    static void onUnconsciousEntityInteract(PlayerInteractEvent.EntityInteract event) { cancelIfUnconscious(event); }

    @SubscribeEvent
    static void onUnconsciousEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) { cancelIfUnconscious(event); }

    private static void cancelIfUnconscious(PlayerInteractEvent event) {
        if (curareUnconscious(event.getEntity()) && event instanceof ICancellableEvent cancellable) {
            cancellable.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onUnconsciousAttack(AttackEntityEvent event) {
        if (curareUnconscious(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** No fall damage while curare-unconscious — honours the non-lethal promise and avoids the tow/
     *  pin velocity spiking into a fall hit. */
    @SubscribeEvent
    static void onUnconsciousFall(LivingIncomingDamageEvent event) {
        if (event.getSource().is(DamageTypeTags.IS_FALL) && curareUnconscious(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private static boolean curareUnconscious(LivingEntity entity) {
        return Poisons.isCurareUnconscious(entity, entity.level().getGameTime());
    }
}
