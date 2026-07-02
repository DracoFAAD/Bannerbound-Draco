package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Stateful client-side crowd for the {@code /bannerbound simulate} stress test. Unlike the earlier
 * stateless analytic movers, each {@link Agent} keeps a position/target and is stepped once per
 * client tick with lightweight steering: walk toward a target at the citizens' speed, step up/down
 * ground like an entity (≤1 block, smoothed), and turn away when a tree/wall/water blocks the path.
 * That gives a believable "pathfinding-ish" feel without per-agent A* — exactly how AAA crowds move.
 *
 * <p>Purely decorative + client-only (the server never knows these exist). The pool is anchored
 * around the PLAYER within the city disc, capped at {@link ClientSimulationState#renderCap()}, so
 * local density stays believable at any city size and the cost stays flat. Render interpolates each
 * agent between its previous and current tick position; see {@link CrowdRenderer}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientCrowd {
    /** Ground speed in blocks/sec — matched to the citizens' MOVEMENT_SPEED (0.4) walking pace. */
    public static final double WALK_SPEED = 3.4;
    private static final double STEP_PER_TICK = WALK_SPEED / 20.0;
    /** How far a walker roams from where it spawned. */
    private static final double WANDER = 10.0;
    /** Max vertical adjust per tick — smooths slopes/steps instead of teleporting. */
    private static final double MAX_Y_STEP = 0.5;
    /** Spawn ring around the player (inside the city); despawn past the cull distance. */
    private static final double SPAWN_MIN = 32.0;
    /** Max degrees an agent's BODY rotates per tick — gives a visible turn instead of snapping. */
    private static final float MAX_TURN = 9.0f;
    /** Max degrees the head leads the body toward the heading (Minecraft turns the head first). */
    private static final float MAX_HEAD = 55.0f;
    /** Max degrees/tick the head itself rotates toward its lead angle (so it eases, not snaps). */
    private static final float HEAD_RATE = 16.0f;
    /** How fast the leg-swing amplitude eases in/out when starting/stopping (per tick). */
    private static final float GAIT_RAMP = 0.10f;
    /** Per-tick downward acceleration and upward step impulse — gives gravity falls + a step-up hop. */
    private static final double GRAVITY = 0.08;
    private static final double JUMP_V = 0.42;

    /** Wrap a degree delta into [-180, 180]. */
    static float wrapDeg(float a) {
        a %= 360f;
        if (a > 180f) a -= 360f; else if (a < -180f) a += 360f;
        return a;
    }

    /** Wrap a heading into [0, 360). */
    static float wrap360(float a) {
        a %= 360f;
        if (a < 0f) a += 360f;
        return a;
    }

    private static final List<Agent> AGENTS = new ArrayList<>();
    private static final Random RNG = new Random();
    private static long builtSeed = 0L;
    private static int builtPop = -1;

    private ClientCrowd() {
    }

    public static List<Agent> agents() { return AGENTS; }

    public static void reset() {
        AGENTS.clear();
        builtPop = -1;
    }

    /** Advance the crowd one client tick: maintain the pool around the player, then step each agent. */
    public static void tick() {
        if (!ClientSimulationState.isActive()) {
            if (!AGENTS.isEmpty()) AGENTS.clear();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return;

        // Full rebuild if the session changed.
        if (ClientSimulationState.seed() != builtSeed || ClientSimulationState.believedPopulation() != builtPop) {
            AGENTS.clear();
            builtSeed = ClientSimulationState.seed();
            builtPop = ClientSimulationState.believedPopulation();
        }

        double px = player.getX(), pz = player.getZ();
        double despawn = ClientSimulationState.CULL_DISTANCE;
        AGENTS.removeIf(a -> {
            double dx = a.x - px, dz = a.z - pz;
            return dx * dx + dz * dz > despawn * despawn;
        });

        // Top the pool back up to the cap with new agents on valid ground near the player.
        int target = ClientSimulationState.renderCap();
        double cityR = ClientSimulationState.cityRadius();
        BlockPos th = ClientSimulationState.townHall();
        double spawnMax = ClientSimulationState.RENDER_RADIUS;
        int tries = 0;
        while (AGENTS.size() < target && tries++ < target * 4) {
            Agent a = trySpawn(level, px, pz, th.getX(), th.getZ(), cityR, spawnMax);
            if (a != null) AGENTS.add(a);
        }

        for (Agent a : AGENTS) {
            a.step(level);
            // Ease the leg-swing amplitude in/out so starting/stopping winds up/down (not a snap).
            float tgt = a.walking ? 1f : 0f;
            a.gaitAmount += Math.max(-GAIT_RAMP, Math.min(GAIT_RAMP, tgt - a.gaitAmount));
        }
    }

    /** Attempt to place one agent on valid open ground in the spawn ring, inside the city disc. */
    private static Agent trySpawn(Level level, double px, double pz, int thx, int thz,
                                  double cityR, double spawnMax) {
        double ang = RNG.nextDouble() * Math.PI * 2.0;
        double r = SPAWN_MIN + RNG.nextDouble() * (spawnMax - SPAWN_MIN);
        double x = px + Math.cos(ang) * r;
        double z = pz + Math.sin(ang) * r;
        double dxt = x - thx, dzt = z - thz;
        if (dxt * dxt + dzt * dzt > cityR * cityR) return null;     // outside the city
        if (!standable(level, x, z, Integer.MIN_VALUE)) return null;
        int gy = surfaceY(level, x, z);
        if (isRoof(level, x, z, gy)) return null;
        Agent a = new Agent();
        a.homeX = x; a.homeZ = z; a.homeY = gy;
        a.x = x; a.z = z; a.y = gy;
        a.prevX = x; a.prevY = gy; a.prevZ = z;
        a.vseed = RNG.nextLong();
        a.idlePhase = RNG.nextDouble() * Math.PI * 2.0;
        a.pauseTicks = RNG.nextInt(20);
        return a;
    }

    // ── World sampling helpers (WORLD_SURFACE is client-synced; block reads are reliable) ──

    static int surfaceY(Level level, double x, double z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
    }

    /** True if (x,z) is open walkable ground: not tree/water, and (if fromY given) climbable ≤1. */
    static boolean standable(Level level, double x, double z, int fromY) {
        int gy = surfaceY(level, x, z);
        BlockState top = level.getBlockState(new BlockPos((int) Math.floor(x), gy - 1, (int) Math.floor(z)));
        if (top.is(BlockTags.LEAVES) || top.is(BlockTags.LOGS) || top.is(BlockTags.PLANKS)
            || top.is(Blocks.HAY_BLOCK) || !top.getFluidState().isEmpty()) {
            return false;
        }
        if (fromY != Integer.MIN_VALUE) {
            int climb = gy - fromY;
            if (climb > 1 || climb < -3) return false;              // can't step up >1 / off a cliff
        }
        return true;
    }

    /** A surface that sits well above its neighbours — i.e. a roof / wall top. */
    static boolean isRoof(Level level, double x, double z, int gy) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        int minN = Math.min(Math.min(
                level.getHeight(Heightmap.Types.MOTION_BLOCKING, xi + 4, zi),
                level.getHeight(Heightmap.Types.MOTION_BLOCKING, xi - 4, zi)),
            Math.min(
                level.getHeight(Heightmap.Types.MOTION_BLOCKING, xi, zi + 4),
                level.getHeight(Heightmap.Types.MOTION_BLOCKING, xi, zi - 4)));
        return gy - minN > 2;
    }

    /** One crowd member with persistent motion state. {@code prev*} are last tick's values, used by
     *  the renderer to interpolate position/facing/gait to a smooth 60fps. */
    public static final class Agent {
        public double homeX, homeZ, homeY;
        public double x, y, z;
        public double prevX, prevY, prevZ;
        public double tx, tz;
        public boolean hasTarget;
        public float facing, prevFacing;
        public float headYaw, prevHeadYaw;                          // head lead (relative to body)
        public double gait, prevGait;
        public float gaitAmount, prevGaitAmount;                    // leg-swing amplitude 0..1 (eases)
        public double vy;                                           // vertical velocity (gravity/hop)
        public boolean onGround = true;
        public boolean walking;
        public int pauseTicks;
        public double idlePhase;
        public long vseed;

        void step(Level level) {
            prevX = x; prevY = y; prevZ = z; prevFacing = facing; prevGait = gait;
            prevHeadYaw = headYaw; prevGaitAmount = gaitAmount;
            if (pauseTicks > 0) { pauseTicks--; walking = false; settle(level); return; }
            if (!hasTarget) { pickTarget(level); }

            double dx = tx - x, dz = tz - z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 0.4) {                                          // arrived
                hasTarget = false;
                walking = false;
                if (RNG.nextInt(3) == 0) { pauseTicks = 20 + RNG.nextInt(60); settle(level); return; }
                pickTarget(level);
                dx = tx - x; dz = tz - z; d = Math.sqrt(dx * dx + dz * dz);
                if (d < 1.0e-3) { settle(level); return; }
            }

            // Head leads toward the heading; body turns more slowly behind it (Minecraft style).
            // Both EASE toward their targets (rate-limited) rather than snapping.
            float desired = (float) Math.toDegrees(Math.atan2(-dx / d, dz / d));
            float diff = wrapDeg(desired - facing);
            float targetHead = Math.max(-MAX_HEAD, Math.min(MAX_HEAD, diff));
            headYaw += Math.max(-HEAD_RATE, Math.min(HEAD_RATE, targetHead - headYaw));
            float turn = Math.max(-MAX_TURN, Math.min(MAX_TURN, diff));
            facing = wrap360(facing + turn);

            // Keep WALKING while turning — step forward along the (easing) facing so the path arcs
            // into the turn, like a person rounding a corner. No stop-turn-go.
            double rad = Math.toRadians(facing);
            double ux = -Math.sin(rad), uz = Math.cos(rad);
            double nx = x + ux * STEP_PER_TICK, nz = z + uz * STEP_PER_TICK;
            if (!standable(level, nx, nz, (int) Math.round(y))) {   // tree/wall/cliff ahead → repath
                hasTarget = false;                                  // turn toward a new target; no long pause
                walking = false;
                settle(level);
                return;
            }
            double moved = Math.sqrt((nx - x) * (nx - x) + (nz - z) * (nz - z));
            x = nx; z = nz; gait += moved; walking = true;
            applyVertical(level);
        }

        /** Gravity + step-up hop so descents fall naturally and ascents hop onto the block. */
        private void applyVertical(Level level) {
            double gy = surfaceY(level, x, z);
            if (onGround && gy > y + 0.1) { vy = JUMP_V; onGround = false; }  // step up → little hop
            vy -= GRAVITY;
            y += vy;
            if (y <= gy) { y = gy; vy = 0; onGround = true; }                 // landed
        }

        /** Standing still: rest on the ground beneath, head re-centres. */
        private void settle(Level level) {
            y = surfaceY(level, x, z);
            vy = 0; onGround = true;
            headYaw *= 0.7f;                                        // ease the head back to forward
        }

        private void pickTarget(Level level) {
            for (int t = 0; t < 6; t++) {
                double ang = RNG.nextDouble() * Math.PI * 2.0;
                double r = RNG.nextDouble() * WANDER;
                double cx = homeX + Math.cos(ang) * r;
                double cz = homeZ + Math.sin(ang) * r;
                if (standable(level, cx, cz, (int) Math.round(homeY))
                    && !isRoof(level, cx, cz, surfaceY(level, cx, cz))) {
                    tx = cx; tz = cz; hasTarget = true;
                    return;
                }
            }
            tx = homeX; tz = homeZ; hasTarget = true;               // fall back to home
        }
    }
}
