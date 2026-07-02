package com.bannerbound.core.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-wide reservation of OPEN guard posts (rod-marked posts not bound to a specific citizen),
 * so a watch spreads one-guard-per-post instead of the whole squad crowding the gate. Mirrors
 * {@link MinerClaims}/{@link PenClaims}: a post's anchor {@link BlockPos} maps to the entity id of
 * the guard manning it; a claim whose owner no longer exists is treated as stale and cleared on
 * access.
 *
 * <p>Bound posts (assigned to one citizen via the rod's target) don't go through here — they're
 * private to that citizen and need no race. Server-thread only in practice; the map is concurrent
 * for safety.</p>
 */
@ApiStatus.Internal
final class GuardPostClaims {
    private static final Map<BlockPos, Integer> CLAIMS = new ConcurrentHashMap<>();

    private GuardPostClaims() {}

    /** True if this post is manned by a guard other than {@code selfId} that still exists; a claim
     *  held by a vanished guard is cleared here and reported as free. */
    static boolean isClaimedByOther(ServerLevel level, BlockPos anchor, int selfId) {
        Integer owner = CLAIMS.get(anchor);
        if (owner == null || owner == selfId) return false;
        if (level.getEntity(owner) instanceof CitizenEntity c && c.isAlive()) return true;
        CLAIMS.remove(anchor, owner);   // stale: owner gone
        return false;
    }

    /** Whether {@code selfId} currently mans this post (used to prefer staying on the same one). */
    static boolean ownedBy(BlockPos anchor, int selfId) {
        Integer owner = CLAIMS.get(anchor);
        return owner != null && owner == selfId;
    }

    /** Reserve (or refresh) this post for {@code selfId}. */
    static void claim(BlockPos anchor, int selfId) {
        CLAIMS.put(anchor.immutable(), selfId);
    }

    /** Drop every reservation held by {@code selfId} — called when the guard stops/changes posts. */
    static void releaseAll(int selfId) {
        CLAIMS.values().removeIf(owner -> owner == selfId);
    }
}
