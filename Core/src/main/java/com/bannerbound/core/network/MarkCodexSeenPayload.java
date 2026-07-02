package com.bannerbound.core.network;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: player opened/read a Chronicle entry. */
public record MarkCodexSeenPayload(String entryId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MarkCodexSeenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "mark_codex_seen"));

    public static final StreamCodec<ByteBuf, MarkCodexSeenPayload> STREAM_CODEC =
        StreamCodec.composite(ByteBufCodecs.STRING_UTF8, MarkCodexSeenPayload::entryId, MarkCodexSeenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
