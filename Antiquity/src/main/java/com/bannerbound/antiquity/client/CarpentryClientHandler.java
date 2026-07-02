package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.network.OpenCarpentrySawPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for the server's {@link OpenCarpentrySawPayload} — opens the saw minigame. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class CarpentryClientHandler {
    private CarpentryClientHandler() {
    }

    public static void open(OpenCarpentrySawPayload payload) {
        Minecraft.getInstance().setScreen(new WoodworkingTableSawScreen(payload));
    }
}
