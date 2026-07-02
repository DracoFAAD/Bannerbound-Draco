package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.WorkforceStatsPayload;

/**
 * Client-side cache of the latest {@link WorkforceStatsPayload} — the per-citizen roster (name, job,
 * work-status) the Town Hall Statistics tab renders. Updated once a second for members of a settlement
 * that has unlocked Mathematics; stays empty otherwise.
 */
@ApiStatus.Internal
public final class ClientWorkforceState {
    private static volatile List<WorkforceStatsPayload.Entry> entries = List.of();

    private ClientWorkforceState() {}

    public static void update(List<WorkforceStatsPayload.Entry> newEntries) {
        entries = newEntries == null ? List.of() : List.copyOf(newEntries);
    }

    /** Latest roster snapshot (empty until the first broadcast / if statistics aren't unlocked). */
    public static List<WorkforceStatsPayload.Entry> getEntries() {
        return entries;
    }
}
