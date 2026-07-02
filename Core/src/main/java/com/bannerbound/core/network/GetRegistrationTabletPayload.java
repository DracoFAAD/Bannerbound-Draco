package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

@ApiStatus.Internal
public record GetRegistrationTabletPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<GetRegistrationTabletPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "get_registration_tablet"));

    public static final StreamCodec<ByteBuf, GetRegistrationTabletPayload> STREAM_CODEC =
        StreamCodec.unit(new GetRegistrationTabletPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
