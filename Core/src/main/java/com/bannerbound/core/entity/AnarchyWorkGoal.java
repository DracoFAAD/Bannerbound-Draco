package com.bannerbound.core.entity;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.social.SocialEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

/**
 * Anarchy-phase <b>flavour</b> behaviour. Self-organizing citizens do their real work through the
 * normal gatherer goals (auto-employed via {@link CitizenEntity#autoEmployIfAnarchy}); this goal
 * only adds the occasional reminder that a lawless band has no one keeping order:
 *
 * <ul>
 *   <li><b>Squabble</b> ({@link #SQUABBLE_CHANCE}): a citizen rarely walks up to a settlement-mate
 *       and has words — a brief look plus a small mutual relationship ding. <b>No damage.</b></li>
 *   <li><b>Slack</b> (otherwise): a citizen wanders to a nearby spot and idles for a few seconds —
 *       "nobody told me to keep working".</li>
 * </ul>
 *
 * <p>This deliberately replaces the old destructive anarchy behaviour (bare-handed block griefing,
 * arson, animal hunting, real punches). It's sparse on purpose — priority 4 alongside patrol, a low
 * canUse roll, and a long cooldown — so most of the time citizens just gather and patrol, and the
 * unrest is a rare bit of texture, not a wrecking crew. Yields the moment a government is enacted.
 */
@ApiStatus.Internal
public class AnarchyWorkGoal extends Goal {
    /** Relationship swing per squabble — applied mutually (both citizens lose a little toward each
     *  other). Kept on the same constant name {@link CitizenEntity} references for its brawl swing. */
    public static final int ANARCHY_RELATION_DELTA_PER_HIT = -5;

    /** Scan radius for a squabble partner / slack wander point. */
    private static final int FLAVOUR_RADIUS = 10;
    /** Per-canUse probability gate — trips feel occasional rather than every cooldown boundary. */
    private static final float CANUSE_ROLL_CHANCE = 0.05f;
    /** Cooldown between flavour trips (decremented in canUse while > 0). ~30–60 s. */
    private static final int COOLDOWN_TICKS_MIN = 600;
    private static final int COOLDOWN_TICKS_MAX = 1200;
    /** Probability a successful roll is a squabble (vs a slack idle). */
    private static final float SQUABBLE_CHANCE = 0.35f;
    /** Hard cap on a squabble trip (walk to the partner + exchange words). */
    private static final int SQUABBLE_TICKS_MAX = 200;
    /** Hard cap on a slack trip (walk to a spot + idle there). */
    private static final int SLACK_TICKS_MAX = 160;
    /** Squared "close enough" radius to a squabble partner / slack point. */
    private static final double REACH_SQ = 9.0;

    private enum Mode { SQUABBLE, SLACK }

    private final CitizenEntity citizen;
    private final double speedModifier;

    private int cooldown = 0;
    private int tripTicks = 0;
    private boolean done = false;
    @Nullable private Mode mode;
    @Nullable private CitizenEntity partner;
    @Nullable private BlockPos slackPos;
    private boolean squabbleApplied = false;

