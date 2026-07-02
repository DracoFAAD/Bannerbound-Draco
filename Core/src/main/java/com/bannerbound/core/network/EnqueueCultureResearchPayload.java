package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Twin of {@link EnqueueResearchPayload} for the Culture tree. Right-click toggle. */
@ApiStatus.Internal
public record EnqueueCultureResearchPayload(String researchId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<EnqueueCultureResearchPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "enqueue_culture_research"));

    public static final StreamCodec<ByteBuf, EnqueueCultureResearchPayload> STREAM_CODEC =
        ByteBufCodecs.STRING_UTF8.map(EnqueueCultureResearchPayload::new, EnqueueCultureResearchPayload::researchId);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
