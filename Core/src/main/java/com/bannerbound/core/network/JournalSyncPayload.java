package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.journal.JournalEntry;
import com.bannerbound.core.journal.JournalObjective;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client full journal snapshot for the current player. */
@ApiStatus.Internal
public record JournalSyncPayload(List<Entry> entries, long gameTick) implements CustomPacketPayload {
    public record Objective(String id, String label, String progressText, boolean complete,
                            List<String> subSteps) {
        public Objective {
            subSteps = subSteps == null ? List.of() : List.copyOf(subSteps);
        }

        public static final StreamCodec<ByteBuf, Objective> STREAM_CODEC = StreamCodec.of(
            (buf, o) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, o.id());
                ByteBufCodecs.STRING_UTF8.encode(buf, o.label());
                ByteBufCodecs.STRING_UTF8.encode(buf, o.progressText());
                ByteBufCodecs.BOOL.encode(buf, o.complete());
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, o.subSteps());
            },
            buf -> new Objective(
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf)
            )
        );
    }

    public record Entry(
        UUID instanceId,
        String entryId,
        String type,
        String title,
        String subtitle,
        int priority,
        long createdTick,
        long deadlineTick,
        long resolvedTick,
        boolean resolved,
        boolean failed,
        String sourceType,
        String sourceId,
        String chronicleEntry,
        List<Objective> objectives,
        boolean showOnHud,
        long targetPos
    ) {
        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.of(
            (buf, e) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, e.instanceId().toString());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.entryId());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.type());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.title());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.subtitle());
                ByteBufCodecs.VAR_INT.encode(buf, e.priority());
                ByteBufCodecs.VAR_LONG.encode(buf, e.createdTick());
                ByteBufCodecs.VAR_LONG.encode(buf, e.deadlineTick());
                ByteBufCodecs.VAR_LONG.encode(buf, e.resolvedTick());
                ByteBufCodecs.BOOL.encode(buf, e.resolved());
                ByteBufCodecs.BOOL.encode(buf, e.failed());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.sourceType());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.sourceId());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.chronicleEntry());
                Objective.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, e.objectives());
                ByteBufCodecs.BOOL.encode(buf, e.showOnHud());
                ByteBufCodecs.VAR_LONG.encode(buf, e.targetPos());
            },
            buf -> new Entry(
                UUID.fromString(ByteBufCodecs.STRING_UTF8.decode(buf)),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                Objective.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf)
            )
        );
    }

    public static final CustomPacketPayload.Type<JournalSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "journal_sync"));

    public static final StreamCodec<ByteBuf, JournalSyncPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, p.entries());
            ByteBufCodecs.VAR_LONG.encode(buf, p.gameTick());
        },
        buf -> new JournalSyncPayload(
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
            ByteBufCodecs.VAR_LONG.decode(buf)
        )
    );

    public static JournalSyncPayload fromEntries(List<JournalEntry> entries, long now) {
        List<Entry> out = new ArrayList<>(entries.size());
        for (JournalEntry e : entries) {
            List<Objective> objectives = new ArrayList<>(e.objectives().size());
            for (JournalObjective o : e.objectives()) {
                objectives.add(new Objective(o.id(), o.label(), o.progressText(), o.complete(), o.subSteps()));
            }
            out.add(new Entry(e.instanceId(), e.entryId(), e.type().name(), e.title(), e.subtitle(),
                e.priority(), e.createdTick(), e.deadlineTick(), e.resolvedTick(), e.resolved(),
                e.failed(), e.sourceType(), e.sourceId(), e.chronicleEntry(), objectives,
                e.shouldShowOnHud(now), e.targetPos()));
        }
        return new JournalSyncPayload(out, now);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
