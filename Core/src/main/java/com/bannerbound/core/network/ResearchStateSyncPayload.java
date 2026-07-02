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

@ApiStatus.Internal
public record ResearchStateSyncPayload(
    List<String> completed,
    String activeResearch,
    List<ProgressEntry> progress,
    double sciencePerSecond,
    int capacity,
    List<String> unlockedItemIds,
    List<String> queue,
    List<ProgressEntry> insightProgress,
    List<String> firedInsights
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ResearchStateSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "research_state_sync"));

    public static final StreamCodec<ByteBuf, ResearchStateSyncPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.completed());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.activeResearch());
            ProgressEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, p.progress());
            buf.writeDouble(p.sciencePerSecond());
            ByteBufCodecs.VAR_INT.encode(buf, p.capacity());
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.unlockedItemIds());
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.queue());
            ProgressEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, p.insightProgress());
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.firedInsights());
        },
        buf -> new ResearchStateSyncPayload(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ProgressEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
            buf.readDouble(),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
            ProgressEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf)
        )
    );

    public static List<ProgressEntry> flattenProgress(Map<String, Double> progress) {
        List<ProgressEntry> out = new ArrayList<>(progress.size());
        for (Map.Entry<String, Double> e : progress.entrySet()) {
            out.add(new ProgressEntry(e.getKey(), e.getValue()));
        }
        return out;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record ProgressEntry(String researchId, double progress) {
        public static final StreamCodec<ByteBuf, ProgressEntry> STREAM_CODEC = StreamCodec.of(
            (buf, e) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, e.researchId());
                buf.writeDouble(e.progress());
            },
            buf -> new ProgressEntry(
                ByteBufCodecs.STRING_UTF8.decode(buf),
                buf.readDouble()
            )
        );
    }
}
