package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S→C: the server marked the drop-off (or the citizen vanished) — leave drop-location edit mode,
 *  so the client stops drawing the wireframe / prompt. */
@ApiStatus.Internal
public record EndDropLocationEditPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<EndDropLocationEditPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "end_drop_location_edit"));

    public static final StreamCodec<ByteBuf, EndDropLocationEditPayload> STREAM_CODEC =
        StreamCodec.unit(new EndDropLocationEditPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
