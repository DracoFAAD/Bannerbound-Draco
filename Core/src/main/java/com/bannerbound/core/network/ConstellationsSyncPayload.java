package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C: the faith's full pantheon — every constellation's names, domain profile and star
 * id chain. Drives the believer-sky line rendering, Pantheon mode's used-star exclusions,
 * and the (Star Charts gated) domain readouts. Sent on login + every pantheon change.
 */
public record ConstellationsSyncPayload(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(String id, String name, String deityName, int primaryDomain,
                        int secondaryDomain, int[] starIds) {
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.of(
            (buf, e) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, e.id());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.name());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.deityName());
                ByteBufCodecs.VAR_INT.encode(buf, e.primaryDomain());
                ByteBufCodecs.VAR_INT.encode(buf, e.secondaryDomain());
                ByteBufCodecs.VAR_INT.encode(buf, e.starIds().length);
                for (int id : e.starIds()) {
                    ByteBufCodecs.VAR_INT.encode(buf, id);
                }
            },
            buf -> {
                String id = ByteBufCodecs.STRING_UTF8.decode(buf);
                String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                String deity = ByteBufCodecs.STRING_UTF8.decode(buf);
                int primary = ByteBufCodecs.VAR_INT.decode(buf);
                int secondary = ByteBufCodecs.VAR_INT.decode(buf);
                int n = ByteBufCodecs.VAR_INT.decode(buf);
                int[] ids = new int[Math.max(0, Math.min(n, 64))];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = ByteBufCodecs.VAR_INT.decode(buf);
                }
                return new Entry(id, name, deity, primary, secondary, ids);
            }
        );
    }

    public static final CustomPacketPayload.Type<ConstellationsSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "constellations_sync"));

    public static final StreamCodec<ByteBuf, ConstellationsSyncPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entries().size());
            for (Entry e : p.entries()) {
                Entry.STREAM_CODEC.encode(buf, e);
            }
        },
        buf -> {
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<Entry> entries = new ArrayList<>(Math.max(0, Math.min(n, 64)));
            for (int i = 0; i < Math.min(n, 64); i++) {
                entries.add(Entry.STREAM_CODEC.decode(buf));
            }
            return new ConstellationsSyncPayload(entries);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
