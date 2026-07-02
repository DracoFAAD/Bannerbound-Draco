package com.bannerbound.core.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-wide reservation of OPEN herder pens (those not bound to a specific citizen), so multiple herders
 * spread across the available pens instead of all piling onto the first one. Mirrors {@link DiggerClaims}:
 * a pen's anchor {@link BlockPos} maps to the entity id of the herder working it; another herder skips a pen
 * claimed by a still-living herder, so it naturally picks a different pen. A claim whose owner no longer
 * exists (died / unloaded without cleanup) is treated as stale and cleared on access, freeing the pen.
 *
 * <p>Bound pens (assigned to one citizen) don't go through here — they're private to that citizen and need
 * no race. Server-thread only in practice; the map is concurrent for safety.</p>
 */
@ApiStatus.Internal
final class PenClaims {
    private static final Map<BlockPos, Integer> CLAIMS = new ConcurrentHashMap<>();

    private PenClaims() {}

    /** True if this pen is reserved by a herder other than {@code selfId} that still exists; a claim held by
     *  a vanished herder is cleared here and reported as free. */
    static boolean isClaimedByOther(ServerLevel level, BlockPos anchor, int selfId) {
        Integer owner = CLAIMS.get(anchor);
        if (owner == null || owner == selfId) return false;
        if (level.getEntity(owner) instanceof CitizenEntity c && c.isAlive()) return true;
        CLAIMS.remove(anchor, owner);   // stale: owner gone
        return false;
    }

    /** Whether {@code selfId} currently holds this pen (used to prefer staying on the same pen). */
    static boolean ownedBy(BlockPos anchor, int selfId) {
        Integer owner = CLAIMS.get(anchor);
        return owner != null && owner == selfId;
    }

    /** Reserve (or refresh) this pen for {@code selfId}. */
    static void claim(BlockPos anchor, int selfId) {
        CLAIMS.put(anchor.immutable(), selfId);
    }

    /** Drop every reservation held by {@code selfId} — called when the herder stops/changes pens. */
    static void releaseAll(int selfId) {
        CLAIMS.values().removeIf(owner -> owner == selfId);
    }
}
