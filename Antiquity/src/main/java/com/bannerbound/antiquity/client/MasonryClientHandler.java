package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.network.OpenMasonChiselPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for the server's {@link OpenMasonChiselPayload} — opens the chisel minigame. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class MasonryClientHandler {
    private MasonryClientHandler() {
    }

    public static void open(OpenMasonChiselPayload payload) {
        Minecraft.getInstance().setScreen(new MasonChiselScreen(payload));
    }
}
