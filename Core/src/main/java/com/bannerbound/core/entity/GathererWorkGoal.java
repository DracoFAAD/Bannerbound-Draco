package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

/**
 * Category base for <b>gatherers</b> — citizens whose loop is {@code SCAN for a resource → walk
 * → harvest → claim yield into the workstation BE → spend stamina}. Gatherers are <i>self
 * directed</i>: they scan the world around themselves for targets instead of consuming
 * player-placed orders from {@link com.bannerbound.core.api.world.BlockSelectionRegistry}.
 *
 * <p>Current members: {@link ForesterWorkGoal} (logs → fells trees), {@link FisherWorkGoal}
 * (water bodies → cast/wait/retract). The next addition planned for this category is the
 * <b>Forager</b> — extend this class, return the forager workstation's TYPE_ID from
 * {@link #workstationTypeId()}, and implement the usual {@code canUse}/{@code tick} state
 * machine. The shared findAssignment/citizen/speedModifier plumbing comes from {@link WorkGoal}
 * for free.
 *
 * <p>This abstract is intentionally thin today — once the second concrete gatherer's quirks
 * settle and the Forager lands, common patterns (rescan cooldowns, target-age timeouts, the
 * {@code SCAN}/{@code WALK}/{@code WORK} phase skeleton) can be lifted here without churning
 * the leaf classes again.
 */
@ApiStatus.Internal
public abstract class GathererWorkGoal extends WorkGoal {
    protected GathererWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    /** Gatherers are the self-directed roles citizens auto-employ into under anarchy, so they run
     *  willingly there — bypassing the social-window pause and compliance refusal/strike thoughts. */
    @Override
    protected boolean isAnarchyAutoEligible() {
        return true;
    }
}
