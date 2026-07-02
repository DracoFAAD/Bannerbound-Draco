package com.bannerbound.core.event;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

/**
 * Server-side mirror of which players are currently marking a drop-off location (the client-side
 * {@link com.bannerbound.core.client.DropLocationEditState} only blocks the local interaction).
 * The server tracks it so {@link DropLocationServerGuard} can cancel the block-use on the server
 * thread too — otherwise, in single-player, the integrated server would still open the chest the
 * player right-clicked to mark it.
 */
@ApiStatus.Internal
public final class DropLocationEditServer {
    /** player UUID → which citizen + storage slot is being edited. */
    private record Edit(int citizenId, boolean seed, int returnCitizenId) {}

    private static final Map<UUID, Edit> EDITING = new ConcurrentHashMap<>();

    private DropLocationEditServer() {
    }

    /** {@code seed == true} marks the farmer's seed source; otherwise the harvest drop-off. */
    public static void begin(UUID player, int citizenEntityId, boolean seed) {
        EDITING.put(player, new Edit(citizenEntityId, seed, citizenEntityId));
    }

    public static boolean isActive(UUID player) {
        return EDITING.containsKey(player);
    }

    /** Citizen entity id the player is editing, or {@code null} if not editing. */
    public static Integer getCitizenId(UUID player) {
        Edit e = EDITING.get(player);
        return e == null ? null : e.citizenId();
    }

    /** True if the active edit targets the seed source (vs the drop-off). */
    public static boolean isSeed(UUID player) {
        Edit e = EDITING.get(player);
        return e != null && e.seed();
    }

    /** Citizen screen to reopen after a successful edit, or {@code null} for settlement-level edits. */
    public static Integer getReturnCitizenId(UUID player) {
        Edit e = EDITING.get(player);
        if (e == null || e.returnCitizenId() == com.bannerbound.core.network.OpenDropLocationEditPayload.PREFERRED_STORAGE_TARGET) {
            return null;
        }
        return e.returnCitizenId();
    }

    public static void clear(UUID player) {
        EDITING.remove(player);
    }
}
