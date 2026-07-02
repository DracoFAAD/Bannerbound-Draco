package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C: the two suggestion kinds that have no existing sync of their own — exile and tablet-issue —
 * for the Town Hall "Suggestions" tab. The other four kinds (science / culture / policy / palette)
 * already reach the client via {@code SuggestionStateSyncPayload} / {@code PolicyStateSyncPayload} /
 * {@code PaletteStateSyncPayload}; the tab aggregates all six from the client caches.
 *
 * @param exiles  one entry per citizen with at least one exile suggester (name baked server-side
 *                so the client doesn't need the roster)
 * @param tabletSuggesters members who suggested issuing a registration tablet/paper
 */
@ApiStatus.Internal
public record ExtraSuggestionsPayload(
    List<ExileEntry> exiles,
    List<UUID> tabletSuggesters
) implements CustomPacketPayload {
    public record ExileEntry(UUID citizenUuid, String citizenName, List<UUID> suggesters) {}

    public static final CustomPacketPayload.Type<ExtraSuggestionsPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "extra_suggestions"));

    public static final StreamCodec<ByteBuf, ExtraSuggestionsPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.exiles().size());
            for (ExileEntry e : p.exiles()) {
                UUIDUtil.STREAM_CODEC.encode(buf, e.citizenUuid());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.citizenName());
                ByteBufCodecs.VAR_INT.encode(buf, e.suggesters().size());
                for (UUID u : e.suggesters()) UUIDUtil.STREAM_CODEC.encode(buf, u);
            }
            ByteBufCodecs.VAR_INT.encode(buf, p.tabletSuggesters().size());
            for (UUID u : p.tabletSuggesters()) UUIDUtil.STREAM_CODEC.encode(buf, u);
        },
        buf -> {
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<ExileEntry> exiles = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                UUID citizen = UUIDUtil.STREAM_CODEC.decode(buf);
                String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                int m = ByteBufCodecs.VAR_INT.decode(buf);
                List<UUID> suggesters = new ArrayList<>(m);
                for (int j = 0; j < m; j++) suggesters.add(UUIDUtil.STREAM_CODEC.decode(buf));
                exiles.add(new ExileEntry(citizen, name, suggesters));
            }
            int t = ByteBufCodecs.VAR_INT.decode(buf);
            List<UUID> tablet = new ArrayList<>(t);
            for (int j = 0; j < t; j++) tablet.add(UUIDUtil.STREAM_CODEC.decode(buf));
            return new ExtraSuggestionsPayload(exiles, tablet);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
