package com.bannerbound.core.entity;

import java.util.EnumSet;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Holds the floor at priority 0 while a retaliation swing is pending, then lands the swing.
 * Sits below {@code PanicGoal} (priority 1) in the priority number, so the citizen <b>stops
 * running from pain</b> and faces their attacker the moment {@code CitizenBrawlEvents}
 * schedules a counter-swing. Without this goal, vanilla {@code PanicGoal} would carry the
 * citizen away from the attacker before the {@code aiStep} swing-handler could fire, and the
 * brawl loop would silently break at every step.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@code CitizenBrawlEvents} sets {@link CitizenEntity#schedulePendingRetaliation} on
 *       the victim with a target UUID + scheduled tick (~10 ticks out).</li>
 *   <li>This goal's {@link #canUse} sees the pending state and starts — claiming MOVE+LOOK
 *       at priority 0, which preempts {@code PanicGoal} (priority 1).</li>
 *   <li>{@link #start} stops the navigation so the citizen plants their feet.</li>
 *   <li>{@link #tick} stares the target down each tick. Once the scheduled tick arrives,
 *       performs the swing via {@link CitizenEntity#performBrawlSwing}, notes the brawl
 *       exchange (so the next hit IS treated as ongoing), and clears the pending state —
 *       which makes {@link #canContinueToUse} return false and the goal ends.</li>
 * </ol>
 *
 * <p>Target can be any {@link LivingEntity} (citizen or player) — citizens can be brawling
 * with players too, post-Step 11 where player-on-citizen attacks trigger the same auto-
 * retaliation rolls.
 */
@ApiStatus.Internal
public class BrawlRetaliationGoal extends Goal {
    private final CitizenEntity citizen;
    @Nullable private LivingEntity target;

    public BrawlRetaliationGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        UUID id = citizen.getPendingRetaliationTargetId();
        if (id == null) return false;
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Entity e = sl.getEntity(id);
        if (!(e instanceof LivingEntity le) || !le.isAlive()) {
            // Target gone — clean up the stale pending state.
            citizen.clearPendingRetaliation();
            return false;
        }
        this.target = le;
        return true;
    }

    @Override
    public void start() {
        citizen.getNavigation().stop();
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || !target.isAlive()) return false;
        return citizen.getPendingRetaliationTargetId() != null;
    }

    @Override
    public void tick() {
        if (target == null) return;
        citizen.getLookControl().setLookAt(target, 30.0f, 30.0f);
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        // Hold the spot until the scheduled tick arrives, then swing. Short delay so the
        // attacker can't drift out of range during the wait.
        if (sl.getGameTime() >= citizen.getPendingRetaliationTick()) {
            if (citizen.performBrawlSwing(target)) {
                citizen.noteBrawlExchange(target.getUUID(), sl.getGameTime());
            }
            citizen.clearPendingRetaliation();
        }
    }

    @Override
    public void stop() {
        target = null;
        // Don't clear pendingRetaliation here — start/tick own that. stop() runs both on
        // graceful end and on preemption; clearing here would lose a still-pending retaliation
        // if a higher-priority goal briefly took over.
    }
}
