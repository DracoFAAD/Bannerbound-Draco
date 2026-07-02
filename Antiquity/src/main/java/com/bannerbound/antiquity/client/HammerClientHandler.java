package com.bannerbound.antiquity.client;

import com.bannerbound.antiquity.network.OpenHammerPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Opens the cold-hammer minigame screen when the server sends {@link OpenHammerPayload}. */
@OnlyIn(Dist.CLIENT)
public final class HammerClientHandler {
    private HammerClientHandler() {}

    public static void open(OpenHammerPayload payload) {
        Minecraft.getInstance().setScreen(
            new HammerScreen(payload.pos(), payload.strikes(), payload.canSuperior()));
    }
}
