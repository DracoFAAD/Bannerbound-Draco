package com.bannerbound.core.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-wide reservation of OPEN miner claims (ore-chunk markers not bound to a specific citizen),
 * so multiple miners spread across the marked deposits instead of all piling onto the first one.
 * Mirrors {@link PenClaims}: a marker's anchor {@link BlockPos} maps to the entity id of the miner
 * working it; a claim whose owner no longer exists is treated as stale and cleared on access.
 *
 * <p>Bound markers (assigned to one citizen) don't go through here — they're private to that
 * citizen and need no race. Server-thread only in practice; the map is concurrent for safety.</p>
 */
@ApiStatus.Internal
final class MinerClaims {
    private static final Map<BlockPos, Integer> CLAIMS = new ConcurrentHashMap<>();

    private MinerClaims() {}

    /** True if this marker is reserved by a miner other than {@code selfId} that still exists; a
     *  claim held by a vanished miner is cleared here and reported as free. */
    static boolean isClaimedByOther(ServerLevel level, BlockPos anchor, int selfId) {
        Integer owner = CLAIMS.get(anchor);
        if (owner == null || owner == selfId) return false;
        if (level.getEntity(owner) instanceof CitizenEntity c && c.isAlive()) return true;
        CLAIMS.remove(anchor, owner);   // stale: owner gone
        return false;
    }

    /** Whether {@code selfId} currently holds this marker (used to prefer staying on the same one). */
    static boolean ownedBy(BlockPos anchor, int selfId) {
        Integer owner = CLAIMS.get(anchor);
        return owner != null && owner == selfId;
    }

    /** Reserve (or refresh) this marker for {@code selfId}. */
    static void claim(BlockPos anchor, int selfId) {
        CLAIMS.put(anchor.immutable(), selfId);
    }

    /** Drop every reservation held by {@code selfId} — called when the miner stops/changes claims. */
    static void releaseAll(int selfId) {
        CLAIMS.values().removeIf(owner -> owner == selfId);
    }
}
