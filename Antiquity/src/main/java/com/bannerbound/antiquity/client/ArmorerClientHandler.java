package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.network.OpenArmorerPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client entry point for {@link OpenArmorerPayload} — opens the Armorer's Workbench design screen. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ArmorerClientHandler {
    private ArmorerClientHandler() {}

    public static void open(OpenArmorerPayload payload) {
        Minecraft.getInstance().setScreen(new ArmorerScreen(payload.pos()));
    }
}
