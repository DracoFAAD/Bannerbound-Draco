package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: response to {@link RequestHomeCitizenListPayload}. One flat list of the
 * settlement's citizens, with each entry tagged by its relationship to the home with id
 * {@code homeId}:
 * <ul>
 *   <li>{@link Role#RESIDENT} — lives in this home; the picker shows an "Unassign" button.</li>
 *   <li>{@link Role#HOMELESS} — no home; "Assign" button moves them in.</li>
 *   <li>{@link Role#OTHER} — lives in a different home of the same settlement; "Assign" button
 *       transfers them (one home per citizen), and the picker shows {@link Entry#distance} as
 *       the Chebyshev distance from their current home to this one.</li>
 * </ul>
 * The flat-with-tag shape lets the screen sort and section the list however it likes without
 * the wire format growing a separate envelope per role.
 */
@ApiStatus.Internal
public record HomeCitizenListPayload(UUID homeId, List<Entry> entries) implements CustomPacketPayload {

    public enum Role { RESIDENT, HOMELESS, OTHER }

    /** A single picker row. {@code distance} only carries a meaningful value when {@code role == OTHER}. */
    public record Entry(UUID id, String name, Role role, int distance) {}

    public static final CustomPacketPayload.Type<HomeCitizenListPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "home_citizen_list"));

    public static final StreamCodec<ByteBuf, HomeCitizenListPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.homeId().getMostSignificantBits());
            buf.writeLong(p.homeId().getLeastSignificantBits());
            ByteBufCodecs.VAR_INT.encode(buf, p.entries().size());
            for (Entry e : p.entries()) {
                buf.writeLong(e.id().getMostSignificantBits());
                buf.writeLong(e.id().getLeastSignificantBits());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.name());
                ByteBufCodecs.VAR_INT.encode(buf, e.role().ordinal());
                ByteBufCodecs.VAR_INT.encode(buf, e.distance());
            }
        },
        buf -> {
            UUID homeId = new UUID(buf.readLong(), buf.readLong());
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<Entry> entries = new ArrayList<>(n);
            Role[] roles = Role.values();
            for (int i = 0; i < n; i++) {
                long msb = buf.readLong();
                long lsb = buf.readLong();
                String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                int roleOrd = ByteBufCodecs.VAR_INT.decode(buf);
                int dist = ByteBufCodecs.VAR_INT.decode(buf);
                Role role = (roleOrd >= 0 && roleOrd < roles.length) ? roles[roleOrd] : Role.HOMELESS;
                entries.add(new Entry(new UUID(msb, lsb), name, role, dist));
            }
            return new HomeCitizenListPayload(homeId, entries);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
