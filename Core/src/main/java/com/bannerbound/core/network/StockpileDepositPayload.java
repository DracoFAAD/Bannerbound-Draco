package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: deposit the cursor (carried) stack into the open Stockpile terminal by clicking
 * the storage grid. {@code single} = right-click (one item) vs left-click (the whole carried stack).
 * Handled by {@code StockpileMenu.deposit}.
 */
@ApiStatus.Internal
public record StockpileDepositPayload(int containerId, boolean single) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StockpileDepositPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "stockpile_deposit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StockpileDepositPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StockpileDepositPayload::containerId,
            ByteBufCodecs.BOOL, StockpileDepositPayload::single,
            StockpileDepositPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
