package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server. The "Establish outpost here" confirm from the banner screen: turn the faction
 * banner standing at {@code bannerPos} into an outpost (working claim) on its chunk. The server
 * re-validates everything (membership, research, range, cap, chunk availability) in
 * {@code Outpost.tryEstablish} and replies with a fresh outpost screen.
 */
@ApiStatus.Internal
public record EstablishOutpostPayload(BlockPos bannerPos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<EstablishOutpostPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "establish_outpost"));

    public static final StreamCodec<io.netty.buffer.ByteBuf, EstablishOutpostPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, EstablishOutpostPayload::bannerPos,
            EstablishOutpostPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
