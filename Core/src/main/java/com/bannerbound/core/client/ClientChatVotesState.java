package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.ChatVotesStatePayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client cache of the settlement's in-flight council chat votes for the Town Hall "Votes" tab.
 * Refreshed by {@link ChatVotesStatePayload} on town-hall open and after every cast/start/resolve;
 * {@code receivedAtMs} lets the tab tick each entry's countdown locally between syncs.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientChatVotesState {
    private static volatile List<ChatVotesStatePayload.Entry> entries = List.of();
    private static volatile long receivedAtMs = 0L;

    private ClientChatVotesState() {
    }

    public static void replace(ChatVotesStatePayload p) {
        entries = List.copyOf(p.entries());
        receivedAtMs = System.currentTimeMillis();
    }

    public static List<ChatVotesStatePayload.Entry> getEntries() { return entries; }

    /** Locally-ticked seconds left for an entry (its synced value minus time since the sync). */
    public static int secondsLeftNow(ChatVotesStatePayload.Entry e) {
        long elapsed = (System.currentTimeMillis() - receivedAtMs) / 1000L;
        return (int) Math.max(0L, e.secondsLeft() - elapsed);
    }
}
