package com.bannerbound.core.sim;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.data.CitizenNameLoader;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Throwaway driver for {@code /bannerbound trader_simulate <start> <end> [sailing]} — a single-trader
 * proof of the long-distance traversal model. Two liveness states, switched on observation:
 *
 * <ul>
 *   <li><b>REAL</b> — a real {@link CitizenEntity} carrying its own moving chunk-load ticket (a 5×5
 *       entity-ticking bubble). It walks/boats, lays road live, is robbable. Used whenever a player is
 *       near enough to see it.</li>
 *   <li><b>GHOST</b> — no entity, no ticket, <em>no chunk-load</em>. Its position advances by a clock
 *       at the cruise speed measured while it was real; road is recorded as data and materialized when
 *       a chunk later loads. Used whenever no player is near. The instant a player approaches, it
 *       re-materializes into a REAL entity at its dead-reckoned position (boat reconstructed if it's
 *       over deep water), so robbery/meet-on-road still work — there's just no one to rob a ghost.</li>
 * </ul>
 *
 * The count of simultaneously-real traders is therefore bounded by player count, not trader count.
 *
 * <p>Still stubbed (next phase): the route is a straight-line waypoint chain — coarse heightmap A*
 * (proper land routing + water-avoidance for no-sailing) replaces it. While ghosting, the clock glides
 * straight over terrain/water it can't see; reality is consulted only on realize/road-materialize.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class TraderSimManager {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("bannerbound-trader-sim");

    /** Non-persistent ticket (auto-clears on restart, unlike {@code setChunkForced}). Radius 2 puts the
     *  trader's own chunk at ENTITY_TICKING (ticket level 31 = 33 - radius) so its AI runs with no
     *  player anywhere near — that's what makes a REAL trader keep walking off-screen. Valued by the
     *  SESSION id (not the chunk) so two journeys crossing the same chunk never drop each other's. */
    private static final TicketType<UUID> TRADER_TICKET =
        TicketType.create("bannerbound_trader_sim", UUID::compareTo);
    private static final int TICKET_RADIUS = 2;

    private static final int WAYPOINT_SPACING = 12;    // blocks between waypoints (straight-line fallback)
    private static final double ARRIVE_RADIUS = 2.5;   // horizontal dist to count a waypoint reached
    private static final int DENSIFY_STEP = 4;         // subdivide the coarse route to this spacing — smoother following
    private static final int DEEP_WATER_GRACE = 40;    // ticks a no-sailing trader tries to climb out of water before giving up
    private static final double MOVE_SPEED = 0.8;      // nav speed multiplier — citizen base is locked at 0.4, so ~0.32 (calm)
    private static final int STUCK_TICKS = 80;         // ticks of no progress toward a waypoint before a nudge
    private static final int MAX_NUDGES = 4;           // micro-hops over a ledge before we concede the leg
    private static final double NUDGE_DIST = 2.0;      // how far each unstick hop carries the trader
    private static final double STEP_HEIGHT = 1.0;     // climb 1-block terrace steps by walking, not jumping
    private static final double BOAT_SPEED = 0.35;     // boat propulsion per tick across water
    private static final double WATER_LOOKAHEAD = 2.0; // blocks ahead we sniff for water to board / land
    private static final int WADE_DEPTH = 2;           // water this shallow is walked, not boated/blocked
    private static final int BOARD_COOLDOWN = 30;      // ticks after stepping off before it may re-board

    // Realize-on-observe band is derived from the entity's real client-tracking range (see tickAll):
    // become real exactly when a client would start receiving the entity (no pop-in), and ghost a few
    // chunks past that (no pop-out), with the gap acting as hysteresis.
    private static final double REALIZE_FLOOR = 64.0;  // never realize closer than this
    private static final double GHOST_MARGIN = 48.0;   // ghost this far beyond the realize distance
    private static final double GHOST_MIN_SPEED = 0.08;// clamp the measured cruise speed used by the ghost
    private static final double GHOST_MAX_SPEED = 0.5;

    private static final int ROUTE_GRID = 16;            // coarse A* node spacing (blocks) — bigger = far cheaper
    private static final int ROUTE_MAX_EXPANSIONS = 60000; // headroom to route the long way around water
    private static final double SLOPE_PENALTY = 0.6;     // cost per block of height change between nodes
    private static final double WATER_PENALTY = 8.0;     // extra cost crossing deep water (sailing only)
    private static final double ROAD_DISCOUNT = 0.2;     // an existing-road node costs this fraction → reuse roads
    private static final int OVERRIDE_RADIUS = 12;       // chunks around start/end whose REAL surface
                                                         // (incl. player bridges/builds) overrides the prediction
    private static final int DRAIN_CHUNK_BUDGET = 8;   // chunks of pending road materialized per tick

    /** Every running journey, keyed by session id — the debug run and any number of adopted trade
     *  couriers tick side by side. Sessions are transient; owners rebuild them after a restart. */
    private static final Map<UUID, TraderSimSession> SESSIONS = new java.util.LinkedHashMap<>();
    /** The one {@code /bannerbound trader_simulate} session (stop()/isActive() scope to it). */
    private static UUID debugSessionId;

    // Ghost-laid road persists independently of the session: recorded per chunk, materialized when that
    // chunk next loads (a player visiting it / going to meet the trader), surviving the journey's end.
    private static final Map<Long, List<int[]>> PENDING_ROAD = new HashMap<>();

    // The road *network*: grid cells that carry road, accumulated across every journey (persists for the
    // JVM session). The router discounts these cells so new routes converge onto and follow existing
    // roads instead of laying parallel lines. Concurrent set: route planning READS it off-thread while
    // the main thread stamps new routes in.
    private static final java.util.Set<Long> ROAD_NETWORK = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Completion callbacks for owned journeys (adopted couriers) — fired on the main thread exactly
     *  once, after the session is torn down. Transient; the owner re-attaches on resume. */
    public interface JourneyListener {
        void onArrived(UUID journeyId);

        /** {@code reason} ∈ route_failed / muster_timeout / timeout / returned / lost / stopped. */
        void onFailed(UUID journeyId, String reason);
    }

    private TraderSimManager() {
    }

    public static boolean isActive() {
        return debugSessionId != null && SESSIONS.containsKey(debugSessionId);
    }

    /** True while journey {@code id} is still running. */
    public static boolean hasSession(UUID id) {
        return id != null && SESSIONS.containsKey(id);
    }

    /** Begins the DEBUG journey ({@code /bannerbound trader_simulate}): spawns a throwaway trader once
     *  the off-thread route plan lands. Replaces any previous debug run; adopted courier journeys are
     *  untouched. Returns null unless it can't even begin. */
    public static String start(MinecraftServer server, ServerPlayer initiator, BlockPos start, BlockPos end,
                               boolean sailing) {
        if (server == null) return "No server.";
        ServerLevel level = server.overworld();
        stop(server); // clears any previous DEBUG session (plan pending or running)

        long now = level.getGameTime();
        double totalDist = Math.hypot((double) end.getX() - start.getX(), (double) end.getZ() - start.getZ());
        TraderSimSession s = new TraderSimSession(UUID.randomUUID(), null,
            initiator == null ? null : initiator.getUUID(), start, end,
            null, null, now, Long.MAX_VALUE, totalDist, sailing);
        s.debug = true;
        s.gx = start.getX() + 0.5;
        s.gz = start.getZ() + 0.5;
        // Snapshot real loaded-chunk surfaces (incl. player bridges) on the main thread, then plan off-thread.
        Map<Long, int[]> override = snapshotLoadedFloors(level, start, end);
        s.planFuture = CompletableFuture.supplyAsync(() -> planRoute(level, start, end, sailing, override));
        SESSIONS.put(s.id, s);
        debugSessionId = s.id;
        return null;
    }

    /** Begins an ADOPTED courier journey: {@code courier} (a real roster citizen, typically a trading
     *  stocker) is driven from {@code from} to {@code to} by this sim — mustering toward {@code from}
     *  while the route plan runs off-thread, then walking the waypoints. The citizen keeps its own AI
     *  suspended via {@code CitizenEntity.isOnTradeJourney()} (the caller sets the journey id) and is
     *  NEVER ghosted or discarded — it stays real under the moving ticket for the whole trip. */
    public static UUID startAdopted(MinecraftServer server, CitizenEntity courier, BlockPos from,
                                    BlockPos to, boolean sailing, JourneyListener listener) {
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        double totalDist = Math.hypot((double) to.getX() - from.getX(), (double) to.getZ() - from.getZ());
        TraderSimSession s = new TraderSimSession(UUID.randomUUID(), courier.getUUID(), null,
            from, to, null, null, now, Long.MAX_VALUE, totalDist, sailing);
        s.adopted = true;
        s.listener = listener;
        s.musterDeadline = now + 1200L;
        s.gx = courier.getX();
        s.gy = courier.getY();
        s.gz = courier.getZ();

        AttributeInstance step = courier.getAttribute(Attributes.STEP_HEIGHT);
        if (step != null) {
            s.prevStepHeight = step.getBaseValue();
            step.setBaseValue(STEP_HEIGHT);
        }
        if (courier.getNavigation() instanceof GroundPathNavigation gpn) gpn.setCanFloat(sailing);

        ChunkPos center = new ChunkPos(courier.blockPosition());
        level.getChunkSource().addRegionTicket(TRADER_TICKET, center, TICKET_RADIUS, s.id);
        s.ticketCenter = center;

        Map<Long, int[]> override = snapshotLoadedFloors(level, from, to);
        s.planFuture = CompletableFuture.supplyAsync(() -> planRoute(level, from, to, sailing, override));
        SESSIONS.put(s.id, s);
        return s.id;
    }

    /** Ends journey {@code id} (adopted courier abort / owner cleanup). Fires the listener with
     *  {@code reason} unless null. */
    public static void stopSession(MinecraftServer server, UUID id, String reason) {
        TraderSimSession s = SESSIONS.get(id);
        if (s == null) return;
        if (s.planFuture != null) {
            s.planFuture.cancel(true);
            s.planFuture = null;
        }
        finishFailed(server, s, reason == null ? "stopped" : reason, null);
    }

    /** Main-thread handoff once a session's off-thread plan is ready: set the waypoints (debug runs
     *  also spawn their trader here), or fail the journey. */
    private static void onPlanReady(MinecraftServer server, TraderSimSession s, List<int[]> planned) {
        ServerLevel level = server.overworld();
        List<int[]> waypoints = planned;
        if (waypoints == null) {
            if (!s.sailing) {
                finishFailed(server, s, "route_failed",
                    "Couldn't reach — no land route without crossing water (try sailing).");
                return;
            }
            waypoints = buildWaypoints(s.start, s.end); // sailing: straight line, cross by boat
        }
        waypoints = densify(waypoints, s.start); // tight following + stays on narrow bridges
        addRouteToNetwork(s.start, waypoints); // becomes part of the network later routes reuse

        long now = level.getGameTime();
        s.waypoints = waypoints;
        s.maxGameTick = now + Math.min(216000L, 2400L + (long) (routePathLength(s.start, waypoints) * 30.0));

        if (s.debug) {
            ChunkPos center = new ChunkPos(s.start);
            level.getChunk(center.x, center.z);
            int gy = groundY(level, s.start.getX(), s.start.getZ());
            s.gy = gy;
            CitizenEntity trader = makeTrader(level, s, s.gx, gy, s.gz);
            if (trader == null) {
                finishFailed(server, s, "route_failed", "Couldn't spawn the trader.");
                return;
            }
            s.traderId = trader.getUUID();
            level.getChunkSource().addRegionTicket(TRADER_TICKET, center, TICKET_RADIUS, s.id);
            s.ticketCenter = center;
            message(server, s, String.format("Trader dispatched (%d, %d, %d) → (%d, %d, %d), sailing %s.",
                s.start.getX(), s.start.getY(), s.start.getZ(),
                s.end.getX(), s.end.getY(), s.end.getZ(), s.sailing ? "ON" : "OFF"));
        }
    }

    /** Total length of the planned path (start → each waypoint), for a fair time budget on detours. */
    private static double routePathLength(BlockPos start, List<int[]> waypoints) {
        double len = 0.0;
        double px = start.getX() + 0.5, pz = start.getZ() + 0.5;
        for (int[] wp : waypoints) {
            len += horiz(px, pz, wp[0] + 0.5, wp[1] + 0.5);
            px = wp[0] + 0.5;
            pz = wp[1] + 0.5;
        }
        return len;
    }

    /** Per-tick driver. Wire into {@code ResearchEvents.onServerTick} next to {@link SimulationManager#tickAll}. */
    public static void tickAll(MinecraftServer server) {
        if (server == null) return;
        ServerLevel level = server.overworld();
        drainReadyRoad(level); // turn ghost-laid road into blocks in chunks that just loaded (runs even idle)
        if (SESSIONS.isEmpty()) return;
        for (TraderSimSession s : new ArrayList<>(SESSIONS.values())) {
            tickSession(server, level, s);
        }
    }

    private static void tickSession(MinecraftServer server, ServerLevel level, TraderSimSession s) {
        long now = level.getGameTime();

        // Resolve this session's off-thread route plan the tick it lands.
        if (s.planFuture != null && s.planFuture.isDone()) {
            List<int[]> planned;
            try {
                planned = s.planFuture.get();
            } catch (Exception e) {
                planned = null;
                LOGGER.warn("[TraderSim] route planning failed", e);
            }
            s.planFuture = null;
            onPlanReady(server, s, planned);
            if (!SESSIONS.containsKey(s.id)) return; // plan failure finished the session
        }

        // Adopted couriers muster toward the departure point while the plan is still cooking (and
        // keep mustering after it lands, until they actually reach it).
        if (s.adopted && !s.mustered) {
            if (now >= s.musterDeadline) {
                finishFailed(server, s, "muster_timeout", null);
                return;
            }
            CitizenEntity courier = resolveAdopted(server, level, s);
            if (courier == null) return; // lost-grace handling inside resolveAdopted
            followWithTicket(level, s, courier);
            if (horiz(courier.getX(), courier.getZ(), s.start.getX() + 0.5, s.start.getZ() + 0.5)
                    <= ARRIVE_RADIUS + 1.5) {
                s.mustered = true;
                courier.getNavigation().stop();
                resetLeg(s);
            } else if (courier.getNavigation().isDone()) {
                courier.getNavigation().moveTo(s.start.getX() + 0.5,
                    groundY(level, s.start.getX(), s.start.getZ()), s.start.getZ() + 0.5, MOVE_SPEED);
            }
            return;
        }
        if (s.waypoints == null) return; // plan still pending (debug run waits in place)

        if (now >= s.maxGameTick) {
            finishFailed(server, s, "timeout", "Trader simulation hit its time budget — ended.");
            return;
        }

        CitizenEntity trader;
        if (s.adopted) {
            // Adopted couriers are ALWAYS real (a roster citizen is never discarded); no ghosting.
            trader = resolveAdopted(server, level, s);
            if (trader == null) return;
        } else {
            // Resolve the trader's current position (live entity if REAL, dead-reckoned if GHOST).
            trader = null;
            if (!s.ghost) {
                Entity e = s.traderId == null ? null : level.getEntity(s.traderId);
                if (e instanceof CitizenEntity t && !t.isRemoved()) {
                    trader = t;
                } else {
                    ghostFromLastKnown(level, s); // entity vanished unexpectedly → ghost, don't end
                }
            }
            double px = trader != null ? trader.getX() : s.gx;
            double pz = trader != null ? trader.getZ() : s.gz;

            // Switch liveness on observation. Realize exactly when a client would start receiving the
            // entity (its tracking range, capped by server view distance) so there's no pop-in; ghost a
            // few chunks past that so there's no pop-out; the gap is hysteresis.
            double nearest = nearestPlayerDist(server, px, pz);
            int trackChunks = Math.min(EntityType.WANDERING_TRADER.clientTrackingRange(),
                server.getPlayerList().getViewDistance());
            double realizeDist = Math.max(REALIZE_FLOOR, trackChunks * 16.0);
            double ghostDist = realizeDist + GHOST_MARGIN;
            if (s.ghost && nearest <= realizeDist) {
                trader = realize(level, s);
            } else if (!s.ghost && trader != null && nearest >= ghostDist) {
                ghostify(level, s, trader);
                trader = null;
            }
        }

        if (s.ghost || trader == null) {
            tickGhost(server, level, s);
        } else {
            tickReal(server, level, s, now, trader);
        }
        if (!SESSIONS.containsKey(s.id)) return; // a finish fired inside the tick

        if (s.debug && now - s.lastProgressTick >= 40L) {
            s.lastProgressTick = now;
            broadcastProgress(server, s);
        }
    }

    /** The adopted courier entity, with a lost-grace: a chunk-edge race can briefly lose the entity,
     *  so keep the ticket parked at the last known spot and refetch; a courier missing for too long
     *  (killed, or truly gone) fails the journey — the owner falls back to the clock. */
    private static CitizenEntity resolveAdopted(MinecraftServer server, ServerLevel level, TraderSimSession s) {
        Entity e = s.traderId == null ? null : level.getEntity(s.traderId);
        if (e instanceof CitizenEntity t && !t.isRemoved() && t.isAlive()) {
            s.lostTicks = 0;
            return t;
        }
        if (++s.lostTicks > 200) {
            finishFailed(server, s, "lost", null);
        }
        return null;
    }

    /** Moves the session's chunk-ticket bubble to follow {@code walker} into each new chunk. */
    private static void followWithTicket(ServerLevel level, TraderSimSession s, CitizenEntity walker) {
        ChunkPos tc = new ChunkPos(walker.blockPosition());
        if (!tc.equals(s.ticketCenter)) {
            if (s.ticketCenter != null) {
                level.getChunkSource().removeRegionTicket(TRADER_TICKET, s.ticketCenter, TICKET_RADIUS, s.id);
            }
            level.getChunkSource().addRegionTicket(TRADER_TICKET, tc, TICKET_RADIUS, s.id);
            s.ticketCenter = tc;
        }
    }

    /** Ends the DEBUG session and/or cancels its pending plan (adopted journeys are untouched —
     *  they end via {@link #stopSession} or their own completion). */
    public static void stop(MinecraftServer server) {
        TraderSimSession s = debugSessionId == null ? null : SESSIONS.get(debugSessionId);
        debugSessionId = null;
        if (s == null) return;
        if (s.planFuture != null) {
            s.planFuture.cancel(true);
            s.planFuture = null;
        }
        finish(server, s, "Trader simulation stopped.", false);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (TraderSimSession s : new ArrayList<>(SESSIONS.values())) {
            if (s.planFuture != null) {
                s.planFuture.cancel(true);
                s.planFuture = null;
            }
            finish(event.getServer(), s, null, false);
        }
    }

    private static void finishArrived(MinecraftServer server, TraderSimSession s, String why) {
        finish(server, s, why, true);
    }

    private static void finishFailed(MinecraftServer server, TraderSimSession s, String reason, String why) {
        s.pendingFailReason = reason;
        finish(server, s, why, false);
    }

    /** Tears the session down (ticket, boat, spawned entity — adopted couriers are NOT discarded)
     *  and fires the completion listener exactly once. */
    private static void finish(MinecraftServer server, TraderSimSession s, String why, boolean arrived) {
        if (SESSIONS.remove(s.id) == null) return; // already finished on another path
        if (s.id.equals(debugSessionId)) debugSessionId = null;
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (s.ticketCenter != null) {
            level.getChunkSource().removeRegionTicket(TRADER_TICKET, s.ticketCenter, TICKET_RADIUS, s.id);
        }
        if (s.boatId != null) {
            Entity b = level.getEntity(s.boatId);
            if (b != null) {
                Entity rider = s.traderId == null ? null : level.getEntity(s.traderId);
                if (rider != null && rider.getVehicle() == b) rider.stopRiding();
                b.discard();
            }
        }
        if (s.adopted) {
            // The courier is a real roster citizen — restore what adoption changed and leave it be.
            Entity e = s.traderId == null ? null : level.getEntity(s.traderId);
            if (e instanceof CitizenEntity courier && !Double.isNaN(s.prevStepHeight)) {
                AttributeInstance step = courier.getAttribute(Attributes.STEP_HEIGHT);
                if (step != null) step.setBaseValue(s.prevStepHeight);
            }
        } else if (s.traderId != null) {
            Entity e = level.getEntity(s.traderId);
            if (e != null) e.discard();
        }
        if (why != null) message(server, s, why);
        if (s.listener != null) {
            if (arrived) {
                s.listener.onArrived(s.id);
            } else {
                s.listener.onFailed(s.id, s.pendingFailReason == null ? "stopped" : s.pendingFailReason);
            }
        }
    }

    // ─── liveness transitions ─────────────────────────────────────────────────────────────────────

    /** REAL → GHOST: snapshot position, tear down the entity + boat + ticket (stops all chunk-load).
     *  Never called for adopted couriers — a roster citizen is never discarded. */
    private static void ghostify(ServerLevel level, TraderSimSession s, CitizenEntity trader) {
        s.gx = trader.getX();
        s.gy = trader.getY();
        s.gz = trader.getZ();
        s.ghost = true;
        if (s.boatId != null) {
            Entity b = level.getEntity(s.boatId);
            if (b != null) b.discard();
            s.boatId = null;
        }
        trader.discard();
        if (s.ticketCenter != null) {
            level.getChunkSource().removeRegionTicket(TRADER_TICKET, s.ticketCenter, TICKET_RADIUS, s.id);
            s.ticketCenter = null;
        }
    }

    /** GHOST → REAL: load the chunk at the dead-reckoned position and spawn the entity there, putting it
     *  in a boat if it's over deep water (so a player who arrives mid-crossing sees it boated). */
    private static CitizenEntity realize(ServerLevel level, TraderSimSession s) {
        int bx = (int) Math.floor(s.gx);
        int bz = (int) Math.floor(s.gz);
        level.getChunk(bx >> 4, bz >> 4); // force the chunk present so we can read ground + spawn
        boolean overDeep = s.sailing && isDeepWaterColumn(level, bx, bz);
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
        double y = overDeep ? surfaceY : groundY(level, bx, bz);

        CitizenEntity trader = makeTrader(level, s, s.gx, y, s.gz);
        if (trader == null) return null;
        s.traderId = trader.getUUID();
        s.ghost = false;
        s.lastX = Double.NaN; // skip the first speed sample after the spawn jump
        ChunkPos center = new ChunkPos(bx >> 4, bz >> 4);
        level.getChunkSource().addRegionTicket(TRADER_TICKET, center, TICKET_RADIUS, s.id);
        s.ticketCenter = center;

        if (overDeep) {
            Boat boat = spawnBoat(level, s.gx, surfaceY, s.gz, trader.getYRot());
            if (boat != null) {
                trader.startRiding(boat, true);
                s.boatId = boat.getUUID();
            }
        }
        resetLeg(s);
        return trader;
    }

    /** Defensive: the real entity disappeared (chunk edge race) — convert to a ghost at the last known
     *  position instead of ending the whole journey. */
    private static void ghostFromLastKnown(ServerLevel level, TraderSimSession s) {
        s.ghost = true;
        s.boatId = null;
        if (s.ticketCenter != null) {
            level.getChunkSource().removeRegionTicket(TRADER_TICKET, s.ticketCenter, TICKET_RADIUS, s.id);
            s.ticketCenter = null;
        }
    }

    private static CitizenEntity makeTrader(ServerLevel level, TraderSimSession s, double x, double y, double z) {
        CitizenEntity trader = BannerboundCore.CITIZEN.get().create(level);
        if (trader == null) return null;

        // Identity: borrow the initiator's settlement (banner colour, era) if they have one, else a
        // detached citizen with a null settlement (safe — every getSettlement() caller null-checks).
        Settlement set = s.initiator != null ? SettlementData.get(level).getByPlayer(s.initiator) : null;
        Era era = set != null ? set.age() : SettlementData.get(level).getWorldAge();
        CitizenGender gender = level.random.nextBoolean() ? CitizenGender.MALE : CitizenGender.FEMALE;
        ChatFormatting color = set != null ? set.identityFormatting() : ChatFormatting.GOLD;
        trader.initializeCitizen(set != null ? set.id() : null,
            CitizenNameLoader.randomName(level.random, era, gender), gender, era, color);
        trader.setCompliance(100);
        trader.markSimulated(); // not saved to NBT; not part of any settlement roster

        trader.moveTo(x, y, z, 0.0F, 0.0F);
        // Speed: the base is hard-locked to 0.4 by CitizenEntity.recomputeSpeedModifier, so we DON'T set
        // it — we drive the calm pace via the MOVE_SPEED navigation multiplier instead (and the ghost
        // measures whatever the real cruise turns out to be, so the two stay matched).
        AttributeInstance step = trader.getAttribute(Attributes.STEP_HEIGHT);
        if (step != null) step.setBaseValue(STEP_HEIGHT); // walk up terrace ledges instead of stalling
        stripGoals(trader); // ONLY our navigation drives it — no citizen work/patrol/wander goals
        if (trader.getNavigation() instanceof GroundPathNavigation gpn) gpn.setCanFloat(s.sailing);
        if (!level.addFreshEntity(trader)) return null;
        return trader;
    }

    // ─── GHOST tick (computed clock, no chunk access) ─────────────────────────────────────────────

    private static void tickGhost(MinecraftServer server, ServerLevel level, TraderSimSession s) {
        int[] wp = s.waypoints.get(s.index);
        double dx = (wp[0] + 0.5) - s.gx;
        double dz = (wp[1] + 0.5) - s.gz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 1.0e-3) {
            double step = Math.min(clampSpeed(s.observedSpeed), dist);
            s.gx += dx / dist * step;
            s.gz += dz / dist * step;
            recordGhostRoad(s, (int) Math.floor(s.gx), (int) Math.floor(s.gz));
        }
        if (horiz(s.gx, s.gz, wp[0] + 0.5, wp[1] + 0.5) <= ARRIVE_RADIUS) {
            s.index++;
            if (s.index >= s.waypoints.size()) {
                if (s.returning) {
                    finishFailed(server, s, "returned", s.pendingFail);
                } else {
                    finishArrived(server, s, s.debug ? "Trader arrived at its destination." : null);
                }
            }
        }
    }

    /** Remember a road column the ghost glided over, bucketed by chunk, to materialize when that chunk
     *  next loads. If the chunk happens to be loaded right now (a player is near the ghost), queue it
     *  for materialization next tick. */
    private static void recordGhostRoad(TraderSimSession s, int x, int z) {
        if (x == s.lastGhostRoadX && z == s.lastGhostRoadZ) return;
        s.lastGhostRoadX = x;
        s.lastGhostRoadZ = z;
        PENDING_ROAD.computeIfAbsent(ChunkPos.asLong(x >> 4, z >> 4), k -> new ArrayList<>())
            .add(new int[] { x, z });
    }

    // ─── REAL tick (live entity, walks/boats, lays road, robbable) ────────────────────────────────

    private static void tickReal(MinecraftServer server, ServerLevel level, TraderSimSession s, long now,
                                 CitizenEntity trader) {
        trader.setAirSupply(trader.getMaxAirSupply()); // prototype safety: the sim trader never drowns

        // Measure cruise speed (only while actually moving, so pauses don't drag it down) and keep the
        // ghost mirror current so a later ghostify has the exact position + matching speed.
        if (!Double.isNaN(s.lastX)) {
            double disp = horiz(trader.getX(), trader.getZ(), s.lastX, s.lastZ);
            if (disp > 0.05) s.observedSpeed = clampSpeed(s.observedSpeed * 0.8 + disp * 0.2);
        }
        s.lastX = trader.getX();
        s.lastZ = trader.getZ();
        s.gx = trader.getX();
        s.gy = trader.getY();
        s.gz = trader.getZ();

        // Move the force-ticket bubble to follow the trader into each new chunk.
        followWithTicket(level, s, trader);

        boolean boating = trader.isPassenger() && trader.getVehicle() instanceof Boat;
        if (!boating) paveUnder(level, trader, s); // never pave water

        // Advance past any waypoints we've already reached.
        int[] wp = s.waypoints.get(s.index);
        double d = horiz(trader.getX(), trader.getZ(), wp[0] + 0.5, wp[1] + 0.5);
        while (d <= ARRIVE_RADIUS) {
            s.index++;
            if (s.index >= s.waypoints.size()) {
                if (s.returning) {
                    finishFailed(server, s, "returned", s.pendingFail);
                } else {
                    finishArrived(server, s, s.debug ? "Trader arrived at its destination." : null);
                }
                return;
            }
            resetLeg(s);
            wp = s.waypoints.get(s.index);
            d = horiz(trader.getX(), trader.getZ(), wp[0] + 0.5, wp[1] + 0.5);
        }

        // Track genuine progress (closing the gap to the waypoint), shared by the stuck checks below.
        if (d < s.bestDist - 0.25) {
            s.bestDist = d;
            s.noProgressTicks = 0;
            s.nudges = 0;
        } else {
            s.noProgressTicks++;
        }

        // Sniff ahead: only *deep* water (> WADE_DEPTH) needs a boat / blocks a no-sailing trader.
        int[] ahead = lookAhead(trader, wp, WATER_LOOKAHEAD);
        boolean deepAhead = isDeepWaterColumn(level, ahead[0], ahead[1]);
        boolean deepHere = isDeepWaterColumn(level, trader.getBlockX(), trader.getBlockZ());

        if (boating) {
            Boat boat = (Boat) trader.getVehicle();
            // Step off only onto DRY land toward the waypoint; keep the boat going until it's in reach.
            // Short cooldown stops the edge depth-flicker from immediately re-boarding it.
            int[] dry = nearestDryToward(level, trader, wp, 4);
            if (dry != null || s.noProgressTicks > STUCK_TICKS * 2) {
                int[] target = dry != null ? dry : nearestDryToward(level, trader, wp, 16);
                if (target == null) target = ahead;
                int ly = groundY(level, target[0], target[1]);
                trader.stopRiding();
                trader.moveTo(target[0] + 0.5, ly, target[1] + 0.5, trader.getYRot(), 0.0F);
                trader.getNavigation().stop();
                boat.discard();
                s.boatId = null;
                s.noBoatUntil = now + BOARD_COOLDOWN;
                resetLeg(s);
            } else {
                driveBoat(boat, wp);
            }
        } else if (s.sailing && now >= s.noBoatUntil && (deepAhead || deepHere)) {
            // Sailing → board an oak boat at the deep water just ahead and float across.
            int bx = deepAhead ? ahead[0] : trader.getBlockX();
            int bz = deepAhead ? ahead[1] : trader.getBlockZ();
            int by = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
            Boat boat = spawnBoat(level, bx + 0.5, by, bz + 0.5, trader.getYRot());
            if (boat != null) {
                trader.getNavigation().stop();
                trader.startRiding(boat, true);
                s.boatId = boat.getUUID();
                resetLeg(s);
            }
        } else {
            // No-sailing in deep water: first try to RECOVER for a grace window — hop back onto the
            // nearest dry ground toward the waypoint (e.g. back onto a bridge it slipped off) and keep
            // going; never drown. Only if it stays water-locked do we give up and turn back.
            if (!s.sailing && deepHere) {
                s.deepWaterTicks++;
                int[] dry = nearestDryToward(level, trader, wp, 6);
                if (dry != null && s.deepWaterTicks <= DEEP_WATER_GRACE) {
                    trader.moveTo(dry[0] + 0.5, groundY(level, dry[0], dry[1]), dry[1] + 0.5, trader.getYRot(), 0.0F);
                    trader.getNavigation().stop();
                    return;
                }
                int[] home = nearestDryToward(level, trader, new int[] { s.start.getX(), s.start.getZ() }, 16);
                if (home != null) {
                    trader.moveTo(home[0] + 0.5, groundY(level, home[0], home[1]), home[1] + 0.5, trader.getYRot(), 0.0F);
                    trader.getNavigation().stop();
                }
                if (!s.returning) beginReturn(server, s, trader);
                else finishFailed(server, s, "returned", s.pendingFail);
                return;
            }
            s.deepWaterTicks = 0;
            // On foot. Re-issue the path whenever navigation goes idle.
            if (trader.getNavigation().isDone()) {
                int wy = groundY(level, wp[0], wp[1]);
                trader.getNavigation().moveTo(wp[0] + 0.5, wy, wp[1] + 0.5, MOVE_SPEED);
            }
            if (!s.sailing && deepAhead && s.noProgressTicks > STUCK_TICKS * 2) {
                // No sailing and a deep-water wall we can't get past on foot.
                s.noProgressTicks = 0;
                if (!s.returning) {
                    beginReturn(server, s, trader);
                } else {
                    finishFailed(server, s, "returned", s.pendingFail); // blocked again heading home
                }
            } else if (s.noProgressTicks > STUCK_TICKS && !(deepAhead && !s.sailing)) {
                // Progress-based unstick over land ledges (one leg at a time, never skip ahead).
                s.noProgressTicks = 0;
                s.nudges++;
                if (s.nudges <= MAX_NUDGES) {
                    double dx = (wp[0] + 0.5) - trader.getX();
                    double dz = (wp[1] + 0.5) - trader.getZ();
                    double len = Math.max(1.0e-3, Math.sqrt(dx * dx + dz * dz));
                    double hop = Math.min(NUDGE_DIST, len);
                    int nx = (int) Math.floor(trader.getX() + dx / len * hop);
                    int nz = (int) Math.floor(trader.getZ() + dz / len * hop);
                    int ny = groundY(level, nx, nz);
                    trader.moveTo(nx + 0.5, ny, nz + 0.5);
                    trader.getNavigation().stop();
                    s.bestDist = horiz(nx + 0.5, nz + 0.5, wp[0] + 0.5, wp[1] + 0.5);
                } else if (s.returning) {
                    finishFailed(server, s, "returned", s.pendingFail); // wedged heading home
                } else {
                    int wy = groundY(level, wp[0], wp[1]);
                    trader.moveTo(wp[0] + 0.5, wy, wp[1] + 0.5);
                    trader.getNavigation().stop();
                    s.nudges = 0;
                    s.bestDist = Double.MAX_VALUE;
                }
            }
        }
    }

    /** Turn the trader around: rebuild the route from here back to the origin and head home. */
    private static void beginReturn(MinecraftServer server, TraderSimSession s, CitizenEntity trader) {
        s.returning = true;
        s.pendingFail = "Couldn't reach the destination — can't cross water.";
        s.waypoints = buildWaypoints(trader.blockPosition(), s.start);
        s.index = 0;
        resetLeg(s);
        message(server, s, "Trader hit impassable water — turning back.");
    }

    // ─── road materialization (ghost-laid road → blocks, lazily as chunks load) ────────────────────

    /** Materialize ghost-laid road in chunks that just loaded (queued by {@link #onChunkLoad}). Runs
     *  every tick regardless of an active session, so road shows up whenever you walk/teleport to it —
     *  including after the journey has ended. */
    /** Materialize ghost-laid road into blocks for any pending chunk that is currently loaded. Polled
     *  on the main thread (no chunk-load event → no off-thread races), a few times a second, regardless
     *  of an active session — so road shows up whenever you walk/teleport to it, including long after
     *  the journey ended. Chunks not yet visited stay pending. */
    private static void drainReadyRoad(ServerLevel level) {
        if (PENDING_ROAD.isEmpty() || level.getGameTime() % 10L != 0L) return;
        int chunkBudget = DRAIN_CHUNK_BUDGET;
        Iterator<Map.Entry<Long, List<int[]>>> it = PENDING_ROAD.entrySet().iterator();
        while (it.hasNext() && chunkBudget > 0) {
            Map.Entry<Long, List<int[]>> e = it.next();
            ChunkPos cp = new ChunkPos(e.getKey());
            if (!level.hasChunk(cp.x, cp.z)) continue; // not visited yet — leave it pending
            for (int[] pt : e.getValue()) {
                pave3Wide(level, pt[0], pt[1]);
            }
            it.remove();
            chunkBudget--;
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────────────────────────

    private static double nearestPlayerDist(MinecraftServer server, double px, double pz) {
        ServerLevel overworld = server.overworld();
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() != overworld) continue;
            double dd = horiz(p.getX(), p.getZ(), px, pz);
            if (dd < best) best = dd;
        }
        return best;
    }

    private static double clampSpeed(double v) {
        return Math.max(GHOST_MIN_SPEED, Math.min(GHOST_MAX_SPEED, v));
    }

    private static List<int[]> buildWaypoints(BlockPos start, BlockPos end) {
        List<int[]> out = new ArrayList<>();
        double dx = (double) end.getX() - start.getX();
        double dz = (double) end.getZ() - start.getZ();
        double dist = Math.hypot(dx, dz);
        int steps = Math.max(1, (int) Math.ceil(dist / WAYPOINT_SPACING));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            out.add(new int[] {
                (int) Math.round(start.getX() + dx * t),
                (int) Math.round(start.getZ() + dz * t)
            });
        }
        return out;
    }

    /** Subdivides a coarse route into ~{@link #DENSIFY_STEP}-block hops so the trader follows it tightly
     *  (vanilla nav gets little room to weave, and it stays on narrow bridges). */
    private static List<int[]> densify(List<int[]> wps, BlockPos start) {
        List<int[]> out = new ArrayList<>();
        double px = start.getX();
        double pz = start.getZ();
        for (int[] wp : wps) {
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(wp[0] - px, wp[1] - pz) / DENSIFY_STEP));
            for (int i = 1; i <= steps; i++) {
                double t = (double) i / steps;
                out.add(new int[] { (int) Math.round(px + (wp[0] - px) * t), (int) Math.round(pz + (wp[1] - pz) * t) });
            }
            px = wp[0];
            pz = wp[1];
        }
        return out;
    }

    private record RouteNode(long node, double f) {}

    /** Plans a route from {@code start} to {@code end} over PREDICTED terrain — noise-sampled
     *  {@code getBaseHeight} + sea level, so nothing is loaded or generated — as a coarse 8-block-grid
     *  A*. Prefers gentle land; with sailing it may cross deep water at a cost, without sailing deep
     *  water is impassable. Returns the waypoint chain, or null when no route exists (the no-sailing
     *  water-locked case) or the search caps out. */
    private static List<int[]> planRoute(ServerLevel level, BlockPos start, BlockPos end, boolean sailing,
                                         Map<Long, int[]> override) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sea = level.getSeaLevel();

        int egx = Math.floorDiv(end.getX(), ROUTE_GRID);
        int egz = Math.floorDiv(end.getZ(), ROUTE_GRID);
        long startN = ChunkPos.asLong(Math.floorDiv(start.getX(), ROUTE_GRID), Math.floorDiv(start.getZ(), ROUTE_GRID));
        long endN = ChunkPos.asLong(egx, egz);

        Map<Long, Integer> height = new HashMap<>();
        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        HashSet<Long> closed = new HashSet<>();
        PriorityQueue<RouteNode> open = new PriorityQueue<>(Comparator.comparingDouble(RouteNode::f));
        gScore.put(startN, 0.0);
        open.add(new RouteNode(startN, 0.0));

        int expansions = 0;
        while (!open.isEmpty()) {
            if (++expansions > ROUTE_MAX_EXPANSIONS) return null;
            long cur = open.poll().node();
            if (cur == endN) return rebuildRoute(cameFrom, cur, end, override);
            if (!closed.add(cur)) continue; // already finalized via a cheaper path
            ChunkPos cp = new ChunkPos(cur);
            double curG = gScore.getOrDefault(cur, Double.MAX_VALUE);
            int curFloor = routeHeight(gen, rs, level, height, override, cp.x, cp.z);
            int curTravel = (sea - curFloor) > WADE_DEPTH ? sea : curFloor; // boats ride the surface; feet the ground
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int ngx = cp.x + dx, ngz = cp.z + dz;
                    long nb = ChunkPos.asLong(ngx, ngz);
                    if (closed.contains(nb)) continue;
                    int nFloor = routeHeight(gen, rs, level, height, override, ngx, ngz);
                    boolean deep = (sea - nFloor) > WADE_DEPTH;
                    if (!sailing && deep) continue; // deep water is impassable on foot
                    int nTravel = deep ? sea : nFloor;
                    double dist = (dx != 0 && dz != 0 ? 1.4142 : 1.0) * ROUTE_GRID;
                    double slope = Math.abs(nTravel - curTravel);
                    double cost = dist * (1.0 + SLOPE_PENALTY * slope / ROUTE_GRID) + (deep ? WATER_PENALTY * dist : 0.0);
                    if (ROAD_NETWORK.contains(nb)) cost *= ROAD_DISCOUNT; // strongly prefer existing roads
                    double tentative = curG + cost;
                    if (tentative < gScore.getOrDefault(nb, Double.MAX_VALUE)) {
                        cameFrom.put(nb, cur);
                        gScore.put(nb, tentative);
                        open.add(new RouteNode(nb, tentative + routeHeuristic(ngx, ngz, egx, egz)));
                    }
                }
            }
        }
        return null; // open set exhausted with no path
    }

    private static double routeHeuristic(int ax, int az, int bx, int bz) {
        double dx = (double) (ax - bx) * ROUTE_GRID;
        double dz = (double) (az - bz) * ROUTE_GRID;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Predicted SOLID-floor height at a grid node, cached. Gen-free (samples the noise router, not
     *  blocks). OCEAN_FLOOR (not WORLD_SURFACE) so over a lake this is the bed, below sea level — that's
     *  what lets {@code sea - height} read the real water depth instead of 0 at the water's surface. */
    private static int routeHeight(ChunkGenerator gen, RandomState rs, ServerLevel level,
                                   Map<Long, Integer> cache, Map<Long, int[]> override, int gx, int gz) {
        long key = ChunkPos.asLong(gx, gz);
        int[] ov = override.get(key);
        if (ov != null) return ov[0]; // real surface (sees player bridges/builds) in loaded areas
        Integer h = cache.get(key);
        if (h != null) return h;
        int hv = gen.getBaseHeight(gx * ROUTE_GRID, gz * ROUTE_GRID, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
        cache.put(key, hv);
        return hv;
    }

    /** Snapshots the REAL surface (OCEAN_FLOOR — solid top, ignoring fluids, so a bridge over water
     *  reads as ground) of currently-loaded chunks near both endpoints, on the main thread. Per grid
     *  cell (= one chunk at this grid size) it keeps the highest solid surface found, so a thin bridge
     *  through the cell still makes it crossable. The off-thread router prefers these over the
     *  prediction, so it routes over player-built bridges/terrain instead of around them. */
    private static Map<Long, int[]> snapshotLoadedFloors(ServerLevel level, BlockPos start, BlockPos end) {
        Map<Long, int[]> out = new HashMap<>();
        snapshotAround(level, out, start);
        snapshotAround(level, out, end);
        return out;
    }

    private static void snapshotAround(ServerLevel level, Map<Long, int[]> out, BlockPos center) {
        int ccx = center.getX() >> 4;
        int ccz = center.getZ() >> 4;
        for (int cx = ccx - OVERRIDE_RADIUS; cx <= ccx + OVERRIDE_RADIUS; cx++) {
            for (int cz = ccz - OVERRIDE_RADIUS; cz <= ccz + OVERRIDE_RADIUS; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                int best = level.getMinBuildHeight();
                int bx = cx << 4;
                int bz = cz << 4;
                for (int ox = 0; ox < 16; ox += 2) {
                    for (int oz = 0; oz < 16; oz += 2) {
                        int wx = (cx << 4) + ox;
                        int wz = (cz << 4) + oz;
                        int h = level.getHeight(Heightmap.Types.OCEAN_FLOOR, wx, wz);
                        if (h > best) {
                            best = h;
                            bx = wx;
                            bz = wz;
                        }
                    }
                }
                // {highest solid surface, and the column where it is} — grid cell == chunk at ROUTE_GRID 16.
                out.put(ChunkPos.asLong(cx, cz), new int[] { best, bx, bz });
            }
        }
    }

    private static List<int[]> rebuildRoute(Map<Long, Long> cameFrom, long end, BlockPos endPos,
                                            Map<Long, int[]> override) {
        List<int[]> pts = new ArrayList<>();
        Long c = end;
        while (c != null) {
            int[] ov = override.get(c);
            if (ov != null) {
                pts.add(new int[] { ov[1], ov[2] }); // aim at the real surface column (the bridge), not the corner
            } else {
                ChunkPos cp = new ChunkPos(c);
                pts.add(new int[] { cp.x * ROUTE_GRID, cp.z * ROUTE_GRID });
            }
            c = cameFrom.get(c);
        }
        Collections.reverse(pts);
        pts.add(new int[] { endPos.getX(), endPos.getZ() }); // finish exactly on the target
        return pts;
    }

    /** Stamps every grid cell along the route into the persistent road network, so later journeys (real
     *  or ghost) prefer to converge onto and follow it rather than laying a parallel line. */
    private static void addRouteToNetwork(BlockPos start, List<int[]> waypoints) {
        double px = start.getX();
        double pz = start.getZ();
        for (int[] wp : waypoints) {
            double tx = wp[0];
            double tz = wp[1];
            int steps = Math.max(1, (int) (Math.hypot(tx - px, tz - pz) / ROUTE_GRID));
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                int wx = (int) Math.round(px + (tx - px) * t);
                int wz = (int) Math.round(pz + (tz - pz) * t);
                ROAD_NETWORK.add(ChunkPos.asLong(Math.floorDiv(wx, ROUTE_GRID), Math.floorDiv(wz, ROUTE_GRID)));
            }
            px = tx;
            pz = tz;
        }
    }

    /** Lays a 1-wide trail under the trader: dirt → path, stone/sand/gravel → gravel, anything else
     *  (logs, planks, ores, water…) left alone so we never pave a build. Adopted couriers only pave
     *  WILDERNESS — never inside any settlement's claim (a trade route must not path-ify a town's
     *  lawns; the debug trader keeps paving everywhere, it's a terraforming test). */
    private static void paveUnder(ServerLevel level, CitizenEntity trader, TraderSimSession s) {
        BlockPos feet = trader.blockPosition();
        if (feet.equals(s.lastRoadPos)) return;
        s.lastRoadPos = feet;
        if (s.adopted && SettlementData.get(level)
                .getByChunk(new ChunkPos(feet).toLong()) != null) {
            return;
        }
        pave3Wide(level, feet.getX(), feet.getZ());
    }

    /** Paves a 3-wide trail centred on (cx,cz): the column plus its four orthogonal neighbours. */
    private static void pave3Wide(ServerLevel level, int cx, int cz) {
        paveColumn(level, cx, cz);
        paveColumn(level, cx + 1, cz);
        paveColumn(level, cx - 1, cz);
        paveColumn(level, cx, cz + 1);
        paveColumn(level, cx, cz - 1);
    }

    /** Replaces one column's surface with a randomized road material — but only a solid, dry, non-tree
     *  block (never air, water, plants, leaves or logs), and never an existing road block (so the mix
     *  doesn't reshuffle on every pass). Public: stocker outpost runs lay the SAME road style,
     *  column by column so their territory gate applies per block (see StockerWorkGoal). */
    public static void paveColumn(ServerLevel level, int x, int z) {
        BlockPos ground = new BlockPos(x, groundY(level, x, z) - 1, z);
        BlockState surface = level.getBlockState(ground);
        if (!surface.blocksMotion()) return;                                      // solid blocks only
        if (surface.is(BlockTags.LEAVES) || surface.is(BlockTags.LOGS)) return;   // don't pave trees
        if (!level.getFluidState(ground.above()).isEmpty()) return;              // not submerged
        if (isRoad(surface)) return;                                             // already paved
        level.setBlock(ground, roadMaterial(level.getRandom()), 2);
    }

    /** The road palette test — shared with the stocker's "already on a road? don't pave" check. */
    public static boolean isRoad(BlockState s) {
        return s.is(Blocks.DIRT_PATH) || s.is(Blocks.GRAVEL) || s.is(Blocks.COARSE_DIRT);
    }

    /** 70% packed dirt path, 15% gravel, 15% coarse dirt. */
    private static BlockState roadMaterial(RandomSource r) {
        int roll = r.nextInt(100);
        if (roll < 70) return Blocks.DIRT_PATH.defaultBlockState();
        if (roll < 85) return Blocks.GRAVEL.defaultBlockState();
        return Blocks.COARSE_DIRT.defaultBlockState();
    }

    /** Surface standing-Y at a column, scanning down past leaves/logs so waypoints sit on real ground. */
    private static int groundY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int min = level.getMinBuildHeight() + 1;
        for (int i = 0; i < 16 && y > min; i++) {
            BlockState below = level.getBlockState(m.set(x, y - 1, z));
            if (below.blocksMotion() && !below.is(BlockTags.LEAVES) && !below.is(BlockTags.LOGS)) break;
            y--;
        }
        return y;
    }

    /** Clears the trader's own goal + target AI so ONLY our navigation moves it — kills the vanilla
     *  idle-stroll interludes (and the night-time invisibility-potion goal). Navigation/move-control
     *  still tick in {@code Mob.serverAiStep} independent of goals, so external {@code moveTo} drives
     *  it cleanly. One-time at spawn; {@code goalSelector}/{@code targetSelector} are protected, hence
     *  reflection (stable: the NeoForge runtime uses Mojang field names). */
    private static void stripGoals(CitizenEntity trader) {
        for (String field : new String[] { "goalSelector", "targetSelector" }) {
            try {
                Field f = Mob.class.getDeclaredField(field);
                f.setAccessible(true);
                ((GoalSelector) f.get(trader)).removeAllGoals(g -> true);
            } catch (ReflectiveOperationException | ClassCastException e) {
                LOGGER.warn("[TraderSim] couldn't clear {} — movement may be janky", field, e);
            }
        }
    }

    private static double horiz(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void resetLeg(TraderSimSession s) {
        s.bestDist = Double.MAX_VALUE;
        s.noProgressTicks = 0;
        s.nudges = 0;
    }

    /** The block column {@code dist} blocks ahead of the trader toward its current waypoint. */
    private static int[] lookAhead(Entity trader, int[] wp, double dist) {
        double dx = (wp[0] + 0.5) - trader.getX();
        double dz = (wp[1] + 0.5) - trader.getZ();
        double len = Math.max(1.0e-3, Math.sqrt(dx * dx + dz * dz));
        double look = Math.min(dist, len);
        return new int[] {
            (int) Math.floor(trader.getX() + dx / len * look),
            (int) Math.floor(trader.getZ() + dz / len * look)
        };
    }

    /** First DRY column (no standing water at all) stepping from the entity toward {@code toward},
     *  within {@code maxDist} blocks — solid shore to step off onto, so it never lands in water. */
    private static int[] nearestDryToward(ServerLevel level, Entity from, int[] toward, int maxDist) {
        double dx = (toward[0] + 0.5) - from.getX();
        double dz = (toward[1] + 0.5) - from.getZ();
        double len = Math.max(1.0e-3, Math.sqrt(dx * dx + dz * dz));
        for (int dist = 1; dist <= maxDist; dist++) {
            int cx = (int) Math.floor(from.getX() + dx / len * dist);
            int cz = (int) Math.floor(from.getZ() + dz / len * dist);
            if (waterDepth(level, cx, cz) == 0) return new int[] { cx, cz };
        }
        return null;
    }

    /** Depth of standing water at column (x,z): water blocks from the surface down to the first
     *  non-water block. 0 if the column is dry. */
    private static int waterDepth(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int min = level.getMinBuildHeight();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int depth = 0;
        for (int y = top; y > min; y--) {
            if (!level.getFluidState(m.set(x, y, z)).is(FluidTags.WATER)) break;
            depth++;
        }
        return depth;
    }

    /** Deep enough (> {@link #WADE_DEPTH}) that it must be boated — or, with no sailing, blocks the
     *  route. Shallow 1-2 block water is walked straight through. */
    private static boolean isDeepWaterColumn(ServerLevel level, int x, int z) {
        return waterDepth(level, x, z) > WADE_DEPTH;
    }

    private static Boat spawnBoat(ServerLevel level, double x, double y, double z, float yaw) {
        Boat boat = EntityType.BOAT.create(level);
        if (boat == null) return null;
        boat.setVariant(Boat.Type.OAK);
        boat.moveTo(x, y, z, yaw, 0.0F);
        if (!level.addFreshEntity(boat)) return null;
        return boat;
    }

    /** Drives a riderless boat toward the waypoint: face it and push horizontally, letting vanilla
     *  buoyancy hold the surface. Runs each tick because an AI passenger never "paddles". */
    private static void driveBoat(Boat boat, int[] wp) {
        double dx = (wp[0] + 0.5) - boat.getX();
        double dz = (wp[1] + 0.5) - boat.getZ();
        double len = Math.max(1.0e-3, Math.sqrt(dx * dx + dz * dz));
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        boat.setYRot(yaw);
        boat.yRotO = yaw;
        Vec3 dm = boat.getDeltaMovement();
        boat.setDeltaMovement(dx / len * BOAT_SPEED, dm.y, dz / len * BOAT_SPEED);
        boat.hasImpulse = true;
        boat.setPaddleState(true, true); // synced → client animates both oars rowing
    }

    private static void broadcastProgress(MinecraftServer server, TraderSimSession s) {
        if (s.initiator == null) return;
        ServerPlayer p = server.getPlayerList().getPlayer(s.initiator);
        if (p == null) return;
        double px = s.gx;
        double pz = s.gz;
        if (!s.ghost && s.traderId != null) {
            Entity e = server.overworld().getEntity(s.traderId);
            if (e != null) {
                px = e.getX();
                pz = e.getZ();
            }
        }
        double dEnd = horiz(px, pz, s.end.getX() + 0.5, s.end.getZ() + 0.5);
        int pct = (int) Math.round(100.0 * Math.max(0.0, Math.min(1.0, 1.0 - dEnd / Math.max(1.0, s.totalDist))));
        int roadPts = 0;
        for (List<int[]> v : PENDING_ROAD.values()) roadPts += v.size();
        p.displayClientMessage(Component.literal(String.format(
                "Trader %d%% — (%d, %d) — leg %d/%d — %s — road %d",
                pct, (int) Math.floor(px), (int) Math.floor(pz), s.index + 1, s.waypoints.size(),
                s.ghost ? "ghost" : "real", roadPts))
            .withStyle(ChatFormatting.YELLOW), true);
    }

    private static void message(MinecraftServer server, TraderSimSession s, String text) {
        Component c = Component.literal("[TraderSim] " + text).withStyle(ChatFormatting.GOLD);
        if (s.initiator != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(s.initiator);
            if (p != null) p.sendSystemMessage(c);
        }
        server.sendSystemMessage(c);
    }
}
