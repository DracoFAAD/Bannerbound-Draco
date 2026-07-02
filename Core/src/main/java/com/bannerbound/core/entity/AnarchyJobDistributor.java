package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementManager;

import net.minecraft.server.level.ServerLevel;

/**
 * Settlement-level labor distribution. Each time it runs (once per second, from
 * {@code ImmigrationManager.tickAll}) it nudges the gatherer-eligible citizens toward the weighted
 * target head-counts derived from the settlement's labor-priority list
 * ({@link AnarchyJobs#weightedTargets}):
 *
 * <ul>
 *   <li><b>Employ</b> — every unemployed adult is put straight into the most under-staffed job, so
 *       fresh immigrants start working within a second.</li>
 *   <li><b>Re-skill</b> — at most ONE over-staffed citizen switches toward an under-staffed job per
 *       run, so the mix converges gradually without job-flip thrash. This is what stops a player who
 *       grows population fast from being stuck with (say) six foragers forever: the moment a new
 *       gatherer is researched, the targets shift and workers re-skill toward it one per second.</li>
 * </ul>
 *
 * <p>Runs in anarchy always; under a government only while {@link Settlement#laborAutoAssign()} is
 * left on (a chief / council member can switch it off to assign per-citizen instead). Ordered jobs
 * (digger/farmer/herder/stocker) are never touched — they need work-area setup and stay manual, so
 * their holders are excluded from the distributable pool. Re-skilling uses {@link
 * CitizenEntity#setJobType} which keeps the citizen's drop-off / carry pack (it only clears those on
 * a true unassign), so a re-skilled worker doesn't lose its destination.
 */
@ApiStatus.Internal
public final class AnarchyJobDistributor {
    private AnarchyJobDistributor() {
    }

    public static void tick(ServerLevel sl, Settlement s) {
        if (s == null) return;
        List<CitizenEntity> pool = distributablePool(sl, s);
        if (pool.isEmpty()) return;
        Map<String, Integer> targets = AnarchyJobs.weightedTargets(s, pool.size());
        if (targets.isEmpty()) return;   // no enabled gatherer job unlocked → nothing to staff

        Map<String, Integer> current = currentCounts(pool, targets.keySet());

        // Employ every unemployed adult into the most under-staffed job (fast — no thrash, they had
        // no job to flip from).
        for (CitizenEntity c : pool) {
            if (c.getJobType() != null) continue;
            String job = mostUnder(targets, current);
            if (job == null) break;
            c.setJobType(job);
            current.put(job, current.getOrDefault(job, 0) + 1);
        }

        // Re-skill ONE misallocated citizen toward the most under-staffed job (gradual convergence).
        // A citizen sitting in a now-DISABLED or no-longer-staffed gatherer job (one not among the
        // targets) is moved FIRST — otherwise the distributor never touches them and they're stranded
        // there forever after the player disables that job. Failing that, move one from an
        // over-target enabled job.
        String under = mostUnder(targets, current);
        if (under == null) return;   // already balanced
        CitizenEntity mover = firstInUnstaffedGathererJob(pool, targets.keySet());
        if (mover == null) mover = firstInOverTargetJob(pool, targets, current, under);
        if (mover != null) mover.setJobType(under);   // keeps drop-off / carry pack
    }

    /** A pool citizen whose job is a gatherer NOT among the current staffed targets (the player
     *  disabled it, or it's no longer unlocked) — they should be re-skilled onto an enabled job. */
    @Nullable
    private static CitizenEntity firstInUnstaffedGathererJob(List<CitizenEntity> pool, Set<String> staffed) {
        for (CitizenEntity c : pool) {
            String j = c.getJobType();
            if (j != null && AnarchyJobs.isGathererJob(j) && !staffed.contains(j)) return c;
        }
        return null;
    }

    /** A pool citizen in an enabled job that currently has MORE workers than its target. */
    @Nullable
    private static CitizenEntity firstInOverTargetJob(List<CitizenEntity> pool, Map<String, Integer> targets,
                                                      Map<String, Integer> current, String under) {
        for (Map.Entry<String, Integer> e : targets.entrySet()) {
            String over = e.getKey();
            if (over.equals(under)) continue;
            if (current.getOrDefault(over, 0) <= e.getValue()) continue;   // not over its target
            for (CitizenEntity c : pool) {
                if (over.equals(c.getJobType())) return c;
            }
        }
        return null;
    }

    /** Adult, employable citizens that are unemployed OR hold a gatherer job. Excluded: ordered-job
     *  holders (digger/farmer/herder/stocker — player-managed); Village+ ambient-brain citizens (their
     *  labor is grouped, not per-citizen); and PINNED citizens, whose job the player set by hand as a
     *  manual override the distributor must not touch. */
    private static List<CitizenEntity> distributablePool(ServerLevel sl, Settlement s) {
        List<CitizenEntity> out = new ArrayList<>();
        for (CitizenEntity c : SettlementManager.allCitizensOf(sl, s)) {
            if (c.isChild() || c.isPregnant() || c.usesAmbientBrain() || c.isJobPinned()) continue;
            String job = c.getJobType();
            if (job == null || AnarchyJobs.isGathererJob(job)) out.add(c);
        }
        return out;
    }

    private static Map<String, Integer> currentCounts(List<CitizenEntity> pool, Set<String> jobs) {
        Map<String, Integer> counts = new HashMap<>();
        for (String j : jobs) counts.put(j, 0);
        for (CitizenEntity c : pool) {
            String j = c.getJobType();
            if (j != null && counts.containsKey(j)) counts.put(j, counts.get(j) + 1);
        }
        return counts;
    }

    /** The enabled job whose current count is furthest below its target (ties break toward priority
     *  order, the iteration order of {@code targets}). Null when every job is at or over target. */
    @Nullable
    private static String mostUnder(Map<String, Integer> targets, Map<String, Integer> current) {
        String best = null;
        int bestDeficit = 0;
        for (Map.Entry<String, Integer> e : targets.entrySet()) {
            int deficit = e.getValue() - current.getOrDefault(e.getKey(), 0);
            if (deficit > bestDeficit) { bestDeficit = deficit; best = e.getKey(); }
        }
        return best;
    }
}
