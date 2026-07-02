package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.menu.StockEntry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: the Stockpile terminal's state for the open menu {@code containerId} — the
 * enclosure {@code statusOrdinal} ({@code Stockpile.Status}) and {@code containerCount} for the
 * header, plus the summed {@code entries} (totals can exceed a stack, so they can't ride on vanilla
 * slot sync). Sent each tick the menu is open; the client {@code StockpileScreen} filters/scrolls
 * the list and shows the status header locally.
 */
@ApiStatus.Internal
public record StockpileContentsPayload(int containerId, int statusOrdinal, int containerCount,
                                       int usedSlots, int totalSlots,
                                       boolean allowDeposit, boolean allowTake, boolean showTrade,
                                       List<StockEntry> entries) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StockpileContentsPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "stockpile_contents"));

    // Hand-rolled: StreamCodec.composite caps at 6 fields and this carries 8 (header + two worker
    // toggles + the summed list).
    private static final StreamCodec<RegistryFriendlyByteBuf, List<StockEntry>> ENTRIES_CODEC =
        StockEntry.STREAM_CODEC.apply(ByteBufCodecs.list());

    public static final StreamCodec<RegistryFriendlyByteBuf, StockpileContentsPayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.containerId());
                buf.writeVarInt(p.statusOrdinal());
                buf.writeVarInt(p.containerCount());
                buf.writeVarInt(p.usedSlots());
                buf.writeVarInt(p.totalSlots());
                buf.writeBoolean(p.allowDeposit());
                buf.writeBoolean(p.allowTake());
                buf.writeBoolean(p.showTrade());
                ENTRIES_CODEC.encode(buf, p.entries());
            },
            buf -> new StockpileContentsPayload(
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                ENTRIES_CODEC.decode(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
