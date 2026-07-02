package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Workshop;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * Server → client: a snapshot of every workshop's display summary (all settlements), powering the
 * Workshop Orders rod's floating overview labels — name, type (icon resolves client-side via
 * {@code WorkBlockRegistry.iconForType}), validity and worker occupancy. Broadcast alongside the
 * selection snapshot ({@code SelectionBroadcaster}) so the labels track the wireframes.
 */
@ApiStatus.Internal
public record WorkshopSummarySyncPayload(List<String> workshopIds, List<String> customNames,
                                         List<String> typeIds, List<Integer> statusOrdinals,
                                         List<Integer> workerCounts, List<Integer> capacities,
                                         List<Integer> appealOrdinals)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WorkshopSummarySyncPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "workshop_summary_sync"));

    public static final StreamCodec<ByteBuf, WorkshopSummarySyncPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            writeStrings(buf, p.workshopIds);
            writeStrings(buf, p.customNames);
            writeStrings(buf, p.typeIds);
            writeInts(buf, p.statusOrdinals);
            writeInts(buf, p.workerCounts);
            writeInts(buf, p.capacities);
            writeInts(buf, p.appealOrdinals);
        },
        buf -> new WorkshopSummarySyncPayload(
            readStrings(buf), readStrings(buf), readStrings(buf),
            readInts(buf), readInts(buf), readInts(buf), readInts(buf)));

    /** Builds the snapshot from every settlement's workshops. */
    public static WorkshopSummarySyncPayload build(MinecraftServer server) {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        List<Integer> workers = new ArrayList<>();
        List<Integer> capacities = new ArrayList<>();
        List<Integer> appeals = new ArrayList<>();
        for (Settlement s : SettlementData.get(server.overworld()).all()) {
            for (Workshop w : s.workshops().values()) {
                ids.add(w.id().toString());
                names.add(w.customName());
                types.add(w.derivedTypeId());
                statuses.add(w.status().ordinal());
                workers.add(w.workers().size());
                capacities.add(w.capacity());
                appeals.add(w.cachedAppealBeauty() == null
                    ? -1 : w.cachedAppealBeauty().ordinal());
            }
        }
        return new WorkshopSummarySyncPayload(ids, names, types, statuses, workers, capacities,
            appeals);
    }

    private static void writeStrings(ByteBuf buf, List<String> list) {
        ByteBufCodecs.VAR_INT.encode(buf, list.size());
        for (String s : list) ByteBufCodecs.STRING_UTF8.encode(buf, s);
    }

    private static List<String> readStrings(ByteBuf buf) {
        int n = ByteBufCodecs.VAR_INT.decode(buf);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(ByteBufCodecs.STRING_UTF8.decode(buf));
        return out;
    }

    private static void writeInts(ByteBuf buf, List<Integer> list) {
        ByteBufCodecs.VAR_INT.encode(buf, list.size());
        for (Integer i : list) ByteBufCodecs.VAR_INT.encode(buf, i);
    }

    private static List<Integer> readInts(ByteBuf buf) {
        int n = ByteBufCodecs.VAR_INT.decode(buf);
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(ByteBufCodecs.VAR_INT.decode(buf));
        return out;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
