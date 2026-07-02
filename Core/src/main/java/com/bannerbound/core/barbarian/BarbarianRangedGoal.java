package com.bannerbound.core.barbarian;

import java.util.EnumSet;

import com.bannerbound.core.entity.BarbarianEntity;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Lets a barbarian fire its ranged weapon (thrown spear / arrow) at the current target.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Brute</b> (e.g. spearmen): FLAGLESS — runs alongside {@code CitizenCombatGoal} so the
 *       fighter charges while hurling spears, then melees up close.</li>
 *   <li><b>Kiter</b> (bowmen, behaviour {@code skirmisher}): claims MOVE+LOOK — it holds the bow,
 *       backs away when the enemy closes, fires from range, and advances when out of range. Its
 *       {@code CitizenCombatGoal} only engages when truly cornered (see {@link CitizenEntity#prefersRanged}),
 *       and switches the held item to the melee weapon then.</li>
 * </ul>
 */
public class BarbarianRangedGoal extends Goal {
    private static final double MIN_RANGE_SQ = 4.0 * 4.0;    // brute: closer than this → just melee
    private static final double MAX_RANGE_SQ = 20.0 * 20.0;  // beyond this → can't reach
    private static final double KITE_NEAR_SQ = 8.0 * 8.0;    // kiter: back away if closer than this
    private static final double KITE_ACTIVE_SQ = 28.0 * 28.0; // kiter stays engaged out to here
    private static final int INTERVAL = 35;                  // ticks between shots

    private final BarbarianEntity mob;
    private final ResourceLocation projectileId;
    private final double damage;
    private final boolean kite;
    private final int windupLength;   // telegraph: ticks of visible aim/draw before release
    private int cooldown;
    private int windup;               // >0 while winding up a throw/draw
    private LivingEntity windupTarget;

    public BarbarianRangedGoal(BarbarianEntity mob, ResourceLocation projectileId, double damage,
                               boolean kite) {
        this.mob = mob;
        this.projectileId = projectileId;
        this.damage = damage;
        this.kite = kite;
        // Telegraph the attack like the hunter: a bow draws ~0.7s, a spear winds up ~0.9s. Without this
        // there's no tell before a projectile leaves, which the player called out.
        this.windupLength = kite ? 14 : 18;
        if (kite) {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); // kiters drive their own positioning
        }
        // Brutes stay flagless so the melee goal moves them while this still fires.
    }

    @Override
    public void stop() {
        if (windup > 0) mob.stopUsingItem(); // drop the aim pose if interrupted
        windup = 0;
        windupTarget = null;
    }

    /** Enter the visible aim/draw telegraph: raise the weapon (UseAnim) and hold for windupLength. */
    private void beginWindup(LivingEntity target) {
        windup = windupLength;
        windupTarget = target;
        mob.getNavigation().stop();              // plant feet to aim
        mob.startUsingItem(InteractionHand.MAIN_HAND); // arm raise / bow draw pose
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        if (t == null || !t.isAlive()) return false;
        double d = mob.distanceToSqr(t);
        if (kite) return d <= KITE_ACTIVE_SQ;
        return d >= MIN_RANGE_SQ && d <= MAX_RANGE_SQ && mob.getSensing().hasLineOfSight(t);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        LivingEntity t = mob.getTarget();
        if (t == null) return;
        mob.getLookControl().setLookAt(t, 30.0F, 30.0F);

        // Mid-telegraph: keep aim locked, hold the draw, release when the wind-up elapses.
        if (windup > 0) {
            if (kite) mob.getNavigation().stop();
            if (--windup <= 0) {
                mob.stopUsingItem();
                if (windupTarget != null && windupTarget.isAlive()
                        && mob.getSensing().hasLineOfSight(windupTarget)) {
                    fire(windupTarget);
                }
                windupTarget = null;
                cooldown = INTERVAL;
            }
            return;
        }

        double d = mob.distanceToSqr(t);
        double speed = mob.combatSpeed(); // slowed + per-member varied, same as the melee chase
        if (kite) {
            holdRangedItem();
            if (d < KITE_NEAR_SQ) {
                backAwayFrom(t, speed);                // too close — retreat to regain distance
            } else if (d <= MAX_RANGE_SQ) {
                mob.getNavigation().stop();            // in the sweet spot — stand and loose
                if (cooldown-- <= 0 && mob.getSensing().hasLineOfSight(t)) {
                    beginWindup(t);                    // draw the bow (telegraph) before firing
                }
            } else {
                mob.getNavigation().moveTo(t, 0.9 * speed); // too far — close to firing range
            }
        } else {
            if (cooldown-- <= 0 && d >= MIN_RANGE_SQ && d <= MAX_RANGE_SQ
                    && mob.getSensing().hasLineOfSight(t)) {
                beginWindup(t);                        // wind up the spear throw (telegraph)
            }
        }
    }

    private void backAwayFrom(LivingEntity t, double speed) {
        Vec3 away = mob.position().subtract(t.position());
        if (away.lengthSqr() < 1.0e-3) away = new Vec3(1, 0, 0);
        Vec3 dest = mob.position().add(away.normalize().scale(8.0));
        mob.getNavigation().moveTo(dest.x, dest.y, dest.z, 1.0 * speed);
    }

    private void holdRangedItem() {
        Item ranged = mob.rangedItem();
        if (ranged != null && !mob.getMainHandItem().is(ranged)) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(ranged));
        }
    }

    private void fire(LivingEntity target) {
        if (!(mob.level() instanceof ServerLevel sl)) return;
        AbstractArrow arrow = BarbarianProjectiles.create(sl, mob, projectileId, damage);
        if (arrow == null) return;
        double dx = target.getX() - arrow.getX();
        double dy = target.getY(0.5) - arrow.getY(); // aim at the target's body centre
        double dz = target.getZ() - arrow.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horiz * 0.10, dz, 1.5F, 8.0F); // slight arc + a little inaccuracy
        mob.swing(InteractionHand.MAIN_HAND);
        sl.addFreshEntity(arrow);
    }
}
