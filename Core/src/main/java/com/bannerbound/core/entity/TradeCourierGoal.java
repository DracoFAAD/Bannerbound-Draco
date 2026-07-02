package com.bannerbound.core.entity;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;

/**
 * The trade-courier BLOCKER goal: runs the entire time a citizen is adopted by a
 * {@code TraderSimManager} journey ({@link CitizenEntity#isOnTradeJourney()}) and does nothing but
 * hold MOVE+LOOK. Registered at priority 0 ahead of the combat/flee goals, so while it runs every
 * other movement goal — work, patrol, sleep, conversation, panic, combat — is starved by the
 * strict-less-than preemption rule. The sim drives the citizen externally via
 * {@code getNavigation().moveTo(...)}, which ticks in {@code Mob.serverAiStep} independent of
 * goals; flagless helpers (fence-gate opening) still run. The courier deliberately does not fight
 * back — a caravan is killable cargo, not a combatant.
 */
@ApiStatus.Internal
public final class TradeCourierGoal extends net.minecraft.world.entity.ai.goal.Goal {
    private final CitizenEntity citizen;

    public TradeCourierGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!citizen.isOnTradeJourney()) return false;
        // Stale journey (server restart / deal resolved while unloaded): release the citizen back
        // to its own AI instead of freezing it forever under a flag nobody will clear.
        if (com.bannerbound.core.trade.TradeCourierManager.isStaleJourney(citizen)) {
            com.bannerbound.core.trade.TradeCourierManager.clearStale(citizen);
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return citizen.isOnTradeJourney();
    }

    @Override
    public void stop() {
        // Journey over — drop any half-issued path so the resuming stocker AI starts clean.
        citizen.getNavigation().stop();
    }
}
