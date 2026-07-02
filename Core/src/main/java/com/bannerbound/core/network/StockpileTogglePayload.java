package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: flip one of the open Stockpile terminal's toggles. {@code toggle} picks which
 * flag ({@link #TOGGLE_DEPOSIT} = "let workers deposit here", {@link #TOGGLE_TAKE} = "let workers
 * take from here", {@link #TOGGLE_TRADE} = "show for trading") and {@code value} is its new state.
 * The server writes it onto the {@code Stockpile} record and invalidates the settlement
 * storage-pool cache so autonomous workers see the change immediately.
 */
@ApiStatus.Internal
public record StockpileTogglePayload(int containerId, int toggle, boolean value)
        implements CustomPacketPayload {
    public static final int TOGGLE_DEPOSIT = 0;
    public static final int TOGGLE_TAKE = 1;
    public static final int TOGGLE_TRADE = 2;

    public static final CustomPacketPayload.Type<StockpileTogglePayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "stockpile_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StockpileTogglePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StockpileTogglePayload::containerId,
            ByteBufCodecs.VAR_INT, StockpileTogglePayload::toggle,
            ByteBufCodecs.BOOL, StockpileTogglePayload::value,
            StockpileTogglePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
