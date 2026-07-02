package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-only override of the vanilla {@code cast} item property on {@link Items#FISHING_ROD}.
 * Vanilla's predicate only returns 1.0 for a {@link Player} with an active fishing hook —
 * citizens (which aren't Players) would never trigger the bent-rod texture. We re-register the
 * same property so it ALSO returns 1.0 when held by a {@link CitizenEntity} whose
 * {@code isCasting()} flag is true (set by the fisher work goal while the bobber is out).
 * <p>
 * {@link ItemProperties#register} replaces any prior registration for the same key, so this
 * cleanly takes over from the vanilla one. Called from
 * {@code BannerboundCoreClient.onClientSetup} via {@code event.enqueueWork}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class FishingRodCastOverride {
    private static final ResourceLocation CAST_KEY = ResourceLocation.withDefaultNamespace("cast");

    private FishingRodCastOverride() {
    }

    public static void register() {
        ItemProperties.register(Items.FISHING_ROD, CAST_KEY, (stack, level, entity, seed) -> {
            if (entity == null) return 0.0f;
            boolean main = entity.getMainHandItem() == stack;
            boolean off = entity.getOffhandItem() == stack;
            // Vanilla quirk: if main hand also holds a fishing rod, off-hand version isn't drawn cast.
            if (entity.getMainHandItem().getItem() instanceof FishingRodItem) off = false;
            if (!(main || off)) return 0.0f;
            if (entity instanceof Player player && player.fishing != null) return 1.0f;
            if (entity instanceof CitizenEntity citizen && citizen.isCasting()) return 1.0f;
            return 0.0f;
        });
    }
}
