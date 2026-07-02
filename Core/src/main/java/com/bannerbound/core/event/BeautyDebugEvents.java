package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.client.ClientBeautyDebug;
import com.bannerbound.core.network.RequestBlockAppealPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client wiring for the beauty-debug toggle: registers the {@code B} keybind, flips
 * {@link ClientBeautyDebug} on press, and — while debug mode is on — asks the server for the
 * looked-at block's diminishing-returns count so the overlay can show its true current appeal.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class BeautyDebugEvents {
    /** Ticks between refresh queries while the player keeps looking at the same block. */
    private static final int QUERY_INTERVAL = 10;

    private static BlockPos lastQueryPos;
    private static int queryCooldown;

    private BeautyDebugEvents() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ClientBeautyDebug.TOGGLE_KEY);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (ClientBeautyDebug.TOGGLE_KEY.consumeClick()) {
            ClientBeautyDebug.toggle();
        }
        if (!ClientBeautyDebug.isEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() == HitResult.Type.MISS) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        if (queryCooldown > 0) queryCooldown--;
        // Query on a fresh target immediately, then refresh periodically while held on it.
        if (!pos.equals(lastQueryPos) || queryCooldown <= 0) {
            lastQueryPos = pos;
            queryCooldown = QUERY_INTERVAL;
            PacketDistributor.sendToServer(new RequestBlockAppealPayload(pos));
        }
    }
}
