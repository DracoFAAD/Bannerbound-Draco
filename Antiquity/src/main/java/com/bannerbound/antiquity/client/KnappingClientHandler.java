package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.network.OpenKnappingPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for the server's {@link OpenKnappingPayload} — opens the knapping screen. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class KnappingClientHandler {
    private KnappingClientHandler() {
    }

    public static void open(OpenKnappingPayload payload) {
        Minecraft.getInstance().setScreen(new KnappingScreen(payload));
    }
}
