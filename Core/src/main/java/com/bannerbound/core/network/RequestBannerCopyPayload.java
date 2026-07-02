package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: the player clicked the banner editor's "Take a copy" button — asks the server to hand
 * them a cosmetic banner item carrying the faction's full design (color + Heraldry patterns).
 * Placed copies are pure decoration while the main banner stands; the server re-validates
 * membership and Heraldry access.
 */
@ApiStatus.Internal
public record RequestBannerCopyPayload() implements CustomPacketPayload {
    public static final RequestBannerCopyPayload INSTANCE = new RequestBannerCopyPayload();

    public static final CustomPacketPayload.Type<RequestBannerCopyPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "request_banner_copy"));

    public static final StreamCodec<ByteBuf, RequestBannerCopyPayload> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
