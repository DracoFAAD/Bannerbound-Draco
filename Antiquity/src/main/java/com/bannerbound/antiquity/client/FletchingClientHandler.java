package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.network.OpenFletchingPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for the server's {@link OpenFletchingPayload} — opens the minigame screen. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class FletchingClientHandler {
    private FletchingClientHandler() {
    }

    public static void open(OpenFletchingPayload payload) {
        Minecraft.getInstance().setScreen(new FletchingScreen(payload));
    }
}
