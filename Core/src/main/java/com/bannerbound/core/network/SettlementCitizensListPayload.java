package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.SettlementColor;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: response to {@link RequestSettlementCitizensPayload}. Carries the settlement
 * colour and era (so the Citizens screen can pick the right per-era skin) plus one {@link Entry}
 * per citizen with live health, stamina and happiness snapshots so the screen can render the
 * roster without needing each entity client-side. The screen re-requests this payload on each
 * open for a fresh snapshot; live re-sync mid-screen isn't needed for this view.
 */
@ApiStatus.Internal
public record SettlementCitizensListPayload(SettlementColor color, Era era, List<Entry> entries)
        implements CustomPacketPayload {
    /**
     * @param jobTypeId     the citizen's job type id, or {@code ""} when unemployed / entity not
     *                      loaded — drives the readable job label in the roster row.
     * @param jobIconItemId registry id of the job's tool-age icon item ({@code 0} = none), exactly
     *                      what {@link com.bannerbound.core.social.JobIcons#iconItemId} resolves —
     *                      the roster row draws it so the player can spot a worker by role.
     */
    public record Entry(UUID id, String name, float health, float maxHealth, int stamina,
                        int maxStamina, int happiness, int happinessMax,
                        String jobTypeId, int jobIconItemId) {}

    public static final CustomPacketPayload.Type<SettlementCitizensListPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "settlement_citizens_list"));

    public static final StreamCodec<ByteBuf, SettlementCitizensListPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.color().ordinal());
            ByteBufCodecs.VAR_INT.encode(buf, p.era().ordinal());
            ByteBufCodecs.VAR_INT.encode(buf, p.entries().size());
            for (Entry e : p.entries()) {
                buf.writeLong(e.id().getMostSignificantBits());
                buf.writeLong(e.id().getLeastSignificantBits());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.name());
                buf.writeFloat(e.health());
                buf.writeFloat(e.maxHealth());
                ByteBufCodecs.VAR_INT.encode(buf, e.stamina());
                ByteBufCodecs.VAR_INT.encode(buf, e.maxStamina());
                ByteBufCodecs.VAR_INT.encode(buf, e.happiness());
                ByteBufCodecs.VAR_INT.encode(buf, e.happinessMax());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.jobTypeId());
                ByteBufCodecs.VAR_INT.encode(buf, e.jobIconItemId());
            }
        },
        buf -> {
            SettlementColor color = SettlementColor.byIndex(ByteBufCodecs.VAR_INT.decode(buf));
            Era era = Era.fromOrdinalOrDefault(ByteBufCodecs.VAR_INT.decode(buf));
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long msb = buf.readLong();
                long lsb = buf.readLong();
                String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                float health = buf.readFloat();
                float maxHealth = buf.readFloat();
                int stamina = ByteBufCodecs.VAR_INT.decode(buf);
                int maxStamina = ByteBufCodecs.VAR_INT.decode(buf);
                int happiness = ByteBufCodecs.VAR_INT.decode(buf);
                int happinessMax = ByteBufCodecs.VAR_INT.decode(buf);
                String jobTypeId = ByteBufCodecs.STRING_UTF8.decode(buf);
                int jobIconItemId = ByteBufCodecs.VAR_INT.decode(buf);
                entries.add(new Entry(new UUID(msb, lsb), name, health, maxHealth,
                    stamina, maxStamina, happiness, happinessMax, jobTypeId, jobIconItemId));
            }
            return new SettlementCitizensListPayload(color, era, entries);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
