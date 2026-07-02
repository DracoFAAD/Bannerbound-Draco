package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * Process-wide soft lock so two diggers in the same settlement (or across settlements)
 * don't both walk to the same block. Lives in JVM memory only — nothing to persist, because a
 * citizen that died or unloaded between ticks loses its claim implicitly when the goal stops
 * and calls {@link #release(UUID)}.
 * <p>
 * One block ↔ one citizen. {@link #tryClaim} is the only mutator that takes a position; it's
 * atomic per the synchronized block, so two goals racing the same target will see one win.
 */
@ApiStatus.Internal
public final class DiggerBlockClaimRegistry {
    private static final Map<BlockPos, UUID> CLAIMS = new HashMap<>();
    private static final Map<UUID, BlockPos> BY_CITIZEN = new HashMap<>();

    private DiggerBlockClaimRegistry() {
    }

    /**
     * Claim {@code pos} for {@code citizenId}. If the citizen already holds another block, that
     * claim is released first (a citizen only ever targets one block at a time).
     *
     * @return true if the claim succeeded — false if another citizen already owns this position.
     */
    public static synchronized boolean tryClaim(BlockPos pos, UUID citizenId) {
        BlockPos existing = BY_CITIZEN.get(citizenId);
        if (existing != null && !existing.equals(pos)) {
            CLAIMS.remove(existing);
        }
        UUID holder = CLAIMS.get(pos);
        if (holder != null && !holder.equals(citizenId)) {
            return false;
        }
        CLAIMS.put(pos.immutable(), citizenId);
        BY_CITIZEN.put(citizenId, pos.immutable());
        return true;
    }

    /** True if {@code pos} is claimed by a different citizen than {@code citizenId}. */
    public static synchronized boolean isClaimedByOther(BlockPos pos, UUID citizenId) {
        UUID holder = CLAIMS.get(pos);
        return holder != null && !holder.equals(citizenId);
    }

    /** Release whatever block {@code citizenId} currently holds (if any). Idempotent. */
    public static synchronized void release(UUID citizenId) {
        BlockPos held = BY_CITIZEN.remove(citizenId);
        if (held != null) CLAIMS.remove(held);
    }

    /** Drop a specific block claim regardless of who owns it. Called when the block is mined so
     *  the next pass's {@link #tryClaim} doesn't see a stale entry pointing at air. */
    public static synchronized void releaseBlock(BlockPos pos) {
        UUID holder = CLAIMS.remove(pos);
        if (holder != null) {
            BlockPos byHolder = BY_CITIZEN.get(holder);
            if (byHolder != null && byHolder.equals(pos)) BY_CITIZEN.remove(holder);
        }
    }

    /** Test-only: clear all claims. Not used in normal flow. */
    static synchronized void clearAll() {
        CLAIMS.clear();
        BY_CITIZEN.clear();
    }
}
