package com.bannerbound.antiquity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.RopeFencePostBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Fakes solid rope-fence collision <em>analytically</em>, because Minecraft block collision is
 * axis-aligned and can't follow an angled rope smoothly. Each tick, for entities near a rope, every
 * nearby rope is treated as a capsule (its true tie-point-to-tie-point line segment with a radius) and
 * the entity is pushed out of it — pure 3D maths off the real endpoints, so it's smooth at any angle
 * and at bends (no per-cell quantization jitter).
 *
 * <p>The {@link RopeCollisionBlock} markers are only used as the cheap "a rope is near" gate (and for
 * mob pathfinding avoidance); the actual blocking comes from the segments, gathered from the rope tie
 * hosts in the entity's chunk neighbourhood. <b>Sided to avoid rubber-band:</b> the local player is
 * clamped on the client, mobs on the server.</p>
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class RopeFenceCollision {
    /** Barrier height above the ground under the rope — tall enough you can't get over it. */
    private static final double FENCE_HEIGHT = 2.0;
    /** Half-width of the passable opening kept clear at a GATE end of a rope. The wall is pulled back this
     *  far from the gate tie on each adjacent segment, so the gate is a real ~1-block-wide gap an entity can
     *  walk its CENTRE through — not the razor-thin line you got from merely dropping the gate end-cap (which
     *  left the wall running to the tie point, shoving any wide entity, and the herder, straight back out). */
    private static final double GATE_GAP = 0.6;
    private static final double MOVE_EPSILON_SQR = 1.0e-6;
    /** Tag (gametime) Core sets on an entity it just teleported, so a deliberate cross-rope jump isn't
     *  clamped back to the old side. Mirrors {@code CitizenEntity.TELEPORT_AT_KEY} (Core can't be
     *  imported) — every intentional position jump in Core must call its tagDeliberateTeleport. */
    private static final String TELEPORT_AT_KEY = "BannerboundTeleportAt";
    /** Iterations to resolve overlapping capsules (a junction of ropes) to a stable spot per tick. */
    private static final int RESOLVE_PASSES = 6;
    /** Penetration below this is ignored — stops sub-pixel push/pull jitter against a wall. */
    private static final double SLOP = 1.0e-3;
    /** A mob whose centre is within (radius + this) of a rope's vertical band is pressed against the wall,
     *  so any upward (jump) velocity is cancelled — the fence is {@link #FENCE_HEIGHT} tall and unjumpable,
     *  so it must not hop in place trying to clear it. */
    private static final double JUMP_BLOCK_MARGIN = 0.1;
    /** Action-bar readout of what the clamp sees (segs/pushed/pre/post/spd). Flip true only to diagnose. */
    private static final boolean DEBUG = false;
    /** Each entity's position at the end of LAST tick (after any clamp). We track this OURSELVES because
     *  {@code e.xo} is already == {@code getX()} by the time {@link EntityTickEvent.Post} fires for the
     *  local player — so it's useless both as a "did it move" test and as the "which side was it on"
     *  reference the clamp needs. Separate maps per side: in singleplayer the client LocalPlayer and the
     *  integrated-server ServerPlayer share a UUID in one JVM, so a single map would cross-contaminate. */
    private static final Map<UUID, double[]> PREV_CLIENT = new ConcurrentHashMap<>();
    private static final Map<UUID, double[]> PREV_SERVER = new ConcurrentHashMap<>();

    /** Unordered pair of anchors, to gather each rope segment once. */
    private record Pair(RopeAnchor lo, RopeAnchor hi) {}

    private RopeFenceCollision() {}

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity e = event.getEntity();
        if (!(e instanceof LivingEntity) || e.noPhysics || e.isSpectator() || e.isPassenger()) {
            return;
        }
        Level level = e.level();
        if (level.isClientSide && (!(e instanceof Player player) || !player.isLocalPlayer())) {
            return; // client: only the local player (remote players/mobs are server-authoritative there)
        }
        // Clamp the player on BOTH sides (with identical maths) so client and server agree on where it is
        // — clamping client-only let the server keep its own un-clamped position and tug the player back
        // across the rope every tick (the residual jitter even when the clamp result was stable).
        Map<UUID, double[]> prevMap = level.isClientSide ? PREV_CLIENT : PREV_SERVER;
        UUID id = e.getUUID();
        double curX = e.getX(), curZ = e.getZ();
        double[] prev = prevMap.get(id);
        double ox = prev == null ? curX : prev[0];
        double oz = prev == null ? curZ : prev[1];
        double mdx = curX - ox, mdz = curZ - oz;
        boolean moved = mdx * mdx + mdz * mdz >= MOVE_EPSILON_SQR;

        // A deliberate teleport (e.g. the herder placing penned animals, or relocating itself to the pen
        // centre) legitimately jumps the entity across a rope. Accept the new spot as its reference rather
        // than treating the jump as an illegal crossing and shoving it back to the side it was on — that
        // was bouncing herded animals (and the herder) straight back out of the pen.
        if (e.getPersistentData().contains(TELEPORT_AT_KEY)
                && level.getGameTime() - e.getPersistentData().getLong(TELEPORT_AT_KEY) <= 1L) {
            prevMap.put(id, new double[] { curX, curZ });
            return;
        }

        int ecx = Mth.floor(curX) >> 4;
        int ecz = Mth.floor(curZ) >> 4;
        // Also gather segments for a non-player mob that's rising (mid-jump) even if it didn't move
        // horizontally — a mob stuck bouncing against the rope barely moves sideways between ticks, so the
        // `moved` gate alone would never run the jump suppression below that stops the bounce.
        boolean jumpingMob = !(e instanceof Player) && e.getDeltaMovement().y > 0.0;
        boolean wantSegments = moved || jumpingMob || (DEBUG && level.isClientSide && e instanceof Player);
        List<double[]> segments = wantSegments ? gatherSegments(level, ecx, ecz) : List.of();
        boolean pushed = false;
        if (moved && !segments.isEmpty()) {
            pushed = clamp(e, segments, ox, oz);
        }
        // A rope fence is FENCE_HEIGHT tall — taller than any mob can jump — so a mob shoved into it must
        // not be allowed to hop up the invisible wall. Its MoveControl fires jumpControl when it collides
        // (with the wall or a 1.5-tall post), and the clamp leaves that upward velocity intact, so it
        // bounces in place forever ("they think they can jump it, but can't"). Cancel the jump here.
        if (!(e instanceof Player) && !segments.isEmpty()) {
            suppressJump(e, segments);
        }
        // Store the FINAL (possibly clamped) position as next tick's "old" reference.
        prevMap.put(id, new double[] { e.getX(), e.getZ() });

        if (DEBUG && level.isClientSide && e instanceof Player p) {
            double pre = minSegDist(segments, curX, curZ);
            double post = minSegDist(segments, e.getX(), e.getZ());
            String msg = "ropes segs=" + segments.size()
                + " pushed=" + pushed
                + " pre=" + (segments.isEmpty() ? "-" : String.format("%.2f", pre))
                + " post=" + (segments.isEmpty() ? "-" : String.format("%.2f", post))
                + " spd=" + String.format("%.3f", e.getDeltaMovement().horizontalDistance());
            p.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), true);
        }
    }

    /** Cancels a mob's upward (jump) velocity while it's pressed against a rope's vertical band. A rope
     *  fence is {@link #FENCE_HEIGHT} tall — unjumpable — but the horizontal clamp keeps {@code v.y} intact,
     *  so a mob whose AI fires a jump against the wall hops in place forever. When a rising mob's centre is
     *  inside a rope's vertical band AND within (radius + {@link #JUMP_BLOCK_MARGIN}) of the rope line, zero
     *  the upward velocity so it stays grounded and routes around instead. Downward motion (falling /
     *  walking downslope) is untouched, and a mob clear of the wall on its own side is unaffected. */
    private static void suppressJump(Entity e, List<double[]> segments) {
        Vec3 v = e.getDeltaMovement();
        if (v.y <= 0.0) {
            return;
        }
        AABB box = e.getBoundingBox();
        double r = e.getBbWidth() / 2.0;
        double reach = r + JUMP_BLOCK_MARGIN;
        double cx = e.getX(), cz = e.getZ();
        for (double[] s : segments) {
            double ax = s[0], ay = s[1], az = s[2], bx = s[3], by = s[4], bz = s[5];
            double dx = bx - ax, dz = bz - az;
            double len2 = dx * dx + dz * dz;
            double t = len2 < 1.0e-9 ? 0.0
                : Mth.clamp(((cx - ax) * dx + (cz - az) * dz) / len2, 0.0, 1.0);
            double qx = ax + t * dx, qz = az + t * dz;
            double ropeY = ay + t * (by - ay);
            double barrierBottom = ropeY - RopeAnchor.TIE_Y;
            if (box.maxY <= barrierBottom || box.minY >= barrierBottom + FENCE_HEIGHT) {
                continue; // outside this rope's vertical band
            }
            double pdx = cx - qx, pdz = cz - qz;
            if (pdx * pdx + pdz * pdz <= reach * reach) {
                e.setDeltaMovement(v.x, 0.0, v.z);
                return;
            }
        }
    }

    /** DEBUG helper: smallest horizontal distance from (x,z) to any rope segment. */
    private static double minSegDist(List<double[]> segments, double x, double z) {
        double min = Double.MAX_VALUE;
        for (double[] s : segments) {
            double dx = s[3] - s[0], dz = s[5] - s[2];
            double l2 = dx * dx + dz * dz;
            double tt = l2 < 1e-9 ? 0 : Mth.clamp(((x - s[0]) * dx + (z - s[2]) * dz) / l2, 0, 1);
            double cxp = s[0] + tt * dx - x, czp = s[2] + tt * dz - z;
            min = Math.min(min, Math.sqrt(cxp * cxp + czp * czp));
        }
        return min;
    }

    /** Is this tie point a fence POST (vs a gate upright)? Only posts get the wall extended past them —
     *  extending past a gate upright would push the rope wall into the gate's (openable) passage. */
    private static boolean isPost(Level level, RopeAnchor a) {
        return level.getBlockState(a.pos()).getBlock() instanceof RopeFencePostBlock;
    }

    /** Rope segments from tie hosts in the entity's 3×3 chunk neighbourhood, as
     *  {ax,ay,az,bx,by,bz, extendA, extendB} (the trailing flags = 1 if that end is a post). */
    private static List<double[]> gatherSegments(Level level, int ecx, int ecz) {
        Set<Pair> seen = new HashSet<>();
        List<double[]> out = new ArrayList<>();
        for (int cdx = -1; cdx <= 1; cdx++) {
            for (int cdz = -1; cdz <= 1; cdz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(ecx + cdx, ecz + cdz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    if (!(entry.getValue() instanceof RopeTieHost host)) {
                        continue;
                    }
                    BlockPos hp = entry.getKey();
                    // Heal marker coverage saved by older versions (line-only cells) the first time each
                    // host is seen this session — cheap set check, server-side no-op thereafter.
                    RopeTies.refreshFillersOnce(level, hp, host);
                    for (int slot = 0; slot < host.slotCount(); slot++) {
                        RopeAnchor local = new RopeAnchor(hp, slot);
                        for (RopeAnchor other : host.connections(slot)) {
                            Pair key = local.compareTo(other) <= 0
                                ? new Pair(local, other) : new Pair(other, local);
                            if (!seen.add(key)) {
                                continue;
                            }
                            Vec3 va = RopeAnchor.worldTie(level, local);
                            Vec3 vb = RopeAnchor.worldTie(level, other);
                            if (va != null && vb != null) {
                                out.add(new double[] { va.x, va.y, va.z, vb.x, vb.y, vb.z,
                                    isPost(level, local) ? 1.0 : 0.0, isPost(level, other) ? 1.0 : 0.0 });
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Push the entity out of any rope capsule it's inside; returns true if it moved the entity.
     *  {@code ox}/{@code oz} are the entity's position at the end of last tick (tracked in PREV_POS,
     *  NOT {@code e.xo} which is unreliable here) — the side it must be kept on. */
    private static boolean clamp(Entity e, List<double[]> segments, double ox, double oz) {
        AABB box = e.getBoundingBox();
        double r = e.getBbWidth() / 2.0;
        double nx = e.getX(), nz = e.getZ();
        boolean changed = false;

        // Resolve all rope capsules together: a junction overlaps several at once, and pushing out of
        // one can shove the entity into another, so iterate until it's clear of all (or we give up).
        for (int pass = 0; pass < RESOLVE_PASSES; pass++) {
            boolean pushed = false;
            for (double[] s : segments) {
                double ax = s[0], ay = s[1], az = s[2], bx = s[3], by = s[4], bz = s[5];
                double dx = bx - ax, dz = bz - az;
                double len2 = dx * dx + dz * dz;
                // POST end: extend the wall one entity-radius PAST the post along the rope direction. On a
                // diagonal rope you slide along the angled wall; without this you slide off its end at the
                // post and round the little end-cap through the gap to the far side. The extension lives
                // inside the post (which has its own collision) so it just plugs that gap.
                // GATE end (non-post tie host): pull the wall BACK by GATE_GAP so the two segments meeting at
                // a gate leave a real ~1-block opening centred on the gate, instead of running to the tie and
                // leaving only a razor-thin passable line. This is THE fix for wide animals / the herder
                // being shoved out of an open gate — the gate is now a gap their centre can actually cross.
                if (len2 >= 1.0e-9) {
                    double sl = Math.sqrt(len2);
                    double ux = dx / sl, uz = dz / sl;
                    if (s[6] != 0.0) { ax -= ux * r;        az -= uz * r; }
                    else             { ax += ux * GATE_GAP; az += uz * GATE_GAP; }
                    if (s[7] != 0.0) { bx += ux * r;        bz += uz * r; }
                    else             { bx -= ux * GATE_GAP; bz -= uz * GATE_GAP; }
                    dx = bx - ax; dz = bz - az;
                    len2 = dx * dx + dz * dz;
                }
                double t = len2 < 1.0e-9 ? 0.0 : Mth.clamp(((nx - ax) * dx + (nz - az) * dz) / len2, 0.0, 1.0);
                double cx = ax + t * dx, cz = az + t * dz;
                double ropeY = ay + t * (by - ay);
                // Vertical band: ground under the rope up to fence height (rope ties at TIE_Y above base).
                double barrierBottom = ropeY - RopeAnchor.TIE_Y;
                if (box.maxY <= barrierBottom || box.minY >= barrierBottom + FENCE_HEIGHT) {
                    continue;
                }
                if (len2 >= 1.0e-9 && t > 0.0 && t < 1.0) {
                    // Beside the rope's span: block crossing of its true perpendicular plane, kept on
                    // the side the entity was on last tick. SIGNED distance, so even a fast move that
                    // ends well past the rope (unsigned dist > r) is still detected and shoved back.
                    double sl = Math.sqrt(len2);
                    double perpX = -dz / sl, perpZ = dx / sl;
                    double fOld = perpX * (ox - ax) + perpZ * (oz - az);
                    double fNew = perpX * (nx - ax) + perpZ * (nz - az);
                    double side = fOld >= 0.0 ? 1.0 : -1.0;
                    if (side * fNew >= r - SLOP) {
                        continue; // safely ≥ r on its own side
                    }
                    nx += perpX * (side * r - fNew);
                    nz += perpZ * (side * r - fNew);
                } else {
                    // Past an end. Only a POST end gets a solid radial cap; a GATE end gets none — combined
                    // with the GATE_GAP wall-pullback above, that leaves a clean ~1-block opening at the gate.
                    // (The gate BLOCK's own collision handles "closed"; the rope wall still blocks the fence
                    // either side of the opening.)
                    boolean endIsPost = (t >= 1.0) ? s[7] != 0.0 : s[6] != 0.0;
                    if (!endIsPost) {
                        continue;
                    }
                    double px = nx - cx, pz = nz - cz;
                    double dist = Math.sqrt(px * px + pz * pz);
                    if (dist >= r - SLOP) {
                        continue;
                    }
                    double dirX, dirZ;
                    double opx = ox - cx, opz = oz - cz, od = Math.sqrt(opx * opx + opz * opz);
                    if (od > 1.0e-4) {
                        dirX = opx / od;
                        dirZ = opz / od;
                    } else if (dist > 1.0e-4) {
                        dirX = px / dist;
                        dirZ = pz / dist;
                    } else {
                        double sl = Math.sqrt(Math.max(len2, 1.0e-9));
                        dirX = -dz / sl;
                        dirZ = dx / sl;
                    }
                    nx = cx + dirX * r;
                    nz = cz + dirZ * r;
                }
                pushed = true;
                changed = true;
            }
            if (!pushed) {
                break;
            }
        }

        if (changed) {
            e.setPos(nx, e.getY(), nz);
            // Render interpolates the camera as lerp(partialTick, xo, getX()). The local player's xo is
            // left at this tick's PRE-clamp position, so without fixing it the camera lerps from the
            // moved-in spot to the clamped spot every frame → jitter. Set xo to LAST tick's clamped
            // position (ox/oz, our tracked prev) — NOT this tick's (that would make xo==getX, killing
            // interpolation entirely → a choppy ~tick-rate "13 FPS" judder while sliding along).
            e.xo = ox; e.zo = oz;
            e.xOld = ox; e.zOld = oz;
            // Entity.move() already added this tick's lunge-into-the-rope to walkDist (line ~702), so the
            // first-person view-bob (which reads walkDist - walkDistO) sways as if you're walking on the
            // spot. Rewrite walkDist to the NET movement so the camera only bobs for real progress.
            double ddx = nx - ox, ddz = nz - oz;
            double net = Math.sqrt(ddx * ddx + ddz * ddz);
            e.walkDist = (float) (e.walkDistO + net * 0.6);
            // Do NOT touch a PLAYER'S velocity. The position clamp alone holds it (its input-capped speed
            // already produces a normal slide), and writing deltaMovement server-side gets broadcast back
            // to the client where it stacks onto local movement → the speed-boost "launch". Mobs are
            // momentum-driven and server-authoritative, so we DO cancel their into-rope velocity.
            if (!(e instanceof Player)) {
                Vec3 v = e.getDeltaMovement();
                e.setDeltaMovement(ddx, v.y, ddz);
            }
        }
        return changed;
    }
}
