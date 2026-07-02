package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: response to {@link RequestUnemployedCitizensPayload}. Carries the workstation
 * pos so the client can pair the picker screen with the workstation it was opened from, plus
 * the list of (citizen UUID, name) tuples eligible for assignment.
 */
@ApiStatus.Internal
public record CitizenListPayload(BlockPos workstationPos, List<Entry> entries) implements CustomPacketPayload {
    public record Entry(UUID id, String name) {}

    public static final CustomPacketPayload.Type<CitizenListPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "citizen_list"));

    public static final StreamCodec<ByteBuf, CitizenListPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeInt(p.workstationPos().getX());
            buf.writeInt(p.workstationPos().getY());
            buf.writeInt(p.workstationPos().getZ());
            ByteBufCodecs.VAR_INT.encode(buf, p.entries().size());
            for (Entry e : p.entries()) {
                buf.writeLong(e.id().getMostSignificantBits());
                buf.writeLong(e.id().getLeastSignificantBits());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.name());
            }
        },
        buf -> {
            BlockPos pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long msb = buf.readLong();
                long lsb = buf.readLong();
                String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                entries.add(new Entry(new UUID(msb, lsb), name));
            }
            return new CitizenListPayload(pos, entries);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
