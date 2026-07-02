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
 * Server → client: open the settlement-to-settlement {@code TradeScreen} against {@code targetId}.
 * Carries the existing active deal (if any: {@code dealId} non-empty, {@code dealState} =
 * {@code TradeDeal.State} ordinal, {@code awaitingUs} = our side may accept/counter/reject) plus
 * both live pools: {@code myPool} = our show-for-trading stockpiles, {@code theirPool} = theirs
 * (knowledge-filtered server-side). {@code canAct} = the viewer's governance role allows
 * negotiating (chiefdom gates on the chief). Offer lists are viewer-relative: {@code myOffer} =
 * what WE give.
 */
@ApiStatus.Internal
public record OpenTradeScreenPayload(String targetId, String targetName, int targetColorIndex,
                                     String myName, int myColorIndex,
                                     String dealId, int dealState, boolean awaitingUs, boolean canAct,
                                     List<BarterEntry> myOffer, List<BarterEntry> theirOffer,
                                     List<BarterEntry> myPool, List<BarterEntry> theirPool)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenTradeScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_trade_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTradeScreenPayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.targetId());
                ByteBufCodecs.STRING_UTF8.encode(buf, p.targetName());
                ByteBufCodecs.VAR_INT.encode(buf, p.targetColorIndex());
                ByteBufCodecs.STRING_UTF8.encode(buf, p.myName());
                ByteBufCodecs.VAR_INT.encode(buf, p.myColorIndex());
                ByteBufCodecs.STRING_UTF8.encode(buf, p.dealId());
                ByteBufCodecs.VAR_INT.encode(buf, p.dealState());
                buf.writeBoolean(p.awaitingUs());
                buf.writeBoolean(p.canAct());
                BarterEntry.LIST.encode(buf, p.myOffer());
                BarterEntry.LIST.encode(buf, p.theirOffer());
                BarterEntry.LIST.encode(buf, p.myPool());
                BarterEntry.LIST.encode(buf, p.theirPool());
            },
            buf -> new OpenTradeScreenPayload(
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                buf.readBoolean(),
                buf.readBoolean(),
                BarterEntry.LIST.decode(buf),
                BarterEntry.LIST.decode(buf),
                BarterEntry.LIST.decode(buf),
                BarterEntry.LIST.decode(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
