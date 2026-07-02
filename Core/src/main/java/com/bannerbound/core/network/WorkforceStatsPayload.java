package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client roster snapshot for the Town Hall Statistics tab (gated behind the Mathematics
 * research). Broadcast once per second to members of any settlement that has unlocked statistics, so
 * the tab can show who is working, at what job, and WHY (their {@link
 * com.bannerbound.core.entity.CitizenWorkStatus}). Each entry's {@code statusOrdinal} is the ordinal
 * of that enum, or {@code -1} for a citizen who is currently unloaded ("away").
 */
@ApiStatus.Internal
public record WorkforceStatsPayload(
    String settlementId,
    java.util.List<Entry> entries
) implements CustomPacketPayload {

    /** One citizen: display name, current job id ("" if none), CitizenWorkStatus ordinal (-1 = away),
     *  and the network entity id for assignment actions (-1 when unloaded / not actionable). */
    public record Entry(String name, String jobType, int statusOrdinal, int entityId) {}

    public static final CustomPacketPayload.Type<WorkforceStatsPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "workforce_stats"));

    public static final StreamCodec<ByteBuf, WorkforceStatsPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, p.settlementId());
            ByteBufCodecs.VAR_INT.encode(buf, p.entries().size());
            for (Entry e : p.entries()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, e.name());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.jobType());
                ByteBufCodecs.VAR_INT.encode(buf, e.statusOrdinal() + 1); // +1 so the -1 "away" sentinel stays non-negative
                ByteBufCodecs.INT.encode(buf, e.entityId());              // fixed int: handles the -1 sentinel cleanly
            }
        },
        buf -> {
            String settlementId = ByteBufCodecs.STRING_UTF8.decode(buf);
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            java.util.List<Entry> entries = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                String jobType = ByteBufCodecs.STRING_UTF8.decode(buf);
                int statusOrdinal = ByteBufCodecs.VAR_INT.decode(buf) - 1;
                int entityId = ByteBufCodecs.INT.decode(buf);
                entries.add(new Entry(name, jobType, statusOrdinal, entityId));
            }
            return new WorkforceStatsPayload(settlementId, entries);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
