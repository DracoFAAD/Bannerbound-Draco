package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Client → server: withdraw from the open Stockpile terminal. {@code template} identifies the item
 * (count 1, components matched); {@code half} = right-click/shift (half a stack) vs left-click (a
 * full stack). Handled by {@code StockpileMenu.withdraw}.
 */
@ApiStatus.Internal
public record StockpileWithdrawPayload(int containerId, ItemStack template, boolean half)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StockpileWithdrawPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "stockpile_withdraw"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StockpileWithdrawPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StockpileWithdrawPayload::containerId,
            ItemStack.STREAM_CODEC, StockpileWithdrawPayload::template,
            ByteBufCodecs.BOOL, StockpileWithdrawPayload::half,
            StockpileWithdrawPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
