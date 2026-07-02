package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C snapshot of a settlement's culture-tree state. Twin of {@link ResearchStateSyncPayload}
 * for the parallel Culture board in {@code ResearchScreen}'s Culture tab. Same shape minus
 * {@code unlockedItemIds} (the v1 Culture tree doesn't unlock items — narrative milestones
 * only — so we save the bytes and add it back when content needs it).
 */
@ApiStatus.Internal
public record CultureStateSyncPayload(
    List<String> completed,
    String activeResearch,
    List<ResearchStateSyncPayload.ProgressEntry> progress,
    double culturePerSecond,
    int capacity,
    List<String> queue,
    List<ResearchStateSyncPayload.ProgressEntry> insightProgress,
    List<String> firedInsights
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CultureStateSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "culture_state_sync"));

    public static final StreamCodec<ByteBuf, CultureStateSyncPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.completed());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.activeResearch());
            ResearchStateSyncPayload.ProgressEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, p.progress());
            buf.writeDouble(p.culturePerSecond());
            ByteBufCodecs.VAR_INT.encode(buf, p.capacity());
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.queue());
            ResearchStateSyncPayload.ProgressEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, p.insightProgress());
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.firedInsights());
        },
        buf -> new CultureStateSyncPayload(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ResearchStateSyncPayload.ProgressEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
            buf.readDouble(),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
            ResearchStateSyncPayload.ProgressEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf)
        )
    );

    public static List<ResearchStateSyncPayload.ProgressEntry> flattenProgress(Map<String, Double> progress) {
        List<ResearchStateSyncPayload.ProgressEntry> out = new ArrayList<>(progress.size());
        for (Map.Entry<String, Double> e : progress.entrySet()) {
            out.add(new ResearchStateSyncPayload.ProgressEntry(e.getKey(), e.getValue()));
        }
        return out;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
