package com.bannerbound.core.network;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C, once per second to members of faithful settlements (and on faith changes): the
 * settlement's faith snapshot for the town hall — name/path/member count, devotion
 * stockpile + rate, and whether the Choose-Faith window is open.
 */
public record FaithStatePayload(
        boolean hasFaith,
        String faithName,
        int pathOrdinal,
        int memberSettlements,
        double devotionStored,
        double devotionPerSecond,
        boolean choiceWindowOpen) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FaithStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "faith_state"));

    public static final StreamCodec<ByteBuf, FaithStatePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeBoolean(p.hasFaith());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.faithName());
            ByteBufCodecs.VAR_INT.encode(buf, p.pathOrdinal());
            ByteBufCodecs.VAR_INT.encode(buf, p.memberSettlements());
            buf.writeDouble(p.devotionStored());
            buf.writeDouble(p.devotionPerSecond());
            buf.writeBoolean(p.choiceWindowOpen());
        },
        buf -> new FaithStatePayload(
            buf.readBoolean(),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            buf.readDouble(),
            buf.readDouble(),
            buf.readBoolean())
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
