package com.bannerbound.antiquity.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.core.api.fisher.FisherShoreRegistry;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.DropOffContainers;
import com.bannerbound.core.entity.GathererWorkGoal;
import com.bannerbound.core.entity.WorkerPathing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Spear-fisher {@link GathererWorkGoal} — the primitive precursor to the rod {@link
 * com.bannerbound.core.entity.FisherWorkGoal fisher}. It walks the shoreline to a stand beside open
 * water (reusing the fisher's {@link FisherShoreRegistry} soft-locks and shore-walking nav), then —
 * instead of casting a bobber — it hunts a <b>real</b> {@link AbstractFish} in the water: it throws a
 * rope-tethered {@link SpearProjectile} at the fish and <b>reels the catch back to itself by the
 * rope</b> (it never walks to the body, exactly like the player's spear fishing). On arrival the
 * floating {@link SpearedFishEntity} deposits the fish into the citizen's drop-off; a miss reels the
 * empty rope-tethered spear home so it's never lost.
 *
 * <p>The spear is a <b>reusable equipped tool</b>: each throw is a copy of the job tool with the rope
 * set directly on the projectile (it does NOT use {@code SpearItem.releaseUsing}), so no fiber rope is
 * ever consumed. Yield is intentionally <b>fish-gated</b> — when no fish are within reach the worker
 * abandons the dry spot (briefly blacklisting it) and drops to patrol, wandering the shore until fish
 * appear. Registered into Core via {@code CitizenJobRegistry} in {@code BannerboundAntiquity}.
 */
public class SpearFisherWorkGoal extends GathererWorkGoal {
    /** Per-citizen job id (registered with {@code CitizenJobRegistry} / {@code WorkstationUnlocks}). */
    public static final String JOB_TYPE_ID = "spear_fishers_post";

    private static final int SCAN_RADIUS = 48;        // how far from the drop-off we look for fish / a shore stand
    private static final int SCAN_VERT_UP = 8;        // search this far ABOVE the drop-off for the water surface
    private static final int SCAN_VERT_DOWN = 24;     // ...and this far below (a basket can sit well up a bank)
    private static final int SHORE_SEPARATION = 3;    // min blocks between two spear fishers' stands
    private static final int RESCAN_COOLDOWN_TICKS = 40;
    private static final double ARRIVE_SQ = 1.6 * 1.6;
    private static final int WALK_TIMEOUT_TICKS = 200;
    private static final int AVOID_TICKS = 2400;      // a stand we abandoned (unreachable / fishless) is skipped ~2 min

    private static final double THROW_RANGE = 16.0;   // furthest fish we'll spear from the stand
    private static final double THROW_DAMAGE = 4.0;   // matches a wood/bone spear's hit
    private static final double SPEAR_SPEED = 1.6;    // launch velocity (slower than an arrow; it glides in water)
    private static final double THROW_INACCURACY = 0.5;   // small spread (drop is now solved, so misses are rare)
    // The spear is an AbstractArrow: each tick it moves, then its velocity is scaled by friction, then
    // gravity is subtracted from Y. Friction is 0.99 in BOTH air and water (the spear overrides its
    // water inertia to 0.99 to glide), so a single constant-physics arc sim models the whole flight.
    private static final double THROW_FRICTION = 0.99;    // AbstractArrow air & (overridden) water inertia
    private static final double THROW_GRAVITY = 0.05;     // AbstractArrow per-tick gravity (blocks/tick^2)
    private static final int THROW_SIM_TICKS = 64;        // cap on the arc simulation (well past THROW_RANGE)
    private static final int WINDUP_TICKS = 40;           // raise-spear (UseAnim.SPEAR) pose held ~2s before the throw
    private static final int REEL_DELAY_TICKS = 20;       // let the impaled catch sit ~1s before reeling it in
    private static final int THROW_COOLDOWN_TICKS = 30;   // beat between throws
    private static final int THROW_SETTLE_TICKS = 30;     // wait this long for a hit→catch before treating it as a miss
    private static final int STAMINA_PER_CATCH = 10;      // stamina spent per delivered fish
    private static final int REEL_TIMEOUT_TICKS = 200;    // safety cap on a single reel
    private static final double REEL_RANGE = 16.0;        // how far to look for our tethered catch
    private static final int FISH_RESCAN_TICKS = 10;      // when no fish in range, re-hunt for a fishy stand this often
    private static final int STAND_CACHE_TICKS = 1200;    // rebuild the (expensive) shore-stand geometry scan this rarely
    private static final int STAND_CACHE_CAP = 64;        // keep at most this many candidate stands (best generic score)

    private enum Phase { SEEK, WINDUP, AIM_THROW, REEL }

    private Phase phase = Phase.SEEK;
    private BlockPos shorePos;   // the stand we fish from
    private BlockPos waterPos;   // a water block beside the stand (what we look at while seeking)
    private int phaseAge;
    private int rescanCooldown;
    private int throwCooldown;
    private int noFishTicks;
    private BlockPos avoidStand;
    private int avoidTicks;
    private SpearProjectile spear;   // the projectile we last threw (tracked for the miss-recovery reel)
    private boolean caught;          // a catch appeared this reel (→ spend stamina on completion)
    private int catchSeenAge = -1;   // phaseAge when the impaled catch first appeared (for the reel delay)
    private boolean reelSoundPlayed; // the reel sound plays once per reel, when the pull-in starts
    private AbstractFish windupTarget;  // the fish we're winding up to spear (raise-spear pose)
    private int windupTicks;            // ticks left in the raise-spear windup before the throw
    private final List<Spot> standCache = new ArrayList<>();  // cached shore stands near the fisher
    private int standCacheCooldown;     // ticks until the shore-stand geometry is rescanned
    private BlockPos cacheOrigin;       // position the cache was built around (rebuild when she LEAVES
                                        // that 16-block cell — never on ordinary within-cell movement)
    private int dbgWaterSeen, dbgClaimedWater;  // last scan: surface-water columns seen / of those, claimed

    // TEMP diagnostics — throttled so they don't spam. Remove once the AI is verified in-world.
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private int dbgCooldown;

    private void dbg(String msg) {
        if (dbgCooldown > 0) { dbgCooldown--; return; }
        dbgCooldown = 40;
        LOG.info("[SpearFisher id={}] {}", citizen.getId(), msg);
    }

    public SpearFisherWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
        // Stagger the first scan so a batch of spear fishers assigned at once don't all run findSpot
        // on the same tick.
        this.rescanCooldown = citizen.getId() % 100;
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    private Container resolveDepot() {
        return DropOffContainers.resolveJobDepot(citizen);
    }

    /** The spear to throw / show: the equipped job tool, or a default bone spear when working
     *  bare-handed in anarchy. */
    private ItemStack currentSpearStack() {
        ItemStack tool = citizen.getJobTool();
        return tool.isEmpty() ? new ItemStack(BannerboundAntiquity.BONE_SPEAR.get()) : tool;
    }

    @Override
    protected boolean canStartWork() {
        citizen.validateJobStorage();
        if (!citizen.isGatherJobReady(JOB_TYPE_ID)) {
            dbg("not ready: job=" + citizen.getJobType() + " tool=" + citizen.hasJobTool()
                + " dropOff=" + citizen.getDropOff() + " anarchy=" + citizen.isAnarchy());
            return false;
        }
        // A catch is unknown until reeled in, so we need a guaranteed-free slot or we'd lose it.
        Container depot = resolveDepot();
        if (depot == null || !DropOffContainers.hasFreeSlot(depot)) {
            dbg("no depot / full (depot=" + depot + ")");
            return false;
        }

        // Keep a still-valid stand (walkable + our claim held).
        if (shorePos != null && WorkerPathing.isWalkable(citizen.level(), shorePos)
                && !FisherShoreRegistry.isClaimedByOther(shorePos, citizen.getUUID())) {
            FisherShoreRegistry.tryClaim(citizen.getUUID(), shorePos);
            return true;
        }
        shorePos = null;
        waterPos = null;
        if (rescanCooldown-- > 0) return false;

        // Best shore stand beside actual fish; otherwise wait staged at a good generic shore.
        Spot fishy = findStandNearFish();
        if (fishy != null) {
            return claimShore(fishy);
        }
        Spot wait = findGenericShore();
        if (wait == null) {
            dbg("findSpot=null: dropOff=" + citizen.getDropOff() + " cacheStands=" + standCache.size()
                + " surfaceWaterColumns=" + dbgWaterSeen + " (of those claimed=" + dbgClaimedWater + ")"
                + " | scan " + SCAN_RADIUS + "h x [-" + SCAN_VERT_DOWN + ",+" + SCAN_VERT_UP + "]v"
                + (dbgWaterSeen > 0 && dbgClaimedWater == 0
                    ? "  >> water found but UNCLAIMED — claim those chunks" : ""));
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        return claimShore(wait);
    }

    /** Claim a shore stand and walk to it. */
    private boolean claimShore(Spot spot) {
        if (!FisherShoreRegistry.tryClaim(citizen.getUUID(), spot.stand())) {
            rescanCooldown = 5;
            return false;
        }
        shorePos = spot.stand();
        waterPos = spot.water();
        phase = Phase.SEEK;
        phaseAge = 0;
        dbg("claimed stand " + shorePos + " beside water " + waterPos + " → walking there");
        return true;
    }

    @Override
    protected boolean canKeepWorking() {
        if (!citizen.isGatherJobReady(JOB_TYPE_ID)) return false;
        Container depot = resolveDepot();
        if (depot == null || !DropOffContainers.hasFreeSlot(depot)) return false;
        // The stand going invalid (player broke the pier) or being abandoned for lack of fish (shorePos
        // nulled by abandonSpot) drops us back to patrol; we re-acquire a spot on the next poll.
        return shorePos != null && WorkerPathing.isWalkable(citizen.level(), shorePos);
    }

    @Override
    public void start() {
        citizen.setWorking(true);
        citizen.setItemSlot(EquipmentSlot.MAINHAND, currentSpearStack().copy());
        citizen.setAvoidWaterPathing(true);   // walk the shore/pier, don't swim a shortcut
        phaseAge = 0;
        spear = null;
        caught = false;
        phase = Phase.SEEK;
        if (shorePos != null) {
            citizen.getNavigation().moveTo(
                shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
        }
    }

    @Override
    public void stop() {
        citizen.setWorking(false);
        stopWindupPose();
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.setAvoidWaterPathing(false);
        // Don't strand a thrown spear: a still-flying tethered projectile is just a copy of the
        // reusable tool, so discarding it loses nothing and avoids clutter.
        if (spear != null && spear.isAlive()) spear.discard();
        // Don't abandon a fish on the line: if we're interrupted (e.g. job switch) before tickReel
        // gets us reeling, kick off the reel here so the floating catch homes in and deposits on its
        // own independent tick rather than floating until its lifetime timer discards it.
        SpearedFishEntity pendingCatch = findMyCatch();
        if (pendingCatch != null && !pendingCatch.isReeling()) {
            pendingCatch.startReeling();
            playReelSound();
        }
        spear = null;
        FisherShoreRegistry.release(citizen.getUUID());
        shorePos = null;
        waterPos = null;
        phase = Phase.SEEK;
    }

    @Override
    public void tick() {
        if (!citizen.isGatherJobReady(JOB_TYPE_ID) || shorePos == null) return;
        phaseAge++;
        if (avoidTicks > 0) avoidTicks--;
        if (throwCooldown > 0) throwCooldown--;
        switch (phase) {
            case SEEK -> tickSeek();
            case AIM_THROW -> tickAimThrow();
            case WINDUP -> tickWindup();
            case REEL -> tickReel();
        }
    }

    private void tickSeek() {
        // Face the water we're walking to.
        if (waterPos != null) {
            citizen.getLookControl().setLookAt(
                waterPos.getX() + 0.5, waterPos.getY() + 0.5, waterPos.getZ() + 0.5);
        }
        double d = citizen.position().distanceToSqr(
            shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5);
        if (d <= ARRIVE_SQ && !citizen.isInWater()) {
            citizen.getNavigation().stop();
            phase = Phase.AIM_THROW;
            phaseAge = 0;
            return;
        }
        if (citizen.getNavigation().isDone()) {
            if (phaseAge > WALK_TIMEOUT_TICKS) { abandonSpot(); return; }
            citizen.getNavigation().moveTo(
                shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
        }
    }

    private void tickAimThrow() {
        if (throwCooldown > 0) return;
        AbstractFish fish = findTargetFish();
        if (fish != null) {
            noFishTicks = 0;
            // Raise the spear (vanilla UseAnim.SPEAR pose) for a beat before throwing — same windup the
            // player does. The held bone spear's UseAnim drives the humanoid arm pose client-side.
            windupTarget = fish;
            windupTicks = skilledWorkTicks(WINDUP_TICKS); // a practiced spear-fisher raises and throws quicker
            citizen.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
            citizen.getLookControl().setLookAt(fish);
            phase = Phase.WINDUP;
            phaseAge = 0;
            dbg("winding up at fish " + fish.getType() + " at " + fish.blockPosition());
            return;
        }
        // No fish reachable from this stand. Don't camp it — periodically re-hunt for a stand beside
        // ACTUAL fish elsewhere in range and relocate there, so he follows fish that spawn/move to
        // another shore. If no fish are reachable anywhere, just keep waiting (the next scan catches
        // fish that respawn). He only leaves the water entirely if the stand itself goes invalid.
        noFishTicks++;
        if (noFishTicks % FISH_RESCAN_TICKS == 0) {
            Spot fishy = findStandNearFish();
            if (fishy != null && !fishy.stand().equals(shorePos)) {
                dbg("relocating toward fish: " + shorePos + " -> " + fishy.stand());
                relocateTo(fishy);
                return;
            }
            dbg("at stand " + shorePos + ", no fish reachable in range (waiting)");
        }
    }

    /** Hold the raise-spear pose, tracking the fish; release into a throw after {@link #WINDUP_TICKS},
     *  or abort back to aiming if the fish swims off / dies. */
    private void tickWindup() {
        if (windupTarget == null || !windupTarget.isAlive() || !windupTarget.isInWater()
                || windupTarget.distanceToSqr(citizen) > THROW_RANGE * THROW_RANGE) {
            stopWindupPose();
            phase = Phase.AIM_THROW;
            phaseAge = 0;
            return;
        }
        citizen.getLookControl().setLookAt(windupTarget);
        if (--windupTicks > 0) return;
        AbstractFish fish = windupTarget;
        stopWindupPose();
        dbg("throwing spear at fish " + fish.getType());
        throwSpearAt(fish);
        phase = Phase.REEL;
        phaseAge = 0;
    }

    /** End the raise-spear use pose (clears the client-side UseAnim.SPEAR arm pose). */
    private void stopWindupPose() {
        if (citizen.isUsingItem()) citizen.stopUsingItem();
        windupTarget = null;
        windupTicks = 0;
    }

    /** Claim a new stand (replacing the current claim) and walk to it — used to follow fish without
     *  blacklisting the old spot (unlike {@link #abandonSpot}). */
    private void relocateTo(Spot spot) {
        stopWindupPose();
        if (!FisherShoreRegistry.tryClaim(citizen.getUUID(), spot.stand())) return;
        shorePos = spot.stand();
        waterPos = spot.water();
        noFishTicks = 0;
        phase = Phase.SEEK;
        phaseAge = 0;
        citizen.getNavigation().moveTo(
            shorePos.getX() + 0.5, shorePos.getY(), shorePos.getZ() + 0.5, skilledSpeed());
    }

    private void throwSpearAt(AbstractFish fish) {
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        citizen.getNavigation().stop();
        citizen.getLookControl().setLookAt(fish);
        SpearProjectile s = new SpearProjectile(sl, citizen, currentSpearStack().copy(), THROW_DAMAGE);
        // Lead a slow swimmer by roughly where it'll be during the spear's flight (straight-line time;
        // the arc is a touch longer, but fish drift slowly enough that this is well within the hitbox).
        Vec3 start = s.position();
        double dist = Math.sqrt(fish.distanceToSqr(citizen));
        double leadTicks = dist / SPEAR_SPEED;
        Vec3 aim = fish.position().add(0.0, fish.getBbHeight() * 0.5, 0.0)
            .add(fish.getDeltaMovement().scale(leadTicks));
        // Solve a launch that COMPENSATES for the spear's gravity drop so it arrives on the fish. Aiming
        // straight at the target lands ~2.5 blocks low at full range (the spear falls 0.05/tick^2) — that
        // systematic low miss is the inaccuracy the player reported. Falls back to a direct line if the
        // target is somehow out of ballistic reach (it never is within THROW_RANGE at SPEAR_SPEED).
        Vec3 launch = solveThrowVelocity(start, aim);
        if (launch == null) launch = aim.subtract(start);
        s.shoot(launch.x, launch.y, launch.z, (float) SPEAR_SPEED, (float) THROW_INACCURACY);
        s.setRopeTethered(true);   // tether directly — no fiber rope consumed (reusable tool)
        s.markNoRecovery();        // throwaway copy of the equipped spear — never droppable (no dup)
        sl.addFreshEntity(s);
        sl.playSound(null, citizen.blockPosition(),
            BannerboundAntiquity.SPEAR_THROW_ROPE_SOUND.get(), SoundSource.NEUTRAL, 0.6F, 1.0F);
        this.spear = s;
        this.caught = false;
        this.reelSoundPlayed = false;
        // Empty the hand the instant he throws — the spear IS the projectile now. Reads as a single
        // spear; it's put back in hand once the catch / empty spear is reeled home (finishReel).
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    /**
     * A launch velocity (magnitude {@link #SPEAR_SPEED}) from {@code start} that lands on {@code target}
     * under the spear's flight physics. Scans launch pitches from flat to a high lob and keeps the one
     * whose simulated arc passes closest to the target's height at the target's range, preferring the
     * flattest (fastest, least lead error) on a tie. Returns {@code null} if nothing reaches it.
     */
    private static Vec3 solveThrowVelocity(Vec3 start, Vec3 target) {
        Vec3 delta = target.subtract(start);
        double horiz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (horiz < 1.0e-3) {   // straight up / down — no horizontal component to solve
            return new Vec3(0.0, Math.signum(delta.y) * SPEAR_SPEED, 0.0);
        }
        Vec3 horizDir = new Vec3(delta.x / horiz, 0.0, delta.z / horiz);
        double bestErr = Double.MAX_VALUE;
        double bestPitch = Double.NaN;
        // Flat (-35°) to lofted (+70°) in 1° steps — fine enough that the residual height error is
        // well under a fish's hitbox. Iterating low→high and only replacing on a strict improvement
        // makes the FLATTEST viable pitch win, i.e. a direct shot is preferred over a needless lob.
        for (int deg = -35; deg <= 70; deg++) {
            double pitch = Math.toRadians(deg);
            double yAtTarget = simulateArcHeight(start.y, pitch, horiz);
            if (Double.isNaN(yAtTarget)) continue;   // arc fell short of the target's range
            double err = Math.abs(yAtTarget - target.y);
            if (err < bestErr - 1.0e-4) {
                bestErr = err;
                bestPitch = pitch;
            }
        }
        if (Double.isNaN(bestPitch)) return null;
        return horizDir.scale(Math.cos(bestPitch) * SPEAR_SPEED)
            .add(0.0, Math.sin(bestPitch) * SPEAR_SPEED, 0.0);
    }

    /**
     * Height (world Y) the spear reaches when its horizontal travel first equals {@code horizDist},
     * launched from {@code startY} at {@code pitch} radians and {@link #SPEAR_SPEED}, or {@code NaN} if
     * it never gets that far. Each tick advances by the current velocity, then applies friction, then
     * gravity — the {@link net.minecraft.world.entity.projectile.AbstractArrow} order — so the
     * simulated drop matches the real projectile.
     */
    private static double simulateArcHeight(double startY, double pitch, double horizDist) {
        double vh = Math.cos(pitch) * SPEAR_SPEED;
        double vy = Math.sin(pitch) * SPEAR_SPEED;
        double h = 0.0;
        double y = startY;
        for (int t = 0; t < THROW_SIM_TICKS; t++) {
            double prevH = h;
            double prevY = y;
            h += vh;
            y += vy;
            if (h >= horizDist) {   // crossed the target's range this tick → interpolate the height
                double frac = (horizDist - prevH) / Math.max(1.0e-6, h - prevH);
                return prevY + (y - prevY) * frac;
            }
            vh *= THROW_FRICTION;
            vy = vy * THROW_FRICTION - THROW_GRAVITY;
            if (vh < 1.0e-4) break;   // horizontal speed bled out before reaching the target
        }
        return Double.NaN;
    }

    /** The reel cue, played once when the pull-in starts (mirrors the player's reel sound). */
    private void playReelSound() {
        if (reelSoundPlayed) return;
        reelSoundPlayed = true;
        if (citizen.level() instanceof ServerLevel sl) {
            sl.playSound(null, citizen.blockPosition(), BannerboundAntiquity.SPEAR_REEL_SOUND.get(),
                SoundSource.NEUTRAL, 0.9F, 0.8F + citizen.getRandom().nextFloat() * 0.2F);
        }
    }

    private void tickReel() {
        // A hit converts the projectile into a tethered catch (SpearFishingEvents). Pull it home; on
        // arrival it deposits the fish into our drop-off (SpearedFishEntity.grantToDepot).
        SpearedFishEntity catchEntity = findMyCatch();
        if (catchEntity != null) {
            caught = true;
            if (catchSeenAge < 0) catchSeenAge = phaseAge;          // first sighting of the impaled fish
            if (phaseAge - catchSeenAge < REEL_DELAY_TICKS) return; // let it sit a beat before reeling
            if (!catchEntity.isReeling()) { catchEntity.startReeling(); playReelSound(); }
            if (phaseAge - catchSeenAge > REEL_TIMEOUT_TICKS) finishReel();
            return;
        }
        // No catch (yet). If the spear is still in flight, give the throw a moment to resolve; once the
        // settle window passes with no catch it was a miss → reel the empty rope-tethered spear home.
        if (spear != null && spear.isAlive()) {
            if (phaseAge >= THROW_SETTLE_TICKS && !spear.isReeling()) { spear.startReeling(); playReelSound(); }
            if (phaseAge > REEL_TIMEOUT_TICKS) finishReel();
            return;
        }
        // Spear gone and no catch tethered → the reel finished (catch deposited, or empty spear vanished).
        finishReel();
    }

    private void finishReel() {
        if (caught) {
            citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "fish");
            citizen.consumeStamina(STAMINA_PER_CATCH);   // a delivered catch is hard work
            // SpearedFishEntity has already deposited the actual food item; storage handles food.
        }
        // The spear / catch has been reeled home: put the spear back in hand so he "has it again".
        citizen.setItemSlot(EquipmentSlot.MAINHAND, currentSpearStack().copy());
        spear = null;
        caught = false;
        catchSeenAge = -1;
        reelSoundPlayed = false;
        phase = Phase.AIM_THROW;                 // re-aim from the same stand
        phaseAge = 0;
        throwCooldown = THROW_COOLDOWN_TICKS;
    }

    /** Our floating catch (a SpearedFishEntity tethered to this citizen), within reel range, or null. */
    private SpearedFishEntity findMyCatch() {
        if (!(citizen.level() instanceof ServerLevel sl)) return null;
        AABB area = citizen.getBoundingBox().inflate(REEL_RANGE);
        for (SpearedFishEntity e : sl.getEntitiesOfClass(SpearedFishEntity.class, area,
                e -> e.isTethered()
                    && e.getOwnerUUID().map(citizen.getUUID()::equals).orElse(false))) {
            return e;
        }
        return null;
    }

    /** Nearest reachable live fish in water within throw range of the CITIZEN (works from a shore stand
     *  or from the raft seat). */
    private AbstractFish findTargetFish() {
        if (!(citizen.level() instanceof ServerLevel sl) || shorePos == null) return null;
        AABB area = citizen.getBoundingBox().inflate(THROW_RANGE);
        AbstractFish best = null;
        double bestSq = Double.MAX_VALUE;
        for (AbstractFish f : sl.getEntitiesOfClass(AbstractFish.class, area,
                f -> f.isAlive() && f.isInWater())) {
            double dSq = f.distanceToSqr(citizen);
            if (dSq > THROW_RANGE * THROW_RANGE) continue;
            if (dSq < bestSq && citizen.hasLineOfSight(f)) {
                bestSq = dSq;
                best = f;
            }
        }
        return best;
    }

    /** Release the stand, blacklist it briefly, and drop back to patrol (re-acquire next poll). Used
     *  for an unreachable stand and for the fish-gated "no fish here" yield. */
    private void abandonSpot() {
        stopWindupPose();
        if (spear != null && spear.isAlive()) spear.discard();
        spear = null;
        avoidStand = shorePos;
        avoidTicks = AVOID_TICKS;
        FisherShoreRegistry.release(citizen.getUUID());
        shorePos = null;
        waterPos = null;
        noFishTicks = 0;
        phase = Phase.SEEK;
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
    }

    // ─── Spot finding (shore stand beside open water, near the drop-off) ───────────────────────────

    private record Spot(BlockPos stand, BlockPos water, double score) {}

    /** Find live fish near the drop-off, then the best CACHED stand within throw range of the nearest
     *  reachable one. Null when no fish exist or none can be fished from a legal stand. This is what
     *  makes him go to where the fish are — and it's cheap: one entity query + a scan of the cached
     *  stands (the expensive block geometry was scanned once into {@link #standCache}). */
    private Spot findStandNearFish() {
        if (!(citizen.level() instanceof ServerLevel sl)) return null;
        BlockPos origin = citizen.blockPosition();
        ensureStandCache();
        if (standCache.isEmpty()) return null;
        Settlement settlement = citizen.getSettlement();
        // Fish are near the surface, so the vertical span is small — keeps the entity query tight.
        List<AbstractFish> fish = sl.getEntitiesOfClass(AbstractFish.class,
            new AABB(origin).inflate(SCAN_RADIUS, 8.0, SCAN_RADIUS),
            f -> f.isAlive() && f.isInWater()
                && (settlement == null || inClaim(settlement, f.blockPosition())));
        if (fish.isEmpty()) return null;
        fish.sort(java.util.Comparator.comparingDouble(f -> f.blockPosition().distSqr(origin)));
        for (AbstractFish target : fish) {
            BlockPos fishPos = target.blockPosition();
            Spot best = null;
            double bestSq = Double.MAX_VALUE;
            for (Spot s : standCache) {
                double d = s.stand().distSqr(fishPos);
                if (d > THROW_RANGE * THROW_RANGE) continue;     // out of spear range of this fish
                if (d < bestSq && standValid(s.stand())) { bestSq = d; best = s; }
            }
            if (best != null) return best;   // a reachable stand for this (nearest-first) fish
        }
        return null;   // fish exist but none have a legal, reachable cached stand
    }

    /** Best currently-valid CACHED shore stand — the waiting spot used when no fish are around yet.
     *  The cache is pre-sorted best-first, so the first valid entry wins. */
    private Spot findGenericShore() {
        ensureStandCache();
        for (Spot s : standCache) {
            if (standValid(s.stand())) return s;
        }
        return null;
    }

    /** Rebuild the shore-stand cache when it's stale, empty, or the fisher moved to a different
     *  16-block cell. The block scan is the expensive part, so it runs ~once a minute instead of
     *  every fish poll — the cell quantisation keeps ordinary walking (patrol steps, relocating a
     *  few blocks along the bank) from invalidating it every block boundary. */
    private void ensureStandCache() {
        BlockPos origin = citizen.blockPosition();
        boolean sameCell = cacheOrigin != null
            && (origin.getX() >> 4) == (cacheOrigin.getX() >> 4)
            && (origin.getZ() >> 4) == (cacheOrigin.getZ() >> 4);
        if (standCacheCooldown > 0 && !standCache.isEmpty() && sameCell) {
            standCacheCooldown--;
            return;
        }
        rebuildStandCache();
        cacheOrigin = origin;
        standCacheCooldown = STAND_CACHE_TICKS;
    }

    /** Scan the blocks around the drop-off ONCE for every shore stand beside claimed surface water,
     *  scored by pier/openness/proximity, and keep the best {@link #STAND_CACHE_CAP}. Per-use validity
     *  (walkable now, not claimed by another fisher, not blacklisted) is re-checked cheaply in
     *  {@link #standValid}, so this geometry result stays reusable for a while. */
    private void rebuildStandCache() {
        standCache.clear();
        dbgWaterSeen = 0;
        dbgClaimedWater = 0;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null || !(citizen.level() instanceof ServerLevel sl)) return;
        BlockPos origin = citizen.blockPosition();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        Set<Long> tried = new HashSet<>();
        List<Spot> found = new ArrayList<>();
        // Column scan: for each (dx,dz) find the TOPMOST surface water in a tall vertical window, so the
        // drop-off can sit well above the water (a basket up on a bank, not right at the shore).
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                boolean claimed = inClaim(settlement, c.set(origin.getX() + dx, origin.getY(), origin.getZ() + dz));
                for (int dy = SCAN_VERT_UP; dy >= -SCAN_VERT_DOWN; dy--) {
                    c.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!sl.isLoaded(c)) continue;
                    if (!isSurfaceWater(sl, c)) continue;
                    dbgWaterSeen++;                       // a surface-water column (claimed or not)
                    if (claimed) {
                        dbgClaimedWater++;
                        for (BlockPos stand : standsBeside(sl, c)) {
                            if (!tried.add(stand.asLong())) continue;
                            double score = (pierBonus(sl, stand) * 8 + openWater3x3(sl, c)) * 100.0
                                         - origin.distSqr(stand) * 0.01;
                            found.add(new Spot(stand.immutable(), c.immutable(), score));
                        }
                    }
                    break;   // only the top water surface of this column
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(Spot::score).reversed());
        for (int i = 0; i < Math.min(found.size(), STAND_CACHE_CAP); i++) {
            standCache.add(found.get(i));
        }
    }

    /** Cheap per-use validity of a cached stand: walkable now, not another fisher's, not blacklisted. */
    private boolean standValid(BlockPos stand) {
        if (avoidTicks > 0 && stand.equals(avoidStand)) return false;
        if (FisherShoreRegistry.isClaimedByOther(stand, citizen.getUUID())) return false;
        if (FisherShoreRegistry.isAnyClaimWithin(stand, citizen.getUUID(), SHORE_SEPARATION)) return false;
        return WorkerPathing.isWalkable(citizen.level(), stand);
    }

    private static List<BlockPos> standsBeside(Level level, BlockPos water) {
        List<BlockPos> out = new ArrayList<>(4);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adj = water.relative(dir);
            if (WorkerPathing.isWalkable(level, adj)) out.add(adj.immutable());
            else if (WorkerPathing.isWalkable(level, adj.above())) out.add(adj.above().immutable());
        }
        return out;
    }

    private static int pierBonus(Level level, BlockPos stand) {
        int sides = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos n = stand.relative(dir);
            if (isWater(level, n) || isWater(level, n.below())) sides++;
        }
        return sides;
    }

    private static int openWater3x3(Level level, BlockPos surface) {
        int open = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (isSurfaceWater(level, surface.offset(dx, 0, dz))) open++;
            }
        }
        return open;
    }

    private static boolean inClaim(Settlement settlement, BlockPos pos) {
        return settlement.claimedChunks().contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    private static boolean isWater(Level level, BlockPos pos) {
        return pos != null && level.getFluidState(pos).is(FluidTags.WATER);
    }

    private static boolean isSurfaceWater(Level level, BlockPos pos) {
        if (!level.getFluidState(pos).is(FluidTags.WATER)) return false;
        return level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
    }
}
