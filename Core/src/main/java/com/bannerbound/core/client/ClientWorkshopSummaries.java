package com.bannerbound.core.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.WorkshopSummarySyncPayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of every workshop's display summary (name, type, status, occupancy), keyed by
 * workshop id. Replaced wholesale on each {@link WorkshopSummarySyncPayload}; read by
 * {@code SelectionRenderer} for the Workshop Orders rod's floating overview labels.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientWorkshopSummaries {

    /** One workshop's label data. {@code customName} empty = show the derived type name.
     *  {@code appealOrdinal} is the workplace-appeal {@code ChunkBeauty} ordinal, −1 = unscored. */
    public record Summary(String customName, String typeId, int statusOrdinal,
                          int workerCount, int capacity, int appealOrdinal) {
    }

    private static final Map<UUID, Summary> SUMMARIES = new HashMap<>();

    private ClientWorkshopSummaries() {
    }

    public static void replace(WorkshopSummarySyncPayload payload) {
        SUMMARIES.clear();
        for (int i = 0; i < payload.workshopIds().size(); i++) {
            try {
                SUMMARIES.put(UUID.fromString(payload.workshopIds().get(i)), new Summary(
                    payload.customNames().get(i), payload.typeIds().get(i),
                    payload.statusOrdinals().get(i), payload.workerCounts().get(i),
                    payload.capacities().get(i),
                    i < payload.appealOrdinals().size() ? payload.appealOrdinals().get(i) : -1));
            } catch (IllegalArgumentException ignored) {
                // malformed id — skip the row rather than dropping the whole snapshot
            }
        }
    }

    @Nullable
    public static Summary get(UUID workshopId) {
        return SUMMARIES.get(workshopId);
    }
}
