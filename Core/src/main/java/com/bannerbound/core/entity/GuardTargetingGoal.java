package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * The guard squad's <b>tactical targeting brain</b> (GUARD_PLAN.md §10). A target-selector goal (so it
 * keeps working even when the work brain is throttled at Village+), it picks — every
 * {@link #REASSIGN_INTERVAL} ticks — the best hostile for THIS guard to fight, <i>replacing</i> the
 * naive "nearest" the shared citizen selectors use for militia. The squad therefore <b>spreads across
 * raiders</b> instead of dog-piling one, while still prioritising the raiders that matter.
 *
 * <p>Decentralised — no central manager. Each guard scores every in-band hostile:
 * <ul>
 *   <li><b>Spread</b> (−) by the number of OTHER guards already on it → the squad fans out;</li>
 *   <li><b>Objective</b> (+) for hostiles near the banner / town hall → defend the core;</li>
 *   <li><b>Threat</b> (+) for ranged kiters → pin the dangerous, slippery ones;</li>
 *   <li><b>Finish</b> (+) for low-health targets → gang up to put a wounded raider down;</li>
 *   <li><b>Distance</b> (−) as a tie-break → don't sprint across town.</li>
 * </ul>
 * It reads peers' live {@link CitizenEntity#getTarget()} for the spread term; staggered scans let the
 * assignment settle over a couple of passes. {@link GuardCombatGoal} then does the moving and swinging
 * on whatever target this goal set. Enemy <i>players</i> are left to the rally player selector — this
 * goal never touches a Player target.
 */
@ApiStatus.Internal
public class GuardTargetingGoal extends Goal {
    private static final int REASSIGN_INTERVAL = 12;
    private static final double SCAN_RADIUS = 28.0;
    private static final double SCAN_HEIGHT = 10.0;

    private static final double SPREAD_WEIGHT = 6.0;     // per fellow guard already on the target
    private static final double THREAT_BONUS = 5.0;      // ranged kiter
    private static final double FINISH_BONUS = 4.0;      // target below 40% health
    /** Bonus for the attacker who is actively damaging THIS guard (the retaliation license from
     *  {@code GuardCombatEvents}) — answering your own assailant usually outranks fanning out. */
    private static final double RETALIATION_BONUS = 8.0;
    private static final double OBJECTIVE_WEIGHT = 0.15; // per block of banner-proximity
    private static final double OBJECTIVE_CAP = 6.0;
    private static final double DIST_WEIGHT = 0.08;      // per block from this guard (tie-break)

    private final CitizenEntity citizen;
    private int cooldown;

    public GuardTargetingGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        // No flags: this only writes the target field; GuardCombatGoal (goalSelector) does the fighting.
        this.cooldown = citizen.getId() % REASSIGN_INTERVAL;   // stagger so a squad doesn't scan in lockstep
    }

    @Override
    public boolean canUse() {
        return citizen.isGuard() && citizen.level() instanceof ServerLevel;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        if (--cooldown > 0) return;
        cooldown = REASSIGN_INTERVAL;
        if (citizen.level() instanceof ServerLevel sl) reassign(sl);
    }

    private void reassign(ServerLevel sl) {
        Settlement s = citizen.getSettlement();
        if (s == null) return;
        // Whoever last damaged this guard is a legal target regardless of band or type — the
        // retaliation license from GuardCombatEvents ("guards counter-attack whoever hurts them").
        LivingEntity retaliation = citizen.guardRetaliationTarget(sl);
        // A rally-targeted enemy player outranks raider assignment — leave Player targets to the
        // player selector (priority 0) — UNLESS that player is this guard's live retaliation
        // attacker, in which case this goal owns the fight like any raider's.
        if (citizen.getTarget() instanceof Player p && !citizen.isGuardRetaliationTarget(p)) return;

        AABB box = citizen.getBoundingBox().inflate(SCAN_RADIUS, SCAN_HEIGHT, SCAN_RADIUS);
        List<LivingEntity> hostiles = new ArrayList<>();
        List<CitizenEntity> peers = new ArrayList<>();
        for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e == citizen) continue;
            if (e instanceof CitizenEntity c && c.isGuard() && c.getSettlement() == s) {
                peers.add(c);
            } else if (citizen.isHostileToMe(e) && GuardCombatGoal.withinDefenseBand(s, e.blockPosition())) {
                hostiles.add(e);
            }
        }
        if (retaliation != null && !hostiles.contains(retaliation)) {
            hostiles.add(retaliation);   // may be out-of-band or a player — still fair game
        }

        if (hostiles.isEmpty()) {
            // No raiders on home ground — drop any stale target so the guard returns to patrol.
            if (citizen.getTarget() != null) citizen.setTarget(null);
            return;
        }

        BlockPos hub = s.hasTownHall() ? s.townHallPos() : s.bannerPos();
        LivingEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (LivingEntity h : hostiles) {
            double score = 0.0;
            // Spread: fan the squad out across the raiders.
            int coverage = 0;
            for (CitizenEntity p : peers) {
                if (p.getTarget() == h) coverage++;
            }
            score -= coverage * SPREAD_WEIGHT;
            // Objective: a raider closer to the hub is more urgent — defend the core.
            if (hub != null) {
                double dHub = Math.sqrt(h.distanceToSqr(hub.getX() + 0.5, hub.getY(), hub.getZ() + 0.5));
                score += Math.max(0.0, OBJECTIVE_CAP - dHub * OBJECTIVE_WEIGHT);
            }
            // Retaliation: the one actively shooting/stabbing THIS guard.
            if (h == retaliation) score += RETALIATION_BONUS;
            // Threat: kiting archers are dangerous and hard to pin — bump them up.
            if (h instanceof CombatantCitizen cc && cc.prefersRanged()) score += THREAT_BONUS;
            // Finish: gang up to put a wounded raider down.
            if (h.getMaxHealth() > 0 && h.getHealth() < h.getMaxHealth() * 0.4f) score += FINISH_BONUS;
            // Distance tie-break: prefer the closer of otherwise-equal targets.
            score -= Math.sqrt(citizen.distanceToSqr(h)) * DIST_WEIGHT;
            if (score > bestScore) { bestScore = score; best = h; }
        }
        if (best != null && best != citizen.getTarget()) citizen.setTarget(best);
    }
}
