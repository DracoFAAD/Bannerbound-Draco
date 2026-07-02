package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.world.BlockSelection;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client. Full snapshot of every active {@link BlockSelection} on the server. Pushed on
 * player join, on registry mutation (register / unregister / complete), and on settlement
 * disband cleanup. The client mirror feeds the rod-held selection renderer.
 * <p>
 * Snapshot rather than delta because the registry is small (settlements × few-jobs) and the
 * client-side cache rebuild is cheap. Cuts a class of "drift" bugs at the cost of bandwidth.
 */
@ApiStatus.Internal
public record SelectionSyncPayload(List<BlockSelection> selections) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SelectionSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "selection_sync"));

    public static final StreamCodec<ByteBuf, SelectionSyncPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.selections().size());
            for (BlockSelection s : p.selections()) {
                buf.writeLong(s.rodId().getMostSignificantBits());
                buf.writeLong(s.rodId().getLeastSignificantBits());
                buf.writeLong(s.settlementId().getMostSignificantBits());
                buf.writeLong(s.settlementId().getLeastSignificantBits());
                ByteBufCodecs.VAR_INT.encode(buf, s.colorIndex());
                buf.writeInt(s.a().getX()); buf.writeInt(s.a().getY()); buf.writeInt(s.a().getZ());
                buf.writeInt(s.b().getX()); buf.writeInt(s.b().getY()); buf.writeInt(s.b().getZ());
                ByteBufCodecs.STRING_UTF8.encode(buf, s.workstationType());
                buf.writeBoolean(s.completed());
                // Farmer pipeline extras: creatorId + seed item id.
                buf.writeLong(s.creatorId().getMostSignificantBits());
                buf.writeLong(s.creatorId().getLeastSignificantBits());
                ByteBufCodecs.STRING_UTF8.encode(buf, s.seedItemId());
                // Kind discriminator + home id + home pos — drive the client renderer's split
                // between workstation-outline and home-tint paths AND the per-bound-rod
                // visibility filter (the client matches its rod's BOUND_HOME_POS against the
                // selection's homePos). Always written; both ends are matched mod versions.
                ByteBufCodecs.VAR_INT.encode(buf, s.kind().ordinal());
                buf.writeLong(s.homeId().getMostSignificantBits());
                buf.writeLong(s.homeId().getLeastSignificantBits());
                buf.writeInt(s.homePos().getX());
                buf.writeInt(s.homePos().getY());
                buf.writeInt(s.homePos().getZ());
                // Per-citizen binding (NO_CITIZEN = open to all workers of the type).
                buf.writeLong(s.assignedCitizenId().getMostSignificantBits());
                buf.writeLong(s.assignedCitizenId().getLeastSignificantBits());
            }
        },
        buf -> {
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<BlockSelection> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                java.util.UUID rodId = new java.util.UUID(buf.readLong(), buf.readLong());
                java.util.UUID settlementId = new java.util.UUID(buf.readLong(), buf.readLong());
                int color = ByteBufCodecs.VAR_INT.decode(buf);
                BlockPos a = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
                BlockPos b = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
                String type = ByteBufCodecs.STRING_UTF8.decode(buf);
                boolean done = buf.readBoolean();
                java.util.UUID creator = new java.util.UUID(buf.readLong(), buf.readLong());
                String seed = ByteBufCodecs.STRING_UTF8.decode(buf);
                BlockSelection.Kind kind = BlockSelection.Kind.fromOrdinalOrDefault(
                    ByteBufCodecs.VAR_INT.decode(buf));
                java.util.UUID homeId = new java.util.UUID(buf.readLong(), buf.readLong());
                BlockPos homePos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
                java.util.UUID citizen = new java.util.UUID(buf.readLong(), buf.readLong());
                out.add(new BlockSelection(rodId, settlementId, color, a, b,
                    kind, type, homeId, homePos, done, creator, seed, citizen));
            }
            return new SelectionSyncPayload(out);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
