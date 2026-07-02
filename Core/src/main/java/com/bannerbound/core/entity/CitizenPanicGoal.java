package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.world.entity.ai.goal.PanicGoal;

/**
 * Citizen-flavoured panic goal that yields whenever a brawl retaliation is queued. The
 * vanilla {@link PanicGoal} at priority 1 should be preempted by {@link BrawlRetaliationGoal}
 * at priority 0 via the standard goal-selector flag-replacement rule, but in practice that
 * preemption races against the same-tick start of PanicGoal — the citizen kept running
 * instead of swinging back.
 *
 * <p>Subclassing PanicGoal and overriding {@link #canUse} + {@link #canContinueToUse} to
 * explicitly check {@link CitizenEntity#getPendingRetaliationTargetId()} gives us a hard
 * guarantee: panic never starts (and any in-progress panic stops) while a counter-swing is
 * pending. The brawl retaliation always wins, no matter the tick interleaving.
 */
@ApiStatus.Internal
public class CitizenPanicGoal extends PanicGoal {
    private final CitizenEntity citizen;

    public CitizenPanicGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
        this.citizen = citizen;
    }

    @Override
    public boolean canUse() {
        // Block panic from starting while a retaliation swing is queued — the citizen is
        // about to swing back, not run away. Once the swing lands (and pendingRetaliation is
        // cleared) panic is free to take over the next tick.
        if (citizen.getPendingRetaliationTargetId() != null) return false;
        // Guards hold the line: being hit by an enemy never makes them flee. The yield is SURGICAL,
        // though — a guard on fire / in lava / freezing still flees the hazard (otherwise a blanket
        // no-panic would walk the watch straight into a lava pool). Combat-hurt panic only is dropped.
        if (GuardWorkGoal.JOB_TYPE_ID.equals(citizen.getJobType())
                && !citizen.isOnFire() && !citizen.isInLava() && !citizen.isFreezing()) {
            return false;
        }
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        // Same gate on continuation: if the citizen gets hit mid-panic and the new hit
        // schedules a retaliation, panic stops on the next tick so the brawl goal can grab
        // MOVE and plant the citizen's feet.
        if (citizen.getPendingRetaliationTargetId() != null) return false;
        return super.canContinueToUse();
    }
}
