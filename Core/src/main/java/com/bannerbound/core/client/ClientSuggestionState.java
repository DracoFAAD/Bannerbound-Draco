package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bannerbound.core.network.SuggestionStateSyncPayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the player's settlement's suggestion-marker state. Keyed by research id;
 * each entry is the list of suggesters in click order, used by the research screen to draw
 * the [+N] + skin-head badge row above each suggested node.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientSuggestionState {
    private static volatile Map<String, List<UUID>> science = Collections.emptyMap();
    private static volatile Map<String, List<UUID>> culture = Collections.emptyMap();

    private ClientSuggestionState() {
    }

    public static void replace(SuggestionStateSyncPayload payload) {
        science = toMap(payload.science());
        culture = toMap(payload.culture());
    }

    private static Map<String, List<UUID>> toMap(List<SuggestionStateSyncPayload.Entry> entries) {
        Map<String, List<UUID>> out = new HashMap<>(entries.size());
        for (SuggestionStateSyncPayload.Entry e : entries) {
            out.put(e.researchId(), List.copyOf(e.suggesters()));
        }
        return Map.copyOf(out);
    }

    public static List<UUID> getScienceSuggesters(String researchId) {
        List<UUID> s = science.get(researchId);
        return s == null ? List.of() : s;
    }
    public static List<UUID> getCultureSuggesters(String researchId) {
        List<UUID> s = culture.get(researchId);
        return s == null ? List.of() : s;
    }
    /** Full suggestion maps — the Suggestions tab aggregates these into its row list. */
    public static Map<String, List<UUID>> getAllScience() { return science; }
    public static Map<String, List<UUID>> getAllCulture() { return culture; }
}
