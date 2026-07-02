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
 * C→S: the player right-clicked one of a workstation's floating ghost-preview targets (browse
 * arrows or the ghost result). The targets are pure client-side billboards — no entity, no block —
 * so the client ray-tests them itself ({@code GhostClickEvents}) and forwards the intent; the
 * server validates range/locks and acts ({@code GhostWorkstationActions.serverHandle}).
 */
@ApiStatus.Internal
public record GhostActionPayload(BlockPos pos, int action) implements CustomPacketPayload {
    /** Left browse arrow — cycle the ghost preview to the previous candidate recipe. */
    public static final int CYCLE_LEFT = 0;
    /** Right browse arrow — cycle the ghost preview to the next candidate recipe. */
    public static final int CYCLE_RIGHT = 1;
    /** The ghost result — pull the missing ingredients from the player's inventory. */
    public static final int FILL = 2;

    public static final CustomPacketPayload.Type<GhostActionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundAntiquity.MODID, "ghost_action"));

    public static final StreamCodec<ByteBuf, GhostActionPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, GhostActionPayload::pos,
        ByteBufCodecs.VAR_INT, GhostActionPayload::action,
        GhostActionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
