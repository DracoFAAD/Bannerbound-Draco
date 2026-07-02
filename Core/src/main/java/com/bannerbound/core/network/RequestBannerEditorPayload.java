package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: the player clicked the town hall's "Banner" button — asks the server to validate
 * (member, Heraldry researched) and answer with an {@link OpenBannerEditorPayload} snapshot.
 */
@ApiStatus.Internal
public record RequestBannerEditorPayload() implements CustomPacketPayload {
    public static final RequestBannerEditorPayload INSTANCE = new RequestBannerEditorPayload();

    public static final CustomPacketPayload.Type<RequestBannerEditorPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "request_banner_editor"));

    public static final StreamCodec<ByteBuf, RequestBannerEditorPayload> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
