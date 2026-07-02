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
 * Server → client: flash the detected shell of the home at {@code housePos} for {@code durationTicks}.
 * The detected boxes are already in {@link com.bannerbound.core.client.ClientSelectionState} (they
 * were broadcast as HOME selections), so this only needs to tell the client which home to draw and
 * for how long — the renderer reuses the home silhouette in a fixed colour. Stored in
 * {@link com.bannerbound.core.client.DetectPreviewState}.
 */
@ApiStatus.Internal
public record ShowDetectPreviewPayload(BlockPos housePos, int durationTicks) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ShowDetectPreviewPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "show_detect_preview"));

    public static final StreamCodec<ByteBuf, ShowDetectPreviewPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, ShowDetectPreviewPayload::housePos,
            ByteBufCodecs.VAR_INT, ShowDetectPreviewPayload::durationTicks,
            ShowDetectPreviewPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
