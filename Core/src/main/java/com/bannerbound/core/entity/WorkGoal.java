package com.bannerbound.core.entity;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workstation;

import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Common base for every citizen work goal. Owns the universal scaffolding that every concrete
 * goal would otherwise duplicate:
 *
 * <ul>
 *   <li>The owning {@link CitizenEntity} and a pathfinding {@code speedModifier} — supplied by
 *       the concrete subclass and exposed to it via protected fields.</li>
 *   <li>The vanilla {@link Goal.Flag#MOVE} + {@link Goal.Flag#LOOK} flags, set in the base
 *       constructor since all current work goals claim both.</li>
 *   <li>A shared {@link #findAssignment()} resolver: settlement → workstation-for-this-citizen
 *       → type check via {@link #workstationTypeId()} → building-validity check → active
 *       toggle. Returns {@code null} (the universal "yield to patrol" signal) whenever any of
 *       those guards trip.</li>
 * </ul>
 *
 * <p>Concrete goals supply the workstation type id by implementing {@link #workstationTypeId()}.
 * Intermediate category abstracts ({@link GathererWorkGoal}, {@link OrderedWorkGoal},
 * {@link LogisticsWorkGoal}) sit between this and the leaf goals to keep the taxonomy explicit
 * and give future shared behaviour (e.g. a common gatherer scan loop) a clean home.
 */
@ApiStatus.Internal
public abstract class WorkGoal extends Goal {
    protected final CitizenEntity citizen;
    protected final double speedModifier;

    /** Top-end movement-speed bonus at mastery: a novice travels at the base job speed, a master
     *  at +40%. Shared so every profession quickens on one curve. */
    private static final double SKILL_SPEED_BONUS = 0.4;
    /** Top-end work-action speed-up at mastery: a master's hands shave up to 45% off a task's
     *  tick budget. Mirrors {@code CrafterWorkGoal.skillScaledTicks} (novice ≈ ×1.0, master ≈ ×0.55). */
    private static final float SKILL_WORK_SPEEDUP = 0.45F;

    protected WorkGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /**
     * This citizen's skill in the job this goal runs, on the shared crafter-system XP-saturation
     * curve: {@code xp / (xp + NPC_XP_HALF)} — 0 for a raw novice, climbing asymptotically toward
     * 1 (half-way at {@link com.bannerbound.core.api.quality.QualityMath#NPC_XP_HALF} completed
     * actions). The XP bucket is keyed by this goal's own {@link #workstationTypeId()}, the same
     * key {@code grantJobXp} writes to, so "skill" reads identically for every profession.
     */
    protected final float jobSkill() {
        float xp = citizen.getJobXp(workstationTypeId());
        return xp / (xp + com.bannerbound.core.api.quality.QualityMath.NPC_XP_HALF);
    }

    /**
     * The pathfinding speed a skilled worker travels at — the universal "better at the job → gets
     * there faster" lever every goal routes its navigation through. Base job {@code speedModifier}
     * for a novice, rising to +{@value #SKILL_SPEED_BONUS} at mastery, with mood riding on top
     * exactly as it scales crafter work speed (happy ≈ +15%, miserable ≈ −30%).
     */
    protected final double skilledSpeed() {
        return speedModifier * (1.0 + SKILL_SPEED_BONUS * jobSkill())
            * citizen.happinessPerformanceMultiplier();
    }

    /**
     * Scales a base work-action tick budget by skill: full duration for a novice, down to
     * ×(1−{@value #SKILL_WORK_SPEEDUP}) at mastery, with mood folded in the same direction a happy
     * worker is quicker (divide — the multiplier is a speed, not a duration). Mirrors
     * {@code CrafterWorkGoal.skillScaledTicks} so every profession's hands quicken on one curve.
     * Never returns less than 1 tick.
     */
    protected final int skilledWorkTicks(int baseTicks) {
        float mult = 1.0F - SKILL_WORK_SPEEDUP * jobSkill();
        mult /= citizen.happinessPerformanceMultiplier();
        return Math.max(1, Math.round(baseTicks * mult));
    }

    /**
     * Universal stamina gate. {@link #canUse()} and {@link #canContinueToUse()} are {@code final}
     * here so no work goal can forget it: a stamina-exhausted citizen neither <i>starts</i> nor
     * <i>keeps</i> a job — the moment stamina hits 0 the goal yields and the citizen drops to
     * {@link SettlementPatrolGoal} to rest (see {@link CitizenEntity#isStaminaExhausted()}).
     *
     * <p>Subclasses express only <i>their own</i> start / continue conditions through
     * {@link #canStartWork()} / {@link #canKeepWorking()}. How much stamina a given task spends,
     * and when, stays each goal's own business — only the "0 stamina ⇒ stop" rule is shared.
     */
    @Override
    public final boolean canUse() {
        if (citizen.isOnTradeJourney()) return false;   // adopted trade courier — the sim owns the citizen
        if (citizen.usesAmbientBrain()) return false;   // Village+: labor is grouped/rate-based, not per-citizen A* work
        if (!citizen.isAiActive()) return false;        // no player nearby → don't scan for work, idle
        if (citizen.isStaminaExhausted()) return false;
        if (citizen.isPoisoned()) return false;         // too sick to work until cured (POISON_PLAN)
        if (citizen.isPregnant() || citizen.isChild()) return false;
        // No faction banner raised → ALL labor halts, every job, anarchy included — the
        // settlement is literally not bound to anything (see FactionBanner). Citizens drop to
        // patrol/social until a member plants a banner in territory.
        if (isBannerDown()) return false;
        // RALLY = total mobilization: every citizen drops their job to fight (or stand ready) until the
        // rally is called off. Guards keep DEFENDING regardless through GuardCombatGoal (a non-WorkGoal),
        // and their own patrol is just paused like any other work. See GUARD_PLAN.md.
        if (citizen.isSettlementRallying()) return false;
        // Self-organized anarchy gatherers ignore the social-window pause and every compliance
        // refusal/strike thought — in anarchy they work willingly and compliance instead governs
        // whether they consent to a player-requested job switch (see ServerPayloadHandler).
        if (!isAnarchyAuto()) {
            if (isAfternoonGathering(citizen)) return false;
            if (hasRefusalThought(citizen)) return false;
        }
        // Carry pack full → yield so DeliverHaulGoal walks the load to the town hall before we gather
        // more (anarchy, no real storage).
        if (isAnarchyAuto() && citizen.isAnarchyHaulDropOff() && citizen.isHaulFull()) return false;
        // Stagger the (potentially A*-triggering) work scan onto this citizen's think tick so work
        // starts spread across ticks rather than clustering. Only gates STARTING work; an
        // already-running job continues every tick via canContinueToUse below.
        if (!citizen.isThinkTick()) return false;
        return canStartWork();
    }

    @Override
    public final boolean canContinueToUse() {
        if (citizen.isOnTradeJourney()) return false;   // adopted trade courier — the sim owns the citizen
        if (citizen.usesAmbientBrain()) return false;
        if (citizen.isStaminaExhausted()) return false;
        if (citizen.isPoisoned()) return false;         // drop the active job while poisoned (POISON_PLAN)
        // Pregnant women and children drop the active job mid-task the moment the flag flips —
        // a worker who becomes pregnant overnight wakes up without a job; a citizen who ages
        // back into adulthood can immediately claim one on the next 20-tick poll.
        if (citizen.isPregnant() || citizen.isChild()) return false;
        if (isBannerDown()) return false; // banner falls mid-task → tools down immediately
        if (citizen.isSettlementRallying()) return false; // rally called → drop the job and fight
        if (!isAnarchyAuto()) {
            if (isAfternoonGathering(citizen)) return false;
            if (hasRefusalThought(citizen)) return false;
        }
        if (isAnarchyAuto() && citizen.isAnarchyHaulDropOff() && citizen.isHaulFull()) return false;
        return canKeepWorking();
    }

    /** True when the citizen belongs to a settlement whose FACTION BANNER is down — the
     *  universal "no banner, no labor" sanction (see
     *  {@link com.bannerbound.core.api.settlement.FactionBanner}). Settlement-less citizens
     *  (wanderers) are unaffected. */
    private boolean isBannerDown() {
        Settlement s = citizen.getSettlement();
        return s != null && !s.hasFactionBanner();
    }

    /** True when this goal is a self-directed gatherer ({@link #isAnarchyAutoEligible()}) AND the
     *  citizen's settlement is in anarchy — i.e. this is self-organized work that bypasses the
     *  social window and every compliance refusal/strike thought. */
    private boolean isAnarchyAuto() {
        return isAnarchyAutoEligible() && citizen.isAnarchy();
    }

    /** Whether this work goal auto-employs and runs under anarchy. Only the self-directed
     *  {@link GathererWorkGoal gatherers} return true; ordered/logistics workers need a government
     *  and stay gated by the normal refusal checks. */
    protected boolean isAnarchyAutoEligible() {
        return false;
    }

    /** Step 12: returns true while this citizen has any active "no work" thought —
     *  {@link com.bannerbound.core.social.ThoughtKind#NO_WORK_RIGHT_NOW NO_WORK_RIGHT_NOW}
     *  (any work), {@link com.bannerbound.core.social.ThoughtKind#NO_WORK_TODAY NO_WORK_TODAY}
     *  (full-day strike), or {@link com.bannerbound.core.social.ThoughtKind#NO_WORK_AS_JOB
     *  NO_WORK_AS_JOB} (any job — even per-job, dropping ALL work for the minute is the
     *  simpler behaviour; a citizen who refuses Forester won't immediately Farmer either).
     *  This is the shared gate that makes refusal thoughts actually suppress work. */
    public static boolean hasRefusalThought(CitizenEntity citizen) {
        com.bannerbound.core.social.Thoughts t = citizen.getThoughts();
        if (t == null) return false;
        if (t.has(com.bannerbound.core.social.ThoughtKind.NO_WORK_RIGHT_NOW, null)) return true;
        if (t.has(com.bannerbound.core.social.ThoughtKind.NO_WORK_TODAY, null)) return true;
        // Per-partner NO_WORK_AS_JOB: any active entry suppresses; the partner UUID is just a
        // tag for "which job was refused" — we don't gate per-job here, refusing one job
        // pauses the citizen's whole work loop until the thought expires.
        for (com.bannerbound.core.social.Thought th : t.entries()) {
            if (th.kind() == com.bannerbound.core.social.ThoughtKind.NO_WORK_AS_JOB) return true;
        }
        return false;
    }

    /** Late-afternoon gathering window — citizens stop working and patrol around the town hall
     *  / campfire for the last 2 in-game minutes (2400 ticks) before night begins at 12500. The
     *  intent is that even a fully-employed settlement still has a social window before bed;
     *  ConversationGoal naturally fires once work yields, so this is the only hook needed.
     *  10_100 = 12_500 (night start, matches SleepGoal) − 2_400 (2 in-game min). */
    public static boolean isAfternoonGathering(CitizenEntity c) {
        if (!(c.level() instanceof net.minecraft.server.level.ServerLevel sl)) return false;
        // Nightshift policy cancels the pre-bed social window — employed citizens keep working
        // right up until they head to bed (SleepGoal still pulls them at night).
        com.bannerbound.core.api.settlement.Settlement s = c.getSettlement();
        if (s != null && s.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.NIGHTSHIFT)) {
            return false;
        }
        // Night Watch keeps GUARDS on the beat straight through dusk — no campfire hour for the
        // watch (everyone else still gathers; SleepGoal's guard exemption handles the night).
        if (s != null && c.isGuard()
                && s.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.NIGHT_WATCH)) {
            return false;
        }
        long t = sl.getDayTime() % 24_000L;
        return t >= 10_100L && t < 12_500L;
    }

    /** Goal-specific start condition; the stamina check is already handled by {@link #canUse()}. */
    protected abstract boolean canStartWork();

    /** Goal-specific continue condition; the stamina check is already handled by
     *  {@link #canContinueToUse()} — a worker that hits 0 stamina stops mid-task and rests. */
    protected abstract boolean canKeepWorking();

    /**
     * Workstation type id this goal expects to be assigned to (e.g. {@code "foresters_log"}).
     * Used by {@link #findAssignment()} to reject assignments to the wrong type. Each concrete
     * goal returns its block entity's {@code TYPE_ID} constant.
     */
    protected abstract String workstationTypeId();

    /**
     * Returns the citizen's current workstation assignment if and only if every gating check
     * passes: the citizen is in a settlement, the settlement has an assignment for them, that
     * assignment matches {@link #workstationTypeId()}, the building is still structurally
     * valid, and the player hasn't toggled the workstation inactive. {@code null} otherwise —
     * concrete goals treat that as "drop back to patrol."
     */
    protected Workstation findAssignment() {
        Settlement s = citizen.getSettlement();
        if (s == null) return null;
        Workstation ws = s.getWorkstationFor(citizen.getUUID());
        if (ws == null) return null;
        if (!workstationTypeId().equals(ws.type())) return null;
        if (!ws.buildingValid()) return null;
        if (!ws.active()) return null;
        return ws;
    }
}
