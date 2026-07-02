package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;

import com.bannerbound.core.BannerboundCore;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client. Carries everything the {@code ExpandTerritoryScreen} needs to render the
 * birdseye chunk picker without making extra round-trips:
 * <ul>
 *   <li>{@link #claimedChunks} — long-packed ChunkPos for every chunk this settlement owns.</li>
 *   <li>{@link #foreignChunks} — long-packed ChunkPos for chunks owned by OTHER settlements
 *       inside the visible window (rendered with a dimmer color, non-interactive).</li>
 *   <li>{@link #townHallChunkPacked} — anchor for the camera/centering.</li>
 *   <li>{@link #colorOrdinal} — own settlement color for highlight.</li>
 *   <li>{@link #expansionsInEra} / {@link #maxExpansions} — progress + cap label.</li>
 *   <li>{@link #currentTierPopulation} / {@link #currentTierItemIds} / {@link #currentTierItemCounts}
 *       — the cost ladder entry for the NEXT expansion, so the side panel can render and the
 *       button can grey out if the player can't afford.</li>
 *   <li>{@link #biome} — the resolved majority biome (for display, e.g. "Desert tier").</li>
 *   <li>{@link #canAfford} — server-computed flag (covers items + population + cap together).</li>
 * </ul>
 */
@ApiStatus.Internal
public record OpenExpandTerritoryScreenPayload(
    List<Long> claimedChunks,
    List<Long> foreignChunks,
    long townHallChunkPacked,
    int colorOrdinal,
    int expansionsInEra,
    int maxExpansions,
    int currentTierPopulation,
    int currentPopulation,
    List<String> currentTierItemIds,
    List<Integer> currentTierItemCounts,
    String biome,
    boolean canAfford,
    List<Long> beautyChunks,
    List<Integer> beautyTagIds,
    List<Integer> beautyAdjacency,
    List<Integer> beautyEffective,
    /** Council per-chunk vote tallies — parallel lists, one entry per chunk with at least
     *  one active vote. Empty in Chiefdom / NONE. {@link #voteVoterIds} lists who voted on
     *  each chunk in cast order, so the screen can render their skin heads next to the
     *  {@code (N/X)} text. */
    List<ChunkMarker> votes,
    /** Chiefdom per-chunk suggestion markers. Empty in Council / NONE. */
    List<ChunkMarker> suggestions,
    /** Live council threshold (1 / 2 / ceil(N/2)). Used by the screen to label markers as
     *  {@code (votes/needed)}. 0 in non-Council settlements. */
    int councilVoteThreshold
) implements CustomPacketPayload {

    /** One per-chunk marker — used for both Council votes and Chiefdom suggestions. */
    public record ChunkMarker(long packedChunkPos, List<UUID> playerIds) {
    }
    public static final CustomPacketPayload.Type<OpenExpandTerritoryScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_expand_territory_screen"));

    public static final StreamCodec<ByteBuf, OpenExpandTerritoryScreenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.claimedChunks().size());
            for (long c : p.claimedChunks()) buf.writeLong(c);
            ByteBufCodecs.VAR_INT.encode(buf, p.foreignChunks().size());
            for (long c : p.foreignChunks()) buf.writeLong(c);
            buf.writeLong(p.townHallChunkPacked());
            ByteBufCodecs.VAR_INT.encode(buf, p.colorOrdinal());
            ByteBufCodecs.VAR_INT.encode(buf, p.expansionsInEra());
            ByteBufCodecs.VAR_INT.encode(buf, p.maxExpansions());
            ByteBufCodecs.VAR_INT.encode(buf, p.currentTierPopulation());
            ByteBufCodecs.VAR_INT.encode(buf, p.currentPopulation());
            ByteBufCodecs.VAR_INT.encode(buf, p.currentTierItemIds().size());
            for (String id : p.currentTierItemIds()) ByteBufCodecs.STRING_UTF8.encode(buf, id);
            for (int n : p.currentTierItemCounts()) ByteBufCodecs.VAR_INT.encode(buf, n);
            ByteBufCodecs.STRING_UTF8.encode(buf, p.biome());
            buf.writeBoolean(p.canAfford());
            ByteBufCodecs.VAR_INT.encode(buf, p.beautyChunks().size());
            for (long c : p.beautyChunks()) buf.writeLong(c);
            for (int t : p.beautyTagIds()) ByteBufCodecs.VAR_INT.encode(buf, t);
            for (int a : p.beautyAdjacency()) buf.writeInt(a);
            for (int eff : p.beautyEffective()) ByteBufCodecs.VAR_INT.encode(buf, eff);
            encodeMarkers(buf, p.votes());
            encodeMarkers(buf, p.suggestions());
            ByteBufCodecs.VAR_INT.encode(buf, p.councilVoteThreshold());
        },
        buf -> {
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<Long> claimed = new ArrayList<>(n);
            for (int i = 0; i < n; i++) claimed.add(buf.readLong());
            int fn = ByteBufCodecs.VAR_INT.decode(buf);
            List<Long> foreign = new ArrayList<>(fn);
            for (int i = 0; i < fn; i++) foreign.add(buf.readLong());
            long th = buf.readLong();
            int color = ByteBufCodecs.VAR_INT.decode(buf);
            int exp = ByteBufCodecs.VAR_INT.decode(buf);
            int max = ByteBufCodecs.VAR_INT.decode(buf);
            int tierPop = ByteBufCodecs.VAR_INT.decode(buf);
            int curPop = ByteBufCodecs.VAR_INT.decode(buf);
            int items = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> ids = new ArrayList<>(items);
            for (int i = 0; i < items; i++) ids.add(ByteBufCodecs.STRING_UTF8.decode(buf));
            List<Integer> counts = new ArrayList<>(items);
            for (int i = 0; i < items; i++) counts.add(ByteBufCodecs.VAR_INT.decode(buf));
            String biome = ByteBufCodecs.STRING_UTF8.decode(buf);
            boolean afford = buf.readBoolean();
            int bn = ByteBufCodecs.VAR_INT.decode(buf);
            List<Long> beautyChunks = new ArrayList<>(bn);
            for (int i = 0; i < bn; i++) beautyChunks.add(buf.readLong());
            List<Integer> beautyTags = new ArrayList<>(bn);
            for (int i = 0; i < bn; i++) beautyTags.add(ByteBufCodecs.VAR_INT.decode(buf));
            List<Integer> beautyAdj = new ArrayList<>(bn);
            for (int i = 0; i < bn; i++) beautyAdj.add(buf.readInt());
            List<Integer> beautyEff = new ArrayList<>(bn);
            for (int i = 0; i < bn; i++) beautyEff.add(ByteBufCodecs.VAR_INT.decode(buf));
            List<ChunkMarker> votes = decodeMarkers(buf);
            List<ChunkMarker> suggestions = decodeMarkers(buf);
            int threshold = ByteBufCodecs.VAR_INT.decode(buf);
            return new OpenExpandTerritoryScreenPayload(claimed, foreign, th, color, exp, max,
                tierPop, curPop, ids, counts, biome, afford,
                beautyChunks, beautyTags, beautyAdj, beautyEff,
                votes, suggestions, threshold);
        }
    );

    private static void encodeMarkers(ByteBuf buf, List<ChunkMarker> markers) {
        ByteBufCodecs.VAR_INT.encode(buf, markers.size());
        for (ChunkMarker m : markers) {
            buf.writeLong(m.packedChunkPos());
            ByteBufCodecs.VAR_INT.encode(buf, m.playerIds().size());
            for (UUID id : m.playerIds()) UUIDUtil.STREAM_CODEC.encode(buf, id);
        }
    }
    private static List<ChunkMarker> decodeMarkers(ByteBuf buf) {
        int n = ByteBufCodecs.VAR_INT.decode(buf);
        List<ChunkMarker> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long chunk = buf.readLong();
            int m = ByteBufCodecs.VAR_INT.decode(buf);
            List<UUID> ids = new ArrayList<>(m);
            for (int j = 0; j < m; j++) ids.add(UUIDUtil.STREAM_CODEC.decode(buf));
            out.add(new ChunkMarker(chunk, ids));
        }
        return out;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Convenience: townHall chunk pos as BlockPos at chunk min-corner. */
    public BlockPos townHallChunkOrigin() {
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(townHallChunkPacked);
        return new BlockPos(cp.getMinBlockX(), 0, cp.getMinBlockZ());
    }
}
