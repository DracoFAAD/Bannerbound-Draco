package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the open Stockpile terminal's "Detect" button — re-scan the enclosure and flash
 * its wireframe (green floor, blue containers, red fail spot) behind the GUI, like the House Detect.
 */
@ApiStatus.Internal
public record StockpileDetectPayload(int containerId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StockpileDetectPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "stockpile_detect"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StockpileDetectPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StockpileDetectPayload::containerId,
            StockpileDetectPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
