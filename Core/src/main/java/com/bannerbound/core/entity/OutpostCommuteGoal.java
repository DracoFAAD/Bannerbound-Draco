package com.bannerbound.core.entity;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ChunkPos;

/**
 * Walks an outpost-assigned worker the long haul from the settlement out to its remote work site,
 * then hands off to the local work / sleep / idle goals once on site. The actual travel — short,
 * vanilla-pathable hops instead of one truncating 128-block {@code moveTo}, plus an off-screen
 * abstract-step rescue — lives in the shared {@link LongHaulWalker}; this goal just owns the
 * lifecycle (when to start/stop) and the arrival hand-off.
 *
 * <p><b>Priority 2, registered before SleepGoal</b> (see {@link CitizenEntity#registerGoals}): same
 * priority means a worker mid-commute at nightfall isn't preempted into a doomed single 128-block
 * walk to a far outpost bed — it finishes the hop-walk to the site first, then SleepGoal beds it
 * down on arrival. Inert (one cheap null check) for any citizen without an outpost assignment, since
 * only the outpost work goals ever set {@link CitizenEntity#getOutpostSite()}.
 *
 * @see LongHaulWalker
 * @see SettlementPatrolGoal SettlementPatrolGoal — idles the worker around the site once arrived
 */
@ApiStatus.Internal
public class OutpostCommuteGoal extends Goal {
    /** Horizontal distance to the site at which we hand off to the local work/idle/sleep goals. */
    private static final double ARRIVE = 12.0;
    private static final double ARRIVE_SQ = ARRIVE * ARRIVE;

    private final CitizenEntity citizen;
    private final double speedModifier;
    private final LongHaulWalker walker = new LongHaulWalker();

    private BlockPos site;

    public OutpostCommuteGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!citizen.isAiActive()) return false;
        BlockPos s = liveSite();
        if (s == null) return false;
        if (horizDistSq(s) <= ARRIVE_SQ) return false;   // already there — local goals take over
        if (!citizen.isThinkTick()) return false;        // stagger the first pathfind like other goals
        this.site = s;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        BlockPos s = liveSite();
        if (s == null || !s.equals(site)) return false;
        return horizDistSq(s) > ARRIVE_SQ;
    }

    @Override
    public void start() {
        walker.reset(citizen);
    }

    @Override
    public void stop() {
        walker.reset(citizen);
        site = null;
    }

    @Override
    public void tick() {
        if (site != null) {
            // ARRIVED is handled by canContinueToUse going false (which runs stop()); WALKING /
            // WAITING just keep the goal alive while the walker drives navigation.
            walker.stepToward(citizen, site, speedModifier, ARRIVE, true);
        }
    }

    /** The outpost site IF it's still a live working claim of the worker's settlement, else null. */
    private BlockPos liveSite() {
        BlockPos s = citizen.getOutpostSite();
        if (s == null) return null;
        Settlement set = citizen.getSettlement();
        if (set == null || !set.workingClaims().contains(new ChunkPos(s).toLong())) return null;
        return s;
    }

    /** Horizontal (XZ) squared distance from the worker to {@code p}'s column centre. */
    private double horizDistSq(BlockPos p) {
        double dx = (p.getX() + 0.5) - citizen.getX();
        double dz = (p.getZ() + 0.5) - citizen.getZ();
        return dx * dx + dz * dz;
    }
}
