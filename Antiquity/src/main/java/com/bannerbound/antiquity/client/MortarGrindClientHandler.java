package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.network.OpenMortarGrindPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only entry point for the server's mortar grind minigame payload. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class MortarGrindClientHandler {
    private MortarGrindClientHandler() {}

    public static void open(OpenMortarGrindPayload payload) {
        Minecraft.getInstance().setScreen(new MortarGrindScreen(payload));
    }
}
