package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

/**
 * Category base for <b>ordered</b> workers — citizens whose targets are <i>player-placed
 * orders</i> stored in {@link com.bannerbound.core.api.world.BlockSelectionRegistry} (committed
 * via the Foreman's Rod). They don't scan the world freely; they iterate the registry's
 * selections owned by their settlement and pick the closest valid block.
 *
 * <p>Current members: {@link DiggerWorkGoal} (mines blocks inside selections, gated by tier +
 * Quarry flag), {@link FarmerWorkGoal} (tills/plants/harvests selections per their assigned
 * seed).
 *
 * <p>This abstract is intentionally thin today — shared mechanics like the
 * "tried-but-unreachable TTL", the per-block claim registry handoff, and the no-approach
 * progress watchdog are sensible candidates to lift once both subclasses settle on identical
 * implementations. Leaving them in the leaves for now preserves the existing tuning per role.
 */
@ApiStatus.Internal
public abstract class OrderedWorkGoal extends WorkGoal {
    protected OrderedWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }
}
