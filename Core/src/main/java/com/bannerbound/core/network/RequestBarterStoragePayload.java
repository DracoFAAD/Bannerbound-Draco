package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: the open barter screen polls (every ~10 ticks) for a fresh storage snapshot so the offer can be
 * greyed out the instant the town inventory drops below what's on the table mid-negotiation. The server
 * replies with a {@link BarterStoragePayload}.
 */
@ApiStatus.Internal
public record RequestBarterStoragePayload(int messengerEntityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestBarterStoragePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "request_barter_storage"));

    public static final StreamCodec<ByteBuf, RequestBarterStoragePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> ByteBufCodecs.VAR_INT.encode(buf, p.messengerEntityId),
        buf -> new RequestBarterStoragePayload(ByteBufCodecs.VAR_INT.decode(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
