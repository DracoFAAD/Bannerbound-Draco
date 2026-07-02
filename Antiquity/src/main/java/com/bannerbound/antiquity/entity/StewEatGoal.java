package com.bannerbound.antiquity.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.block.StoneCookingPotBlock;
import com.bannerbound.antiquity.block.entity.StoneCookingPotBlockEntity;
import com.bannerbound.antiquity.social.AntiquityThoughts;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Leisure goal: an idle citizen wanders to a nearby {@link StoneCookingPotBlockEntity} holding a
 * finished stew within the settlement claims, eats a serving (draining the pot like the player or the
 * larder would), and comes away with a strong {@link AntiquityThoughts#ENJOYED_STEW} food mood
 * ("I ate a warm stew", +10). The direct sibling of {@link GrogDrinkGoal} — same throttled scan,
 * leisure priority (3), and long post-meal cooldown so a settlement doesn't form an eating heartbeat —
 * attached to Core's {@link CitizenEntity} through the generic {@code CitizenGoalRegistry}. Poisoned
 * stews are skipped ({@link StoneCookingPotBlock#hasReadyServing} excludes them).
 */
public class StewEatGoal extends Goal {
    /** Only consider pots within this many blocks of the citizen. */
    private static final int SEARCH_RADIUS = 24;
    /** "At the pot" — within ~2.5 blocks; a touch generous so a citizen parked just shy starts eating. */
    private static final double EAT_REACH_SQ = 6.25;
    /** Time spent eating once arrived (≈3.5s), with an audible bite at the start and halfway. */
    private static final int EAT_DURATION = 70;
    /** Give up walking if we can't reach the pot in this long (path blocked / target gone). */
    private static final int WALK_TIMEOUT = 200;
    /** Post-meal cooldown range — 5 to 12 in-game minutes (eating out is occasional, not constant). */
    private static final int COOLDOWN_MIN = 6_000;
    private static final int COOLDOWN_MAX = 14_400;
    /** Throttle the pot scan with a small extra interval on top of think ticks. */
    private static final int SCAN_INTERVAL = 40;

    private final CitizenEntity citizen;
    private final double speedModifier;

    @Nullable private BlockPos targetPot;
    private int cooldown = 0;
    private int scanCooldown = 0;
    private int ticksRunning = 0;
    private int eatTicks = 0;
    private boolean eating = false;

    public StewEatGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) { cooldown--; return false; }
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        if (!citizen.isAiActive()) return false;
        if (citizen.isPassenger() || citizen.isChild()) return false;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        if (settlement.activeCrisis() != null) return false;          // no leisure meals mid-crisis
        if (!citizen.isThinkTick()) return false;
        if (scanCooldown > 0) { scanCooldown--; return false; }
        scanCooldown = SCAN_INTERVAL;

        BlockPos pot = findPot(sl, settlement);
        if (pot == null) return false;
        this.targetPot = pot;
        return true;
    }

    @Override
    public void start() {
        ticksRunning = 0;
        eatTicks = 0;
        eating = false;
        if (targetPot != null) {
            citizen.getNavigation().moveTo(
                targetPot.getX() + 0.5, targetPot.getY(), targetPot.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return targetPot != null;
    }

    @Override
    public void tick() {
        if (targetPot == null || !(citizen.level() instanceof ServerLevel sl)) return;
        ticksRunning++;

        if (!(sl.getBlockEntity(targetPot) instanceof StoneCookingPotBlockEntity)) {
            targetPot = null;                               // pot broken / changed → bail
            return;
        }

        if (eating) {
            facePot();
            eatTicks--;
            if (eatTicks == EAT_DURATION / 2) {
                playBite(sl);                               // second bite, mid-meal
            }
            if (eatTicks <= 0) {
                if (StoneCookingPotBlock.takeServing(sl, targetPot)) {
                    onEaten(sl);
                }
                targetPot = null;                           // done (stop() rolls the cooldown)
            }
            return;
        }

        double d2 = citizen.position().distanceToSqr(
            targetPot.getX() + 0.5, targetPot.getY() + 0.5, targetPot.getZ() + 0.5);
        if (d2 <= EAT_REACH_SQ) {
            if (!StoneCookingPotBlock.hasReadyServing(sl, targetPot)) {
                targetPot = null;                           // someone emptied it before we arrived
                return;
            }
            citizen.getNavigation().stop();
            facePot();
            eating = true;
            eatTicks = EAT_DURATION;
            playBite(sl);                                   // first bite, the moment they reach it
        } else if (citizen.getNavigation().isDone()) {
            citizen.getNavigation().moveTo(
                targetPot.getX() + 0.5, targetPot.getY(), targetPot.getZ() + 0.5, speedModifier);
        }
        if (ticksRunning > WALK_TIMEOUT) {                  // couldn't get there — give up this round
            targetPot = null;
        }
    }

    @Override
    public void stop() {
        citizen.getNavigation().stop();
        targetPot = null;
        eating = false;
        eatTicks = 0;
        cooldown = rollCooldown();
    }

    /** The morale payoff once the meal finishes: a strong, lasting food-pillar mood. */
    private void onEaten(ServerLevel sl) {
        long now = sl.getGameTime();
        citizen.getThoughts().add(AntiquityThoughts.ENJOYED_STEW, null, now, sl.random);
        citizen.recomputeHappiness();
    }

    /** An audible bite at the pot — at the start and halfway through, so it reads as actively eating. */
    private void playBite(ServerLevel sl) {
        if (targetPot == null) return;
        sl.playSound(null, targetPot, SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL,
            0.6F, 0.9F + sl.random.nextFloat() * 0.2F);
    }

    private void facePot() {
        if (targetPot == null) return;
        citizen.getLookControl().setLookAt(
            targetPot.getX() + 0.5, targetPot.getY() + 0.5, targetPot.getZ() + 0.5);
    }

    /** Nearest claimed-chunk pot with a finished (non-poisoned) stew, within {@link #SEARCH_RADIUS}. */
    @Nullable
    private BlockPos findPot(ServerLevel sl, Settlement settlement) {
        BlockPos origin = citizen.blockPosition();
        double bestSq = (double) SEARCH_RADIUS * SEARCH_RADIUS;
        BlockPos best = null;
        for (long packed : settlement.claimedChunks()) {
            ChunkPos cp = new ChunkPos(packed);
            LevelChunk chunk = sl.getChunkSource().getChunkNow(cp.x, cp.z);
            if (chunk == null) continue;
            for (var entry : chunk.getBlockEntities().entrySet()) {
                BlockEntity be = entry.getValue();
                if (!(be instanceof StoneCookingPotBlockEntity)) continue;
                BlockPos p = entry.getKey();
                if (!StoneCookingPotBlock.hasReadyServing(sl, p)) continue;
                double dsq = origin.distSqr(p);
                if (dsq < bestSq) {
                    bestSq = dsq;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    private int rollCooldown() {
        int span = COOLDOWN_MAX - COOLDOWN_MIN;
        if (span <= 0) return COOLDOWN_MIN;
        if (citizen.level() instanceof ServerLevel sl) {
            return COOLDOWN_MIN + sl.random.nextInt(span + 1);
        }
        return COOLDOWN_MIN;
    }
}
