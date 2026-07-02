package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: the player's choice in the barbarian parley. {@code action} 0=ACCEPT (hand over demands),
 * 1=REFUSE, 2=TRADE (the offer at {@code tradeIndex}). The server re-validates the messenger, the
 * acting player's settlement, and the items before applying any relationship change.
 */
@ApiStatus.Internal
public record BarbarianParleyActionPayload(int messengerEntityId, int action, int tradeIndex)
        implements CustomPacketPayload {
    public static final int ACCEPT = 0;
    public static final int REFUSE = 1;
    public static final int TRADE = 2;

    public static final CustomPacketPayload.Type<BarbarianParleyActionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "barbarian_parley_action"));

    public static final StreamCodec<ByteBuf, BarbarianParleyActionPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.messengerEntityId());
            ByteBufCodecs.VAR_INT.encode(buf, p.action());
            ByteBufCodecs.VAR_INT.encode(buf, p.tradeIndex());
        },
        buf -> new BarbarianParleyActionPayload(ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
