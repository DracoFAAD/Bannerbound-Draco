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
 * Server → client: list of workstations in a settlement, sent in response to
 * {@link RequestWorkstationsPayload}. The citizen entity id ties the response back to the citizen
 * that requested it so the picker screen can keep both halves of the assignment in scope.
 */
@ApiStatus.Internal
public record WorkstationListPayload(int citizenEntityId, List<Entry> entries)
    implements CustomPacketPayload {

    public record Entry(BlockPos pos, String type, UUID currentWorker, String currentWorkerName) {
        public static final UUID NO_WORKER = new UUID(0, 0);
    }

    public static final CustomPacketPayload.Type<WorkstationListPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "workstation_list"));

    public static final StreamCodec<ByteBuf, WorkstationListPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeInt(p.citizenEntityId());
            ByteBufCodecs.VAR_INT.encode(buf, p.entries().size());
            for (Entry e : p.entries()) {
                buf.writeInt(e.pos().getX());
                buf.writeInt(e.pos().getY());
                buf.writeInt(e.pos().getZ());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.type());
                UUID worker = e.currentWorker() == null ? Entry.NO_WORKER : e.currentWorker();
                buf.writeLong(worker.getMostSignificantBits());
                buf.writeLong(worker.getLeastSignificantBits());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.currentWorkerName() == null ? "" : e.currentWorkerName());
            }
        },
        buf -> {
            int entityId = buf.readInt();
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                BlockPos pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
                String type = ByteBufCodecs.STRING_UTF8.decode(buf);
                UUID worker = new UUID(buf.readLong(), buf.readLong());
                String workerName = ByteBufCodecs.STRING_UTF8.decode(buf);
                UUID resolvedWorker = worker.equals(Entry.NO_WORKER) ? null : worker;
                entries.add(new Entry(pos, type, resolvedWorker, workerName.isEmpty() ? null : workerName));
            }
            return new WorkstationListPayload(entityId, entries);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
