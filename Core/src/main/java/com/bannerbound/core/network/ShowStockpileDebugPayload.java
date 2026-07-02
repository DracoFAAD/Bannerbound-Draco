package com.bannerbound.core.network;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: flash a {@code StockpileEnclosure} scan as a wireframe for {@code durationTicks} —
 * the detected interior tiles (green), the connected container blocks (blue), and the exact failure
 * position if any (red). Reuses {@code SelectionRenderer}'s silhouette/box primitives; the data is
 * stashed client-side in {@code StockpileDebugState}. Debug aid until the storage terminal lands.
 */
@ApiStatus.Internal
public record ShowStockpileDebugPayload(List<BlockPos> interior, List<BlockPos> containers,
                                        Optional<BlockPos> failPos, int durationTicks)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ShowStockpileDebugPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "show_stockpile_debug"));

    public static final StreamCodec<ByteBuf, ShowStockpileDebugPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), ShowStockpileDebugPayload::interior,
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), ShowStockpileDebugPayload::containers,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), ShowStockpileDebugPayload::failPos,
            ByteBufCodecs.VAR_INT, ShowStockpileDebugPayload::durationTicks,
            ShowStockpileDebugPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
