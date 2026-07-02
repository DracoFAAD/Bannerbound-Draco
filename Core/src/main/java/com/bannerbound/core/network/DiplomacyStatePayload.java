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

@ApiStatus.Internal
public record DiplomacyStatePayload(boolean rallying, int winnerCooldownSeconds, List<Row> rows,
                                    List<BarbarianRow> barbarianRows)
        implements CustomPacketPayload {
    public record Row(
        String settlementId,
        String name,
        int stance,
        int distanceBlocks,
        String direction,
        int pendingSeconds,
        int cooldownSeconds,
        boolean peaceOfferedByUs,
        boolean peaceOfferedByThem,
        boolean capturedTarget,
        boolean capturedByUs,
        boolean canRaze,
        String objective,
        boolean cityState,
        String goods,   // city-states only: comma-joined item ids of the best-stocked wares
        String seeks,   // city-states only: comma-joined item ids of the active demand slots
        boolean canTrade,  // settlements: the viewer may open a trade with this row
        int tradeBadge     // settlements: unread trade proposals awaiting the viewer (0/1)
    ) {}

    /** Read-only barbarian-camp standing: name (in the camp's colour), stance (CampRelationState
     *  ordinal), numeric score, and distance/direction from the viewer's hall. No actions — these are
     *  informational rows shown below the settlement/city-state rows. */
    public record BarbarianRow(
        String name,
        int nameColor,        // RGB from the camp type's ChatFormatting
        int relationOrdinal,  // CampRelationState ordinal (0=hostile,1=neutral,2=friendly)
        int score,
        int distanceBlocks,
        String direction
    ) {}

    public static final int STANCE_PEACE = 0;
    public static final int STANCE_PENDING = 1;
    public static final int STANCE_WAR = 2;
    public static final int STANCE_CAPTURED = 3;

    public static final CustomPacketPayload.Type<DiplomacyStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "diplomacy_state"));

    public static final StreamCodec<ByteBuf, DiplomacyStatePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeBoolean(p.rallying());
            ByteBufCodecs.VAR_INT.encode(buf, p.winnerCooldownSeconds());
            ByteBufCodecs.VAR_INT.encode(buf, p.rows().size());
            for (Row row : p.rows()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, row.settlementId());
                ByteBufCodecs.STRING_UTF8.encode(buf, row.name());
                ByteBufCodecs.VAR_INT.encode(buf, row.stance());
                ByteBufCodecs.VAR_INT.encode(buf, row.distanceBlocks());
                ByteBufCodecs.STRING_UTF8.encode(buf, row.direction());
                ByteBufCodecs.VAR_INT.encode(buf, row.pendingSeconds());
                ByteBufCodecs.VAR_INT.encode(buf, row.cooldownSeconds());
                buf.writeBoolean(row.peaceOfferedByUs());
                buf.writeBoolean(row.peaceOfferedByThem());
                buf.writeBoolean(row.capturedTarget());
                buf.writeBoolean(row.capturedByUs());
                buf.writeBoolean(row.canRaze());
                ByteBufCodecs.STRING_UTF8.encode(buf, row.objective());
                buf.writeBoolean(row.cityState());
                ByteBufCodecs.STRING_UTF8.encode(buf, row.goods());
                ByteBufCodecs.STRING_UTF8.encode(buf, row.seeks());
                buf.writeBoolean(row.canTrade());
                ByteBufCodecs.VAR_INT.encode(buf, row.tradeBadge());
            }
            ByteBufCodecs.VAR_INT.encode(buf, p.barbarianRows().size());
            for (BarbarianRow b : p.barbarianRows()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, b.name());
                ByteBufCodecs.VAR_INT.encode(buf, b.nameColor());
                ByteBufCodecs.VAR_INT.encode(buf, b.relationOrdinal());
                ByteBufCodecs.VAR_INT.encode(buf, b.score());
                ByteBufCodecs.VAR_INT.encode(buf, b.distanceBlocks());
                ByteBufCodecs.STRING_UTF8.encode(buf, b.direction());
            }
        },
        buf -> {
            boolean rallying = buf.readBoolean();
            int winnerCooldownSeconds = ByteBufCodecs.VAR_INT.decode(buf);
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            List<Row> rows = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                rows.add(new Row(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readBoolean(),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readBoolean(),
                    ByteBufCodecs.VAR_INT.decode(buf)));
            }
            int bcount = ByteBufCodecs.VAR_INT.decode(buf);
            List<BarbarianRow> barbarianRows = new ArrayList<>(bcount);
            for (int i = 0; i < bcount; i++) {
                barbarianRows.add(new BarbarianRow(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf)));
            }
            return new DiplomacyStatePayload(rallying, winnerCooldownSeconds, rows, barbarianRows);
        });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
