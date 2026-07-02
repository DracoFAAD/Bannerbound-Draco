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

/**
 * Server → client: open the Workshop menu (rod shift-right-click inside a workshop). Carries the
 * header snapshot plus the worker roster: currently assigned workers and assignable candidates
 * (settlement citizens, unemployed flagged so the menu can list them first). Min-stock rows arrive
 * with Phase 3.
 *
 * @param workshopId        the workshop's id (UUID string; echoed back by C→S edits)
 * @param customName        the player-chosen name ("" = display the derived type)
 * @param derivedTypeId     the derived workshop type id (registered id, "mixed" or "none")
 * @param statusOrdinal     {@code Workshop.Status} ordinal (validity + reason display)
 * @param capacity          max workers (= reachable work block count)
 * @param workerIds         assigned citizen UUIDs (strings), parallel to {@code workerNames}
 * @param workerNames       assigned citizen display names
 * @param workerJobIcons    item registry id of each worker's job icon (0 = none)
 * @param candidateIds      assignable citizen UUIDs (strings), parallel to the lists below
 * @param candidateNames    assignable citizen display names
 * @param candidateEmployed whether each candidate already holds some other job
 * @param candidateJobIcons item registry id of each candidate's current-job icon (0 = none)
 * @param minStockItemIds   item registry ids of every output this workshop can produce
 * @param minStockValues    the configured settlement-wide minimum per output (0 = off)
 * @param minStockCounts    current settlement-wide count per output (stockpiles + workshops)
 * @param appealOrdinal     {@code ChunkBeauty} ordinal of the workplace appeal (−1 = unscored)
 * @param orderCounts       queued-order count per output (parallel to minStockItemIds; 0 = none)
 * @param autoOrderCounts   chain-derived auto-order count per output (parallel; display-only —
 *                          the ± buttons edit only the player orders)
 * @param workerPositions   each worker's pinned station family type id (parallel to workerIds;
 *                          "" = Any / auto-pick) — drives the Workers-tab station chooser
 * @param stationTypeIds    the distinct station family type ids present in this workshop (the
 *                          options the per-worker station chooser cycles through)
 */
@ApiStatus.Internal
public record OpenWorkshopMenuPayload(String workshopId, String customName, String derivedTypeId,
                                      int statusOrdinal, int capacity,
                                      List<String> workerIds, List<String> workerNames,
                                      List<Integer> workerJobIcons,
                                      List<String> candidateIds, List<String> candidateNames,
                                      List<Boolean> candidateEmployed,
                                      List<Integer> candidateJobIcons,
                                      List<Integer> minStockItemIds,
                                      List<Integer> minStockValues,
                                      List<Integer> minStockCounts,
                                      int appealOrdinal,
                                      List<Integer> orderCounts,
                                      List<Integer> autoOrderCounts,
                                      List<String> workerPositions,
                                      List<String> stationTypeIds)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenWorkshopMenuPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_workshop_menu"));

    // Too many fields for StreamCodec.composite — hand-rolled symmetric encode/decode.
    public static final StreamCodec<ByteBuf, OpenWorkshopMenuPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, p.workshopId);
            ByteBufCodecs.STRING_UTF8.encode(buf, p.customName);
            ByteBufCodecs.STRING_UTF8.encode(buf, p.derivedTypeId);
            ByteBufCodecs.VAR_INT.encode(buf, p.statusOrdinal);
            ByteBufCodecs.VAR_INT.encode(buf, p.capacity);
            writeStrings(buf, p.workerIds);
            writeStrings(buf, p.workerNames);
            writeInts(buf, p.workerJobIcons);
            writeStrings(buf, p.candidateIds);
            writeStrings(buf, p.candidateNames);
            ByteBufCodecs.VAR_INT.encode(buf, p.candidateEmployed.size());
            for (Boolean b : p.candidateEmployed) ByteBufCodecs.BOOL.encode(buf, b);
            writeInts(buf, p.candidateJobIcons);
            writeInts(buf, p.minStockItemIds);
            writeInts(buf, p.minStockValues);
            writeInts(buf, p.minStockCounts);
            ByteBufCodecs.VAR_INT.encode(buf, p.appealOrdinal + 1); // VAR_INT is unsigned-friendly
            writeInts(buf, p.orderCounts);
            writeInts(buf, p.autoOrderCounts);
            writeStrings(buf, p.workerPositions);
            writeStrings(buf, p.stationTypeIds);
        },
        buf -> {
            String workshopId = ByteBufCodecs.STRING_UTF8.decode(buf);
            String customName = ByteBufCodecs.STRING_UTF8.decode(buf);
            String derivedTypeId = ByteBufCodecs.STRING_UTF8.decode(buf);
            int statusOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
            int capacity = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> workerIds = readStrings(buf);
            List<String> workerNames = readStrings(buf);
            List<Integer> workerIcons = readInts(buf);
            List<String> candidateIds = readStrings(buf);
            List<String> candidateNames = readStrings(buf);
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<Boolean> employed = new ArrayList<>(n);
            for (int i = 0; i < n; i++) employed.add(ByteBufCodecs.BOOL.decode(buf));
            List<Integer> candidateIcons = readInts(buf);
            List<Integer> minItems = readInts(buf);
            List<Integer> minValues = readInts(buf);
            List<Integer> minCounts = readInts(buf);
            int appealOrdinal = ByteBufCodecs.VAR_INT.decode(buf) - 1;
            List<Integer> orderCounts = readInts(buf);
            List<Integer> autoOrderCounts = readInts(buf);
            List<String> workerPositions = readStrings(buf);
            List<String> stationTypeIds = readStrings(buf);
            return new OpenWorkshopMenuPayload(workshopId, customName, derivedTypeId,
                statusOrdinal, capacity, workerIds, workerNames, workerIcons,
                candidateIds, candidateNames, employed, candidateIcons,
                minItems, minValues, minCounts, appealOrdinal, orderCounts, autoOrderCounts,
                workerPositions, stationTypeIds);
        });

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
