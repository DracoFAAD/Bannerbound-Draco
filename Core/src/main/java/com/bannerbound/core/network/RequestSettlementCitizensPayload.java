package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: open the Citizens screen for the requesting player's settlement. The server
 * looks up the player's settlement and replies with {@link SettlementCitizensListPayload}.
 */
@ApiStatus.Internal
public record RequestSettlementCitizensPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestSettlementCitizensPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "request_settlement_citizens"));

    public static final StreamCodec<ByteBuf, RequestSettlementCitizensPayload> STREAM_CODEC =
        StreamCodec.unit(new RequestSettlementCitizensPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
