package com.bannerbound.core.api.workshop;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;

/**
 * Server-side mutual-exclusion for work blocks: while a citizen crafts at a station, player
 * interaction with it is rejected — and while a player runs a station session (e.g. the fletching
 * minigame), NPCs skip it. Transient by design (not persisted): locks clear on restart, and both
 * lock holders re-assert theirs every craft. Keyed by position; values are the holder's UUID
 * (citizen or player).
 */
public final class WorkBlockLocks {
    private static final Map<BlockPos, UUID> LOCKS = new ConcurrentHashMap<>();

    private WorkBlockLocks() {
    }

    /** Claims {@code pos} for {@code holder}. Returns false if someone else already holds it. */
    public static boolean lock(BlockPos pos, UUID holder) {
        UUID prev = LOCKS.putIfAbsent(pos.immutable(), holder);
        return prev == null || prev.equals(holder);
    }

    /** Releases {@code pos} if {@code holder} owns it. */
    public static void unlock(BlockPos pos, UUID holder) {
        LOCKS.remove(pos.immutable(), holder);
    }

    /** True when someone OTHER than {@code who} holds the lock at {@code pos}. */
    public static boolean isLockedByOther(BlockPos pos, UUID who) {
        UUID holder = LOCKS.get(pos.immutable());
        return holder != null && !holder.equals(who);
    }

    /** Drops the lock at {@code pos} regardless of holder — for when the station block itself is
     *  destroyed (a rebuilt block at the same pos must not inherit a stale claim). */
    public static void forceUnlock(BlockPos pos) {
        LOCKS.remove(pos.immutable());
    }
}
