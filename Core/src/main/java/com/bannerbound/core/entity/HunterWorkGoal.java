package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.hunter.HunterHooks;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Hunter {@link GathererWorkGoal} — hunts <b>wild (undomesticated) animals OUTSIDE the
 * settlement's claims</b>, the field counterpart to the {@link HerderWorkGoal herder} (who tends
 * domesticated stock inside a pen). Which animals are huntable is data-driven via the
 * {@code #bannerbound:huntable} entity-type tag, so modded animals can opt in from a datapack.
 *
 * <h2>Trips, like the forager</h2>
 * No giant prey scans: the hunter <b>roams</b> the wild band (unclaimed chunks within
 * {@link #HUNT_BAND_CHUNKS} of the border — a deeper ring than the forager's) and watches a small
 * radius around itself for prey. An animal that flees INTO claimed land gets sanctuary — the
 * hunter only ever kills on unclaimed ground.
 *
 * <h2>Weapons</h2>
 * The job tool resolves through the {@code "hunt"} tool-age role (swords in the Core ages, a spear
 * in Antiquity's bone age). Once the settlement researches {@link HunterHooks#FLAG_ARCHERY} the
 * hunter upgrades to a stored bow and shoots from range — its arrows are never collectible
 * (skeleton-style {@link AbstractArrow.Pickup#DISALLOWED}) so they can't be farmed. With a spear
 * tool the {@link HunterHooks.Extension} opens each engagement with a throw before the melee kill.
 *
 * <h2>Kills</h2>
 * Prey dies a normal death; {@code HunterKillEvents} reroutes the (known-set-filtered) death drops
 * straight into the hunter's drop-off, so the hunter never hauls meat home by hand.
 */
@ApiStatus.Internal
public class HunterWorkGoal extends GathererWorkGoal {
    /** Per-citizen job id (registered with {@code CitizenJobRegistry} / {@code WorkstationUnlocks}). */
    public static final String JOB_TYPE_ID = "hunters_camp";

    /** Data-driven prey list — entity types a hunter will stalk (datapack-extendable). */
    public static final TagKey<EntityType<?>> HUNTABLE_TAG = TagKey.create(Registries.ENTITY_TYPE,
        ResourceLocation.fromNamespaceAndPath("bannerbound", "huntable"));

    /** Bows a hunter may shoot with once Archery is researched — vanilla bow in Core; Antiquity
     *  adds its primitive bow via the tag (any expansion bow joins the same way). */
    public static final TagKey<net.minecraft.world.item.Item> HUNTER_BOWS_TAG = TagKey.create(
        Registries.ITEM, ResourceLocation.fromNamespaceAndPath("bannerbound", "hunter_bows"));

    // Roaming (the trip) — a deeper band than the forager's, because game roams farther than flowers.
    private static final int LEASH_RADIUS = 80;          // how far each roam hop wanders from the hunter
    private static final int HUNT_BAND_CHUNKS = 6;       // huntable ring depth outside the border (96 blocks)
    private static final int ROAM_TIMEOUT_TICKS = 300;   // can't reach the roam point → pick a new one
    private static final double ARRIVE_SQ = 2.2 * 2.2;
    private static final int BARREN_YIELD_STREAK = 4;    // preyless roams in a row → rest (yield to patrol)
    private static final int BARREN_COOLDOWN_TICKS = 300;
    private static final int RESCAN_COOLDOWN_TICKS = 20;

    // Prey scanning — small box around the hunter itself, never a settlement-wide sweep.
    private static final int PREY_SCAN_RADIUS = 24;
    private static final int PREY_SCAN_HEIGHT = 8;

    // Engagement.
    private static final double MELEE_REACH_SQ = 4.0;          // ~2 blocks, matches CitizenCombatGoal
    private static final double CHASE_SPEED_FACTOR = 1.5;      // scared prey → run it down
    private static final double STEALTH_SPEED_FACTOR = 0.55;   // crouch-stalk gait
    private static final int REPATH_INTERVAL = 10;
    private static final int ENGAGE_TIMEOUT_TICKS = 600;       // 30 s on one animal → give up, blacklist it
    private static final int AVOID_PREY_TICKS = 1200;          // blacklisted animal is skipped ~1 min
    private static final int CLAIM_TICKS = 100;                // hunt claim refresh window on the prey
    private static final int STAMINA_PER_KILL = 8;

    // Bow.
    private static final double BOW_RANGE = 14.0;
    private static final int BOW_DRAW_TICKS = 25;
    private static final int BOW_COOLDOWN_TICKS = 20;          // beat between shots (after the draw)
    private static final double ARROW_VELOCITY = 1.6;
    private static final float ARROW_INACCURACY = 2.0F;

    // Spear opener (Antiquity extension). The stealth throw happens from JUST OUTSIDE the prey's
    // sneak-detection radius (Antiquity default 12 for a crouching threat) — winding up at 14
    // blocks keeps the animal calm until the spear is already in the air.
    private static final double SPEAR_RANGE = 14.0;
    private static final int SPEAR_WINDUP_TICKS = 20;
    /** Spooked prey closer than this is simply run down and stabbed; beyond it (and still in spear
     *  range) the hunter hurls the spear on the run instead of letting the prey pull away. */
    private static final double RUNNING_THROW_MIN_SQ = 6.0 * 6.0;
    /** Shorter raise for the running throw — a snap hurl, not the planted stealth windup. */
    private static final int RUNNING_THROW_WINDUP_TICKS = 12;

    /** Hunt claim on the prey so two hunters never converge on one animal (expiry-stamped — a dead
     *  hunter's claim simply lapses). */
    private static final String CLAIM_ID_TAG = "BannerboundHuntedBy";
    private static final String CLAIM_UNTIL_TAG = "BannerboundHuntClaimUntil";

    private static final double DEFAULT_BARE_HAND_DAMAGE = 1.0;
    private static final double DEFAULT_ATTACK_SPEED = 1.0;

    private enum Phase { ROAM, HUNT }

    private enum Windup { NONE, BOW, SPEAR }

    private Phase phase = Phase.ROAM;
    private Mob target;
    private BlockPos roamPos;
    private int roamAge;
    private int rescanCooldown;
    private int barrenStreak;
    private int engageAge;
    private int attackCooldown;
    private int repathCooldown;
    private Windup windup = Windup.NONE;
    private int windupTicks;
    private boolean windupMoving;    // running throw: keep chasing while the spear is raised
    private boolean spearThrown;     // one opener per engagement
    private int avoidEntityId = -1;  // last given-up prey (single slot is plenty)
    private long avoidUntilGameTime;

    public HunterWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
        // Stagger first scans so a batch of hunters hired at once don't all sweep the same tick.
        this.rescanCooldown = citizen.getId() % RESCAN_COOLDOWN_TICKS;
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    private Container resolveDepot() {
        return DropOffContainers.resolveJobDepot(citizen);
    }

    @Override
    protected boolean canStartWork() {
        citizen.validateJobStorage();
        if (!citizen.isGatherJobReady(JOB_TYPE_ID)) return false;
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Container depot = resolveDepot();
        if (depot == null || !DropOffContainers.hasFreeSlot(depot)) return false;
        maybeUpgradeToBow(sl);

        // Keep a still-valid quarry.
        if (target != null && isValidTarget(sl, target)) {
            phase = Phase.HUNT;
            return true;
        }
        target = null;
        if (rescanCooldown-- > 0) return false;

        Mob prey = findPreyNear(sl);
        if (prey != null) {
            setTarget(sl, prey);
            return true;
        }
        BlockPos roam = pickRoamPos(sl);
        if (roam == null) {
            rescanCooldown = BARREN_COOLDOWN_TICKS;
            return false;   // no reachable wilderness in range → patrol for a while
        }
        roamPos = roam;
        roamAge = 0;
        phase = Phase.ROAM;
        return true;
    }

    @Override
    protected boolean canKeepWorking() {
        if (!citizen.isGatherJobReady(JOB_TYPE_ID)) return false;
        Container depot = resolveDepot();
        return depot != null && DropOffContainers.hasFreeSlot(depot);
    }

    @Override
    public void start() {
        citizen.setWorking(true);
        equipWeapon();
        citizen.setAvoidWaterPathing(true);   // hunt the banks, don't swim after deer… er, cows
        attackCooldown = 0;
        repathCooldown = 0;
        if (phase == Phase.HUNT && target != null) {
            citizen.getNavigation().moveTo(target, skilledSpeed());
        } else if (roamPos != null) {
            moveTo(roamPos, skilledSpeed());
        }
    }

    @Override
    public void stop() {
        citizen.setWorking(false);
        stopWindup();
        standUp();
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.setAvoidWaterPathing(false);
        target = null;
        roamPos = null;
        spearThrown = false;
        phase = Phase.ROAM;
    }

    @Override
    public void tick() {
        if (!citizen.isGatherJobReady(JOB_TYPE_ID) || !(citizen.level() instanceof ServerLevel sl)) return;
        if (attackCooldown > 0) attackCooldown--;
        switch (phase) {
            case ROAM -> tickRoam(sl);
            case HUNT -> tickHunt(sl);
        }
    }

    // ─── Roaming the wild band ─────────────────────────────────────────────────────────────────────

    private void tickRoam(ServerLevel sl) {
        // Keep an eye out for prey while walking — engage anything we pass.
        if (rescanCooldown-- <= 0) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            Mob prey = findPreyNear(sl);
            if (prey != null) {
                setTarget(sl, prey);
                barrenStreak = 0;
                return;
            }
        }
        if (roamPos == null) { roamPos = pickRoamPos(sl); roamAge = 0; return; }
        double d = citizen.position().distanceToSqr(roamPos.getX() + 0.5, roamPos.getY(), roamPos.getZ() + 0.5);
        if (d <= ARRIVE_SQ || ++roamAge > ROAM_TIMEOUT_TICKS) {
            // Reached (or gave up on) this roam point with nothing to show — after a few barren
            // sweeps in a row, rest a while instead of pacing the wilds forever.
            if (++barrenStreak >= BARREN_YIELD_STREAK) {
                barrenStreak = 0;
                roamPos = null;
                rescanCooldown = BARREN_COOLDOWN_TICKS;
                return;
            }
            roamPos = pickRoamPos(sl);
            roamAge = 0;
            if (roamPos != null) moveTo(roamPos, skilledSpeed());
            return;
        }
        if (citizen.getNavigation().isDone()) moveTo(roamPos, skilledSpeed());
    }

    /** A walkable surface point in the hunt band (unclaimed land near our border). Sampled near the
     *  hunter itself so trips drift along the band; falls back to the drop-off to recover range. */
    private BlockPos pickRoamPos(ServerLevel sl) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
        BlockPos near = pickBandPointNear(sl, settlement, citizen.blockPosition());
        if (near != null) return near;
        BlockPos drop = citizen.getDropOff();
        return drop != null ? pickBandPointNear(sl, settlement, drop) : null;
    }

    private BlockPos pickBandPointNear(ServerLevel sl, Settlement settlement, BlockPos anchor) {
        for (int attempt = 0; attempt < 12; attempt++) {
            int x = anchor.getX() + citizen.getRandom().nextInt(LEASH_RADIUS * 2 + 1) - LEASH_RADIUS;
            int z = anchor.getZ() + citizen.getRandom().nextInt(LEASH_RADIUS * 2 + 1) - LEASH_RADIUS;
            if (!inHuntBandColumn(sl, settlement, x, z)) continue;
            int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (WorkerPathing.isWalkable(sl, p) || WorkerPathing.isWalkable(sl, p.above())) {
                return p;
            }
        }
        return null;
    }

    /** True if the column is unclaimed by ANYONE and within {@link #HUNT_BAND_CHUNKS} of one of OUR
     *  claimed chunks — the forager-band rule, one ring deeper. */
    private static boolean inHuntBandColumn(ServerLevel sl, Settlement settlement, int x, int z) {
        if (settlement == null) return false;
        int cx = x >> 4;
        int cz = z >> 4;
        if (SettlementData.get(sl).getByChunk(ChunkPos.asLong(cx, cz)) != null) return false;
        java.util.Set<Long> ours = settlement.claimedChunks();
        for (int dx = -HUNT_BAND_CHUNKS; dx <= HUNT_BAND_CHUNKS; dx++) {
            for (int dz = -HUNT_BAND_CHUNKS; dz <= HUNT_BAND_CHUNKS; dz++) {
                if (ours.contains(ChunkPos.asLong(cx + dx, cz + dz))) return true;
            }
        }
        return false;
    }

    // ─── The hunt ──────────────────────────────────────────────────────────────────────────────────

    private void tickHunt(ServerLevel sl) {
        if (target == null) { phase = Phase.ROAM; return; }
        if (!target.isAlive()) { onKill(); return; }
        if (!isValidTarget(sl, target) || ++engageAge > ENGAGE_TIMEOUT_TICKS) {
            giveUpOnTarget(sl);
            return;
        }
        refreshClaim(sl, target);
        citizen.getLookControl().setLookAt(target, 30.0F, 30.0F);

        // Mid-windup (bow draw / spear raise): hold the pose, release on time.
        if (windup != Windup.NONE) {
            tickWindup(sl);
            return;
        }

        ItemStack weapon = citizen.getJobTool();
        double dSq = citizen.distanceToSqr(target);

        // Bow: shoot from a standstill whenever the prey is in range with a clear line.
        if (isBowWeapon(weapon)) {
            if (dSq <= BOW_RANGE * BOW_RANGE && citizen.hasLineOfSight(target)) {
                citizen.getNavigation().stop();
                if (attackCooldown <= 0) beginWindup(Windup.BOW, BOW_DRAW_TICKS, false, false);
                return;
            }
            approach(sl, dSq);
            return;
        }

        // Spear opener (Antiquity): one throw per engagement. The throw is a SNEAK attack — the
        // hunter plants and hurls from the crouch while the prey is still calm (standing up is what
        // spooks it). Once the prey has bolted the spear is only worth hurling on the run when the
        // animal is pulling away; close in, it's faster to just run it down and stab.
        if (!spearThrown && HunterHooks.get().isThrowableSpear(weapon)
                && dSq <= SPEAR_RANGE * SPEAR_RANGE && dSq > MELEE_REACH_SQ * 2.0
                && citizen.hasLineOfSight(target)) {
            if (!HunterHooks.get().isPreyScared(target)) {
                citizen.getNavigation().stop();
                beginWindup(Windup.SPEAR, SPEAR_WINDUP_TICKS,
                    HunterHooks.get().wantsStealth(citizen, target), false);
                return;
            }
            if (dSq > RUNNING_THROW_MIN_SQ) {
                beginWindup(Windup.SPEAR, RUNNING_THROW_WINDUP_TICKS, false, true);
                return;
            }
            // Spooked and close → fall through to the melee chase below.
        }

        // Melee: close and stab.
        if (dSq <= MELEE_REACH_SQ) {
            standUp();
            citizen.getNavigation().stop();
            if (attackCooldown <= 0) {
                citizen.swing(InteractionHand.MAIN_HAND);
                target.hurt(citizen.damageSources().mobAttack(citizen), (float) qualityScaledDamage());
                attackCooldown = meleeCooldownTicks();
                if (!target.isAlive()) onKill();
            }
        } else {
            approach(sl, dSq);
        }
    }

    /** Close the distance: crouch-stalk while the extension says the prey is still calm, chase at
     *  speed once it's spooked. Throttled repath, CitizenCombatGoal-style. */
    private void approach(ServerLevel sl, double dSq) {
        HunterHooks.Extension ext = HunterHooks.get();
        double speed;
        if (ext.isPreyScared(target)) {
            standUp();
            speed = skilledSpeed() * CHASE_SPEED_FACTOR;
        } else if (ext.wantsStealth(citizen, target)) {
            crouch();
            speed = skilledSpeed() * STEALTH_SPEED_FACTOR;
        } else {
            standUp();
            speed = skilledSpeed();
        }
        if (--repathCooldown <= 0 || citizen.getNavigation().isDone()) {
            citizen.getNavigation().moveTo(target, speed);
            repathCooldown = REPATH_INTERVAL;
        }
    }

    // ─── Windups (bow draw / spear raise) ─────────────────────────────────────────────────────────

    /** Raise the weapon. {@code keepCrouch} holds the stalk pose through a stealth throw (standing
     *  up is exactly what would spook the prey mid-windup); {@code moving} is the running throw —
     *  the hunter keeps chasing while the spear is raised instead of planting its feet. */
    private void beginWindup(Windup kind, int ticks, boolean keepCrouch, boolean moving) {
        if (!keepCrouch) standUp();
        windup = kind;
        windupTicks = ticks;
        windupMoving = moving;
        // The held item's UseAnim drives the client arm pose (BOW_AND_ARROW / THROW_SPEAR).
        citizen.startUsingItem(InteractionHand.MAIN_HAND);
    }

    private void tickWindup(ServerLevel sl) {
        double rangeSq = (windup == Windup.BOW ? BOW_RANGE * BOW_RANGE : SPEAR_RANGE * SPEAR_RANGE) * 1.5;
        if (target == null || !target.isAlive()
                || !citizen.hasLineOfSight(target)
                || citizen.distanceToSqr(target) > rangeSq) {
            stopWindup();   // prey broke line / bolted out of range — back to the chase
            return;
        }
        citizen.getLookControl().setLookAt(target, 30.0F, 30.0F);
        // Running throw: keep the chase going under the raised spear so the prey can't pull away.
        if (windupMoving && (--repathCooldown <= 0 || citizen.getNavigation().isDone())) {
            citizen.getNavigation().moveTo(target, skilledSpeed() * CHASE_SPEED_FACTOR);
            repathCooldown = REPATH_INTERVAL;
        }
        if (--windupTicks > 0) return;
        Windup kind = windup;
        Mob prey = target;
        stopWindup();
        if (kind == Windup.BOW) {
            shootArrow(sl, prey);
            attackCooldown = BOW_COOLDOWN_TICKS;
        } else if (kind == Windup.SPEAR) {
            if (HunterHooks.get().throwSpear(citizen, prey, citizen.getJobTool().copy(), qualityScaledDamage())) {
                spearThrown = true;
            } else {
                spearThrown = true;   // don't retry a failing opener every tick — fall through to melee
            }
        }
    }

    private void stopWindup() {
        if (citizen.isUsingItem()) citizen.stopUsingItem();
        windup = Windup.NONE;
        windupTicks = 0;
        windupMoving = false;
    }

    /** Skeleton-style shot: the arrow is never collectible, so hunters can't be farmed for arrows.
     *  The extension may supply the arrow entity (Antiquity → flint arrow) and a velocity factor
     *  (the primitive bow's quality-scaled handicap) — a slower arrow hits softer, like a player's. */
    private void shootArrow(ServerLevel sl, Mob prey) {
        ItemStack bow = citizen.getJobTool();
        AbstractArrow arrow = HunterHooks.get().createArrow(citizen, bow);
        if (arrow == null) {
            arrow = new Arrow(sl, citizen, new ItemStack(Items.ARROW), citizen.getMainHandItem());
        }
        // Effective hit ≈ the tool-age weapon damage (AbstractArrow scales base damage by velocity);
        // the bow's craftsmanship quality acts through the velocity factor, never durability.
        arrow.setBaseDamage(Math.max(2.0, weaponDamage() / ARROW_VELOCITY));
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        float velocity = (float) ARROW_VELOCITY * HunterHooks.get().bowVelocityFactor(bow);
        double dx = prey.getX() - citizen.getX();
        double dy = prey.getY(0.3333) - arrow.getY();
        double dz = prey.getZ() - citizen.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horiz * 0.2, dz, velocity, ARROW_INACCURACY);
        sl.addFreshEntity(arrow);
        sl.playSound(null, citizen.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL,
            1.0F, 1.0F / (citizen.getRandom().nextFloat() * 0.4F + 0.8F));
    }

    // ─── Targets ───────────────────────────────────────────────────────────────────────────────────

    private void setTarget(ServerLevel sl, Mob prey) {
        target = prey;
        engageAge = 0;
        spearThrown = false;
        phase = Phase.HUNT;
        refreshClaim(sl, prey);
        citizen.getNavigation().moveTo(prey, skilledSpeed());
    }

    private void onKill() {
        // Drops were rerouted to the drop-off by HunterKillEvents at death time.
        citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "hunt");
        citizen.consumeStamina(STAMINA_PER_KILL);
        target = null;
        spearThrown = false;
        engageAge = 0;
        standUp();
        // Stay alert: the herd is scattering — rescan right away rather than roaming off.
        rescanCooldown = 0;
        phase = Phase.ROAM;
    }

    /** Timed out / prey escaped into claims: blacklist it briefly so we don't immediately re-pick it. */
    private void giveUpOnTarget(ServerLevel sl) {
        if (target != null) {
            avoidEntityId = target.getId();
            avoidUntilGameTime = sl.getGameTime() + AVOID_PREY_TICKS;
        }
        stopWindup();
        standUp();
        target = null;
        spearThrown = false;
        engageAge = 0;
        phase = Phase.ROAM;
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
    }

    /** Nearest huntable wild animal around the hunter itself (never a settlement-wide sweep). */
    private Mob findPreyNear(ServerLevel sl) {
        long now = sl.getGameTime();
        AABB box = citizen.getBoundingBox().inflate(PREY_SCAN_RADIUS, PREY_SCAN_HEIGHT, PREY_SCAN_RADIUS);
        Mob best = null;
        double bestSq = Double.MAX_VALUE;
        for (Mob m : sl.getEntitiesOfClass(Mob.class, box, m -> isHuntable(sl, m, now))) {
            double d = citizen.distanceToSqr(m);
            if (d < bestSq) { best = m; bestSq = d; }
        }
        return best;
    }

    private boolean isValidTarget(ServerLevel sl, Mob m) {
        return isHuntable(sl, m, sl.getGameTime());
    }

    /** The full prey test: tagged huntable, adult, wild (no domestication mark of any kind, including
     *  the expansion's), unleashed, unclaimed by herders or other hunters, standing on unclaimed land. */
    private boolean isHuntable(ServerLevel sl, Mob m, long now) {
        if (!m.isAlive() || m.isBaby()) return false;
        if (!m.getType().is(HUNTABLE_TAG)) return false;
        if (!citizen.isHunterPreyEnabled(m.getType())) return false;   // Job-tab prey toggle
        if (m.getId() == avoidEntityId && now < avoidUntilGameTime) return false;
        if (m.isLeashed()) return false;
        if (m.getPersistentData().getBoolean(HerderWorkGoal.DOMESTICATED_TAG)) return false;
        if (m instanceof TamableAnimal t && t.isTame()) return false;
        if (m instanceof AbstractHorse h && h.isTamed()) return false;
        Integer herded = m.getExistingDataOrNull(BannerboundCore.HERDED_BY.get());
        if (herded != null && herded != 0) return false;   // a herder is corralling it
        if (HunterHooks.get().isDomesticated(m)) return false;
        if (claimedByOtherHunter(m, now)) return false;
        // Only on unclaimed ground — an animal that flees into ANY settlement's claims is safe.
        return SettlementData.get(sl).getByChunk(new ChunkPos(m.blockPosition()).toLong()) == null;
    }

    private boolean claimedByOtherHunter(Mob m, long now) {
        CompoundTag t = m.getPersistentData();
        return t.getLong(CLAIM_UNTIL_TAG) >= now && t.getInt(CLAIM_ID_TAG) != citizen.getId();
    }

    private void refreshClaim(ServerLevel sl, Mob m) {
        CompoundTag t = m.getPersistentData();
        t.putInt(CLAIM_ID_TAG, citizen.getId());
        t.putLong(CLAIM_UNTIL_TAG, sl.getGameTime() + CLAIM_TICKS);
    }

    // ─── Weapons ───────────────────────────────────────────────────────────────────────────────────

    /** Show the job tool in the main hand (bare-handed in anarchy when none is installed). */
    private void equipWeapon() {
        ItemStack tool = citizen.getJobTool();
        citizen.setItemSlot(EquipmentSlot.MAINHAND, tool.isEmpty() ? ItemStack.EMPTY : tool.copy());
    }

    /** True for anything the hunter treats as a bow — the {@code #bannerbound:hunter_bows} tag
     *  (vanilla bow, Antiquity's primitive bow), plus any {@link BowItem} as a safety net. */
    private static boolean isBowWeapon(ItemStack stack) {
        return stack.is(HUNTER_BOWS_TAG) || stack.getItem() instanceof BowItem;
    }

    /**
     * Once Archery is researched, a hunter holding a melee tool swaps it for a bow (anything in
     * {@code #bannerbound:hunter_bows}) from the settlement's preferred storage — the melee tool
     * goes back into that storage. Runs on the throttled work-scan tick, government only — anarchy
     * hunters keep self-organizing bare-handed.
     */
    private void maybeUpgradeToBow(ServerLevel sl) {
        if (citizen.isAnarchy()) return;
        if (isBowWeapon(citizen.getJobTool())) return;
        Settlement s = citizen.getSettlement();
        if (s == null || !ResearchManager.hasFlag(s, HunterHooks.FLAG_ARCHERY)) return;
        Container storage = DropOffContainers.resolvePreferredStorage(citizen);
        if (storage == null) return;
        ItemStack bow = ItemStack.EMPTY;
        for (int i = 0; i < storage.getContainerSize() && bow.isEmpty(); i++) {
            if (isBowWeapon(storage.getItem(i))) {
                bow = storage.removeItem(i, 1);
            }
        }
        if (bow.isEmpty()) return;
        storage.setChanged();
        ItemStack old = citizen.getJobTool();
        if (!old.isEmpty()) {
            ItemStack leftover = DropOffContainers.insert(storage, old);
            if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
        }
        citizen.setJobTool(bow);
        if (citizen.isWorking()) equipWeapon();
    }

    private double weaponDamage() {
        Settlement s = citizen.getSettlement();
        return s == null ? DEFAULT_BARE_HAND_DAMAGE : s.getWeaponDamageOrDefault(DEFAULT_BARE_HAND_DAMAGE);
    }

    /** Tool-age damage scaled by the held weapon's craftsmanship quality — effectiveness only,
     *  never durability (NPC tools don't wear). Used for melee strikes and the spear throw; bow
     *  shots instead apply quality through the velocity factor (see {@link #shootArrow}) so it
     *  isn't double-counted. An unfletched/creative tool reads as STANDARD (×1.0). */
    private double qualityScaledDamage() {
        return weaponDamage()
            * com.bannerbound.core.api.quality.QualityTier.of(citizen.getJobTool()).statMultiplier();
    }

    /** Ticks between melee swings, from the tool age's {@code weapon_attack_speed} (floored at 5). */
    private int meleeCooldownTicks() {
        Settlement s = citizen.getSettlement();
        double atkSpeed = s == null ? DEFAULT_ATTACK_SPEED : s.getWeaponAttackSpeedOrDefault(DEFAULT_ATTACK_SPEED);
        if (atkSpeed <= 0.0) return 20;
        return Math.max(5, (int) Math.round(20.0 / atkSpeed));
    }

    // ─── Pose / movement helpers ───────────────────────────────────────────────────────────────────

    private void crouch() {
        if (citizen.getPose() != Pose.CROUCHING) citizen.setPose(Pose.CROUCHING);
    }

    private void standUp() {
        if (citizen.getPose() == Pose.CROUCHING) citizen.setPose(Pose.STANDING);
    }

    private void moveTo(BlockPos p, double speed) {
        if (p == null) return;
        citizen.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, speed);
    }
}
