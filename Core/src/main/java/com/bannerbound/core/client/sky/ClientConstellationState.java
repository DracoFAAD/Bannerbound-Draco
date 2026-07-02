package com.bannerbound.core.client.sky;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.bannerbound.core.network.ConstellationsSyncPayload;

import org.jetbrains.annotations.ApiStatus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the faith's pantheon ({@link ConstellationsSyncPayload}): drives the
 * believer-sky line rendering and Pantheon mode's used-star exclusions.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientConstellationState {
    private static volatile List<ConstellationsSyncPayload.Entry> entries = List.of();
    private static volatile Set<Integer> usedStars = Set.of();

    private ClientConstellationState() {
    }

    public static void replace(ConstellationsSyncPayload payload) {
        Set<Integer> used = new HashSet<>();
        for (ConstellationsSyncPayload.Entry e : payload.entries()) {
            for (int id : e.starIds()) {
                used.add(id);
            }
        }
        entries = List.copyOf(payload.entries());
        usedStars = Set.copyOf(used);
    }

    public static void clear() {
        entries = List.of();
        usedStars = Set.of();
    }

    public static List<ConstellationsSyncPayload.Entry> entries() {
        return entries;
    }

    public static boolean starUsed(int starId) {
        return usedStars.contains(starId);
    }

    public static int count() {
        return entries.size();
    }
}
