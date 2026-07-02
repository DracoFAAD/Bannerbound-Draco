package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.vanilla.VanillaContentState;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent;

/**
 * Suppresses vanilla lightning-strike transformations when vanilla content is stripped
 * ({@link VanillaContentState#isEnabled()} is false). In a from-scratch settlement a thunderstorm
 * shouldn't quietly turn a farmer's pig into a zombified piglin or a villager into a witch — those
 * are vanilla-mob plumbing the settlement doesn't speak.
 *
 * <p>This is deliberately narrow: it cancels the strike <i>only</i> for the four entities whose
 * vanilla {@code thunderHit} converts them — {@link Pig} → zombified piglin, {@link Villager} →
 * witch, {@link MushroomCow} variant toggle, {@link Creeper} → charged. Every other entity (players,
 * cows, item frames, structures) is left to take normal lightning damage and fire, so the storm
 * still feels real; it just won't mutate our livestock and townsfolk into hostiles. Parallels
 * {@link HostileSpawnGate}, which strips the spawning side of the same vanilla layer.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class LightningConversionGate {
    private LightningConversionGate() {
    }

    @SubscribeEvent
    public static void onStruckByLightning(EntityStruckByLightningEvent event) {
        if (VanillaContentState.isEnabled()) return; // vanilla untouched
        if (transforms(event.getEntity())) event.setCanceled(true);
    }

    private static boolean transforms(Entity e) {
        return e instanceof Pig || e instanceof Villager || e instanceof MushroomCow
            || e instanceof Creeper;
    }
}
