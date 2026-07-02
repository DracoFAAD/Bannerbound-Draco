package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: the player's move in a barbarian barter. {@code PROPOSE} submits the offer as edited (the camp
 * evaluates it — accept executes + warms relations by generosity, reject sours them); {@code DECLINE}
 * walks away; {@code DEFER} takes the "we'll get it for you" grace on a demand. The item values in the
 * lists are ignored — the server recomputes everything authoritatively from live state.
 */
@ApiStatus.Internal
public record BarterActionPayload(
    int messengerEntityId,
    int action,
    List<BarterEntry> youGive,
    List<BarterEntry> youGet
) implements CustomPacketPayload {

    public static final int PROPOSE = 0;
    public static final int DECLINE = 1;
    public static final int DEFER = 2;

    public static final CustomPacketPayload.Type<BarterActionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "barter_action"));

    public static final StreamCodec<ByteBuf, BarterActionPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.messengerEntityId);
            ByteBufCodecs.VAR_INT.encode(buf, p.action);
            BarterEntry.LIST.encode(buf, p.youGive);
            BarterEntry.LIST.encode(buf, p.youGet);
        },
        buf -> new BarterActionPayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            BarterEntry.LIST.decode(buf),
            BarterEntry.LIST.decode(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
