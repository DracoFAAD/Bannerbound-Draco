package com.bannerbound.core.network;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: start researching a faith-tree node (left-click on the FAITH tab). */
public record StartFaithResearchPayload(String researchId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StartFaithResearchPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "start_faith_research"));

    public static final StreamCodec<ByteBuf, StartFaithResearchPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> ByteBufCodecs.STRING_UTF8.encode(buf, p.researchId()),
        buf -> new StartFaithResearchPayload(ByteBufCodecs.STRING_UTF8.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
