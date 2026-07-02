package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the beauty-debug overlay asking for the diminishing-returns state of the
 * block at {@code pos} (how many of that block type the chunk's surface-and-up scan counted).
 * Sent only while debug mode is active.
 */
@ApiStatus.Internal
public record RequestBlockAppealPayload(BlockPos pos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestBlockAppealPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "request_block_appeal"));

    public static final StreamCodec<ByteBuf, RequestBlockAppealPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, RequestBlockAppealPayload::pos,
            RequestBlockAppealPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
