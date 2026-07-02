package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the open {@code TradeScreen}'s periodic live-refresh poll (mirrors the barter
 * screen's storage poll) — answered with a {@link TradeStoragePayload} for {@code targetId}.
 */
@ApiStatus.Internal
public record RequestTradeStoragePayload(String targetId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestTradeStoragePayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "request_trade_storage"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestTradeStoragePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RequestTradeStoragePayload::targetId,
            RequestTradeStoragePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
