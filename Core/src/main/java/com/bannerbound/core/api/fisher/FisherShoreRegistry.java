package com.bannerbound.core.api.fisher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Server-wide registry that locks a shore tile to a single fisher so multiple citizens — within
 * the same settlement or across settlements — never converge on the exact same spot. Each fisher
 * holds at most one shore at a time; claiming a new shore atomically releases the old one.
 * <p>
 * Mirrors {@link com.bannerbound.core.api.entity.ForesterTreeRegistry} in pattern + lifecycle:
 * lives in memory only, drops on server restart (no fisher is mid-cast at that moment, so the
 * next scan re-claims from scratch), and all calls happen on the server thread so plain
 * {@link HashMap} access is fine. Chunk unload skips {@code Goal.stop()}, so a claim whose owner
 * is no longer loaded/alive is treated as stale and cleared on read (the {@code DiggerClaims}
 * pattern) instead of blocking that shore until restart.
 */
public final class FisherShoreRegistry {
    /** Packed shore-tile {@link BlockPos#asLong()} → owning citizen id. */
    private static final Map<Long, UUID> shoreToFisher = new HashMap<>();
    /** Reverse index so {@link #release} doesn't have to scan the whole shore map. */
    private static final Map<UUID, Long> fisherToShore = new HashMap<>();

    private FisherShoreRegistry() {
    }

    /**
     * Atomic transition: releases any shore currently held by {@code citizenId}, then claims
     * {@code shore}. Returns true on success.
     * <p>
     * If {@code shore} is held by a <em>different</em> citizen the transition fails — the
     * caller's previous claim (if any) is preserved so they don't lose their current spot just
     * because they tried to switch to an unavailable one. Caller should fall through to the next
     * candidate.
     * <p>
     * Idempotent for same-citizen self-claims (already-held → still-held, returns true).
     */
    public static boolean tryClaim(UUID citizenId, BlockPos shore) {
        long key = shore.asLong();
        UUID currentOwner = shoreToFisher.get(key);
        if (currentOwner != null && !currentOwner.equals(citizenId)) {
            if (ownerExists(currentOwner)) {
                return false;
            }
            release(currentOwner);   // stale: owner gone (unloaded/died without stop())
        }
        // Release this citizen's previous claim (if any, and if it's a different tile).
        Long previousKey = fisherToShore.get(citizenId);
        if (previousKey != null && previousKey != key) {
            shoreToFisher.remove(previousKey);
        }
        shoreToFisher.put(key, citizenId);
        fisherToShore.put(citizenId, key);
        return true;
    }

    /** Releases the citizen's current shore claim (if any). Called by {@link
     *  com.bannerbound.core.entity.FisherWorkGoal#stop} when the goal ends so other fishers
     *  can pick up the freed spot. */
    public static void release(UUID citizenId) {
        Long key = fisherToShore.remove(citizenId);
        if (key == null) return;
        UUID owner = shoreToFisher.get(key);
        if (citizenId.equals(owner)) {
            shoreToFisher.remove(key);
        }
    }

    /** True if {@code shore} is held by any citizen other than {@code citizenId}. Used during
     *  candidate filtering so the fisher never even considers a shore another fisher is on. */
    public static boolean isClaimedByOther(BlockPos shore, UUID citizenId) {
        UUID owner = shoreToFisher.get(shore.asLong());
        if (owner == null || owner.equals(citizenId)) return false;
        if (ownerExists(owner)) return true;
        release(owner);   // stale: owner gone (unloaded/died without stop())
        return false;
    }

    /** True if any <em>other</em> fisher's claimed shore is within {@code rangeBlocks} of
     *  {@code shore} (square distance, exclusive). Used to enforce minimum separation between
     *  fishers — even on a same-pier shared spot, two fishers shouldn't stand right next to each
     *  other, both because the player wants to see them visually distinct and because adjacent
     *  cast targets would visually merge. */
    public static boolean isAnyClaimWithin(BlockPos shore, UUID citizenId, int rangeBlocks) {
        double sqRange = (double) rangeBlocks * (double) rangeBlocks;
        List<UUID> stale = null;
        for (Map.Entry<Long, UUID> e : shoreToFisher.entrySet()) {
            if (e.getValue().equals(citizenId)) continue;
            BlockPos otherShore = BlockPos.of(e.getKey());
            if (shore.distSqr(otherShore) >= sqRange) continue;
            if (ownerExists(e.getValue())) return true;
            if (stale == null) stale = new ArrayList<>();
            stale.add(e.getValue());   // stale: owner gone — collect, release after the iteration
        }
        if (stale != null) {
            for (UUID owner : stale) release(owner);
        }
        return false;
    }

    /** True if the claiming citizen still exists (loaded + alive) in any dimension — a claim held by
     *  a vanished entity is stale and gets cleared on read, mirroring {@code DiggerClaims}. */
    private static boolean ownerExists(UUID citizenId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return true;   // can't verify — treat the claim as live
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(citizenId);
            if (e != null && e.isAlive()) return true;
        }
        return false;
    }
}
