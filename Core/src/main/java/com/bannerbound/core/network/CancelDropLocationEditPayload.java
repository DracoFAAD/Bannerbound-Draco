package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: the player left drop-location edit mode (left-click / Escape / opened a screen) without
 *  marking. Clears the server-side edit flag so their next block right-click works normally. */
@ApiStatus.Internal
public record CancelDropLocationEditPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CancelDropLocationEditPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "cancel_drop_location_edit"));

    public static final StreamCodec<ByteBuf, CancelDropLocationEditPayload> STREAM_CODEC =
        StreamCodec.unit(new CancelDropLocationEditPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
