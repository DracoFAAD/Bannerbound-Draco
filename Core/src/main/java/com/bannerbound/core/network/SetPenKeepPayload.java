package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server. The player's chosen "keep how many adults alive" threshold for a pen (from
 * {@code PenKeepScreen}). Carries the pen marker pos and the keep count (0 = auto/full capacity); the
 * server re-validates and writes it into the pen marker's packed seedItemId.
 */
@ApiStatus.Internal
public record SetPenKeepPayload(BlockPos penPos, int keep) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetPenKeepPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "set_pen_keep"));

    public static final StreamCodec<ByteBuf, SetPenKeepPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, SetPenKeepPayload::penPos,
        ByteBufCodecs.VAR_INT, SetPenKeepPayload::keep,
        SetPenKeepPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
