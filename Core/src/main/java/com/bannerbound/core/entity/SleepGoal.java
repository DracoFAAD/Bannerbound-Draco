package com.bannerbound.core.entity;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Home;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * Citizens go home at night and sleep in a bed until morning. Sleep preempts work goals (so a
 * gatherer mid-shift drops their tool, walks home, and lies down when night falls), and
 * yields itself when a panic situation kicks in — fire, mobs, etc. should still wake the
 * citizen up the way they would a vanilla villager.
 *
 * <p><b>Priority:</b> 2, sharing the slot with door/gate goals. Strictly less than the work
 * goals at 3 (so we preempt them — vanilla's WrappedGoal uses strict-less-than for
 * preemption), and strictly greater than panic at 1 (so PanicGoal preempts us). OpenDoorGoal
 * and OpenFenceGateGoal sit at 2 too, but they don't claim {@code Flag.MOVE}, so a
 * citizen walking home through a door still opens it on the way.
 *
 * <p><b>Bed selection:</b> the home's union is scanned at {@link #canUse()} for BedBlock HEAD
 * halves whose {@code OCCUPIED} state is false. Picks the nearest. Setting OCCUPIED on the
 * picked bed is what stops other citizens from converging on the same one — same trick
 * vanilla uses for villager-bed claims.
 *
 * <p><b>Stuck-bed safety:</b> if the bed is destroyed or replaced mid-night,
 * {@link #canContinueToUse()} returns false, the goal stops, and the citizen will re-evaluate
 * on the next tick — either finding a different free bed (still under this goal) or falling
 * through to patrol if none remain. We never leave a stale OCCUPIED state behind: every wake
 * path (morning, bed destroyed, panic preempt) runs through {@link #stop()}.
 */
@ApiStatus.Internal
public class SleepGoal extends Goal {
    /** Vanilla's "you can sleep now" window (dayTime mod 24000). 12541 is when monsters spawn /
     *  beds become usable; we round to 12500. 23459 is when natural wake happens. */
    private static final long NIGHT_START = 12_500L;
    private static final long NIGHT_END = 23_460L;
    /** Distance² at which we count as "at the bed" and call startSleeping. ~1.8 blocks. */
    private static final double BED_REACH_SQ = 3.25;
    /** Fallback reach² (~2.5 blocks) used once the citizen has stopped making progress. A bed with
     *  the roof right above it (only 1 block of headroom, or none) has no standable cell at the bed
     *  for a 2-tall citizen, so the navmesh can only get them <i>near</i> it — that's enough: vanilla
     *  {@code startSleeping} snaps the citizen onto the pillow regardless of the ceiling. Lets low,
     *  cosy huts (roof 1 block above the bed) work, not just the recommended 2-high ones. */
    private static final double BED_SETTLE_REACH_SQ = 6.25;
    /** Re-issue moveTo at most once a second — vanilla navigation can spam-call otherwise. */
    private static final int REPATH_INTERVAL = 20;
    /** In-memory bed reservations shared across every SleepGoal instance on the server. Without
     *  this, two citizens whose {@link #canUse} ticks land in the same game tick both see the
     *  same bed as {@code OCCUPIED=false} (the flag isn't written until they arrive and call
     *  {@code startSleeping}), so they both walk to it and pile on top of each other. A canUse
     *  that picks a bed adds it here; {@link #stop} removes it. ConcurrentHashMap.newKeySet so
     *  iterator/contains/add/remove are all thread-safe in case a non-main thread ever pokes it. */
    private static final java.util.Set<BlockPos> RESERVED_BEDS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final CitizenEntity citizen;
    private final double speedModifier;

    private BlockPos targetBed;
    /** True once {@link CitizenEntity#startSleeping} has been called for this run; on stop we
     *  use this to decide whether to call {@link CitizenEntity#stopSleeping} and clear OCCUPIED. */
    private boolean lying;
    private int repathCooldown;
    /** This run's bed is at the citizen's outpost (rough lodging) rather than their home. */
    private boolean atOutpost;
    /** Last tickCount an outpost-bed scan came up empty — skipped for a while after (the scan
     *  walks a chunk region, too heavy to repeat every canUse poll all night). */
    private int outpostBedRetryAt;

    public SleepGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    private static boolean isNight(ServerLevel sl) {
        long t = sl.getDayTime() % 24_000L;
        return t >= NIGHT_START && t < NIGHT_END;
    }

    /** Public clear of a bed's reservation. Used by {@code CitizenLifecycleEvents} when a
     *  sleeping citizen dies — vanilla doesn't guarantee {@link #stop} runs before entity
     *  removal, so the reservation would otherwise leak and block the bed for everyone else. */
    public static void releaseReservation(BlockPos bed) {
        if (bed != null) RESERVED_BEDS.remove(bed);
    }

    @Override
    public boolean canUse() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        if (!isNight(sl)) return false;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        // NIGHT WATCH: guards stand through the night while the policy is active — no bed for the
        // watch (their weary thought is the price; see PolicyEffects). If a reload left this guard
        // in vanilla's sleeping pose, break it here — the reclaim branch below won't run for us.
        if (citizen.isGuard() && settlement.hasPolicy(
                com.bannerbound.core.api.settlement.PolicyRegistry.NIGHT_WATCH)) {
            if (citizen.isSleeping()) citizen.stopSleeping();
            return false;
        }
        Home home = settlement.getHomeFor(citizen.getUUID());

        // ── Reload-recovery path ─────────────────────────────────────────────────────────────
        // Vanilla saves DATA_SLEEPING_POS_ID + the sleeping pose; this goal's transient
        // {@code lying}/{@code targetBed} fields are NOT saved. So a save-and-reload mid-sleep
        // leaves the citizen visually lying down with vanilla {@code isSleeping()} reporting
        // true, but our goal isn't running — so work / patrol / conversation goals claim the
        // MOVE flag and walk the citizen around while their pose stays SLEEPING. The fix: if
        // the citizen is already in vanilla's sleeping state on a bed in this home (or at this
        // citizen's outpost), reclaim the bed and skip straight to the {@code lying} state.
        if (citizen.isSleeping()) {
            BlockPos already = citizen.getSleepingPos().orElse(null);
            boolean reclaimable = already != null
                && ((home != null && home.valid() && isBedInHome(sl, home, already))
                    || isOutpostBed(sl, settlement, already));
            if (reclaimable) {
                targetBed = already.immutable();
                lying = true;
                RESERVED_BEDS.add(targetBed);
                return true;
            }
            // Sleeping but the bed isn't in this home (player teleport, home moved, bed
            // destroyed). Break out of the bad state so the regular pick path can run.
            citizen.stopSleeping();
        }

        // Outpost workers bed down ON SITE when the outpost offers a roofed free bed — beats the
        // nightly trek home, at the price of the ROUGH_LODGING thought (lower life conditions).
        BlockPos outpostBed = findOutpostBed(sl, settlement);
        if (outpostBed != null) {
            targetBed = outpostBed;
            atOutpost = true;
            return true;
        }

        if (home == null || !home.valid()) return false;
        targetBed = findFreeBed(sl, home);
        return targetBed != null;
    }

    /** True iff {@code bedPos} is a BedBlock HEAD and lies inside one of the home's selection
     *  boxes. Used by the reload-recovery branch in {@link #canUse} to validate the
     *  vanilla-restored sleeping position before reclaiming it. */
    private static boolean isBedInHome(ServerLevel sl, Home home, BlockPos bedPos) {
        BlockState bs = sl.getBlockState(bedPos);
        if (!(bs.getBlock() instanceof BedBlock)) return false;
        if (bs.getValue(BedBlock.PART) != BedPart.HEAD) return false;
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(sl);
        for (BlockSelection box : registry.findByHome(home.id())) {
            if (box.contains(bedPos)) return true;
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        if (!isNight(sl)) return false;
        if (targetBed == null) return false;
        // Bed destroyed/replaced/rotated to a different head pos → wake up.
        BlockState bs = sl.getBlockState(targetBed);
        if (!(bs.getBlock() instanceof BedBlock)) return false;
        if (bs.getValue(BedBlock.PART) != BedPart.HEAD) return false;
        // The bed must still belong to a VALID home this citizen lives in (or a valid outpost bed).
        // Without this a sleeper keeps lying there after the home goes invalid / they're evicted —
        // Homes.validate's eviction wakes them, but a still-running goal (lying=true) just holds them
        // in place. Self-terminating here runs stop(), which wakes + frees the bed cleanly.
        Settlement settlement = citizen.getSettlement();
        boolean homeOk = false;
        if (settlement != null) {
            Home home = settlement.getHomeFor(citizen.getUUID());
            homeOk = home != null && home.valid() && isBedInHome(sl, home, targetBed);
        }
        boolean outpostOk = settlement != null && isOutpostBed(sl, settlement, targetBed);
        return homeOk || outpostOk;
    }

    @Override
    public void start() {
        if (targetBed == null) return;
        repathCooldown = 0;
        citizen.getNavigation().moveTo(
            targetBed.getX() + 0.5, targetBed.getY(), targetBed.getZ() + 0.5, speedModifier);
    }

    @Override
    public void tick() {
        if (targetBed == null) return;
        if (!(citizen.level() instanceof ServerLevel sl)) return;

        if (lying) {
            // Already sleeping — vanilla LivingEntity handles the pose, position lock, time skip
            // logic. We just hold the goal open until canContinueToUse() returns false.
            return;
        }

        double dx = (targetBed.getX() + 0.5) - citizen.getX();
        double dy = targetBed.getY() - citizen.getY();
        double dz = (targetBed.getZ() + 0.5) - citizen.getZ();
        citizen.getLookControl().setLookAt(
            targetBed.getX() + 0.5, targetBed.getY() + 0.5, targetBed.getZ() + 0.5);

        double distSq = dx * dx + dy * dy + dz * dz;
        // Lie down if we walked right up to the bed, OR if we've gotten as close as the navmesh
        // allows (navigation done) and we're still reasonably near — covers beds under a low roof
        // that a standing citizen can't path directly onto.
        boolean settledNearby = distSq <= BED_SETTLE_REACH_SQ && citizen.getNavigation().isDone();
        if (distSq <= BED_REACH_SQ || settledNearby) {
            citizen.getNavigation().stop();
            citizen.startSleeping(targetBed);
            // Mark the bed occupied so other citizens won't pick the same one. We set OCCUPIED
            // on the HEAD half only — that's where vanilla checks it for both renderer + claim
            // semantics. The FOOT half is left as-is, matching vanilla's BedBlock.setPlacedBy
            // behaviour.
            BlockState bs = sl.getBlockState(targetBed);
            if (bs.getBlock() instanceof BedBlock && bs.getValue(BedBlock.PART) == BedPart.HEAD) {
                sl.setBlock(targetBed, bs.setValue(BedBlock.OCCUPIED, true), Block.UPDATE_ALL);
            }
            lying = true;
            // Rough lodging: sleeping at the outpost beats the trek home, but it's a draughty cot
            // on wild land — the worker wakes with a mood debuff (lower life conditions on site).
            if (atOutpost && citizen.getThoughts() != null) {
                citizen.getThoughts().add(com.bannerbound.core.social.ThoughtKind.ROUGH_LODGING,
                    null, sl.getGameTime(), sl.random);
                citizen.recomputeHappiness();
            }
            return;
        }
        if (--repathCooldown <= 0 && citizen.getNavigation().isDone()) {
            citizen.getNavigation().moveTo(
                targetBed.getX() + 0.5, targetBed.getY(), targetBed.getZ() + 0.5, speedModifier);
            repathCooldown = REPATH_INTERVAL;
        }
    }

    @Override
    public void stop() {
        if (lying) {
            citizen.stopSleeping();
            if (citizen.level() instanceof ServerLevel sl && targetBed != null) {
                BlockState bs = sl.getBlockState(targetBed);
                if (bs.getBlock() instanceof BedBlock && bs.getValue(BedBlock.PART) == BedPart.HEAD) {
                    sl.setBlock(targetBed, bs.setValue(BedBlock.OCCUPIED, false), Block.UPDATE_ALL);
                }
            }
            lying = false;
        }
        // Release the reservation regardless of whether we ever actually lay down. A goal that
        // gets preempted (panic, dawn while still walking) needs to free its bed too so the
        // next homeless citizen can claim it.
        if (targetBed != null) {
            RESERVED_BEDS.remove(targetBed);
        }
        targetBed = null;
        repathCooldown = 0;
        atOutpost = false;
    }

    // ─── Outpost lodging: a roofed free bed in the citizen's outpost chunk ───────────────────────

    /**
     * A free, unreserved, ROOFED bed in this citizen's outpost chunk ({@link
     * CitizenEntity#getOutpostSite}), or null. Roof = any motion-blocking block within a few
     * blocks above the bed head — no walls required, per the outpost lodging rule. The scan walks
     * a chunk-sized region, so a miss is cached briefly ({@link #outpostBedRetryAt}) rather than
     * re-scanned on every canUse poll through the night.
     */
    private BlockPos findOutpostBed(ServerLevel sl, Settlement settlement) {
        BlockPos site = citizen.getOutpostSite();
        if (site == null) return null;
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(site);
        if (!settlement.workingClaims().contains(cp.toLong())) return null;   // outpost fell
        if (!sl.hasChunk(cp.x, cp.z)) return null;
        if (citizen.tickCount < outpostBedRetryAt) return null;

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        double cx = citizen.getX(), cy = citizen.getY(), cz = citizen.getZ();
        int minY = site.getY() - 12;
        int maxY = site.getY() + 12;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = cp.getMinBlockX(); x <= cp.getMaxBlockX(); x++) {
            for (int z = cp.getMinBlockZ(); z <= cp.getMaxBlockZ(); z++) {
                for (int y = minY; y <= maxY; y++) {
                    m.set(x, y, z);
                    BlockState bs = sl.getBlockState(m);
                    if (!(bs.getBlock() instanceof BedBlock)) continue;
                    if (bs.getValue(BedBlock.PART) != BedPart.HEAD) continue;
                    if (bs.getValue(BedBlock.OCCUPIED)) continue;
                    if (RESERVED_BEDS.contains(m)) continue;
                    if (!hasRoof(sl, m)) continue;
                    double ddx = x + 0.5 - cx, ddy = y - cy, ddz = z + 0.5 - cz;
                    double d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (d2 < bestDistSq) {
                        bestDistSq = d2;
                        best = m.immutable();
                    }
                }
            }
        }
        if (best == null) {
            outpostBedRetryAt = citizen.tickCount + 200;   // nothing roofed/free — retry in ~10s
            return null;
        }
        RESERVED_BEDS.add(best);
        return best;
    }

    /** Roofed = any motion-blocking block within 6 above the bed head. Walls deliberately not
     *  required — a lean-to over the cot is enough for outpost lodging. */
    private static boolean hasRoof(ServerLevel sl, BlockPos bed) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy <= 6; dy++) {
            m.set(bed.getX(), bed.getY() + dy, bed.getZ());
            if (sl.getBlockState(m).blocksMotion()) return true;
        }
        return false;
    }

    /** Reload-recovery twin of {@link #isBedInHome}: the restored sleeping pos is a bed HEAD in
     *  one of this settlement's working-claimed (outpost) chunks. */
    private static boolean isOutpostBed(ServerLevel sl, Settlement settlement, BlockPos bedPos) {
        BlockState bs = sl.getBlockState(bedPos);
        if (!(bs.getBlock() instanceof BedBlock)) return false;
        if (bs.getValue(BedBlock.PART) != BedPart.HEAD) return false;
        return settlement.workingClaims().contains(
            new net.minecraft.world.level.ChunkPos(bedPos).toLong());
    }

    /** Nearest unoccupied AND unreserved BedBlock HEAD in the home's selection union.
     *  {@code null} if every bed is already taken — caller treats null as "no goal." Adds the
     *  chosen bed to {@link #RESERVED_BEDS} so a concurrent canUse on another citizen this same
     *  tick won't pick it. */
    private BlockPos findFreeBed(ServerLevel sl, Home home) {
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(sl);
        List<BlockSelection> boxes = registry.findByHome(home.id());
        if (boxes.isEmpty()) return null;
        Set<BlockPos> seen = new HashSet<>();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        double cx = citizen.getX(), cy = citizen.getY(), cz = citizen.getZ();
        for (BlockSelection box : boxes) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!seen.add(p)) continue;
                        if (RESERVED_BEDS.contains(p)) continue; // claimed by another citizen
                        BlockState bs = sl.getBlockState(p);
                        if (!(bs.getBlock() instanceof BedBlock)) continue;
                        if (bs.getValue(BedBlock.PART) != BedPart.HEAD) continue;
                        if (bs.getValue(BedBlock.OCCUPIED)) continue;
                        double ddx = p.getX() + 0.5 - cx;
                        double ddy = p.getY() - cy;
                        double ddz = p.getZ() + 0.5 - cz;
                        double d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                        if (d2 < bestDistSq) {
                            bestDistSq = d2;
                            best = p.immutable();
                        }
                    }
                }
            }
        }
        if (best != null) RESERVED_BEDS.add(best);
        return best;
    }
}
