package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.WorkshopStatsPayload;

/**
 * Client-side cache of the latest {@link WorkshopStatsPayload} — the per-workshop stats (staffing,
 * output rate, pending orders) the Town Hall Statistics tab renders. Updated once a second for members
 * of a settlement that has unlocked Mathematics; empty otherwise.
 */
@ApiStatus.Internal
public final class ClientWorkshopState {
    private static volatile List<WorkshopStatsPayload.Entry> entries = List.of();

    private ClientWorkshopState() {}

    public static void update(List<WorkshopStatsPayload.Entry> newEntries) {
        entries = newEntries == null ? List.of() : List.copyOf(newEntries);
    }

    public static List<WorkshopStatsPayload.Entry> getEntries() {
        return entries;
    }
}
