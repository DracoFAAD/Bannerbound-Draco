package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.network.OpenTanningPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Opens the tanning-rack scrape minigame when the open payload arrives. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class TanningClientHandler {
    private TanningClientHandler() {
    }

    public static void open(OpenTanningPayload payload) {
        Minecraft.getInstance().setScreen(new TanningRackScreen(payload));
    }
}
