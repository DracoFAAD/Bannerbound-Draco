package com.bannerbound.core.network;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: open the Chronicle, optionally focused on an entry. */
public record OpenCodexScreenPayload(String entryId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenCodexScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_codex"));

    public static final StreamCodec<ByteBuf, OpenCodexScreenPayload> STREAM_CODEC =
        StreamCodec.composite(ByteBufCodecs.STRING_UTF8, OpenCodexScreenPayload::entryId, OpenCodexScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
