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
 * Server → client: open the carpenter's-table saw minigame for the receiving player. The server
 * holds the authoritative session (player + table pos); the client plays the (non-skill) sawing
 * animation and replies with {@link CarpentryActionPayload}. The log + saw are drawn in-world by the
 * table's renderer, reading the table's budget — so the only thing this payload carries is the pos
 * and how many saw strokes the queued batch takes.
 *
 * @param pos           the carpenter's table the session belongs to
 * @param strokesNeeded saw strokes to complete the batch (scaled to batch size, capped)
 */
@ApiStatus.Internal
public record OpenCarpentrySawPayload(BlockPos pos, int strokesNeeded) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenCarpentrySawPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "open_carpentry_saw"));

    public static final StreamCodec<ByteBuf, OpenCarpentrySawPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenCarpentrySawPayload::pos,
            ByteBufCodecs.VAR_INT, OpenCarpentrySawPayload::strokesNeeded,
            OpenCarpentrySawPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
