package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.fisher.FisherCatchTable;
import com.bannerbound.core.api.fisher.FisherShoreRegistry;
import com.bannerbound.core.api.fisher.FishingVessels;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Fisher {@link GathererWorkGoal} — a self-directed gatherer (no Foreman's Rod). It scans for a
 * stand tile beside open water within its settlement's claims, walks there, casts a
 * {@link FisherBobber} into deep open water, waits for a bite, reels in a catch from
 * {@link FisherCatchTable}, and deposits it into its marked drop-off. Stand tiles are soft-locked
 * through {@link FisherShoreRegistry} so two fishers never share a spot. The Job tab for a fisher is
 * minimal: a fishing-rod tool slot + a drop-off location.
 *
 * <h2>Casting that actually lands in the lake</h2>
 * The hard part of a believable fisher is that the {@link FisherBobber} <em>sticks to the first
 * water it touches</em> (see {@link FisherBobber#tick}). A naïve "lob roughly toward deep water"
 * either thunks the float into a tree/bank or drops it on the shallow shelf at the lake's edge —
 * exactly the broken behaviour we're replacing. So instead of guessing, we <b>simulate the bobber's
 * own ballistic physics</b> for a fan of candidate launch velocities ({@link #simulateLanding}) and
 * keep only a launch we proved lands in open water without clipping terrain first. Crucially the
 * throw is re-solved at cast time ({@link #solveArc}) from the citizen's <em>actual</em> position —
 * not the stand tile it was aiming for — so even if it stops a block short of the spot, the float
 * still arcs into the water rather than onto the bank. If no arc reaches water from where it ended
 * up, it steps back onto the spot and retries instead of casting blind.
 *
 * <p>Stand tiles are then ranked by where their <em>best</em> arc lands: depth and openness dominate,
 * then a shorter flight breaks ties. Because a pier puts the fisher right up against deep water, its
 * short arc into deep water out-scores a long arc lobbed off the bank over the shallows — so a fisher
 * naturally prefers a pier over the shore without any special-casing.
 */
@ApiStatus.Internal
public class FisherWorkGoal extends GathererWorkGoal {
    /** Per-citizen job id (matches {@link com.bannerbound.core.api.settlement.WorkstationUnlocks}). */
    public static final String JOB_TYPE_ID = "fishers_creel";

    private static final int SCAN_RADIUS = 28;       // how far to look for a fishing spot (reaches piers)
    private static final int SHORE_SEPARATION = 3;   // min blocks between two fishers' stands
    private static final int RESCAN_COOLDOWN_TICKS = 40;
    private static final double ARRIVE_SQ = 1.6 * 1.6; // "at the stand" threshold
    private static final int WALK_TIMEOUT_TICKS = 200;
    private static final int CAST_MISS_TICKS = 80;   // bobber never reached water → recast
    private static final int MAX_DEPTH_SCORE = 8;     // depth past this doesn't score extra
    private static final int REEL_TICKS = 6;          // let the bite dip show before the catch lands
    private static final int UPGRADE_SCAN_TICKS = 1200; // while fishing, re-look for a better spot every ~60s
    private static final double UPGRADE_MARGIN = 500.0; // ...and only relocate if it clearly beats the current
    private static final double EXCELLENT_SCORE = 9000.0; // a spot this good is already top-tier — stop re-scanning
    private static final int AVOID_TICKS = 2400;        // a stand we couldn't path to is skipped for ~2 min
    private static final int TOOL_FREE_RECAST_DELAY_TICKS = 40; // anarchy bare-handed: rest between catches (slower)

    // ── Sailing (research-gated). Deep water bites ~2× faster (see FisherBobber), so when the shore
    // can't cast into genuinely deep water but a deep lake/sea lies within sailing range, the fisher
    // conjures a ghost vessel (a raft with Antiquity installed) and paddles out to fish from it. ──
    private static final int SAIL_DEPTH_MIN = 4;          // only sail for water this deep (the 2× bite zone)
    private static final int SAIL_DEPTH_CAP = 16;         // sail scoring probes this deep — true deep sea
                                                          // out-scores the first shelf off the beach
    private static final int SAIL_SCAN_RADIUS = 48;       // how far from the home anchor (town hall) deep water is hunted
    private static final int SAIL_SCAN_STEP = 2;          // column stride of the deep-water scan (centers are big)
    private static final int MIN_SAIL_DIST = 20;          // a sail target is a REAL trip out, not 5 blocks off the sand
    private static final int BOAT_SEPARATION = 8;         // min blocks between two fishers' anchored vessels
    private static final int MIN_SAIL_EXPANSE = 16;       // openExpanse floor for a sail target — a genuine
                                                          // lake/sea, NEVER a 1-block source or a thin channel
                                                          // the raft can't actually float/turn in (max ≈ 25)
    private static final double SAIL_ARRIVE_SQ = 2.0 * 2.0;
    private static final int SAIL_TIMEOUT_TICKS = 400;    // give up a sail leg that makes no progress

    private static final int MAX_CAST_DIST = 12;       // furthest (horizontal) we'll lob the float
    private static final int MAX_FLIGHT_TICKS = 70;    // physics-sim cap for a single cast arc
    private static final float BOBBER_DRAG = 0.98f;    // mirror FisherBobber's airborne horizontal drag
    private static final float BOBBER_GRAVITY = 0.03f; // mirror FisherBobber.GRAVITY
    /** Launch arcs we try per direction — a spread of vertical speeds × horizontal speeds. The float
     *  sticks to the FIRST water it touches, so a flat flick only ever reaches the shallow shelf at
     *  the bank; a higher lob stays above the surface while it passes over that shelf and only drops
     *  into the water further out, reaching the deep water a fisher actually wants. We keep both: the
     *  flat arcs are the fallback that still works under bank-side tree cover (a high lob there is
     *  rejected for clipping leaves), while the higher lobs win at an open shore because they reach
     *  the deeper water the depth score rewards. The arc that lands deepest wins. */
    private static final double[] CAST_VY = {0.10, 0.22, 0.36, 0.50};
    private static final double[] CAST_SPEED = {0.18, 0.30, 0.42, 0.56};
    /** Horizontal aim directions (4 cardinals + 4 diagonals); diagonals are normalised in-loop. */
    private static final int[][] CAST_DIRS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    /** Sparse offsets sampled by {@link #openExpanse} on two rings (~3 and ~5 blocks out) to gauge how
     *  large the surrounding water body is without scanning every block in between. */
    private static final int[][] EXPANSE_SAMPLES = {
        {3, 0}, {-3, 0}, {0, 3}, {0, -3}, {2, 2}, {2, -2}, {-2, 2}, {-2, -2},   // ~radius 3 ring
        {5, 0}, {-5, 0}, {0, 5}, {0, -5}, {4, 4}, {4, -4}, {-4, 4}, {-4, -4}    // ~radius 5 ring
    };

    private enum Phase { SEEK, WAIT, REEL, LAUNCH, SAIL_OUT, SAIL_BACK }

    private Phase phase = Phase.SEEK;
    private BlockPos shorePos;   // standable tile we fish from
    private BlockPos waterPos;   // the open-water block we aim the cast at
    private FisherBobber bobber;
    private int phaseAge;
    private int rescanCooldown;
    private int repositionTries; // consecutive casts that couldn't reach water from where we stood
    private double currentScore; // score of the spot we currently hold (for upgrade comparison)
    private int upgradeCooldown; // ticks until we next re-scan for a clearly better spot
    private BlockPos avoidStand; // a stand we just failed to path to — skipped by findSpot for a while
    private int avoidTicks;      // remaining ticks the avoidStand stays blacklisted
    private int recastDelay;     // tool-free anarchy: idle ticks between catches before recasting
    // ── Sailing state ──
    private boolean sailing;             // this work session fishes from a vessel out on deep water
    private net.minecraft.world.entity.vehicle.Boat vessel;  // the conjured ghost vessel (null ashore)
    private BlockPos launchPos;          // shore tile we board at / are returned to
    private BlockPos boardWater;         // water tile beside the launch (the return leg's target)
    private BlockPos sailTarget;         // the deep open-water point we paddle to (claimed in the registry)
    private int sailTimeout;             // safety cap on a sail leg

    public FisherWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
        // Stagger the first spot-scan by a per-fisher offset so a batch of fishers assigned at once
        // don't all run their (expensive) findSpot on the same tick and stack into one frame spike.
        this.rescanCooldown = citizen.getId() % 100;
    }

    /** Per-fisher jittered interval between upgrade re-scans, so multiple fishers never run their
     *  expensive {@link #findSpot} on the same tick — spreads the cost out instead of stacking it. */
    private int upgradeInterval() {
        return UPGRADE_SCAN_TICKS + (citizen.getId() & 255);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    private Container resolveDepot() {
        return DropOffContainers.resolveJobDepot(citizen);
    }

    public static boolean isFishingRod(net.minecraft.world.item.ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.FishingRodItem;
    }

    /** The single readiness gate for start/keep/tick — job + pool-backed depot via
     *  {@link CitizenEntity#isFisherReady}, plus an actual fishing rod as the job tool. In anarchy
     *  the rod is waived (a self-organizing citizen fishes bare-handed, slower — see the tool-free
     *  recast path), mirroring {@code isFisherReady}'s own anarchy branch. */
    private boolean fisherReady() {
        if (citizen.isAnarchy()) return citizen.isFisherReady();
        return citizen.isFisherReady() && isFishingRod(citizen.getJobTool());
    }

    @Override
    protected boolean canStartWork() {
        citizen.validateJobStorage();   // clear a broken drop-off so we don't fish for a dead container
        boolean ready = fisherReady();

        Container depot = resolveDepot();
        boolean depotOk = depot != null && DropOffContainers.hasFreeSlot(depot);

        // World-reload recovery: the sailing-trip state is transient, so a fisher saved out at sea
        // wakes up seated on her vessel with no trip — and no way to walk anywhere, so she'd idle
        // afloat forever. Adopt the boat under her: re-anchor right here, fish, and ride home as
        // usual when done. If the session can't resume (job/tool gone, depot full, no home bank),
        // step off instead — the orphaned ghost vessel despawns itself (see RaftEntity).
        if (!sailing && citizen.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat b) {
            if (citizen.level() instanceof ServerLevel sl) {
                // Home bank hunted around the BOAT — workers no longer mark a drop-off, so the old
                // getDropOff() origin was null for everyone and she'd step off into deep water.
                BlockPos here = b.blockPosition();
                BlockPos launch = findLaunchStand(sl, here, here);
                if (launch != null && FisherShoreRegistry.tryClaim(citizen.getUUID(), here)) {
                    sailing = true;
                    vessel = b;
                    sailTarget = here;
                    launchPos = launch;
                    boardWater = boardWaterBeside(sl, launch);
                    shorePos = launch;
                    waterPos = null;
                    // Session still viable → anchor right here and fish on. Otherwise (full depot,
                    // missing tool, bedtime) just ride the boat home and end the trip ashore.
                    phase = (ready && depotOk && !isBedtimeSoon()) ? Phase.SAIL_OUT : Phase.SAIL_BACK;
                    phaseAge = 0;
                    sailTimeout = SAIL_TIMEOUT_TICKS;
                    return true;
                }
            }
            citizen.stopRiding();   // no home bank resolvable — step off; the ghost despawns itself
            return false;
        }
        if (!ready || !depotOk) return false;

        // Keep a still-valid spot (water still there + our claim held).
        if (shorePos != null && isWater(citizen.level(), waterPos)
                && !FisherShoreRegistry.isClaimedByOther(shorePos, citizen.getUUID())) {
            FisherShoreRegistry.tryClaim(citizen.getUUID(), shorePos);
            return true;
        }
        shorePos = null;
        waterPos = null;
        if (rescanCooldown-- > 0) return false;
        // Sailing FIRST: once researched (and a vessel provider is installed), deep water is the
        // preferred fishery — it bites ~2× faster (see FisherBobber) — and the trip deliberately
        // leaves the claims: going out past the territory is the point of sailing. The drop-off
        // radius is the only leash. Shore casting below is the fallback when no deep water is in
        // range (or no launch bank / vessel).
        // The registry claim goes on the SEA SPOT (not the launch bank): that's what must be exclusive
        // so two fishers' vessels never anchor on top of each other (see BOAT_SEPARATION).
        SailPlan plan = planSailTrip();
        if (plan != null && FisherShoreRegistry.tryClaim(citizen.getUUID(), plan.target())) {
            sailing = true;
            launchPos = plan.launch();
            sailTarget = plan.target();
            shorePos = plan.launch();
            waterPos = null;          // chosen once we anchor out on the deep water
            phase = Phase.LAUNCH;
            phaseAge = 0;
            return true;
        }
        CastSolution spot = findSpot();
        if (spot == null) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        if (!FisherShoreRegistry.tryClaim(citizen.getUUID(), spot.stand())) {
            rescanCooldown = 5;
            return false;
        }
        shorePos = spot.stand();
        waterPos = spot.target();
        currentScore = spot.score();
        upgradeCooldown = upgradeInterval();
        repositionTries = 0;
        phase = Phase.SEEK;
        phaseAge = 0;
        return true;
    }

    @Override
    protected boolean canKeepWorking() {
        // The ride home outranks every goal-level gate (lost tool, full depot): she's out on the
        // water, and the only sane continuation is reaching the bank. (WorkGoal's hard gates —
        // stamina 0, AI deactivation, sleep — can still interrupt; stop() then leaves her seated
        // and the next start adopts the boat.)
        if (sailing && phase == Phase.SAIL_BACK) {
            return vessel != null && vessel.isAlive() && citizen.getVehicle() == vessel;
        }
        if (!fisherReady()) return false;
        Container depot = resolveDepot();
        boolean depotOk = depot != null && DropOffContainers.hasFreeSlot(depot);
        // Sailing trip: while walking to the launch we only need the stand; afloat we need the vessel
        // under us (the cast water is only chosen once anchored out on the deep spot). A full depot
        // doesn't hard-stop a fisher at sea — she rides home first (SAIL_BACK) and the goal ends
        // ashore instead of teleporting her off the water.
        if (sailing) {
            if (phase == Phase.LAUNCH) {
                return depotOk && shorePos != null && WorkerPathing.isWalkable(citizen.level(), shorePos);
            }
            if (vessel == null || !vessel.isAlive() || citizen.getVehicle() != vessel) return false;
            if (!depotOk) startSailBack();
            return phase == Phase.SAIL_OUT || phase == Phase.SAIL_BACK
                || isWater(citizen.level(), waterPos);
        }
        if (!depotOk) return false;  // full → stop
        // Bail the moment the spot stops being valid — not just if the water vanished, but if the
        // ground we stand on is gone too (e.g. the player breaks the pier out from under the fisher).
        // Without the stand check she'd keep "fishing" from the open water until the next upgrade scan.
        return shorePos != null
            && WorkerPathing.isWalkable(citizen.level(), shorePos)
            && isWater(citizen.level(), waterPos);
    }

    @Override
    public void start() {
        citizen.setWorking(true);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, citizen.getJobTool().copy());
        citizen.setAvoidWaterPathing(true);   // walk the shore/pier to the spot, don't swim a shortcut
        // An ADOPTED trip (world-reload recovery) starts already seated on the vessel out at sea —
        // keep its SAIL_OUT phase; everything else walks to its spot first.
        boolean adopted = sailing && vessel != null && citizen.getVehicle() == vessel;
        if (!adopted) {
            phase = sailing ? Phase.LAUNCH : Phase.SEEK;
        }
        phaseAge = 0;
        if (shorePos != null && !citizen.isPassenger()) {
            citizen.getNavigation().moveTo(
                shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
        }
    }

    @Override
    public void stop() {
        citizen.setWorking(false);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.setAvoidWaterPathing(false);
        discardBobber();
        // Hard interrupt while afloat (AI deactivation when the player leaves, sleep preemption, a
        // refusal thought): leave her SEATED on the vessel instead of teleporting her ashore. The
        // next goal start adopts the boat under her — resume fishing or ride it home (see
        // canStartWork) — and an occupied ghost raft never self-despawns, so nothing is stranded.
        if (sailing && vessel != null && vessel.isAlive() && citizen.getVehicle() == vessel) {
            vessel = null;
            sailing = false;
            sailTarget = null;
            boardWater = null;
            launchPos = null;
        } else {
            endSailing();
        }
        FisherShoreRegistry.release(citizen.getUUID());
        shorePos = null;
        waterPos = null;
        phase = Phase.SEEK;
        recastDelay = 0;
    }

    @Override
    public void tick() {
        if (!fisherReady() || shorePos == null) return;
        phaseAge++;
        if (avoidTicks > 0) avoidTicks--;
        // Periodically re-evaluate: if a clearly better spot has appeared (e.g. the player just built
        // a pier), relocate to it instead of fishing the same spot forever. Skipped mid-bite so we
        // don't walk off a hooked fish, and while sailing (the deep water IS the better spot).
        if (--upgradeCooldown <= 0) {
            upgradeCooldown = upgradeInterval();
            if (!sailing) tryUpgradeSpot();
        }
        // Hold the vessel steady while fishing from it so drift doesn't drag the cast around.
        if (sailing && vessel != null && vessel.isAlive()
                && (phase == Phase.SEEK || phase == Phase.WAIT || phase == Phase.REEL)) {
            FishingVessels.anchor(vessel);
        }
        // End an afloat session gracefully: ride home BEFORE stamina runs dry or bedtime hits, so the
        // trip ends on the shore instead of a mid-sea hard stop (which would leave her seated at sea
        // until the next adoption). getStamina() <= 1: one more catch would exhaust her out there.
        if (sailing && phase != Phase.LAUNCH && phase != Phase.SAIL_BACK
                && (citizen.getStamina() <= 1 || isBedtimeSoon())) {
            startSailBack();
        }
        // Always face the water we're fishing (not chosen yet while walking out / paddling).
        if (waterPos != null) {
            citizen.getLookControl().setLookAt(
                waterPos.getX() + 0.5, waterPos.getY() + 0.5, waterPos.getZ() + 0.5);
            // Seated, the look control's gentle head-turn fights the boat's passenger-yaw clamp and
            // often loses — so she'd stare at nothing while her line hangs elsewhere. Face the float
            // outright (body + head); the fishing-line render anchors on body yaw, so the line then
            // reads correctly out of her hands too.
            if (sailing && citizen.isPassenger()) {
                float yawTo = (float) (Math.toDegrees(Math.atan2(
                    waterPos.getZ() + 0.5 - citizen.getZ(),
                    waterPos.getX() + 0.5 - citizen.getX())) - 90.0);
                citizen.setYRot(yawTo);
                citizen.yBodyRot = yawTo;
                citizen.yHeadRot = yawTo;
            }
        }

        // Tool-free anarchy rest between catches: stand idle on the spot for a beat before recasting,
        // so a bare-handed fisher pulls in fish slower than one given a rod.
        if (recastDelay > 0) { recastDelay--; return; }

        switch (phase) {
            case SEEK -> tickSeek();
            case WAIT -> tickWait();
            case REEL -> tickReel();
            case LAUNCH -> tickLaunch();
            case SAIL_OUT -> tickSailOut();
            case SAIL_BACK -> tickSailBack();
        }
    }

    /** Re-scans for the best spot and relocates there if it clearly beats the one we hold. This is what
     *  lets a fisher leave an early shore corner for a pier the player builds later: without it, {@link
     *  #canStartWork} keeps the first valid spot indefinitely and never re-scans. Won't interrupt a bite,
     *  requires a margin over the current score to avoid thrashing between near-equal spots, and only
     *  commits once the new shore is claimed. */
    private void tryUpgradeSpot() {
        if (phase == Phase.REEL) return;
        if (bobber != null && bobber.isHooked()) return;
        // Already in a top-tier spot (deep, open, on a pier)? Don't burn a full scan looking for
        // something better that almost certainly isn't there. This is the big steady-state saving:
        // well-placed fishers stop re-scanning entirely; only those in mediocre spots keep looking.
        if (currentScore >= EXCELLENT_SCORE) return;
        CastSolution spot = findSpot();
        if (spot == null || spot.stand().equals(shorePos)) return;
        if (spot.score() <= currentScore + UPGRADE_MARGIN) return;
        if (!FisherShoreRegistry.tryClaim(citizen.getUUID(), spot.stand())) return;
        shorePos = spot.stand();
        waterPos = spot.target();
        currentScore = spot.score();
        repositionTries = 0;
        discardBobber();
        phase = Phase.SEEK;
        phaseAge = 0;
        citizen.getNavigation().moveTo(
            shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
    }

    private void tickSeek() {
        // Afloat, SEEK means "cast again from the seat" — the fisher never walks while on the vessel.
        if (sailing && citizen.isPassenger()) {
            cast();
            return;
        }
        double d = citizen.position().distanceToSqr(
            shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5);
        // Only fish once we're actually standing ON the spot (on the pier/shore) — not while still
        // swimming up to it. Casting from the water left the fisher bobbing in the lake.
        if (d <= ARRIVE_SQ && !citizen.isInWater()) {
            cast();
            return;
        }
        if (citizen.getNavigation().isDone()) {
            // (Re)issue the path; if we just can't get there (or can't climb out of the water onto
            // the spot), drop it and rescan.
            if (phaseAge > WALK_TIMEOUT_TICKS) { abandonSpot(); return; }
            citizen.getNavigation().moveTo(
                shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
        }
    }

    private void cast() {
        citizen.getNavigation().stop();
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        discardBobber();
        // Afloat, re-roll the aim water EVERY cast (random forward spot, 4–9 blocks out) so repeat
        // casts wander across the water instead of drilling the same tile.
        if (sailing) {
            BlockPos fresh = pickCastWaterNearVessel(sl);
            if (fresh != null) waterPos = fresh;
        }
        // Solve the throw from where the citizen ACTUALLY stands this instant (not the scan-time stand
        // tile it may have stopped a block or two short of), aiming at the chosen water. We only ever
        // throw a launch the bobber physics prove settles in water — so the float can't be lobbed onto
        // the bank. If no arc from here reaches the water (the citizen is off the spot, or a trunk is
        // in the line), step back onto the stand and retry rather than casting blind onto the grass.
        Vec3 eye = new Vec3(citizen.getX(), citizen.getEyeY() - 0.1, citizen.getZ());
        Vec3 vel = solveArc(sl, eye, waterPos);
        if (vel == null) {
            if (++repositionTries > 3) { abandonSpot(); return; }
            if (sailing) {
                // From the vessel: re-pick a castable water tile around the hull and retry (SEEK
                // re-casts from the seat — there's no walking to do out here).
                waterPos = pickCastWaterNearVessel(sl);
                if (waterPos == null) { abandonSpot(); return; }
                phase = Phase.SEEK;
                phaseAge = 0;
                return;
            }
            phase = Phase.SEEK;
            phaseAge = 0;
            citizen.getNavigation().moveTo(
                shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
            return;
        }
        repositionTries = 0;
        bobber = new FisherBobber(sl, citizen, vel);
        sl.addFreshEntity(bobber);
        sl.playSound(null, citizen.blockPosition(),
            net.minecraft.sounds.SoundEvents.FISHING_BOBBER_THROW,
            net.minecraft.sounds.SoundSource.NEUTRAL, 0.5f, 0.9f);
        phase = Phase.WAIT;
        phaseAge = 0;
    }

    private void tickWait() {
        if (bobber == null || !bobber.isAlive()) { cast(); return; }
        if (!isWater(citizen.level(), bobber.blockPosition())) {
            // Bobber still flying or it missed the water entirely — recast if it's been too long.
            if (phaseAge > CAST_MISS_TICKS) cast();
            return;
        }
        // The bobber runs the whole lure/approach/bite sim itself; reel once it actually hooks.
        if (bobber.isHooked()) {
            phase = Phase.REEL;
            phaseAge = 0;
        }
    }

    private void tickReel() {
        if (phaseAge < REEL_TICKS) return;
        if (citizen.level() instanceof ServerLevel sl && bobber != null) {
            BlockPos bp = bobber.blockPosition();
            ItemStack catchStack = FisherCatchTable.roll(
                sl, citizen, citizen.getSettlement(), bp, isOpenWater(sl, bp));
            // The catch feeds the town via the larder once deposited, not a live status bonus (COOKING_PLAN.md Part 1).
            deposit(catchStack);
            // Statistic: credit the "fishing" source with the food-stuff this catch yielded, so crisis
            // objectives and town-hall stats can read how much the settlement has produced by fishing.
            com.bannerbound.core.api.settlement.Settlement settlement = citizen.getSettlement();
            if (settlement != null && !catchStack.isEmpty()) {
                settlement.addFoodProduced("fishing", catchStack.getCount());
            }
            sl.playSound(null, citizen.blockPosition(),
                net.minecraft.sounds.SoundEvents.FISHING_BOBBER_RETRIEVE,
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.6f, 1.0f);
            citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "fish");
            citizen.consumeStamina(1);
        }
        discardBobber();
        // Loop: fish the same spot again (canKeepWorking re-validates the water + claim). Tool-free
        // in anarchy, rest a beat first (handled by the recastDelay gate in tick()), recasting from
        // SEEK once it elapses — we're already standing on the spot.
        if (citizen.isAnarchy() && !citizen.hasJobTool()) {
            recastDelay = TOOL_FREE_RECAST_DELAY_TICKS;
            phase = Phase.SEEK;
            phaseAge = 0;
        } else {
            cast();
        }
    }

    private void deposit(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        Container depot = resolveDepot();
        ItemStack leftover = depot == null ? stack : DropOffContainers.insert(depot, stack);
        if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
    }

    private void discardBobber() {
        if (bobber != null) {
            bobber.discard();
            bobber = null;
        }
    }

    private void abandonSpot() {
        discardBobber();
        endSailing();
        // Blacklist this stand briefly so the next scan doesn't immediately re-pick the very spot we
        // just failed to reach (e.g. a pier the navigation can't path onto) and loop forever.
        avoidStand = shorePos;
        avoidTicks = AVOID_TICKS;
        FisherShoreRegistry.release(citizen.getUUID());
        shorePos = null;
        waterPos = null;
        repositionTries = 0;
        phase = Phase.SEEK;
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
    }

    // ─── Sailing: conjure a ghost vessel and fish the deep water the shore can't reach ─────────────

    /** Walk to the launch stand; once there, conjure the ghost vessel on the water beside it, board,
     *  and paddle out. */
    private void tickLaunch() {
        if (launchPos == null || sailTarget == null) { abandonSpot(); return; }
        double d = citizen.position().distanceToSqr(
            launchPos.getX() + 0.5, launchPos.getY(), launchPos.getZ() + 0.5);
        if (d <= ARRIVE_SQ && !citizen.isInWater()) {
            if (!board()) { abandonSpot(); return; }
            phase = Phase.SAIL_OUT;
            phaseAge = 0;
            sailTimeout = SAIL_TIMEOUT_TICKS;
            return;
        }
        if (citizen.getNavigation().isDone()) {
            if (phaseAge > WALK_TIMEOUT_TICKS) { abandonSpot(); return; }
            citizen.getNavigation().moveTo(
                launchPos.getX() + 0.5, launchPos.getY(), launchPos.getZ() + 0.5, skilledSpeed());
        }
    }

    /** Conjure the ghost vessel on water beside the launch stand and seat the fisher on it. */
    private boolean board() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        BlockPos water = boardWaterBeside(sl, launchPos);
        if (water == null) return false;
        this.boardWater = water;   // the return leg paddles back to this tile
        double surfaceY = water.getY() + sl.getFluidState(water).getHeight(sl, water);
        float yaw = (float) Math.toDegrees(Math.atan2(
            sailTarget.getZ() - water.getZ(), sailTarget.getX() - water.getX())) - 90.0F;
        citizen.getNavigation().stop();
        vessel = FishingVessels.spawnGhostVessel(sl, water.getX() + 0.5, surfaceY, water.getZ() + 0.5, yaw);
        return vessel != null && citizen.startRiding(vessel, true);
    }

    /** Surface-water tile horizontally beside {@code stand} (its level or one below), or null. */
    private static BlockPos boardWaterBeside(ServerLevel sl, BlockPos stand) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos n = stand.relative(dir);
            if (isSurfaceWater(sl, n)) return n;
            if (isSurfaceWater(sl, n.below())) return n.below();
        }
        return null;
    }

    /** Paddle toward the deep-water target; on arrival (or timeout — fish wherever we got to), anchor,
     *  pick a cast tile beside the hull, and start fishing from the seat. */
    private void tickSailOut() {
        if (vessel == null || !vessel.isAlive() || !citizen.isPassenger()) { abandonSpot(); return; }
        if (!(citizen.level() instanceof ServerLevel sl) || sailTarget == null) { abandonSpot(); return; }
        double d = vessel.position().distanceToSqr(
            sailTarget.getX() + 0.5, vessel.getY(), sailTarget.getZ() + 0.5);
        if (d <= SAIL_ARRIVE_SQ || --sailTimeout <= 0) {
            FishingVessels.anchor(vessel);
            waterPos = pickCastWaterNearVessel(sl);
            if (waterPos == null) { abandonSpot(); return; }
            // The deep water IS the best spot — don't upgrade-scan away from the vessel.
            currentScore = EXCELLENT_SCORE;
            repositionTries = 0;
            cast();
            return;
        }
        FishingVessels.drive(vessel, sailTarget.getX() + 0.5, sailTarget.getZ() + 0.5,
            FishingVessels.VESSEL_SPEED);
    }

    /** A castable surface-water tile out from the vessel — so the float lands in open water beside
     *  the hull (not under it) and the line reads clearly. Collects every candidate in a ~90° cone
     *  ahead of the bow at 4–9 blocks out, then picks one at RANDOM, so consecutive casts spread
     *  across the water instead of drilling one tile. The cone deliberately excludes straight-side
     *  casts: at 90° the seated body yaw sits at the boat's passenger-yaw clamp and the two fight,
     *  which jittered the fishing-line anchor. */
    private BlockPos pickCastWaterNearVessel(ServerLevel sl) {
        if (vessel == null) return null;
        BlockPos base = vessel.blockPosition();
        double heading = Math.toRadians(vessel.getYRot() + 90.0);
        double fx = Math.cos(heading);
        double fz = Math.sin(heading);
        List<BlockPos> candidates = new ArrayList<>();
        for (int[] dir : CAST_DIRS) {
            double len = Math.sqrt((double) (dir[0] * dir[0] + dir[1] * dir[1]));
            if ((dir[0] * fx + dir[1] * fz) / len < 0.45) continue;   // outside the forward cone — skip
            for (int r = 4; r <= 9; r++) {
                int dx = (int) Math.round(dir[0] / len * r);
                int dz = (int) Math.round(dir[1] / len * r);
                for (int dy = 1; dy >= -2; dy--) {
                    BlockPos c = base.offset(dx, dy, dz);
                    if (isSurfaceWater(sl, c)) {
                        candidates.add(c.immutable());
                        break;
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(citizen.getRandom().nextInt(candidates.size()));
    }

    /** Step off the vessel and remove it (no-op when not sailing). A trip that ends AFLOAT finishes
     *  with a half-stride snap onto the launch bank — dismounting into the shallows left the citizen
     *  swimming, and water pathing visibly breaks them. This is NOT the old across-the-map teleport:
     *  the visible ride home already happened; this is just the step ashore. A stop on dry land (the
     *  LAUNCH walk) doesn't snap. Hard interrupts never reach this — stop() leaves her seated for the
     *  next adoption instead. */
    private void endSailing() {
        if (!sailing) return;
        boolean wasAfloat = citizen.isPassenger() && citizen.getVehicle() == vessel;
        if (wasAfloat) {
            citizen.stopRiding();
        }
        if ((wasAfloat || citizen.isInWater()) && launchPos != null) {
            citizen.moveTo(launchPos.getX() + 0.5, launchPos.getY(), launchPos.getZ() + 0.5,
                citizen.getYRot(), 0.0F);
            CitizenEntity.tagDeliberateTeleport(citizen);
            citizen.getNavigation().stop();
        }
        if (vessel != null && vessel.isAlive()) vessel.discard();
        vessel = null;
        sailing = false;
        sailTarget = null;
        boardWater = null;
        launchPos = null;
    }

    /** The pre-bed wind-down window (skipped under the Nightshift policy): afloat fishers head home,
     *  and no NEW sailing trip launches — otherwise a fisher arriving home at dusk would immediately
     *  plan another trip and yo-yo at the bank until the social window finally gates her.
     *  <p>Starts at 9_000, a full 1_100 ticks before WorkGoal's social-window gate hard-stops the
     *  goal at 10_100 — the ride home can take up to {@link #SAIL_TIMEOUT_TICKS} (400), and an
     *  interrupted ride can't be resumed until night, so the margin must comfortably cover the
     *  worst-case return. (The earlier 9_700 start left only 400 ticks: any slow ride got cut off
     *  mid-water, which read as fishers never sailing home at dusk.) */
    private boolean isBedtimeSoon() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Settlement s = citizen.getSettlement();
        if (s != null && s.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.NIGHTSHIFT)) {
            return false;
        }
        long t = sl.getDayTime() % 24_000L;
        return t >= 9_000L && t < 12_500L;
    }

    /** Turn the trip around: stop fishing and paddle back to the boarding water. The goal keeps
     *  running through the ride; it ends ashore (or the hard-stop fallback steps her off). */
    private void startSailBack() {
        if (!sailing || phase == Phase.SAIL_BACK || phase == Phase.LAUNCH) return;
        discardBobber();
        waterPos = null;             // no longer fishing — also stops the look-at
        phase = Phase.SAIL_BACK;
        phaseAge = 0;
        sailTimeout = SAIL_TIMEOUT_TICKS;
    }

    /** Paddle home; on arrival step ashore, despawn the vessel, and let the goal wind down (the next
     *  poll re-plans — or stops for good if the depot/stamina/bedtime ended the session). */
    private void tickSailBack() {
        if (vessel == null || !vessel.isAlive() || !citizen.isPassenger()) { abandonSpot(); return; }
        BlockPos home = boardWater != null ? boardWater : launchPos;
        if (home == null) { abandonSpot(); return; }
        double d = vessel.position().distanceToSqr(home.getX() + 0.5, vessel.getY(), home.getZ() + 0.5);
        if (d <= SAIL_ARRIVE_SQ || --sailTimeout <= 0) {
            endSailing();
            FisherShoreRegistry.release(citizen.getUUID());
            shorePos = null;
            waterPos = null;
            phase = Phase.SEEK;
            rescanCooldown = 20;
            return;
        }
        // Hurry home a touch faster than the cruise out — the return leg races the social-window
        // hard gate at dusk, and a fisher paddling hard for the bank reads naturally anyway.
        FishingVessels.drive(vessel, home.getX() + 0.5, home.getZ() + 0.5,
            FishingVessels.VESSEL_SPEED * 1.25);
    }

    /** A planned sailing trip: the shore tile to board at and the deep water to paddle to. */
    private record SailPlan(BlockPos launch, BlockPos target) {}

    /**
     * Plan a sailing trip — the PREFERRED way to fish once researched, because deep water bites ~2×
     * faster. Needs the {@link FishingVessels#FLAG_SAILING sailing} research, a vessel provider, a
     * {@link #SAIL_DEPTH_MIN}-deep open spot within {@link #SAIL_SCAN_RADIUS} of the drop-off, and a
     * walkable launch bank. Claims do NOT bound the trip — sailing is the sanctioned excursion past
     * the territory; the fixed scan radius around the town hall is the only leash. (It must be a
     * FIXED anchor: planning from the citizen's own position let every trip end ashore farther out
     * and plan the next one from there — fishers migrated down the coast indefinitely.)
     * {@code null} → fall back to shore casting.
     */
    private SailPlan planSailTrip() {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null || !(citizen.level() instanceof ServerLevel sl)) return null;
        if (!FishingVessels.hasProvider() || !FishingVessels.isSailingUnlocked(settlement)) return null;
        if (isBedtimeSoon()) return null;   // wind-down: don't launch a trip she'd immediately turn around
        BlockPos origin = settlement.townHallPos() != null ? settlement.townHallPos() : citizen.blockPosition();
        BlockPos deep = findDeepOpenWater(sl, origin);
        if (deep == null) return null;
        BlockPos launch = findLaunchStand(sl, origin, deep);
        if (launch == null) return null;
        return new SailPlan(launch, deep);
    }

    /** Water depth below {@code surface} for SAIL scoring — probes to {@link #SAIL_DEPTH_CAP}, deeper
     *  than the cast score's 8-block cap, so the true deep sea out-scores the first shelf off the
     *  beach (everything tying at 8 was why fishers anchored a boat-length from the sand). */
    private static int sailDepth(ServerLevel sl, BlockPos surface) {
        int depth = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos().set(surface);
        while (depth < SAIL_DEPTH_CAP && sl.getFluidState(p).is(FluidTags.WATER)) {
            depth++;
            p.move(0, -1, 0);
        }
        return depth;
    }

    /** Deepest / most open water surface within {@link #SAIL_SCAN_RADIUS} of the home anchor with depth ≥
     *  {@link #SAIL_DEPTH_MIN} — unbounded by claims, at least {@link #MIN_SAIL_DIST} out (a real trip),
     *  and {@link #BOAT_SEPARATION} clear of other fishers' claimed sea spots. Distance is a mild BONUS,
     *  so the fisher heads for open sea rather than hugging the first drop-off. */
    private BlockPos findDeepOpenWater(ServerLevel sl, BlockPos origin) {
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestScore = -Double.MAX_VALUE;
        for (int dx = -SAIL_SCAN_RADIUS; dx <= SAIL_SCAN_RADIUS; dx += SAIL_SCAN_STEP) {
            for (int dz = -SAIL_SCAN_RADIUS; dz <= SAIL_SCAN_RADIUS; dz += SAIL_SCAN_STEP) {
                for (int dy = 8; dy >= -24; dy--) {
                    c.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!sl.isLoaded(c)) break;
                    if (!isSurfaceWater(sl, c)) continue;
                    double distSq = origin.distSqr(c);
                    if (distSq >= (double) MIN_SAIL_DIST * MIN_SAIL_DIST
                            && !FisherShoreRegistry.isClaimedByOther(c, citizen.getUUID())
                            && !FisherShoreRegistry.isAnyClaimWithin(c, citizen.getUUID(), BOAT_SEPARATION)) {
                        int depth = sailDepth(sl, c);
                        int expanse = openExpanse(sl, c);
                        // Gate sailing on a genuinely open, navigable body: deep enough for the 2× bite
                        // zone AND wide enough that the raft has room to float (the 3×3 around the anchor
                        // is all surface water) and isn't a 1-block puddle / thin channel. This is THE fix
                        // for fishers paddling into a single source block and farmers wading after them.
                        if (depth >= SAIL_DEPTH_MIN && expanse >= MIN_SAIL_EXPANSE && hasRaftRoom(sl, c)) {
                            double score = depth * 30
                                         + expanse * 2
                                         + Math.sqrt(distSq) * 0.8;
                            if (score > bestScore) {
                                bestScore = score;
                                best = c.immutable();
                            }
                        }
                    }
                    break;   // only the topmost water surface of this column
                }
            }
        }
        return best;
    }

    /** Walkable shore stand to launch from: beside surface water near the drop-off, as close to the
     *  sail target as possible (so the fisher boards on the bank facing the deep water). Unlike
     *  shore-cast spots this is NOT claim-bound — the launch may sit on an unclaimed bank bordering
     *  the settlement. */
    private BlockPos findLaunchStand(ServerLevel sl, BlockPos origin, BlockPos target) {
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        Set<Long> tried = new HashSet<>();
        BlockPos best = null;
        double bestScore = -Double.MAX_VALUE;
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = 4; dy >= -12; dy--) {
                    c.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!sl.isLoaded(c)) break;
                    if (!isSurfaceWater(sl, c)) continue;
                    for (BlockPos stand : standsBeside(sl, c)) {
                        if (!tried.add(stand.asLong())) continue;
                        if (avoidTicks > 0 && stand.equals(avoidStand)) continue;
                        if (FisherShoreRegistry.isClaimedByOther(stand, citizen.getUUID())) continue;
                        double score = -stand.distSqr(target) - stand.distSqr(origin) * 0.05;
                        if (score > bestScore) {
                            bestScore = score;
                            best = stand.immutable();
                        }
                    }
                    break;   // topmost water surface of this column only
                }
            }
        }
        return best;
    }

    // ─── Spot finding ────────────────────────────────────────────────────────────────────────────

    /** A scored fishing spot: the stand tile, the open-water block its best arc reaches, and the
     *  comparison score (higher = better). The actual launch is re-solved at cast time from the
     *  citizen's real position ({@link #solveArc}), so no velocity is stored here. */
    private record CastSolution(BlockPos stand, BlockPos target, double score) {}

    /**
     * Best fishing spot near the citizen's <b>drop-off</b> (which the player places by the water).
     * Stand candidates are walkable tiles beside surface water, inside claims, off any farmer field,
     * not locked by another fisher and {@link #SHORE_SEPARATION} from any other's. Each candidate is
     * scored by the best arc it can throw ({@link #solveCast}); a stand with no arc that reaches any
     * open water at all — every arc blocked by terrain or overshooting onto land — is dropped. Highest
     * score wins, and since depth dominates the score this naturally favours piers casting short arcs
     * straight into deep water, while still letting a fisher work a shallow shore when that's all there is.
     */
    private CastSolution findSpot() {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null || !(citizen.level() instanceof ServerLevel sl)) return null;
        BlockPos origin = citizen.blockPosition();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        Set<Long> triedStands = new HashSet<>();
        CastSolution best = null;
        for (int dy = -4; dy <= 3; dy++) {
            for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    c.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!sl.isLoaded(c)) continue;
                    if (!isSurfaceWater(sl, c)) continue;
                    if (!inClaim(settlement, c)) continue;
                    for (BlockPos stand : standsBeside(sl, c)) {
                        if (!triedStands.add(stand.asLong())) continue;      // already evaluated this tile
                        if (avoidTicks > 0 && stand.equals(avoidStand)) continue;  // recently unreachable
                        if (isField(sl, settlement, stand)) continue;        // don't stand on / fish a field
                        if (FisherShoreRegistry.isClaimedByOther(stand, citizen.getUUID())) continue;
                        if (FisherShoreRegistry.isAnyClaimWithin(stand, citizen.getUUID(), SHORE_SEPARATION)) continue;
                        CastSolution sol = solveCast(sl, settlement, origin, stand);
                        if (sol == null) continue;
                        if (best == null || sol.score() > best.score()) best = sol;
                    }
                }
            }
        }
        return best;
    }

    /** Walkable tiles horizontally adjacent to {@code water} the fisher can stand on to cast. Returns
     *  every eligible neighbour (not just the first) so a pier tile poking out over the water is
     *  considered alongside the dry bank. */
    private static List<BlockPos> standsBeside(Level level, BlockPos water) {
        List<BlockPos> out = new ArrayList<>(4);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adj = water.relative(dir);
            if (WorkerPathing.isWalkable(level, adj)) out.add(adj.immutable());
            else if (WorkerPathing.isWalkable(level, adj.above())) out.add(adj.above().immutable());
        }
        return out;
    }

    /**
     * Finds the best validated cast from {@code stand}. For a fan of launch directions × arc shapes
     * it runs the bobber's own physics ({@link #simulateLanding}) and keeps the arc that settles in
     * the deepest, most open water, rejecting any that strike terrain or fall outside the claim. Depth
     * is only a <em>preference</em>, not a gate: a stand whose only reachable water is the shallow
     * shelf still scores (just low), so the fisher fishes the best water it can reach rather than
     * standing idle when no deep cast is available. This is a reachability+scoring probe from the
     * stand tile; the throw itself is re-solved from the citizen's true position in {@link #cast}.
     */
    private CastSolution solveCast(ServerLevel sl, Settlement settlement, BlockPos origin, BlockPos stand) {
        Vec3 from = new Vec3(stand.getX() + 0.5,
                             stand.getY() + citizen.getEyeHeight() - 0.1,
                             stand.getZ() + 0.5);
        int pier = pierBonus(sl, stand);   // how surrounded-by-water this stand is (a pier/peninsula tip)
        CastSolution best = null;
        for (int[] d : CAST_DIRS) {
            if (!waterNearby(sl, stand, d[0], d[1])) continue;  // nothing to cast at this way — skip the sims
            double len = Math.sqrt((double) (d[0] * d[0] + d[1] * d[1]));
            double ux = d[0] / len;
            double uz = d[1] / len;
            for (double vy : CAST_VY) {
                for (double sp : CAST_SPEED) {
                    Vec3 v0 = new Vec3(ux * sp, vy, uz * sp);
                    Landing land = simulateLanding(sl, from, v0);
                    if (land == null) continue;
                    BlockPos w = land.pos();
                    int hdx = w.getX() - stand.getX();
                    int hdz = w.getZ() - stand.getZ();
                    if (hdx * hdx + hdz * hdz > MAX_CAST_DIST * MAX_CAST_DIST) continue;
                    if (!inClaim(settlement, w)) continue;
                    if (!isSurfaceWater(sl, w)) continue;                 // landed under an overhang
                    // Expanse + depth dominate (cast toward the big lake, into its deep part), and a
                    // stand that juts into the water — a pier or peninsula tip — gets a standing bonus
                    // so it's preferred over a corner of shore that can reach similar water. A shorter
                    // flight breaks ties; proximity to the drop-off is only the final tiebreaker (its
                    // weight is tiny on purpose, so a better-but-farther spot still wins). Shallow
                    // water isn't rejected — it's just a low-scoring fallback so it never sits idle.
                    double score = (castScore(sl, w) + pier * 8) * 100.0
                                 - land.ticks() * 0.5
                                 - origin.distSqr(stand) * 0.01;
                    if (best == null || score > best.score()) {
                        best = new CastSolution(stand, w, score);
                    }
                }
            }
        }
        return best;
    }

    /** How "pier-like" a stand is: the number of its four horizontal neighbours that are water (at the
     *  stand's level or one below, to allow for the surface sitting a step down). A peninsula or pier
     *  tip poking into the lake scores 3–4; a flat stretch of bank scores 1. Used to nudge the fisher
     *  out onto a pier — which reaches deeper, more open water — rather than fishing from a shore corner. */
    private static int pierBonus(Level level, BlockPos stand) {
        int sides = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos n = stand.relative(dir);
            if (isWater(level, n) || isWater(level, n.below())) sides++;
        }
        return sides;
    }

    /** Cheap precheck: is there castable surface water within a few blocks of {@code stand} in the
     *  ({@code dx},{@code dz}) direction? Lets {@link #solveCast} skip the whole velocity sweep for
     *  directions that point inland, so widening the aim fan stays cheap. Scans a short vertical window
     *  because the water surface may sit a step below or above the stand tile. */
    private static boolean waterNearby(ServerLevel sl, BlockPos stand, int dx, int dz) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= 4; i++) {
            for (int dy = -2; dy <= 1; dy++) {
                p.set(stand.getX() + dx * i, stand.getY() + dy, stand.getZ() + dz * i);
                if (isSurfaceWater(sl, p)) return true;
            }
        }
        return false;
    }

    /**
     * Solves the actual throw at cast time: from the citizen's true eye {@code from}, searches the
     * flat-arc launch grid for the velocity that the bobber physics settle in {@code target}'s water
     * (or open water right beside it), preferring the closest landing. Returns null when none of the
     * arcs reach water from here — the citizen has drifted off the spot or a trunk blocks the line, so
     * {@link #cast} repositions rather than throwing the float onto the bank.
     */
    private Vec3 solveArc(ServerLevel sl, Vec3 from, BlockPos target) {
        double dx = target.getX() + 0.5 - from.x;
        double dz = target.getZ() + 0.5 - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0e-3) return null;
        double ux = dx / horiz;
        double uz = dz / horiz;
        Vec3 best = null;
        double bestErr = Double.MAX_VALUE;
        for (double vy : CAST_VY) {
            for (double sp : CAST_SPEED) {
                Vec3 v0 = new Vec3(ux * sp, vy, uz * sp);
                Landing land = simulateLanding(sl, from, v0);
                if (land == null) continue;                       // hit terrain / never reached water
                double err = land.pos().distSqr(target);          // landed in water — how close to target?
                if (err <= 9.0 && err < bestErr) { bestErr = err; best = v0; }
            }
        }
        return best;
    }

    /** Where a cast landed and how long it flew. */
    private record Landing(BlockPos pos, int ticks) {}

    /**
     * Replays {@link FisherBobber#tick}'s airborne integration for a launch of {@code v0} from
     * {@code from} and reports where the float would first settle. Returns the water block it sticks
     * in (the bobber sticks the first tick its block is water), or {@code null} if it strikes solid
     * terrain, leaves the world, or never reaches water within {@link #MAX_FLIGHT_TICKS}. Because the
     * same drag/gravity constants drive both this and the real bobber, a velocity that lands well
     * here lands well in the world.
     */
    private static Landing simulateLanding(ServerLevel sl, Vec3 from, Vec3 v0) {
        double x = from.x, y = from.y, z = from.z;
        double vx = v0.x, vy = v0.y, vz = v0.z;
        // Reuse one mutable position for the whole arc — this method is the hottest in the scan
        // (16 arcs × dozens of steps × hundreds of stands), so a fresh BlockPos per step would be the
        // single biggest source of GC churn. We only materialise an immutable BlockPos on a water hit.
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int t = 1; t <= MAX_FLIGHT_TICKS; t++) {
            p.set((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (sl.getFluidState(p).is(FluidTags.WATER)) return new Landing(p.immutable(), t);
            // Struck a trunk, wall or bank before reaching water → this arc thunks into a block.
            if (!sl.getBlockState(p).getCollisionShape(sl, p).isEmpty()) return null;
            if (y < sl.getMinBuildHeight()) return null;
            vx *= BOBBER_DRAG;
            vz *= BOBBER_DRAG;
            vy -= BOBBER_GRAVITY;
            x += vx;
            y += vy;
            z += vz;
        }
        return null;
    }

    /** True if {@code shore} is on a field the fisher shouldn't stand on or fish from: tilled farmland
     *  (or a crop growing on it), or anywhere inside a marked farmer field selection. Keeps fishers off
     *  the farmers' crops. */
    private boolean isField(ServerLevel level, Settlement settlement, BlockPos shore) {
        if (level.getBlockState(shore.below()).getBlock() instanceof net.minecraft.world.level.block.FarmBlock) return true;
        if (level.getBlockState(shore).getBlock() instanceof net.minecraft.world.level.block.CropBlock) return true;
        for (BlockSelection sel : BlockSelectionRegistry.get(level).getForSettlement(settlement.id())) {
            if (sel.completed() || sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!FarmerWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (shore.getX() >= sel.minX() && shore.getX() <= sel.maxX()
             && shore.getY() >= sel.minY() && shore.getY() <= sel.maxY()
             && shore.getZ() >= sel.minZ() && shore.getZ() <= sel.maxZ()) {
                return true;
            }
        }
        return false;
    }

    private static boolean inClaim(Settlement settlement, BlockPos pos) {
        // ChunkPos.asLong avoids allocating a ChunkPos object — this runs for every scanned water tile.
        return settlement.claimedChunks().contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** "Good fishing spot" score for a surface-water point. Rewards a big OPEN body of water (so the
     *  fisher casts toward the lake, not into a cramped near-shore pocket) and DEPTH (so within that
     *  water it picks the deep part). Expanse is weighted to dominate — a wide lake beats a small pool
     *  even if the pool is a touch deeper — which is what "fish toward the bigger lake" means; depth
     *  then separates spots of similar openness. */
    private static int castScore(Level level, BlockPos surface) {
        int depth = Math.min(waterDepthBelow(level, surface), MAX_DEPTH_SCORE);
        // Depth weighted heavily so a fisher strongly prefers the deepest reachable water (deep water
        // bites ~2× faster — see FisherBobber). Openness still separates similarly-deep spots.
        return depth * 30 + openExpanse(level, surface) * 2;
    }

    /** A measure of how large the open water body around {@code surface} is — NOT just whether the
     *  immediate tiles are water. A dense 3×3 core plus two sparse sample rings reaching ~5 blocks out
     *  lets a vast lake out-score a 5×5-sized pocket (which a plain 5×5 count couldn't tell apart),
     *  for the same number of block lookups. Max ≈ 9 + 16 = 25. */
    private static int openExpanse(Level level, BlockPos surface) {
        int open = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (isSurfaceWater(level, surface.offset(dx, 0, dz))) open++;
            }
        }
        for (int[] o : EXPANSE_SAMPLES) {
            if (isSurfaceWater(level, surface.offset(o[0], 0, o[1]))) open++;
        }
        return open;
    }

    /** The 3×3 of surface water centred on {@code surface} is fully open — the raft needs a tile to
     *  float on plus room around it to turn/approach. Rejects single source blocks and 1-wide channels
     *  that pass the depth check but leave the vessel jammed. */
    private static boolean hasRaftRoom(Level level, BlockPos surface) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!isSurfaceWater(level, surface.offset(dx, 0, dz))) return false;
            }
        }
        return true;
    }

    /** Water blocks straight down from {@code surface} (how deep the pool is there), capped at 8. */
    private static int waterDepthBelow(Level level, BlockPos surface) {
        int depth = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos().set(surface);
        while (depth < 8 && level.getFluidState(p).is(FluidTags.WATER)) {
            depth++;
            p.move(0, -1, 0);
        }
        return depth;
    }

    private static boolean isWater(Level level, BlockPos pos) {
        return pos != null && level.getFluidState(pos).is(FluidTags.WATER);
    }

    /** Water with air directly above — a castable open surface, not water under an overhang. */
    private static boolean isSurfaceWater(Level level, BlockPos pos) {
        if (!level.getFluidState(pos).is(FluidTags.WATER)) return false;
        BlockState above = level.getBlockState(pos.above());
        return above.isAir() || above.getCollisionShape(level, pos.above()).isEmpty();
    }

    /** Rough "open water" test for the treasure roll: the 5×5 around the bobber is water/air at its
     *  level with air above — no tight pools or under-cover spots. */
    private static boolean isOpenWater(Level level, BlockPos bobber) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos p = bobber.offset(dx, 0, dz);
                boolean watery = level.getFluidState(p).is(FluidTags.WATER) || level.getBlockState(p).isAir();
                if (!watery) return false;
                if (!level.getBlockState(p.above()).isAir()) return false;
            }
        }
        return true;
    }
}
