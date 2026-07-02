package com.bannerbound.core.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-wide reservation of dig targets so multiple quarryworkers divide a work area instead of all
 * piling onto the same block (which left them wedging against each other, neither able to reach it).
 * <p>
 * One {@link BlockPos} maps to the entity id of the worker that called it. A worker skips any block
 * claimed by <i>another</i> worker while scanning, so the second worker naturally picks the next
 * block. Claims are released when the owner mines the block, abandons it, or stops working; a claim
 * whose owner no longer exists in the world (died / unloaded without cleanup) is treated as stale and
 * cleared on access, so the position frees up on its own.
 * <p>
 * Safe without locking: entity AI ticks run sequentially on the server thread, so claim/scan order is
 * deterministic; {@link ConcurrentHashMap} just guards against the odd cross-thread peek.
 */
@ApiStatus.Internal
final class DiggerClaims {
    private static final Map<BlockPos, Integer> CLAIMS = new ConcurrentHashMap<>();

    private DiggerClaims() {
    }

    /** True if {@code pos} is reserved by a worker other than {@code selfId} that still exists. A claim
     *  held by a vanished entity is cleared here and reported as free. */
    static boolean isClaimedByOther(ServerLevel level, BlockPos pos, int selfId) {
        Integer owner = CLAIMS.get(pos);
        if (owner == null || owner == selfId) return false;
        if (level.getEntity(owner) instanceof CitizenEntity) return true;
        CLAIMS.remove(pos, owner);   // stale: owner gone
        return false;
    }

    /** True if any OTHER still-existing worker holds a reservation (target block or standing spot)
     *  within {@code radius} of {@code pos}. Used to keep a second digger from crowding into a spot
     *  another is already working — in a one-wide tunnel the lone stand tile reads as taken, so the
     *  second worker leaves the whole area alone instead of fighting for it. */
    static boolean hasOtherClaimNear(ServerLevel level, BlockPos pos, int selfId, double radius) {
        double r2 = radius * radius;
        for (Map.Entry<BlockPos, Integer> e : CLAIMS.entrySet()) {
            int owner = e.getValue();
            if (owner == selfId) continue;
            if (pos.distSqr(e.getKey()) > r2) continue;
            if (level.getEntity(owner) instanceof CitizenEntity) return true;
            CLAIMS.remove(e.getKey(), owner);   // stale: owner gone
        }
        return false;
    }

    /** Reserve {@code pos} for {@code selfId} (replacing any prior owner — the caller has just been
     *  cleared to take it because {@link #isClaimedByOther} was false). */
    static void claim(BlockPos pos, int selfId) {
        CLAIMS.put(pos.immutable(), selfId);
    }

    /** Drop {@code selfId}'s reservation on {@code pos} (no-op if it owns nothing there). */
    static void release(BlockPos pos, int selfId) {
        CLAIMS.remove(pos, selfId);
    }

    /** Drop every reservation held by {@code selfId} — called when the worker stops digging entirely. */
    static void releaseAll(int selfId) {
        CLAIMS.values().removeIf(owner -> owner == selfId);
    }
}
