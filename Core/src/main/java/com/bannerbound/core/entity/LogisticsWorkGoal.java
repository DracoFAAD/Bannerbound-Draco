package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

/**
 * Category base for <b>logistics</b> workers — citizens whose job is to shuttle items between
 * workstations rather than produce or gather. They neither scan resources directly nor consume
 * Foreman's Rod orders; their cycle is keyed off other workstations' inventory state.
 *
 * <p>Current member: {@link StockerWorkGoal} (visits every production workstation in claimed
 * territory, drains overflow into the stockpile rack's connected storage enclosure).
 *
 * <p>This abstract is intentionally thin today — when a second logistics role lands (e.g. a
 * Hauler tied to a specific origin/destination pair, or a Courier between settlements) the
 * shared "round-trip with phase timer" skeleton can be lifted up.
 */
@ApiStatus.Internal
public abstract class LogisticsWorkGoal extends WorkGoal {
    protected LogisticsWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }
}
