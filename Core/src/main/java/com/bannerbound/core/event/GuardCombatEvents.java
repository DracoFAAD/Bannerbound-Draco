package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.GuardWorkGoal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Guard combat event hooks (GUARD_PLAN.md §11 — "guards counter-attack whoever damages them"):
 *
 * <ul>
 *   <li><b>Retaliation license.</b> Any damage to a guard from a non-friendly living attacker
 *       records that attacker as the guard's retaliation target
 *       ({@link CitizenEntity#noteGuardRetaliation}). {@code GuardTargetingGoal} folds it into
 *       target scoring and {@code GuardCombatGoal} accepts it even outside the defense-band
 *       leash — so a guard is never a free kill for an attacker its normal hostile predicate
 *       doesn't cover (an enemy player outside a rally, a raider plinking from past the border).
 *       This is also what justifies the guard's combat-hurt panic immunity: a guard that can't
 *       flee combat pain must always be allowed to answer it.</li>
 *   <li><b>Kill XP.</b> Guards earn their {@code guards_post} XP on the KILL regardless of how it
 *       landed (melee swing, arrow, sling rock) — the death event sees the projectile owner, which
 *       the combat goal's swing loop can't.</li>
 * </ul>
 *
 * <p>Friendly attackers never trigger retaliation: a fellow citizen of the same settlement (that's
 * a brawl — {@link CitizenBrawlEvents} owns it) or a player member of the guard's own settlement
 * (an accidental whack from the boss shouldn't start a fight to the death).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class GuardCombatEvents {
    private GuardCombatEvents() {
    }

    /** True when {@code attacker} damaging {@code guard} should flip the guard into real combat —
     *  i.e. the attacker is NOT "one of ours". Shared with {@link CitizenBrawlEvents} so the brawl
     *  roll and guard retaliation never both claim the same hit. */
    public static boolean isRetaliationWorthy(CitizenEntity guard, LivingEntity attacker) {
        if (attacker == guard || !attacker.isAlive()) return false;
        Settlement s = guard.getSettlement();
        if (attacker instanceof CitizenEntity ac) {
            // Same-settlement citizen → internal brawl, not war. Barbarians/mercenaries subclass
            // CitizenEntity but carry no (or a different) settlement id, so they fall through.
            return ac.getSettlementId() == null
                || !ac.getSettlementId().equals(guard.getSettlementId());
        }
        if (attacker instanceof Player p) {
            return s == null || !s.members().contains(p.getUUID());
        }
        return true;   // mobs, and anything else that bleeds
    }

    @SubscribeEvent
    public static void onGuardHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity guard) || !guard.isGuard()) return;
        if (!(guard.level() instanceof ServerLevel sl)) return;
        Entity raw = event.getSource().getEntity();
        if (!(raw instanceof LivingEntity attacker)) return;
        if (!isRetaliationWorthy(guard, attacker)) return;
        guard.noteGuardRetaliation(attacker.getUUID(), sl.getGameTime());
        // Snap response: if the guard is idle, aim it at the attacker right away rather than
        // waiting out GuardTargetingGoal's next staggered pass.
        LivingEntity current = guard.getTarget();
        if (current == null || !current.isAlive()) {
            guard.setTarget(attacker);
        }
    }

    /**
     * NPC projectiles never harm their own settlement: an arrow or sling rock fired by a citizen
     * (guard, hunter) that would strike a fellow citizen or a member player passes clean through
     * (impact cancelled — the standard invisible rule of every colony/RTS game). Without this, a
     * stray guard arrow into the melee line dealt real damage AND read as "citizen hit citizen",
     * rolling a BRAWL between two guards mid-raid. Enemy/neutral targets are untouched; barbarians
     * and mercenaries carry no (or a different) settlement id, so their shots still hurt.
     */
    @SubscribeEvent
    public static void onProjectileImpact(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event) {
        if (!(event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit)) return;
        if (!(event.getProjectile().getOwner() instanceof CitizenEntity shooter)) return;
        java.util.UUID home = shooter.getSettlementId();
        if (home == null) return;   // barbarians / mercenaries: not our rule
        Entity struck = hit.getEntity();
        boolean friendly = (struck instanceof CitizenEntity c && home.equals(c.getSettlementId()))
            || (struck instanceof Player p && shooter.getSettlement() != null
                && shooter.getSettlement().members().contains(p.getUUID()));
        if (friendly) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onGuardKill(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof CitizenEntity guard) || !guard.isGuard()) {
            return;
        }
        LivingEntity victim = event.getEntity();
        // Credit only legitimate quarry — hostiles by any route, or the licensed retaliation
        // target — so herding livestock into a guard can't farm watch XP.
        if (!guard.isHostileToMe(victim) && !guard.isGuardRetaliationTarget(victim)) return;
        guard.grantJobXp(GuardWorkGoal.JOB_TYPE_ID, 1.0f, "guard");
    }
}
