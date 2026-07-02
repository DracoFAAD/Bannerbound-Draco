package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C: opens the barbarian parley screen for the messenger the player right-clicked. Carries the
 * camp's identity + stance and its data-driven demands/trades so the screen renders without further
 * server queries. Item references are registry id strings the client resolves for name + icon.
 */
@ApiStatus.Internal
public record OpenBarbarianParleyPayload(
    int messengerEntityId,
    String campName,
    int campColor,        // ARGB-less RGB from the camp type's ChatFormatting
    String typeName,      // englishName (e.g. "Raiders") for the header
    String greetingKey,   // lang key for the greeting line
    int relState,         // CampRelationState ordinal
    boolean canImprove,   // accepting can lift relations (false for marauders → cooldown only)
    List<Demand> demands,
    List<Trade> trades
) implements CustomPacketPayload {

    public record Demand(String item, int count) {}
    public record Trade(String giveItem, int giveCount, String getItem, int getCount) {}

    public static final CustomPacketPayload.Type<OpenBarbarianParleyPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "open_barbarian_parley"));

    public static final StreamCodec<ByteBuf, OpenBarbarianParleyPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.messengerEntityId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.campName());
            ByteBufCodecs.VAR_INT.encode(buf, p.campColor());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.typeName());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.greetingKey());
            ByteBufCodecs.VAR_INT.encode(buf, p.relState());
            ByteBufCodecs.BOOL.encode(buf, p.canImprove());
            ByteBufCodecs.VAR_INT.encode(buf, p.demands().size());
            for (Demand d : p.demands()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, d.item());
                ByteBufCodecs.VAR_INT.encode(buf, d.count());
            }
            ByteBufCodecs.VAR_INT.encode(buf, p.trades().size());
            for (Trade t : p.trades()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, t.giveItem());
                ByteBufCodecs.VAR_INT.encode(buf, t.giveCount());
                ByteBufCodecs.STRING_UTF8.encode(buf, t.getItem());
                ByteBufCodecs.VAR_INT.encode(buf, t.getCount());
            }
        },
        buf -> {
            int id = ByteBufCodecs.VAR_INT.decode(buf);
            String campName = ByteBufCodecs.STRING_UTF8.decode(buf);
            int color = ByteBufCodecs.VAR_INT.decode(buf);
            String typeName = ByteBufCodecs.STRING_UTF8.decode(buf);
            String greeting = ByteBufCodecs.STRING_UTF8.decode(buf);
            int rel = ByteBufCodecs.VAR_INT.decode(buf);
            boolean canImprove = ByteBufCodecs.BOOL.decode(buf);
            int nd = ByteBufCodecs.VAR_INT.decode(buf);
            List<Demand> demands = new ArrayList<>(nd);
            for (int i = 0; i < nd; i++) {
                demands.add(new Demand(ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf)));
            }
            int nt = ByteBufCodecs.VAR_INT.decode(buf);
            List<Trade> trades = new ArrayList<>(nt);
            for (int i = 0; i < nt; i++) {
                trades.add(new Trade(ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf)));
            }
            return new OpenBarbarianParleyPayload(id, campName, color, typeName, greeting, rel,
                canImprove, demands, trades);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
