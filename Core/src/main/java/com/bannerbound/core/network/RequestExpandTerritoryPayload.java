package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server. Fired when the player clicks "Expand Territory" in the Town Hall screen.
 *  Server validates the player is in a settlement and replies with
 *  {@link OpenExpandTerritoryScreenPayload}. No body — server already knows the player. */
@ApiStatus.Internal
public record RequestExpandTerritoryPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestExpandTerritoryPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "request_expand_territory"));

    public static final StreamCodec<ByteBuf, RequestExpandTerritoryPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {},
        buf -> new RequestExpandTerritoryPayload()
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
