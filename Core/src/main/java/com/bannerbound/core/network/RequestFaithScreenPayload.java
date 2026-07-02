package com.bannerbound.core.network;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: the player clicked the town hall Faith button while the Choose-Faith window is
 * open — send back a fresh {@link OpenChooseFaithScreenPayload} snapshot.
 */
public record RequestFaithScreenPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestFaithScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "request_faith_screen"));

    public static final StreamCodec<ByteBuf, RequestFaithScreenPayload> STREAM_CODEC =
        StreamCodec.of((buf, p) -> { }, buf -> new RequestFaithScreenPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
