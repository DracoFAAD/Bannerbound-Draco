package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C snapshot of every active research-suggestion marker for the player's settlement.
 * Sent on every toggle (a non-chief click adds or retracts) and on every enact (chief
 * actually researches the node, clearing the marker).
 *
 * <p>Two parallel maps for the two trees. Each entry is "research id → list of suggester
 * UUIDs in click order"; the client looks up the player's skin texture via the existing
 * tab-list {@code PlayerInfo} mapping and renders 8×8 head icons next to a [+N] badge.
 *
 * <p>UUIDs (not names) are sent because the player skin lookup is keyed by UUID on the
 * client. Names are derived from the same {@code PlayerInfo} cache on hover (later polish).
 */
@ApiStatus.Internal
public record SuggestionStateSyncPayload(
    List<Entry> science,
    List<Entry> culture
) implements CustomPacketPayload {
    public record Entry(String researchId, List<UUID> suggesters) { }

    public static final CustomPacketPayload.Type<SuggestionStateSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "suggestion_state_sync"));

    public static final StreamCodec<ByteBuf, SuggestionStateSyncPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            encodeEntries(buf, p.science());
            encodeEntries(buf, p.culture());
        },
        buf -> new SuggestionStateSyncPayload(decodeEntries(buf), decodeEntries(buf))
    );

    private static void encodeEntries(ByteBuf buf, List<Entry> entries) {
        ByteBufCodecs.VAR_INT.encode(buf, entries.size());
        for (Entry e : entries) {
            ByteBufCodecs.STRING_UTF8.encode(buf, e.researchId());
            ByteBufCodecs.VAR_INT.encode(buf, e.suggesters().size());
            for (UUID id : e.suggesters()) UUIDUtil.STREAM_CODEC.encode(buf, id);
        }
    }
    private static List<Entry> decodeEntries(ByteBuf buf) {
        int n = ByteBufCodecs.VAR_INT.decode(buf);
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = ByteBufCodecs.STRING_UTF8.decode(buf);
            int m = ByteBufCodecs.VAR_INT.decode(buf);
            List<UUID> sugg = new ArrayList<>(m);
            for (int j = 0; j < m; j++) sugg.add(UUIDUtil.STREAM_CODEC.decode(buf));
            out.add(new Entry(id, sugg));
        }
        return out;
    }

    /** Flatten a settlement's suggestion map into the wire-friendly entry list. */
    public static List<Entry> flatten(Map<String, LinkedHashSet<UUID>> source) {
        List<Entry> out = new ArrayList<>(source.size());
        for (Map.Entry<String, LinkedHashSet<UUID>> e : source.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            out.add(new Entry(e.getKey(), new ArrayList<>(e.getValue())));
        }
        return out;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
