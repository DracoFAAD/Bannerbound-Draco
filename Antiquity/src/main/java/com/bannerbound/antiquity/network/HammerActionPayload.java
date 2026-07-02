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
 * Client → server: a step in the active cold-hammer minigame.
 * <ul>
 *   <li>{@link #COMMIT} — first strike: the server consumes the casting (+stick) — the commitment point.</li>
 *   <li>{@link #COMPLETE} — after the last strike; {@code scores} carries the per-strike 0–100 grades.
 *       The server aggregates them, applies the hammer-rank quality gate, and gives the finished tool.</li>
 *   <li>{@link #CANCEL} — aborted; if it had committed, the inputs are already forfeit.</li>
 * </ul>
 */
@ApiStatus.Internal
public record HammerActionPayload(BlockPos pos, int action, List<Integer> scores)
        implements CustomPacketPayload {
    public static final int COMMIT = 0;
    public static final int COMPLETE = 1;
    public static final int CANCEL = 2;
    /** A single strike landed; {@code scores} carries its one 0–100 grade. The server fires the
     *  world-visible effects (sparks, hammer-clang, arm swing) so other players see/hear the smithing. */
    public static final int STRIKE = 3;

    public static final CustomPacketPayload.Type<HammerActionPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "hammer_action"));

    public static final StreamCodec<ByteBuf, HammerActionPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, HammerActionPayload::pos,
            ByteBufCodecs.VAR_INT, HammerActionPayload::action,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), HammerActionPayload::scores,
            HammerActionPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
