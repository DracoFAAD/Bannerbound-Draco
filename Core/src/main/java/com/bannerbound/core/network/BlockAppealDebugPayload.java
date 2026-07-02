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
 * Server → client: the diminishing-returns state of the block at {@code pos}, answering a
 * {@link RequestBlockAppealPayload}.
 *
 * @param pos           the queried block position (matched against what the client is looking at)
 * @param queuePosition this block's 1-based slot in its type's diminishing-returns queue (0 = not
 *                      counted — underground, chunk not tracked, or pos isn't in a home's union
 *                      when {@code inHouse} is set). When {@code inHouse} is true the slot is the
 *                      home's per-type position; otherwise it's the chunk's.
 * @param tracked       whether the chunk has a scanned beauty record at all (irrelevant when
 *                      {@code inHouse} is true — a home's score doesn't depend on the chunk scan)
 * @param inHouse       true iff {@code pos} fell inside a home selection — UI then shows the
 *                      home's appeal instead of the chunk's
 * @param appeal        the fully-resolved appeal of this block (base appeal under the OWNING
 *                      settlement's culture styles × diminishing returns for its queue slot).
 *                      Computed server-side so it's viewer-independent — every client sees the
 *                      same value for the same block, regardless of their own settlement's styles.
 */
@ApiStatus.Internal
public record BlockAppealDebugPayload(BlockPos pos, int queuePosition, boolean tracked,
                                      boolean inHouse, float appeal)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlockAppealDebugPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "block_appeal_debug"));

    public static final StreamCodec<ByteBuf, BlockAppealDebugPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, BlockAppealDebugPayload::pos,
            ByteBufCodecs.VAR_INT, BlockAppealDebugPayload::queuePosition,
            ByteBufCodecs.BOOL, BlockAppealDebugPayload::tracked,
            ByteBufCodecs.BOOL, BlockAppealDebugPayload::inHouse,
            ByteBufCodecs.FLOAT, BlockAppealDebugPayload::appeal,
            BlockAppealDebugPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
