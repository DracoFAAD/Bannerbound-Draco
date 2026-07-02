package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

@ApiStatus.Internal
public record DisbandSettlementPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DisbandSettlementPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "disband_settlement"));

    public static final StreamCodec<ByteBuf, DisbandSettlementPayload> STREAM_CODEC =
        StreamCodec.unit(new DisbandSettlementPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
