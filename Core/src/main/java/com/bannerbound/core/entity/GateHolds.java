package com.bannerbound.core.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;

/**
 * Single-owner reservation for fence gates a worker is actively holding open. The shared
 * {@link OpenFenceGateGoal} defers to any held gate, so it can't fight the holder over the same gate — that
 * tug-of-war was the rapid open/close <em>flicker</em> at a herder's pen gate (two systems toggling the
 * same block every tick).
 *
 * <p>A hold <b>expires</b> if it isn't re-asserted every tick (TTL), so a holder that dies, unloads, or
 * simply stops wanting the gate never leaks a permanent lock — {@link OpenFenceGateGoal} resumes managing
 * the gate a couple ticks later. Server-thread only in practice; the map is concurrent for safety.</p>
 */
@ApiStatus.Internal
public final class GateHolds {
    /** Ticks a hold survives without re-assertion. The holder re-stamps it every tick while it wants it. */
    private static final long TTL = 3L;
    /** posLong → {ownerEntityId, gameTime of last assertion}. */
    private static final Map<Long, long[]> HELD = new ConcurrentHashMap<>();

    private GateHolds() {}

    /** Assert (or refresh) a hold on {@code pos} by {@code ownerId}. Call every tick while holding. */
    public static void hold(BlockPos pos, int ownerId, long gameTime) {
        HELD.put(pos.asLong(), new long[] { ownerId, gameTime });
    }

    /** Drop a hold (only if {@code ownerId} owns it). Holds also lapse on their own via {@link #TTL}. */
    public static void release(BlockPos pos, int ownerId) {
        long key = pos.asLong();
        long[] v = HELD.get(key);
        if (v != null && v[0] == ownerId) {
            HELD.remove(key);
        }
    }

    /** Is this gate currently held by some live holder? If so, {@link OpenFenceGateGoal} must leave it alone. */
    public static boolean isHeld(BlockPos pos, long gameTime) {
        long[] v = HELD.get(pos.asLong());
        return v != null && gameTime - v[1] <= TTL;
    }
}
