package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.job.CitizenJobRegistry;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.WorkstationUnlocks;

/**
 * Gatherer-job catalogue + the settlement's labor-priority maths. With no government to assign work,
 * a settlement's citizens employ <i>themselves</i> — but only into the <b>gatherer</b> roles, the
 * self-directed jobs that scan the world for resources ({@link ForesterWorkGoal},
 * {@link FisherWorkGoal}, {@link ForagerWorkGoal}). The <b>ordered</b> roles (digger/farmer, driven
 * by Foreman's-Rod orders) and logistics/herder roles need direction and stay manual.
 *
 * <p>This class is the single source of truth for "which jobs are gatherers" and for turning the
 * settlement's player-set priority list into weighted target head-counts that the
 * {@link AnarchyJobDistributor} staffs toward. The Job-tab switch list and the server's
 * switch-request guard read the same gatherer set so everything agrees.
 */
@ApiStatus.Internal
public final class AnarchyJobs {
    /** The built-in Core gatherer job ids. Expansion gatherers are added via
     *  {@link CitizenJobRegistry} and merged in by {@link #isGathererJob}/{@link #gathererOrder}. */
    private static final Set<String> BUILTIN_GATHERER_IDS = Set.of(
        ForesterWorkGoal.JOB_TYPE_ID,
        FisherWorkGoal.JOB_TYPE_ID,
        ForagerWorkGoal.JOB_TYPE_ID);

    /** Stable default order for the built-in gatherer jobs (forester, fisher, forager) — used when the
     *  player hasn't ranked them and to append newly-unlocked jobs. Registry gatherers are appended
     *  after these by {@link #gathererOrder}, sorted by their {@code anarchyOrder}. */
    private static final String[] BUILTIN_ORDER = {
        ForesterWorkGoal.JOB_TYPE_ID,
        FisherWorkGoal.JOB_TYPE_ID,
        ForagerWorkGoal.JOB_TYPE_ID,
    };

    private AnarchyJobs() {
    }

    /** True if {@code id} is one of the self-organizing gatherer jobs (built-in or registry). */
    public static boolean isGathererJob(@Nullable String id) {
        if (id == null) return false;
        if (BUILTIN_GATHERER_IDS.contains(id)) return true;
        CitizenJobRegistry.JobDef d = CitizenJobRegistry.byId(id);
        return d != null && d.gatherer();
    }

    /** Full gatherer order: the built-ins first, then registry gatherers sorted by {@code anarchyOrder}. */
    private static List<String> gathererOrder() {
        List<String> out = new ArrayList<>(BUILTIN_ORDER.length + 2);
        for (String j : BUILTIN_ORDER) out.add(j);
        List<CitizenJobRegistry.JobDef> defs = new ArrayList<>();
        for (CitizenJobRegistry.JobDef d : CitizenJobRegistry.all()) {
            if (d.gatherer()) defs.add(d);
        }
        defs.sort(Comparator.comparingInt(CitizenJobRegistry.JobDef::anarchyOrder));
        for (CitizenJobRegistry.JobDef d : defs) {
            if (!out.contains(d.jobTypeId())) out.add(d.jobTypeId());
        }
        return out;
    }

    /** Research-unlocked gatherer job ids for this settlement, in default order. Mirrors the
     *  unlock-flag gate {@code ServerPayloadHandler.unlockedJobTypeIds} uses, restricted to gatherers.
     *  A registry gatherer marked {@code obsoletedByUnit} drops out once that successor unit is
     *  researched, so the distributor re-skills its holders toward the successor (spear → rod fisher). */
    public static List<String> unlockedGathererJobs(Settlement s) {
        List<String> out = new ArrayList<>();
        for (String job : gathererOrder()) {
            String flag = WorkstationUnlocks.flagForWorkstation(job);
            if (flag != null && !ResearchManager.hasFlag(s, flag)) continue;   // not unlocked yet
            if (isObsoleted(s, job)) continue;                                 // superseded by a later job
            out.add(job);
        }
        return out;
    }

    /** True when {@code job} is a registry gatherer whose successor unit ({@code obsoletedByUnit}) has
     *  been researched — i.e. the job is retired in favour of that successor. */
    public static boolean isObsoleted(Settlement s, String job) {
        CitizenJobRegistry.JobDef d = CitizenJobRegistry.byId(job);
        if (d == null || d.obsoletedByUnit() == null) return false;
        return ResearchManager.hasFlag(s, WorkstationUnlocks.flagForUnit(d.obsoletedByUnit()));
    }

    /** Unlocked gatherers ordered by the settlement's labor priority (player order first, then any
     *  unlocked-but-unranked jobs appended in default order). Includes disabled jobs — the Town Hall
     *  list shows them toggled off; the distributor filters them via {@link #enabledOrderedGatherers}. */
    public static List<String> orderedUnlockedGatherers(Settlement s) {
        List<String> unlocked = unlockedGathererJobs(s);
        List<String> out = new ArrayList<>(unlocked.size());
        for (String j : s.laborPriority()) {
            if (unlocked.contains(j) && !out.contains(j)) out.add(j);
        }
        for (String j : unlocked) {
            if (!out.contains(j)) out.add(j);   // newly-unlocked / unranked → default order, at the end
        }
        return out;
    }

    /** Enabled (not switched off) unlocked gatherers in priority order — the jobs the distributor staffs. */
    public static List<String> enabledOrderedGatherers(Settlement s) {
        List<String> out = new ArrayList<>();
        for (String j : orderedUnlockedGatherers(s)) {
            if (!s.isLaborJobDisabled(j)) out.add(j);
        }
        return out;
    }

    /**
     * Weighted target head-count per enabled gatherer job. Linear weights {@code k, k-1, ... 1} by
     * priority position (top of the list = most workers) drive a proportional spread, but each job is
     * bounded by its {@linkplain Settlement#laborCap worker cap} ({@code -1} = no limit). Workers are
     * handed out one at a time to the job with the best {@code weight / (held + 1)} ratio (the
     * Webster/Sainte-Laguë divisor method), skipping any job already at its cap — so a capped job's
     * surplus cascades to the other unlocked gatherers, and if every enabled job is capped out the
     * remaining workers go unplaced (they stay unemployed; the distributor re-checks next tick).
     * Iteration order is priority order. Empty when no enabled gatherer job is unlocked.
     */
    public static Map<String, Integer> weightedTargets(Settlement s, int poolSize) {
        List<String> jobs = enabledOrderedGatherers(s);
        Map<String, Integer> targets = new LinkedHashMap<>();
        int k = jobs.size();
        if (k == 0) return targets;
        int[] res = new int[k];
        int[] weight = new int[k];
        int[] cap = new int[k];
        for (int i = 0; i < k; i++) {
            weight[i] = k - i;                  // top of the list = highest weight
            cap[i] = s.laborCap(jobs.get(i));   // -1 = no limit
        }
        for (int n = 0; n < poolSize; n++) {
            int best = -1;
            double bestScore = -1.0;
            for (int i = 0; i < k; i++) {
                if (cap[i] >= 0 && res[i] >= cap[i]) continue;            // capped out
                double score = (double) weight[i] / (res[i] + 1);
                if (score > bestScore) { bestScore = score; best = i; }   // ties → higher priority (lower i)
            }
            if (best < 0) break;   // every enabled job is capped — leave the rest unplaced
            res[best]++;
        }
        for (int i = 0; i < k; i++) targets.put(jobs.get(i), res[i]);
        return targets;
    }
}
