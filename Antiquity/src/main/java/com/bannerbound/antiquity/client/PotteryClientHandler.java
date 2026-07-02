package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.network.OpenPotteryPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for the server's pottery minigame payload. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class PotteryClientHandler {
    private PotteryClientHandler() {}

    public static void open(OpenPotteryPayload payload) {
        Minecraft.getInstance().setScreen(new PotteryScreen(payload));
    }
}
