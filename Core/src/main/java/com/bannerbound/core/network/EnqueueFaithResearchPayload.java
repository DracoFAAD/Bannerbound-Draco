package com.bannerbound.core.network;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: queue (or toggle off) a faith-tree node — right-click on the FAITH tab. */
public record EnqueueFaithResearchPayload(String researchId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<EnqueueFaithResearchPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "enqueue_faith_research"));

    public static final StreamCodec<ByteBuf, EnqueueFaithResearchPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> ByteBufCodecs.STRING_UTF8.encode(buf, p.researchId()),
        buf -> new EnqueueFaithResearchPayload(ByteBufCodecs.STRING_UTF8.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
