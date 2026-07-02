package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.HuntingFear;
import com.bannerbound.antiquity.entity.SpearProjectile;
import com.bannerbound.antiquity.item.HideQuality;
import com.bannerbound.antiquity.item.PoisonedFoodData;
import com.bannerbound.antiquity.poison.PoisonState;
import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.antiquity.poison.Poisons;
import com.bannerbound.antiquity.tannery.HideGrading;
import com.bannerbound.antiquity.tannery.Hides;
import com.bannerbound.antiquity.tannery.WeaponCategory;
import com.bannerbound.core.entity.BreedingEvents;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/**
 * Animals drop bones when killed — the bootstrap material for bone tools. Adults only (no baby
 * drops, matching vanilla). Chicken 50% → 1; cow/sheep/goat/pig 100% → 1–3; horse 100% → 2–4.
 * Added on top of the entity's normal loot.
 *
 * <p>This handler ALSO drops a quality-tagged raw HIDE (TANNERY plan): for the five hide species
 * (cow/sheep/pig/goat/horse) it adds a {@code <species>_hide} stamped with a {@link HideQuality} —
 * graded by the weapon-vs-preference for wild kills, or by living conditions for a player-slaughtered
 * domesticated animal. (The herder's auto-cull adds hides separately via {@code HerderHooks}, since
 * it bypasses this event.) Runs at default priority — before {@code DropGatingEvents} (LOW, strips
 * unknown items) and {@code HunterKillEvents} (LOWEST, reroutes to the hunter's depot).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class AnimalDropsEvents {
    private AnimalDropsEvents() {
    }

    @SubscribeEvent
    static void onAnimalDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.isBaby()) return;
        if (!(entity.level() instanceof ServerLevel level)) return;

        int bones = boneCountFor(entity, level.getRandom());
        if (bones > 0) {
            ItemEntity drop = new ItemEntity(level,
                entity.getX(), entity.getY() + 0.2, entity.getZ(),
                new ItemStack(Items.BONE, bones));
            drop.setDefaultPickUpDelay();
            event.getDrops().add(drop);
        }

        // A poisoned animal yields a ruined hide and tainted meat (see poisonKill for the wolfsbane
        // exception).
        PoisonType poison = poisonKill(entity, event.getSource());
        addHide(event, level, entity, poison);
        if (poison != null) {
            laceMeat(event, poison);
        }
    }

    /** The poison that should taint an animal's spoils, or {@code null}.
     *
     *  <p>Wolfsbane is the HUNTING poison: immobilise-then-spear is the intended kill, so its hide must
     *  still grade normally — only a wolfsbane animal that actually DIED FROM the poison (killing blow
     *  was POISON_DAMAGE) yields tainted spoils. Every OTHER poison taints as long as it's active in the
     *  animal at death, however it was killed. */
    private static PoisonType poisonKill(LivingEntity entity, DamageSource source) {
        PoisonState state = Poisons.getPoison(entity);
        if (!state.active()) return null;
        PoisonType type = state.type();
        if (type == PoisonType.WOLFSBANE) {
            return (source != null && source.is(BannerboundAntiquity.POISON_DAMAGE)) ? type : null;
        }
        return type;
    }

    /** Stamp the corresponding poison onto any FOOD the animal dropped (raw meat) — eat it and you're
     *  poisoned, the same as laced food. Wild kill → no poisoner (no settlement tooltip). */
    private static void laceMeat(LivingDropsEvent event, PoisonType poison) {
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            if (stack.has(DataComponents.FOOD)) {
                stack.set(BannerboundAntiquity.POISONED_FOOD.get(), new PoisonedFoodData(poison.id(), 1, ""));
            }
        }
    }

    /** Adds a quality-tagged raw hide for the five hide species (no-op for others). A poison death
     *  ruins it to POOR regardless of weapon/conditions. */
    private static void addHide(LivingDropsEvent event, ServerLevel level, LivingEntity entity, PoisonType poison) {
        Item hide = Hides.hideFor(entity.getType());
        if (hide == null) return;

        HideQuality quality;
        if (poison != null) {
            quality = HideQuality.POOR; // tainted by poison — a ruined hide
        } else if (HuntingFear.isTamed(entity)) {
            // Domesticated animal slaughtered by hand: graded by local living conditions (no herder
            // skill on a player kill — the auto-cull path supplies the skill term via the hook).
            quality = HideGrading.gradeHerd(
                BreedingEvents.breedChance(level, entity.blockPosition()), 0);
        } else {
            quality = HideGrading.gradeHunt(entity.getType(), weaponCategory(event.getSource()));
        }

        ItemStack stack = new ItemStack(hide);
        stack.set(BannerboundAntiquity.HIDE_QUALITY.get(), quality);
        ItemEntity drop = new ItemEntity(level,
            entity.getX(), entity.getY() + 0.2, entity.getZ(), stack);
        drop.setDefaultPickUpDelay();
        event.getDrops().add(drop);
    }

    /** The weapon category of a kill, or {@code null} (improper kill → POOR). Projectiles decide by
     *  their type; melee/throw by the killer's held weapon (NPC job tool or player main hand). */
    private static WeaponCategory weaponCategory(DamageSource source) {
        if (source == null) return null;
        Entity direct = source.getDirectEntity();
        if (direct instanceof SpearProjectile) return WeaponCategory.SPEAR;
        if (direct instanceof AbstractArrow) return WeaponCategory.ARROW;
        Entity killer = source.getEntity();
        if (killer instanceof CitizenEntity c) return WeaponCategory.of(c.getJobTool());
        if (killer instanceof Player p) return WeaponCategory.of(p.getMainHandItem());
        return null;
    }

    private static int boneCountFor(LivingEntity entity, RandomSource rng) {
        if (entity instanceof Chicken) {
            return rng.nextFloat() < 0.5f ? 1 : 0;
        }
        if (entity instanceof Horse) {
            return 2 + rng.nextInt(3); // 2–4
        }
        if (entity instanceof Cow || entity instanceof Sheep
                || entity instanceof Goat || entity instanceof Pig) {
            return 1 + rng.nextInt(3); // 1–3
        }
        if (entity instanceof Wolf || entity instanceof Ocelot) {
            return 1 + rng.nextInt(2); // 1–2 — predators yield bone too
        }
        return 0;
    }
}
