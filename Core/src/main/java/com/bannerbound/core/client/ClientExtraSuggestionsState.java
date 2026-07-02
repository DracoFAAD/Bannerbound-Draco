package com.bannerbound.core.client;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.ExtraSuggestionsPayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client cache of the exile / tablet suggestions for the Town Hall "Suggestions" tab. The other
 * four suggestion kinds (science / culture / policy / palette) live in their existing caches
 * ({@link ClientSuggestionState}, {@link ClientPolicyState}, {@link ClientPaletteState}); the tab
 * aggregates all six.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientExtraSuggestionsState {
    private static volatile List<ExtraSuggestionsPayload.ExileEntry> exiles = List.of();
    private static volatile List<UUID> tabletSuggesters = List.of();

    private ClientExtraSuggestionsState() {
    }

    public static void replace(ExtraSuggestionsPayload p) {
        exiles = List.copyOf(p.exiles());
        tabletSuggesters = List.copyOf(p.tabletSuggesters());
    }

    public static List<ExtraSuggestionsPayload.ExileEntry> getExiles() { return exiles; }
    public static List<UUID> getTabletSuggesters() { return tabletSuggesters; }
}
