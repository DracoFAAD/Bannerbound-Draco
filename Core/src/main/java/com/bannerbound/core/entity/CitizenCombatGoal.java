package com.bannerbound.core.entity;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Self-defence AI. Whenever the citizen has a non-null {@link CitizenEntity#getTarget} (set by
 * the {@code NearestAttackableTargetGoal} sitting in their target selector, or by vanilla's
 * {@code lastHurtByMob} pathway when something hits them), this goal:
 *
 * <ol>
 *   <li>Equips the settlement's current tool-age sword in the main hand (preserves whatever
 *       was there so {@link #stop} can restore it).</li>
 *   <li>Pathfinds to the target at speed×1.1 (slightly faster than normal so they actually
 *       close the distance with a swordsman gait).</li>
 *   <li>Swings + applies {@code weaponDamage} half-hearts every {@code 20 / weaponAttackSpeed}
 *       ticks while in melee range (~2 blocks).</li>
 * </ol>
 *
 * <p><b>Priority 0</b> — strictly less than {@link net.minecraft.world.entity.ai.goal.PanicGoal}
 * at 1 so combat preempts panic-from-pain. Strictly less than every work, sleep, conversation
 * and patrol goal too, so a citizen attacked mid-chop drops the axe and fights. Shares priority
 * 0 with {@link net.minecraft.world.entity.ai.goal.FloatGoal} but their flag sets don't conflict
 * (FloatGoal claims JUMP, this goal claims MOVE+LOOK).
 */
@ApiStatus.Internal
public class CitizenCombatGoal extends Goal {
    /** Default damage when no tool age is unlocked (citizens punch with their fists). */
    private static final double DEFAULT_BARE_HAND_DAMAGE = 1.0;
    /** Default attacks per second when no tool age is unlocked. */
    private static final double DEFAULT_BARE_HAND_ATTACK_SPEED = 1.0;
    /** Squared melee reach — within this we stop pathing and swing. ~2 blocks. */
    private static final double MELEE_REACH_SQ = 4.0;
    /** Cap how often we re-issue moveTo so we don't spam the navigator every tick. */
    private static final int REPATH_INTERVAL = 10;

    private final CitizenEntity citizen;
    private final double speedModifier;
    /** &gt; 0 overrides the settlement tool-age lookup (for settlement-less fighters, e.g. barbarians
     *  driven by a {@code BarbarianCapability}); &lt; 0 means "use the settlement". */
    private final double damageOverride;
    private final double attackSpeedOverride;

    @Nullable private LivingEntity currentTarget;
    @Nullable private ItemStack stashedMainHand;
    private int attackCooldown;
    private int repathCooldown;

    public CitizenCombatGoal(CitizenEntity citizen, double speedModifier) {
        this(citizen, speedModifier, -1.0, -1.0);
    }

    /** Override variant: drives damage/attack-speed from explicit values instead of the settlement
     *  (the citizen keeps whatever weapon was pre-equipped in its main hand). */
    public CitizenCombatGoal(CitizenEntity citizen, double speedModifier, double damageOverride,
                             double attackSpeedOverride) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.damageOverride = damageOverride;
        this.attackSpeedOverride = attackSpeedOverride;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // A trade courier never fights — it keeps walking and is killable cargo (TradeCourierGoal
        // holds the movement flags anyway; this removes the insertion-order race entirely).
        if (citizen.isOnTradeJourney()) return false;
        // Guards fight with GuardCombatGoal (also priority 0, MOVE+LOOK) — yield so the two don't both
        // claim the slot (the winner would be insertion-order luck). A guard uses ONLY the smart goal.
        if (GuardWorkGoal.JOB_TYPE_ID.equals(citizen.getJobType())) return false;
        LivingEntity t = citizen.getTarget();
        if (t == null || !t.isAlive()) return false;
        // A kiting bowman only melees when truly cornered (~3 blocks) — otherwise it holds distance
        // and its BarbarianRangedGoal does the work.
        if (citizen instanceof CombatantCitizen b && b.prefersRanged()
                && citizen.distanceToSqr(t) > 9.0) return false;
        // Sanity bound — don't chase a target halfway across the world if some other system
        // set it sloppily; the mob's target selector picks within ~16 blocks.
        if (citizen.distanceToSqr(t) > 32 * 32) return false;
        currentTarget = t;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (currentTarget == null || !currentTarget.isAlive()) return false;
        // Drop the chase if the target gets far enough that they're effectively disengaged.
        if (citizen.distanceToSqr(currentTarget) > 24 * 24) return false;
        // Yield to the flee-creeper goal once the current target is a creeper with its fuse
        // lit. Combat releases MOVE+LOOK the same tick AvoidEntityGoal needs them to start the
        // sprint-away path. Without this the citizen would keep slap-fighting the creeper and
        // eat the explosion.
        if (currentTarget instanceof net.minecraft.world.entity.monster.Creeper c
            && c.getSwellDir() > 0) {
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        if (currentTarget == null) return;
        attackCooldown = 0;
        repathCooldown = 0;
        equipWeapon();
        citizen.getNavigation().moveTo(currentTarget, chaseSpeed());
        citizen.setTarget(currentTarget); // ensure mob-target field stays in sync
    }

    /** Chase speed — barbarians get a per-member variance factor (&lt;1) so they don't all sprint in
     *  at the same insane pace; normal citizens use the goal's plain speed modifier. */
    private double chaseSpeed() {
        return citizen instanceof CombatantCitizen b ? speedModifier * b.combatSpeed() : speedModifier;
    }

    @Override
    public void tick() {
        if (currentTarget == null) return;
        citizen.getLookControl().setLookAt(currentTarget, 30.0f, 30.0f);

        double dSq = citizen.distanceToSqr(currentTarget);
        if (dSq <= MELEE_REACH_SQ) {
            // In range — stop and swing on cooldown.
            citizen.getNavigation().stop();
            if (attackCooldown <= 0) {
                citizen.swing(InteractionHand.MAIN_HAND);
                double damage;
                if (damageOverride > 0) {
                    damage = damageOverride;
                } else if (citizen instanceof CombatantCitizen b && b.combatDamage() > 0) {
                    damage = b.combatDamage(); // barbarian capability damage
                } else {
                    Settlement s = citizen.getSettlement();
                    damage = s == null ? DEFAULT_BARE_HAND_DAMAGE
                        : s.getWeaponDamageOrDefault(DEFAULT_BARE_HAND_DAMAGE);
                }
                currentTarget.hurt(citizen.damageSources().mobAttack(citizen), (float) damage);
                attackCooldown = attackCooldownTicks();
            }
        } else {
            // Out of range — close the gap. Throttle moveTo so we don't recompute paths every
            // tick (vanilla nav grinds when spammed).
            if (--repathCooldown <= 0 || citizen.getNavigation().isDone()) {
                citizen.getNavigation().moveTo(currentTarget, chaseSpeed());
                repathCooldown = REPATH_INTERVAL;
            }
        }
        if (attackCooldown > 0) attackCooldown--;
    }

    @Override
    public void stop() {
        currentTarget = null;
        citizen.setTarget(null);
        citizen.getNavigation().stop();
        // Restore whatever the citizen was holding before combat (a hoe / axe / nothing). The
        // next work goal's start() will re-equip its own tool anyway, but we leave the slot in
        // its pre-combat state so off-duty citizens don't end up brandishing a sword forever.
        if (stashedMainHand != null) {
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, stashedMainHand);
            stashedMainHand = null;
        }
    }

    /** Look up the settlement's current tool-age sword, stash the existing mainhand stack, and
     *  equip the sword. Bare-handed if no settlement / no age / no sword in the age. */
    private void equipWeapon() {
        stashedMainHand = citizen.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).copy();
        // Barbarians: swap to the close-combat weapon (a bowman draws his knife in melee). The ranged
        // goal re-equips the bow once distance is regained.
        Item melee = citizen instanceof CombatantCitizen b ? b.meleeItem() : null;
        if (melee != null && melee != Items.AIR) {
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(melee));
            return;
        }
        Settlement s = citizen.getSettlement();
        if (s == null) return;
        Item sword = s.getToolForRole("sword");
        if (sword == Items.AIR) return;
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(sword));
    }

    /** Ticks between swings — derived from the tool age's {@code weapon_attack_speed}. Floored
     *  at 5 ticks so a future absurd attack-speed value can't melt mobs in a tick. */
    private int attackCooldownTicks() {
        double atkSpeed;
        if (attackSpeedOverride > 0) {
            atkSpeed = attackSpeedOverride;
        } else if (citizen instanceof CombatantCitizen b && b.combatAttackSpeed() > 0) {
            atkSpeed = b.combatAttackSpeed(); // barbarian capability attack speed
        } else {
            Settlement s = citizen.getSettlement();
            atkSpeed = s == null ? DEFAULT_BARE_HAND_ATTACK_SPEED
                : s.getWeaponAttackSpeedOrDefault(DEFAULT_BARE_HAND_ATTACK_SPEED);
        }
        if (atkSpeed <= 0.0) return 20;
        return Math.max(5, (int) Math.round(20.0 / atkSpeed));
    }
}