    public AnarchyWorkGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) { cooldown--; return false; }
        if (!(citizen.level() instanceof ServerLevel)) return false;
        Settlement s = citizen.getSettlement();
        if (s == null || s.governmentType() != Settlement.Government.NONE) return false;
        if (citizen.isPregnant() || citizen.isChild() || citizen.isStaminaExhausted()) return false;
        if (citizen.getRandom().nextFloat() >= CANUSE_ROLL_CHANCE) return false;

        if (citizen.getRandom().nextFloat() < SQUABBLE_CHANCE) {
            CitizenEntity victim = findNearbyCitizen();
            if (victim != null) {
                this.mode = Mode.SQUABBLE;
                this.partner = victim;
                return true;
            }
        }
        BlockPos spot = pickSlackPos();
        if (spot == null) return false;
        this.mode = Mode.SLACK;
        this.slackPos = spot;
        return true;
    }

    @Override
    public void start() {
        done = false;
        squabbleApplied = false;
        if (mode == Mode.SQUABBLE && partner != null) {
            tripTicks = SQUABBLE_TICKS_MAX;
            citizen.getNavigation().moveTo(partner, speedModifier);
        } else if (slackPos != null) {
            tripTicks = SLACK_TICKS_MAX;
            citizen.getNavigation().moveTo(
                slackPos.getX() + 0.5, slackPos.getY(), slackPos.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (done || tripTicks <= 0) return false;
        Settlement s = citizen.getSettlement();
        if (s == null || s.governmentType() != Settlement.Government.NONE) return false;
        if (mode == Mode.SQUABBLE && (partner == null || !partner.isAlive())) return false;
        return true;
    }

    @Override
    public void tick() {
        tripTicks--;
        if (mode == Mode.SQUABBLE) {
            tickSquabble();
        } else {
            tickSlack();
        }
    }

    /** Walk up to the partner, look at them, exchange a small mutual relationship ding — no damage. */
    private void tickSquabble() {
        if (partner == null || !partner.isAlive()) { done = true; return; }
        citizen.getLookControl().setLookAt(partner, 30.0f, 30.0f);
        if (citizen.distanceToSqr(partner) <= REACH_SQ) {
            if (!squabbleApplied) {
                SocialEvents.applyMutual(citizen, partner, ANARCHY_RELATION_DELTA_PER_HIT);
                squabbleApplied = true;
            }
            done = true;
        } else if (citizen.getNavigation().isDone()) {
            citizen.getNavigation().moveTo(partner, speedModifier);
            if (tripTicks <= 0) done = true;
        }
    }

    /** Stroll to the chosen spot and idle there until the trip times out. */
    private void tickSlack() {
        if (slackPos == null) { done = true; return; }
        double distSq = citizen.distanceToSqr(
            slackPos.getX() + 0.5, slackPos.getY(), slackPos.getZ() + 0.5);
        if (distSq <= REACH_SQ) {
            citizen.getNavigation().stop();   // loiter — the trip timer ends the idle
        } else if (citizen.getNavigation().isDone()) {
            done = true;   // couldn't reach the spot → end the trip cleanly
        }
    }

    @Override
    public void stop() {
        cooldown = COOLDOWN_TICKS_MIN
            + citizen.getRandom().nextInt(COOLDOWN_TICKS_MAX - COOLDOWN_TICKS_MIN + 1);
        mode = null;
        partner = null;
        slackPos = null;
        done = false;
        squabbleApplied = false;
        citizen.getNavigation().stop();
    }

    /** A living settlement-mate (not this citizen, not a child) within {@link #FLAVOUR_RADIUS}. */
    @Nullable
    private CitizenEntity findNearbyCitizen() {
        if (!(citizen.level() instanceof ServerLevel sl)) return null;
        java.util.UUID myStl = citizen.getSettlementId();
        if (myStl == null) return null;
        AABB box = citizen.getBoundingBox().inflate(FLAVOUR_RADIUS);
        List<CitizenEntity> nearby = sl.getEntitiesOfClass(CitizenEntity.class, box,
            c -> c != citizen && c.isAlive() && !c.isChild() && myStl.equals(c.getSettlementId()));
        if (nearby.isEmpty()) return null;
        return nearby.get(citizen.getRandom().nextInt(nearby.size()));
    }

    /** A nearby ground spot to wander to and idle at while slacking off. */
    @Nullable
    private BlockPos pickSlackPos() {
        if (!(citizen.level() instanceof ServerLevel sl)) return null;
        BlockPos base = citizen.blockPosition();
        for (int attempt = 0; attempt < 8; attempt++) {
            int x = base.getX() + citizen.getRandom().nextInt(FLAVOUR_RADIUS * 2 + 1) - FLAVOUR_RADIUS;
            int z = base.getZ() + citizen.getRandom().nextInt(FLAVOUR_RADIUS * 2 + 1) - FLAVOUR_RADIUS;
            int y = sl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (WorkerPathing.isWalkable(sl, p) || WorkerPathing.isWalkable(sl, p.above())) {
                return p;
            }
        }
        return null;
    }
}
