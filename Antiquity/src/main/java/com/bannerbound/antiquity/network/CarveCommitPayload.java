package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: the player committed an in-world carve while its ghost preview was showing. The previewed
 * block is hidden (air) on the client, so vanilla's pick can't hit it — the use-key press is
 * forwarded here with the anchor position and the server replays the carve there
 * ({@code Carves.commit}).
 */
@ApiStatus.Internal
public record CarveCommitPayload(BlockPos anchor) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CarveCommitPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundAntiquity.MODID, "carve_commit"));

    public static final StreamCodec<ByteBuf, CarveCommitPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, CarveCommitPayload::anchor,
        CarveCommitPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
