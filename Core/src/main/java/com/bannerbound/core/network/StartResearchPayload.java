package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

@ApiStatus.Internal
public record StartResearchPayload(String researchId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StartResearchPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "start_research"));

    public static final StreamCodec<ByteBuf, StartResearchPayload> STREAM_CODEC =
        ByteBufCodecs.STRING_UTF8.map(StartResearchPayload::new, StartResearchPayload::researchId);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
