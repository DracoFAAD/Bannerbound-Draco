package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;

/**
 * The glanceable "what is this worker doing / why isn't it" verdict shown as the headline on the
 * Job tab (see {@code CitizenScreen} A1). One enum serves every job:
 *
 * <ul>
 *   <li>Generic activity / block states ({@link #WORKING}, {@link #IDLE}, {@link #NO_TOOL},
 *       {@link #NO_DROPOFF}, {@link #NO_STAMINA}) are <i>derived</i> server-side in
 *       {@code ServerPayloadHandler.sendCitizenJobState} from already-available facts — no per-goal
 *       instrumentation needed.</li>
 *   <li>The plantation-specific live sub-states ({@link #PLANTING}, {@link #WAITING},
 *       {@link #HARVESTING}, {@link #NEEDS_SAPLINGS}, {@link #AREA_FULL}) are published by
 *       {@link ForesterPlantationGoal} through {@link CitizenEntity#getCurrentWorkStatus()} (a
 *       transient runtime field it keeps fresh and clears to {@link #IDLE} on stop).</li>
 * </ul>
 *
 * <p>Sent to the client as the ordinal in {@code CitizenJobStatePayload}; the client maps it to a
 * colour + the {@code bannerbound.citizen.job.status.<name>} lang key. Ordinal order is the wire
 * contract — append new states at the end, never reorder.
 */
@ApiStatus.Internal
public enum CitizenWorkStatus {
    /** No job, or employed but momentarily nothing to do. */
    IDLE,
    /** Employed, tooled, unblocked — actively working (the generic "all good" state). */
    WORKING,
    /** Carrying a load back to the drop-off / town hall. */
    HAULING,
    /** Tending an order but waiting on something out of the worker's hands (e.g. crops/trees growing). */
    WAITING,
    /** Job needs a held tool and none is installed. */
    NO_TOOL,
    /** Job needs a marked drop-off and none is set. */
    NO_DROPOFF,
    /** Out of stamina — resting before it can work again. */
    NO_STAMINA,
    /** Something in the world is blocking the task (no valid target, area obstructed). */
    BLOCKED,
    /** The marked drop-off container is full — the worker can't deposit, so it has stopped. */
    STORAGE_FULL,
    // ── Forester plantation live sub-states ──────────────────────────────────────────────────────
    PLANTING,
    HARVESTING,
    NEEDS_SAPLINGS,
    AREA_FULL,
    // ── Schedule / settlement-wide states (derived in the payload handler) ────────────────────────
    /** Asleep in bed at night — {@link SleepGoal} preempts all work. */
    SLEEPING,
    /** The pre-bed social window (dusk): citizens gather and chat before sleeping. */
    SOCIALIZING,
    /** The faction banner is down — ALL labor in the settlement is halted until it's raised. */
    BANNER_DOWN,
    /** Refusing to work (an active "won't work" thought under a government). */
    ON_STRIKE,
    /** Pregnant — stepped back from work until after the birth. */
    EXPECTING,
    // ── Crafter ──────────────────────────────────────────────────────────────────────────────────
    /** Crafter with no workshop bound. */
    NO_WORKSHOP,
    /** Crafter whose workshop has nothing craftable right now (no orders, min-stock satisfied). */
    NO_ORDERS,
    /** Crafter that WANTS to craft (an order / min-stock deficit) but is short the inputs — the
     *  stocker still needs to deliver them. Distinct from {@link #NO_ORDERS} so the Job tab reads
     *  "waiting on materials" instead of the misleading "nothing to craft". */
    NEED_MATERIALS,
    // ── Farmer ─────────────────────────────────────────────────────────────────────────────────
    /** Farmer with bare/empty farmland waiting to be planted but no seeds in its marked seed source
     *  (nor its seed cache). The field-tending analog of {@link #NEEDS_SAPLINGS}, so a farmer standing
     *  idle for want of seeds is flagged instead of falsely reading "Working". */
    NEEDS_SEEDS;

    /**
     * Derives the glanceable work-status verdict from observable server-side facts. Single source of
     * truth shared by the on-demand Job-tab payload ({@code ServerPayloadHandler.sendCitizenJobState})
     * and the continuous overhead "!" poll (the citizen's 20-tick {@code aiStep} block, which only
     * needs {@link #category()} on the result). Mirrors the precedence the Job-tab headline uses: hard
     * blockers (banner/strike/stamina) win over a stale published value; a goal that publishes a live
     * sub-state ({@link ForesterPlantationGoal}, crafter) is otherwise honoured.
     *
     * @param citizen   the citizen to evaluate (server-side)
     * @param settlement the citizen's settlement (must be non-null — caller checks)
     * @param anarchy   {@code true} when the settlement has no government (gating skips strike/social)
     */
    public static CitizenWorkStatus derive(CitizenEntity citizen, Settlement settlement, boolean anarchy) {
        String jobType = citizen.getJobType() == null ? "" : citizen.getJobType();
        CitizenWorkStatus published = citizen.getCurrentWorkStatus();
        if (jobType.isEmpty()) {
            return IDLE;
        } else if (citizen.isSleeping()) {
            return SLEEPING;
        } else if (!settlement.hasFactionBanner()) {
            return BANNER_DOWN;
        } else if (citizen.isPregnant()) {
            return EXPECTING;
        } else if (!anarchy && WorkGoal.isAfternoonGathering(citizen)) {
            return SOCIALIZING;
        } else if (citizen.isStaminaExhausted()) {
            return NO_STAMINA;
        } else if (!anarchy && WorkGoal.hasRefusalThought(citizen)) {
            return ON_STRIKE;
        } else if (published != IDLE) {
            return published;
        } else if (CrafterWorkGoal.isWorkshopJob(jobType) && citizen.getAssignedWorkshopId() == null) {
            return NO_WORKSHOP;
        } else if (!anarchy && com.bannerbound.core.social.JobIcons.requiresTool(jobType)
                && !citizen.hasJobTool()) {
            // Only jobs that actually need a tool (not foragers; and never in anarchy, where
            // gatherers work tool-free) read as NO_TOOL — otherwise tool-free workers showed
            // "idle – no tool" wrongly.
            return NO_TOOL;
        } else {
            return WORKING;
        }
    }

    /** Colour bucket for the Job-tab headline: working/hauling read green, idle/waiting amber,
     *  hard blockers red. Kept here so server and client agree on the bucketing. */
    public Category category() {
        return switch (this) {
            case WORKING, HAULING, PLANTING, HARVESTING -> Category.GOOD;
            case IDLE, WAITING, AREA_FULL, NO_ORDERS, NEED_MATERIALS,
                 SLEEPING, SOCIALIZING, EXPECTING -> Category.NEUTRAL;
            case NO_TOOL, NO_DROPOFF, NO_STAMINA, BLOCKED, STORAGE_FULL, NEEDS_SAPLINGS, NEEDS_SEEDS,
                 BANNER_DOWN, ON_STRIKE, NO_WORKSHOP -> Category.BLOCKED;
        };
    }

    public enum Category { GOOD, NEUTRAL, BLOCKED }
}
