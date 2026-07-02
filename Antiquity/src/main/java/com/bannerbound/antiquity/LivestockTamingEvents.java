package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.HuntingFear;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Feeding an untameable livestock animal (cow, sheep, pig, chicken, …) its favourite food once
 * <b>tames</b> it: from then on it reverts to vanilla behaviour — it no longer flees the player, drops
 * no footprints, and is exempt from the hunting fear/bleed (see {@link HuntingFear#isTamed}). The
 * {@code TAMED_LIVESTOCK} flag is serialized, so it sticks for that animal forever.
 *
 * <p>Pets/mounts that have their own vanilla taming (wolves, cats, horses) and predators (ocelots)
 * are left to their own rules. Runs at {@link EventPriority#HIGHEST} so taming still happens even when
 * Core's {@code AnimalBreedingGate} later cancels the breeding interaction for an un-researched player
 * — calming an animal is separate from breeding it.</p>
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class LivestockTamingEvents {
    private LivestockTamingEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onFeed(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getTarget() instanceof Animal animal)
                || animal instanceof TamableAnimal || animal instanceof AbstractHorse
                || animal instanceof Ocelot) {
            return; // only untameable livestock; pets/mounts/predators keep their own rules
        }
        if (HuntingFear.isTamed(animal)) {
            return; // already tamed — don't re-burst hearts
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !animal.isFood(stack)) {
            return; // must be its favourite food
        }
        HuntingFear.setTamed(animal);
        if (animal.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HEART,
                animal.getX(), animal.getY() + animal.getBbHeight() * 0.6, animal.getZ(),
                7, animal.getBbWidth(), 0.5, animal.getBbWidth(), 0.0);
        }
    }
}
