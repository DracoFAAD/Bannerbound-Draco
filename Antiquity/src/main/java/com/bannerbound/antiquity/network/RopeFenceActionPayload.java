package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: the player left-clicked the rope part of a rope fence post at {@code pos}. Left-click is
 * cancelled client-side (so no block-break is predicted) and this is sent instead; the server then
 * either cancels the player's in-progress tie or breaks one of the post's ropes (see
 * {@code RopeFenceInteractions.serverHandle}). Vanilla's left-click packet can't carry this intent,
 * hence a dedicated payload.
 */
@ApiStatus.Internal
public record RopeFenceActionPayload(BlockPos pos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RopeFenceActionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundAntiquity.MODID, "rope_fence_action"));

    public static final StreamCodec<ByteBuf, RopeFenceActionPayload> STREAM_CODEC =
        BlockPos.STREAM_CODEC.map(RopeFenceActionPayload::new, RopeFenceActionPayload::pos);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
