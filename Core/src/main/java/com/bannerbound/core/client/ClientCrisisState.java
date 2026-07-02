package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.CrisisStatePayload;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client mirror used by HUD layers and the town-hall world marker. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientCrisisState {
    private static CrisisStatePayload state = CrisisStatePayload.empty();

    private ClientCrisisState() {
    }

    public static void replace(CrisisStatePayload payload) {
        state = payload == null ? CrisisStatePayload.empty() : payload;
    }

    public static CrisisStatePayload get() {
        return state;
    }

    public static boolean active() {
        return state.active();
    }

    public static boolean awaitingChoice() {
        return state.active() && state.awaitingChoice();
    }

    public static BlockPos townHallPos() {
        return state.townHallPos() == 0L ? null : BlockPos.of(state.townHallPos());
    }
}
