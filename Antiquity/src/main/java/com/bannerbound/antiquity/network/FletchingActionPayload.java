package com.bannerbound.antiquity.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: a step in the active fletching minigame.
 * <ul>
 *   <li>{@link #COMMIT} — fired on the FIRST stretch release; the server consumes the station's pile
 *       (the commitment point — cancelling after this forfeits the inputs).</li>
 *   <li>{@link #COMPLETE} — fired after the last stretch; {@code scores} carries the per-stretch
 *       0–100 scores. The server aggregates them, rolls the quality tier, and pops the finished item.</li>
 *   <li>{@link #CANCEL} — the player aborted (Escape). Server clears the session; if it had committed,
 *       the inputs are already gone (forfeit), otherwise the untouched pile stays on the station.</li>
 * </ul>
 *
 * @param pos    the station the session belongs to (matched against the server session)
 * @param action one of {@link #COMMIT} / {@link #COMPLETE} / {@link #CANCEL}
 * @param scores per-stretch scores (only meaningful for {@link #COMPLETE}; empty otherwise)
 */
@ApiStatus.Internal
public record FletchingActionPayload(BlockPos pos, int action, List<Integer> scores)
        implements CustomPacketPayload {
    public static final int COMMIT = 0;
    public static final int COMPLETE = 1;
    public static final int CANCEL = 2;

    public static final CustomPacketPayload.Type<FletchingActionPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "fletching_action"));

    public static final StreamCodec<ByteBuf, FletchingActionPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, FletchingActionPayload::pos,
            ByteBufCodecs.VAR_INT, FletchingActionPayload::action,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), FletchingActionPayload::scores,
            FletchingActionPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
