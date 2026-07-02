package com.bannerbound.core.network;

import java.util.List;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C: the player's FAITH-tree research state. Per-FAITH shared (FAITH_PLAN Part 2.5) —
 * every member settlement of the faith receives the same snapshot. Broadcast once per
 * second while a node is filling, on every change, and on login.
 *
 * @param devotionPerSecond the faith's TOTAL rate (all member settlements summed) — what
 *                          actually fills the active node.
 */
public record FaithResearchStatePayload(
        List<String> completed,
        String activeResearch,
        List<ResearchStateSyncPayload.ProgressEntry> progress,
        double devotionPerSecond,
        List<String> queue,
        List<ResearchStateSyncPayload.ProgressEntry> insightProgress,
        List<String> firedInsights) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FaithResearchStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "faith_research_state"));

    public static final StreamCodec<ByteBuf, FaithResearchStatePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.completed());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.activeResearch());
            ResearchStateSyncPayload.ProgressEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, p.progress());
            buf.writeDouble(p.devotionPerSecond());
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.queue());
            ResearchStateSyncPayload.ProgressEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, p.insightProgress());
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, p.firedInsights());
        },
        buf -> new FaithResearchStatePayload(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ResearchStateSyncPayload.ProgressEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
            buf.readDouble(),
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf),
            ResearchStateSyncPayload.ProgressEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf)
        )
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
