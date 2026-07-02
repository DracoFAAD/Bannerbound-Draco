package com.bannerbound.core.api.farmer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bannerbound.core.network.OpenSeedPickerPayload;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Process-wide queue of seed-picker prompts waiting to be delivered. A farmer selection that
 * finishes tilling but has no seed assigned is queued here keyed by the player who created it.
 * When that player is online, the popup pushes immediately. Otherwise it sits until login,
 * and {@code FactionEvents.onPlayerLoggedIn} drains the pending entries.
 * <p>
 * Not persisted: if the server restarts, the next tick of the work goal re-detects "tilled but
 * unseeded" selections and re-queues them.
 */
public final class AwaitingSeedRegistry {
    private static final Map<UUID, Deque<UUID>> PENDING = new HashMap<>();

    private AwaitingSeedRegistry() {
    }

    /** Queue a pending seed prompt for {@code creatorId} on {@code rodId}. If the creator is
     *  online, also push the popup right now. Idempotent: re-queuing the same rod is a no-op. */
    public static synchronized void queueAndMaybePush(MinecraftServer server, UUID creatorId,
                                                       UUID rodId,
                                                       List<String> candidateSeeds,
                                                       List<String> bonusSeeds) {
        Deque<UUID> q = PENDING.computeIfAbsent(creatorId, k -> new ArrayDeque<>());
        if (!q.contains(rodId)) q.addLast(rodId);
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(creatorId);
        if (player != null) {
            PacketDistributor.sendToPlayer(player, new OpenSeedPickerPayload(rodId, candidateSeeds, bonusSeeds));
        }
    }

    /** Returns and removes the pending rod ids for {@code creatorId} (FIFO). Empty list if none. */
    public static synchronized List<UUID> drainFor(UUID creatorId) {
        Deque<UUID> q = PENDING.remove(creatorId);
        if (q == null || q.isEmpty()) return List.of();
        return new ArrayList<>(q);
    }

    /** True if {@code rodId} is already in the queue for {@code creatorId}. */
    public static synchronized boolean isQueued(UUID creatorId, UUID rodId) {
        Deque<UUID> q = PENDING.get(creatorId);
        return q != null && q.contains(rodId);
    }

    /** Remove {@code rodId} from any pending queue. Called when the seed is chosen or the
     *  selection is deleted out from under the prompt. */
    public static synchronized void unqueue(UUID rodId) {
        for (Deque<UUID> q : PENDING.values()) {
            q.remove(rodId);
        }
        PENDING.values().removeIf(Deque::isEmpty);
    }
}
