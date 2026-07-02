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
 * Client → server: a trade-screen action against settlement {@code targetId}. {@code action} =
 * {@code TradeManager.ACTION_*} (propose / counter / accept / reject / cancel); {@code dealId}
 * addresses the existing deal (empty for a fresh PROPOSE). {@code give}/{@code get} are
 * viewer-relative item lines (my side / their side), meaningful for PROPOSE and COUNTER only —
 * unit values are recomputed authoritatively server-side.
 */
@ApiStatus.Internal
public record TradeActionPayload(String targetId, String dealId, int action,
                                 List<BarterEntry> give, List<BarterEntry> get)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TradeActionPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "trade_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeActionPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TradeActionPayload::targetId,
            ByteBufCodecs.STRING_UTF8, TradeActionPayload::dealId,
            ByteBufCodecs.VAR_INT, TradeActionPayload::action,
            BarterEntry.LIST, TradeActionPayload::give,
            BarterEntry.LIST, TradeActionPayload::get,
            TradeActionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
