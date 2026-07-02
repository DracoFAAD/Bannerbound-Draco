package com.bannerbound.core.entity;

import java.util.EnumSet;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.hunter.HunterHooks;
import com.bannerbound.core.api.quality.QualityMath;
import com.bannerbound.core.api.quality.QualityTier;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * The guard's <b>combat AI</b> — the "much better than self-defence" fight (GUARD_PLAN.md). Registered
 * at <b>priority 0</b> for every citizen but only {@link #canUse() usable} when the citizen holds the
 * guard job, so {@link CitizenCombatGoal} (which yields for guards) never competes for the slot. A
 * plain {@link Goal} (not a {@link WorkGoal}), so it keeps fighting even when the work brain is
 * throttled (Village+ ambient brain / no player nearby).
 *
 * <p>What makes it better than the baseline chase-and-swing:
 * <ul>
 *   <li><b>Leash to home</b> — a guard only engages hostiles inside the settlement's claims or within
 *       {@link #DEFENSE_BAND_CHUNKS} chunks of them, so it never suicide-chases a fleeing raider into
 *       the wild to be swarmed. The one exemption is the guard's live <b>retaliation target</b>
 *       ({@link CitizenEntity#isGuardRetaliationTarget}): whoever is actively damaging this guard is
 *       fair game even from outside the band, so the watch can't be plinked from past the border.</li>
 *   <li><b>The weapon in hand is REAL</b> — damage and swing speed are read off the actual job-tool
 *       {@link ItemStack}'s attribute modifiers (a bronze sword hits harder than a bone club because
 *       the item says so), scaled by craftsmanship {@link QualityTier} and the guard's own
 *       {@code guards_post} mastery. No weapon in storage → the guard fights with its FISTS
 *       (1.0 damage, slow) — stocking the armory is a real logistics decision, not a cosmetic one.</li>
 *   <li><b>Ranged guards</b> — a guard whose drawn weapon is a bow ({@code #bannerbound:hunter_bows})
 *       or a sling ({@code #bannerbound:guard_slings}) fights at range: windup telegraph, planted
 *       shot, back-pedal when the enemy closes, advance when out of range or sight. Arrows are
 *       {@code Pickup.DISALLOWED} (no farming); sling rocks fire through the
 *       {@link HunterHooks.Extension#shootSling} seam (Antiquity spawns the {@code ThrownRock}).</li>
 * </ul>
 *
 * <p>It also keeps {@link CitizenCombatGoal}'s creeper-swell yield — but only when the guard is
 * actually inside blast radius, so an archer guard keeps shooting a lit creeper from safety while a
 * melee guard sprints clear (the priority-0 {@code AvoidEntityGoal} takes over).
 */
@ApiStatus.Internal
public class GuardCombatGoal extends Goal {
    private static final double BARE_HAND_DAMAGE = 1.0;
    private static final int BARE_HAND_COOLDOWN_TICKS = 20;
    private static final double MELEE_REACH_SQ = 4.0;       // ~2 blocks, matches CitizenCombatGoal
    private static final int REPATH_INTERVAL = 10;
    /** A guard fights hostiles inside our claims or within this many chunks of them; beyond that it
     *  breaks off rather than chase into the wild (anti-swarm leash). */
    private static final int DEFENSE_BAND_CHUNKS = 2;
    /** Mastery adds up to this fraction to a guard's hit (novice ×1.0 → master ×1.5). */
    private static final double SKILL_DAMAGE_BONUS = 0.5;
    /** Creeper-swell yield only inside this radius² — melee guards flee the blast, archers keep
     *  shooting from safety. */
    private static final double CREEPER_YIELD_SQ = 49.0;    // 7 blocks
    /** Melee recovery footwork: while the swing recovers, give ground whenever the enemy is
     *  inside this radius² — don't stand chest-to-chest trading free hits. */
    private static final double FENCING_SPACE_SQ = 3.0 * 3.0;
    /** How far one fencing back-step retreats (short — the guard steps back IN to strike the
     *  moment its cooldown is ready, so the fight oscillates rather than migrating). */
    private static final double FENCING_STEP_BLOCKS = 3.5;

    // ── Ranged (bow / sling) ────────────────────────────────────────────────────────────────
    private static final double RANGED_TOO_CLOSE_SQ = 36.0;   // < 6 blocks → back-pedal
    /** One ranged kite back-pedal — long, to reopen real shooting distance. */
    private static final double RANGED_KITE_STEP_BLOCKS = 8.0;
    private static final double BOW_RANGE_SQ = 24.0 * 24.0;
    private static final double SLING_RANGE_SQ = 16.0 * 16.0;
    private static final int BOW_WINDUP_TICKS = 14;           // matches BarbarianRangedGoal's bowman
    private static final int SLING_WINDUP_TICKS = 12;
    private static final int BOW_SHOT_COOLDOWN = 30;
    private static final int SLING_SHOT_COOLDOWN = 25;
    private static final float ARROW_VELOCITY = 1.6F;
    private static final float ARROW_INACCURACY = 4.0F;
    /** Intended full hit of a guard's bow shot before quality/skill scaling (an arrow's real damage
     *  is {@code baseDamage × velocity}, so the base is derived from this at fire time). */
    private static final double BOW_HIT_DAMAGE = 6.0;
    /** Sling rock hit before quality/skill scaling — matches the player slingshot's rock. */
    private static final double SLING_HIT_DAMAGE = 4.0;

    private final CitizenEntity citizen;
    private final double speedModifier;

    @Nullable private LivingEntity target;
    @Nullable private ItemStack stashedMainHand;
    private int attackCooldown;
    private int repathCooldown;
    private int windupTicks;

    public GuardCombatGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (citizen.isOnTradeJourney()) return false; // couriers don't fight — killable cargo
        if (!citizen.isGuard()) return false;
        LivingEntity t = citizen.getTarget();
        if (t == null || !t.isAlive()) return false;
        if (shouldYieldToCreeper(t)) return false;
        // Leash: defend home ground — except vs whoever is actively damaging this guard.
        if (!withinDefenseBand(t) && !citizen.isGuardRetaliationTarget(t)) return false;
        target = t;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!citizen.isGuard()) return false;
        if (target == null || !target.isAlive()) return false;
        if (shouldYieldToCreeper(target)) return false;
        // Target fled the band → break off, unless it's the guard's live retaliation attacker.
        return withinDefenseBand(target) || citizen.isGuardRetaliationTarget(target);
    }

    /** Yield to the flee-creeper goal once the fuse is lit — but only when this guard is close
     *  enough to eat the blast. A ranged guard at distance keeps firing. */
    private boolean shouldYieldToCreeper(LivingEntity t) {
        return t instanceof Creeper c && c.getSwellDir() > 0
            && citizen.distanceToSqr(c) < CREEPER_YIELD_SQ;
    }

    @Override
    public void start() {
        attackCooldown = 0;
        repathCooldown = 0;
        windupTicks = 0;
        equipWeapon();
        if (target != null) citizen.getNavigation().moveTo(target, speedModifier);
    }

    @Override
    public void tick() {
        if (target == null) return;
        citizen.getLookControl().setLookAt(target, 30.0f, 30.0f);
        if (attackCooldown > 0) attackCooldown--;
        ItemStack weapon = GuardWorkGoal.currentWeapon(citizen);
        if (citizen.level() instanceof ServerLevel sl && GuardWorkGoal.isRangedWeapon(weapon)) {
            rangedTick(sl, weapon);
        } else {
            meleeTick(weapon);
        }
    }

    @Override
    public void stop() {
        target = null;
        windupTicks = 0;
        if (citizen.isUsingItem()) citizen.stopUsingItem();
        citizen.getNavigation().stop();
        // Leave citizen.getTarget() to GuardTargetingGoal — it owns the target field and reassigns or
        // clears it next pass (a guard breaking off one raider re-picks the next there, not nearest).
        if (stashedMainHand != null) {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, stashedMainHand);
            stashedMainHand = null;
        }
    }

    // ─── Melee ──────────────────────────────────────────────────────────────────────────────

    private void meleeTick(ItemStack weapon) {
        double dSq = citizen.distanceToSqr(target);
        if (attackCooldown <= 0) {
            // Swing ready: close and strike.
            if (dSq <= MELEE_REACH_SQ) {
                citizen.getNavigation().stop();
                citizen.swing(InteractionHand.MAIN_HAND);
                target.hurt(citizen.damageSources().mobAttack(citizen), (float) meleeDamage(weapon));
                attackCooldown = meleeCooldownTicks(weapon);
                // A sliver of XP per landed hit — the tank earns too, not just the kill-stealer.
                // The kill itself pays 1.0 via GuardCombatEvents (covers arrows/slings as well).
                citizen.grantJobXp(GuardWorkGoal.JOB_TYPE_ID, 0.1f, "guard");
            } else if (--repathCooldown <= 0 || citizen.getNavigation().isDone()) {
                citizen.getNavigation().moveTo(target, speedModifier);
                repathCooldown = REPATH_INTERVAL;
            }
            return;
        }
        // Recovery FOOTWORK — what separates a trained guard from a brawling citizen: while the
        // swing recovers, GIVE GROUND instead of standing chest-to-chest trading free hits. The
        // strike's knockback opens the gap; the enemy spends the whole cooldown window closing it,
        // and the guard steps back in the moment the next swing is ready (the branch above). Net
        // drift ≈ 0 — the fight oscillates in place instead of migrating. Exception: never back
        // off from a RANGED enemy (that's handing an archer its preferred range) — crowd it so it
        // has to keep repositioning instead of shooting.
        if (targetShootsBack()) {
            if (dSq > MELEE_REACH_SQ && (--repathCooldown <= 0 || citizen.getNavigation().isDone())) {
                citizen.getNavigation().moveTo(target, speedModifier);
                repathCooldown = REPATH_INTERVAL;
            }
            return;
        }
        if (dSq < FENCING_SPACE_SQ) {
            backAwayFrom(target, FENCING_STEP_BLOCKS);
        } else {
            citizen.getNavigation().stop();
        }
    }

    /** True when the current target fights at range — a kiting barbarian bowman or anything
     *  holding a projectile weapon (skeleton bow, pillager crossbow, an enemy slinger). Melee
     *  footwork inverts against these: crowd, never give ground. */
    private boolean targetShootsBack() {
        if (target instanceof CombatantCitizen cc && cc.prefersRanged()) return true;
        return target.getMainHandItem().getItem()
            instanceof net.minecraft.world.item.ProjectileWeaponItem;
    }

    /** The held weapon's own attack damage × craftsmanship quality × guard mastery. Bare hands
     *  when the armory had nothing — weak on purpose: stock weapons or field a weak watch. */
    private double meleeDamage(ItemStack weapon) {
        if (weapon.isEmpty()) return BARE_HAND_DAMAGE * skillMultiplier();
        return GuardWorkGoal.weaponAttackDamage(weapon)
            * QualityTier.of(weapon).statMultiplier()
            * skillMultiplier();
    }

    /** Ticks between swings from the held weapon's attack-speed attribute (bare hands: slow). */
    private int meleeCooldownTicks(ItemStack weapon) {
        if (weapon.isEmpty()) return BARE_HAND_COOLDOWN_TICKS;
        double atkSpeed = GuardWorkGoal.weaponAttackSpeed(weapon);
        if (atkSpeed <= 0.0) return BARE_HAND_COOLDOWN_TICKS;
        return Math.max(5, (int) Math.round(20.0 / atkSpeed));
    }

    // ─── Ranged ─────────────────────────────────────────────────────────────────────────────

    private void rangedTick(ServerLevel sl, ItemStack weapon) {
        boolean sling = GuardWorkGoal.isSlingWeapon(weapon);
        double dSq = citizen.distanceToSqr(target);
        boolean los = citizen.getSensing().hasLineOfSight(target);

        if (windupTicks > 0) {
            // Planted, drawing. Release when the telegraph elapses (or the shot window broke).
            if (--windupTicks == 0) {
                if (los && target.isAlive()) fire(sl, weapon, sling);
                if (citizen.isUsingItem()) citizen.stopUsingItem();
                attackCooldown = sling ? SLING_SHOT_COOLDOWN : BOW_SHOT_COOLDOWN;
            }
            return;
        }
        if (dSq < RANGED_TOO_CLOSE_SQ) {
            backAwayFrom(target, RANGED_KITE_STEP_BLOCKS);
        } else if (dSq > (sling ? SLING_RANGE_SQ : BOW_RANGE_SQ) || !los) {
            if (--repathCooldown <= 0 || citizen.getNavigation().isDone()) {
                citizen.getNavigation().moveTo(target, speedModifier * 0.9);
                repathCooldown = REPATH_INTERVAL;
            }
        } else if (attackCooldown <= 0) {
            // Hold-fire discipline: a fellow citizen standing in the fire lane → sidestep for a
            // clear angle instead of shooting through the shield line. (The projectile-immunity
            // net in GuardCombatEvents means a mistake is harmless; this is what the player SEES.)
            if (friendlyInLane(sl)) {
                sidestep();
                attackCooldown = 8;   // re-check the lane shortly, don't thrash every tick
                return;
            }
            // In the sweet spot with a clear line — plant feet and telegraph the shot.
            citizen.getNavigation().stop();
            windupTicks = sling ? SLING_WINDUP_TICKS : BOW_WINDUP_TICKS;
            citizen.startUsingItem(InteractionHand.MAIN_HAND);   // bow-draw / sling-stretch pose
        } else {
            citizen.getNavigation().stop();
        }
    }

    /** True when a same-settlement citizen blocks the straight shot from our eyes to the target. */
    private boolean friendlyInLane(ServerLevel sl) {
        Vec3 from = citizen.getEyePosition();
        Vec3 to = new Vec3(target.getX(), target.getY(0.5), target.getZ());
        java.util.UUID home = citizen.getSettlementId();
        if (home == null) return false;
        for (CitizenEntity c : sl.getEntitiesOfClass(CitizenEntity.class,
                new net.minecraft.world.phys.AABB(from, to).inflate(1.0))) {
            if (c == citizen || c == target) continue;
            if (!home.equals(c.getSettlementId())) continue;
            if (c.getBoundingBox().inflate(0.4).clip(from, to).isPresent()) return true;
        }
        return false;
    }

    /** Step a few blocks perpendicular to the fire lane, hunting for a clear angle. */
    private void sidestep() {
        Vec3 toTarget = target.position().subtract(citizen.position());
        Vec3 side = new Vec3(-toTarget.z, 0, toTarget.x).normalize()
            .scale(citizen.getRandom().nextBoolean() ? 3.0 : -3.0);
        Vec3 dest = citizen.position().add(side);
        citizen.getNavigation().moveTo(dest.x, dest.y, dest.z, speedModifier);
    }

    private void fire(ServerLevel sl, ItemStack weapon, boolean sling) {
        double scaled = QualityTier.of(weapon).statMultiplier() * skillMultiplier();
        citizen.swing(InteractionHand.MAIN_HAND);
        if (sling) {
            HunterHooks.get().shootSling(citizen, target, weapon, SLING_HIT_DAMAGE * scaled);
            return;
        }
        AbstractArrow arrow = HunterHooks.get().createArrow(citizen, weapon);
        if (arrow == null) {
            arrow = new Arrow(sl, citizen, new ItemStack(Items.ARROW), citizen.getMainHandItem());
        }
        // An arrow's real hit is baseDamage × velocity — derive the base from the intended hit.
        arrow.setBaseDamage(Math.max(2.0, BOW_HIT_DAMAGE * scaled / ARROW_VELOCITY));
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;   // skeleton-style: guards can't be farmed
        float velocity = ARROW_VELOCITY * HunterHooks.get().bowVelocityFactor(weapon);
        double dx = target.getX() - citizen.getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - citizen.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horiz * 0.2, dz, velocity, ARROW_INACCURACY);
        sl.addFreshEntity(arrow);
        sl.playSound(null, citizen.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL,
            1.0F, 1.0F / (citizen.getRandom().nextFloat() * 0.4F + 0.8F));
    }

    /** Back-pedal directly away from {@code t} by {@code blocks} — the long ranged-kite step and
     *  the short melee fencing step share this (BarbarianRangedGoal's pattern). */
    private void backAwayFrom(LivingEntity t, double blocks) {
        Vec3 away = citizen.position().subtract(t.position());
        if (away.lengthSqr() < 1.0e-3) away = new Vec3(1, 0, 0);
        Vec3 dest = citizen.position().add(away.normalize().scale(blocks));
        citizen.getNavigation().moveTo(dest.x, dest.y, dest.z, speedModifier);
    }

    // ─── Shared ─────────────────────────────────────────────────────────────────────────────

    /** Draw the guard's weapon (stashing whatever was held so {@link #stop} restores it). */
    private void equipWeapon() {
        stashedMainHand = citizen.getItemBySlot(EquipmentSlot.MAINHAND).copy();
        ItemStack weapon = GuardWorkGoal.currentWeapon(citizen);
        if (!weapon.isEmpty()) {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, weapon.copy());
        }
    }

    /** Mastery scale: novice ×1.0 → master ×{@code 1 + SKILL_DAMAGE_BONUS}. */
    private double skillMultiplier() {
        return 1.0 + SKILL_DAMAGE_BONUS * jobSkill();
    }

    /** This guard's {@code guards_post} mastery on the shared XP-saturation curve (0 novice → ~1 master). */
    private float jobSkill() {
        float xp = citizen.getJobXp(GuardWorkGoal.JOB_TYPE_ID);
        return xp / (xp + QualityMath.NPC_XP_HALF);
    }

    /** True if {@code e} stands inside this guard's settlement claims or within
     *  {@link #DEFENSE_BAND_CHUNKS} chunks of them — the leash that keeps the watch defending home
     *  ground instead of chasing into the wild. */
    private boolean withinDefenseBand(LivingEntity e) {
        Settlement s = citizen.getSettlement();
        return citizen.level() instanceof ServerLevel && s != null
            && withinDefenseBand(s, e.blockPosition());
    }

    /** True if {@code pos} is inside {@code s}'s claims or within {@link #DEFENSE_BAND_CHUNKS} chunks of
     *  them — the shared guard leash, also used by {@link GuardTargetingGoal} so acquisition and the
     *  fight agree on what counts as "home ground." Fails open for an unclaimed settlement. */
    static boolean withinDefenseBand(Settlement s, BlockPos pos) {
        Set<Long> claimed = s.claimedChunks();
        if (claimed.isEmpty()) return true;
        ChunkPos tc = new ChunkPos(pos);
        for (int dx = -DEFENSE_BAND_CHUNKS; dx <= DEFENSE_BAND_CHUNKS; dx++) {
            for (int dz = -DEFENSE_BAND_CHUNKS; dz <= DEFENSE_BAND_CHUNKS; dz++) {
                if (claimed.contains(ChunkPos.asLong(tc.x + dx, tc.z + dz))) return true;
            }
        }
        return false;
    }
}
