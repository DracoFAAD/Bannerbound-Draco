package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: open the mason's-bench chisel-strike minigame for the receiving player. The
 * server holds the authoritative session (player + bench pos); the client plays the timed-strike
 * minigame and replies with {@link MasonryActionPayload}. The stone + chisel are drawn in-world by
 * the bench's renderer, so the only thing this payload carries is the pos and how many strikes the
 * queued batch takes.
 *
 * @param pos            the mason's bench the session belongs to
 * @param strikesNeeded  chisel strikes to complete the batch (scaled to batch size, capped)
 */
@ApiStatus.Internal
public record OpenMasonChiselPayload(BlockPos pos, int strikesNeeded) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenMasonChiselPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "open_mason_chisel"));

    public static final StreamCodec<ByteBuf, OpenMasonChiselPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenMasonChiselPayload::pos,
            ByteBufCodecs.VAR_INT, OpenMasonChiselPayload::strikesNeeded,
            OpenMasonChiselPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
