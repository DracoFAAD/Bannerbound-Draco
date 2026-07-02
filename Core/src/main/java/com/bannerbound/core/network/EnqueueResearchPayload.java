package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

@ApiStatus.Internal
public record EnqueueResearchPayload(String researchId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<EnqueueResearchPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "enqueue_research"));

    public static final StreamCodec<ByteBuf, EnqueueResearchPayload> STREAM_CODEC =
        ByteBufCodecs.STRING_UTF8.map(EnqueueResearchPayload::new, EnqueueResearchPayload::researchId);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
