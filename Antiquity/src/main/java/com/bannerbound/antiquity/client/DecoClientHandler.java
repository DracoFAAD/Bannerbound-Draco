package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.network.DecoChunkSyncPayload;
import com.bannerbound.antiquity.network.DecoUpdatePayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client-only sink for the face-decoration sync payloads (referenced only from the Dist.CLIENT
 *  branch of AntiquityNetwork, so the server never classloads it). Feeds {@link ClientDecorations}. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class DecoClientHandler {
    private DecoClientHandler() {}

    public static void applyChunk(DecoChunkSyncPayload payload) {
        ClientDecorations.putChunk(payload.chunkX(), payload.chunkZ(), payload.entries());
    }

    public static void applyUpdate(DecoUpdatePayload payload) {
        ClientDecorations.applyUpdate(payload.entry());
    }
}
