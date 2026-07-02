package com.bannerbound.core.api.fisher;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/**
 * Sailing for rod-fisher NPCs. Once a settlement researches {@link #FLAG_SAILING}, its fishers may
 * board a small vessel and paddle out onto open water to fish the DEEP water the shore can't reach
 * (deep water bites ~2× faster — see {@code FisherBobber}) — a little beyond the claim edge if need
 * be. Spear fishers never sail: spear fishing is a shallows technique. The vessel is a
 * non-interactable "ghost": it floats and animates like a real boat, but takes no damage, drops
 * nothing, has no collision, and the player can't board, repair, or destroy it. It is spawned from
 * thin air when a fisher launches and discarded when the trip ends.
 *
 * <p><b>Cross-mod vessel:</b> Core has no boat art of its own, so the vessel is supplied by a
 * {@link VesselProvider}. With the Antiquity expansion installed, a provider registered at mod setup
 * returns a ghost <i>raft</i>; with no provider registered, {@link #spawnGhostVessel} returns
 * {@code null} and fishers simply stay on the shore. (Sailing is gated behind an Antiquity research
 * node, so a Core-only install never reaches the no-provider path anyway.)
 *
 * <p>Driving the vessel reuses the trader-sim technique: there's no rider "paddle input", so the
 * controller pushes the hull toward a target each tick and lets vanilla buoyancy hold the surface —
 * see {@link #drive}.
 */
public final class FishingVessels {
    /** Research flag (Core-owned constant; the granting node lives in the fishing tree) that unlocks
     *  boat fishing for a settlement's fishers. */
    public static final String FLAG_SAILING = "bannerbound.sailing";

    /** Default cruising speed (blocks/tick) for a fisher's vessel — matches the trader sim's feel. */
    public static final double VESSEL_SPEED = 0.35;
    /** Max hull turn per tick (degrees) — the vessel carves smooth arcs instead of snapping its yaw,
     *  which both looks right and keeps the synced paddle animation from stuttering. */
    private static final float MAX_TURN_PER_TICK = 4.0F;

    /** Supplies the ghost vessel entity. Registered by an expansion (Antiquity → ghost raft). */
    @FunctionalInterface
    public interface VesselProvider {
        /** Spawn a floating, non-interactable vessel at {@code (x,y,z)} facing {@code yaw}, already
         *  added to the level. Return {@code null} if one couldn't be placed. */
        @Nullable
        Boat spawnGhostVessel(ServerLevel level, double x, double y, double z, float yaw);
    }

    @Nullable
    private static VesselProvider provider;

    private FishingVessels() {
    }

    /** Register the ghost-vessel supplier (call once at mod setup). Last registration wins. */
    public static void setProvider(VesselProvider p) {
        provider = p;
    }

    /** True if a vessel supplier is installed (i.e. an expansion provides boats). */
    public static boolean hasProvider() {
        return provider != null;
    }

    /** Spawn the ghost vessel via the registered provider, or {@code null} if none is installed. */
    @Nullable
    public static Boat spawnGhostVessel(ServerLevel level, double x, double y, double z, float yaw) {
        return provider == null ? null : provider.spawnGhostVessel(level, x, y, z, yaw);
    }

    /** True when {@code settlement} has researched sailing (so its fishers may sail). */
    public static boolean isSailingUnlocked(Settlement settlement) {
        return ResearchManager.hasFlag(settlement, FLAG_SAILING);
    }

    /**
     * Drive a riderless/AI-ridden boat toward {@code (targetX,targetZ)}: steer toward it at most
     * {@link #MAX_TURN_PER_TICK} degrees per tick (a smooth carve, not a yaw snap — snapping is what
     * made the hull and oar animation stutter), thrust along the HULL's heading (throttled while
     * still turning), and row the oars (synced paddle animation). Vanilla buoyancy keeps it on the
     * surface. Call every tick — an AI passenger never supplies paddle input itself.
     */
    public static void drive(Boat boat, double targetX, double targetZ, double speed) {
        double dx = targetX - boat.getX();
        double dz = targetZ - boat.getZ();
        float desired = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float delta = net.minecraft.util.Mth.wrapDegrees(desired - boat.getYRot());
        float yaw = boat.getYRot() + net.minecraft.util.Mth.clamp(delta, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
        boat.yRotO = boat.getYRot();
        boat.setYRot(yaw);
        // Push along where the bow actually points (not the straight line to the target), easing off
        // while the hull is still coming around — so the boat carves an arc instead of crabbing.
        double thrust = speed * (Math.abs(delta) > 60.0F ? 0.35 : 1.0);
        double heading = Math.toRadians(yaw + 90.0);
        Vec3 dm = boat.getDeltaMovement();
        boat.setDeltaMovement(Math.cos(heading) * thrust, dm.y, Math.sin(heading) * thrust);
        boat.hasImpulse = true;
        boat.setPaddleState(true, true);
    }

    /** Hold a boat in place (anchor) — damp horizontal drift so a fisher can fish from a steady deck.
     *  Leaves vertical (buoyancy/bob) alone. Stops the rowing animation. */
    public static void anchor(Boat boat) {
        Vec3 dm = boat.getDeltaMovement();
        boat.setDeltaMovement(dm.x * 0.6, dm.y, dm.z * 0.6);
        boat.setPaddleState(false, false);
    }
}
