package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client per-workshop snapshot for the Town Hall Statistics tab (Phase 2). Broadcast once a
 * second to members of a settlement that has unlocked statistics, so the tab can show each workshop's
 * staffing, output rate, and pending order backlog (and a derived "expected supply" ETA).
 */
@ApiStatus.Internal
public record WorkshopStatsPayload(
    String settlementId,
    java.util.List<Entry> entries
) implements CustomPacketPayload {

    /** One workshop: custom name ("" if none), derived type id, staffing, status ordinal,
     *  output rate (items/sec, smoothed), and total pending order count. */
    public record Entry(String name, String typeId, int workers, int capacity,
                        int statusOrdinal, double outputRate, int pendingOrders) {}

    public static final CustomPacketPayload.Type<WorkshopStatsPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "workshop_stats"));

    public static final StreamCodec<ByteBuf, WorkshopStatsPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, p.settlementId());
            ByteBufCodecs.VAR_INT.encode(buf, p.entries().size());
            for (Entry e : p.entries()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, e.name());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.typeId());
                ByteBufCodecs.VAR_INT.encode(buf, e.workers());
                ByteBufCodecs.VAR_INT.encode(buf, e.capacity());
                ByteBufCodecs.VAR_INT.encode(buf, e.statusOrdinal());
                ByteBufCodecs.DOUBLE.encode(buf, e.outputRate());
                ByteBufCodecs.VAR_INT.encode(buf, e.pendingOrders());
            }
        },
        buf -> {
            String settlementId = ByteBufCodecs.STRING_UTF8.decode(buf);
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            java.util.List<Entry> entries = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(new Entry(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf)));
            }
            return new WorkshopStatsPayload(settlementId, entries);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
