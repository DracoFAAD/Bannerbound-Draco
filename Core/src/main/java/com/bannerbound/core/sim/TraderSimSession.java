package com.bannerbound.core.sim;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Server-side state for one running long-distance journey — either the
 * {@code /bannerbound trader_simulate} debug run (a spawned throwaway trader) or an ADOPTED trade
 * courier (a real roster stocker walking a deal's goods — see {@code TradeCourierManager}). Holds
 * the waypoint chain, the moving force-ticket centre, and the stuck/progress counters the per-tick
 * driver mutates. Sessions are transient (rebuilt after a restart by their owner); the road they
 * lay persists independently.
 */
public final class TraderSimSession {
    public final UUID id;                   // session identity — also the chunk-ticket value
    public UUID traderId;                   // the live entity while REAL; reassigned on each realize
    public final UUID initiator;            // nullable (console-initiated / courier journeys)
    public final BlockPos start;
    public final BlockPos end;
    public List<int[]> waypoints;           // {x, z} columns start → end; null while the plan is pending
    public final long startGameTick;
    public long maxGameTick;                // set once the plan lands (path-length budget)
    public final double totalDist;
    public final boolean sailing;           // may cross deep water by boat; if false, deep water is a wall

    public int index = 0;                      // current waypoint
    public ChunkPos ticketCenter;              // chunk currently force-ticketed (the moving bubble)
    public BlockPos lastRoadPos = null;        // last block we paved (dedup)
    public UUID boatId = null;                  // the oak boat while crossing water (null on land)
    public long noBoatUntil = 0;                // game-tick before which re-boarding is suppressed
    public double bestDist = Double.MAX_VALUE;  // closest horizontal dist achieved to the current waypoint
    public int noProgressTicks = 0;            // ticks since bestDist last improved
    public int nudges = 0;                      // consecutive micro-hops over a ledge (escalates)
    public long lastProgressTick = 0;
    public boolean returning = false;           // gave up on water and is walking back to the origin
    public String pendingFail = null;           // message to deliver once back at the origin
    public String pendingFailReason = null;     // machine-readable failure code for the listener

    // ─── realize-on-observe (computed-clock) state ──────────────────────────────────────────────
    public boolean ghost = false;               // unobserved: no entity, no chunk-load, position by clock
    public double gx, gy, gz;                    // ghost position (mirrors the entity while real)
    public double observedSpeed = 0.18;          // real cruise speed (blocks/tick), measured then reused by the ghost
    public double lastX = Double.NaN;            // previous real x/z, for the speed sample (NaN = skip this tick)
    public double lastZ = Double.NaN;
    public int deepWaterTicks = 0;               // consecutive ticks stuck in deep water (no-sailing recovery grace)

    // ─── per-session route planning (off-thread; resolved by tickAll) ──────────────────────────
    public CompletableFuture<List<int[]>> planFuture;

    // ─── ghost-road dedup (was static — must be per-journey with concurrent sessions) ──────────
    public int lastGhostRoadX = Integer.MIN_VALUE;
    public int lastGhostRoadZ = Integer.MIN_VALUE;

    // ─── adopted-courier mode (a real roster citizen; never ghosted, never discarded) ──────────
    public boolean adopted = false;
    public boolean debug = false;               // the trader_simulate run — progress spam + stop() scope
    public boolean mustered = false;            // adopted: reached the departure point (the stockpile)
    public long musterDeadline = 0;             // adopted: give up mustering after this tick
    public double prevStepHeight = Double.NaN;  // adopted: restore the citizen's step height on end
    public int lostTicks = 0;                   // adopted: ticks the entity has been unresolvable
    public transient TraderSimManager.JourneyListener listener; // re-attached on resume; may be null (debug)

    public TraderSimSession(UUID id, UUID traderId, UUID initiator, BlockPos start, BlockPos end,
                            List<int[]> waypoints, ChunkPos ticketCenter,
                            long startGameTick, long maxGameTick, double totalDist, boolean sailing) {
        this.id = id;
        this.traderId = traderId;
        this.initiator = initiator;
        this.start = start;
        this.end = end;
        this.waypoints = waypoints;
        this.ticketCenter = ticketCenter;
        this.startGameTick = startGameTick;
        this.maxGameTick = maxGameTick;
        this.totalDist = totalDist;
        this.sailing = sailing;
    }
}
