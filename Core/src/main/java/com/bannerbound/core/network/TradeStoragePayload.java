package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: live refresh of both trading pools for the open {@code TradeScreen}
 * (reply to {@link RequestTradeStoragePayload}).
 */
@ApiStatus.Internal
public record TradeStoragePayload(String targetId, List<BarterEntry> myPool,
                                  List<BarterEntry> theirPool) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TradeStoragePayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "trade_storage"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeStoragePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TradeStoragePayload::targetId,
            BarterEntry.LIST, TradeStoragePayload::myPool,
            BarterEntry.LIST, TradeStoragePayload::theirPool,
            TradeStoragePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
