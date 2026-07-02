package com.bannerbound.antiquity.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.block.FermentationTroughBlock;
import com.bannerbound.antiquity.block.entity.FermentationTroughBlockEntity;
import com.bannerbound.antiquity.social.AntiquityThoughts;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Leisure goal (GROG_PLAN.md Phase 4): an idle citizen wanders to a nearby {@link
 * FermentationTroughBlockEntity} holding finished grog within the settlement claims, drinks a serving,
 * and comes away with a brief positive {@link AntiquityThoughts#ENJOYED_GROG} mood plus a touch of
 * tipsy Slowness for flavour. Grog is entirely an Antiquity system — this goal lives here and is
 * attached to Core's {@link CitizenEntity} through the generic {@code CitizenGoalRegistry} (registered
 * in {@code BannerboundAntiquity} setup), so Core never references grog.
 *
 * <p>Modelled on Core's {@code ConversationGoal}: think-tick-throttled scan and a long post-drink
 * cooldown so a settlement doesn't form a drinking heartbeat. Registered at priority 3 (the leisure
 * tier, alongside ConversationGoal) — NOT 4: a priority-4 goal can't preempt the running priority-4
 * SettlementPatrolGoal, so at 4 an idle citizen would patrol endlessly and rarely drink. At 3 a
 * running work goal still holds MOVE (drinks happen off-shift), but idle citizens reliably break for a
 * drink whether or not they're socializing.
 */
public class GrogDrinkGoal extends Goal {
    /** Only consider troughs within this many blocks of the citizen. */
    private static final int SEARCH_RADIUS = 24;
    /** "At the trough" — within ~2.5 blocks. A bit generous so a citizen that the pathfinder parks
     *  just shy of the block starts drinking immediately instead of shuffling/re-pathing in place. */
    private static final double DRINK_REACH_SQ = 6.25;
    /** Sipping time once arrived (≈3.5s) — a deliberate drink, with an audible gulp at the start and
     *  halfway so it reads as drinking the whole time rather than standing and staring. */
    private static final int DRINK_DURATION = 70;
    /** Give up walking if we can't reach the trough in this long (path blocked / target gone). */
    private static final int WALK_TIMEOUT = 200;
    /** Post-attempt cooldown range — 5 to 12 in-game minutes (drinking is occasional, not constant). */
    private static final int COOLDOWN_MIN = 6_000;
    private static final int COOLDOWN_MAX = 14_400;
    /** Throttle the trough scan with a small extra interval on top of think ticks. */
    private static final int SCAN_INTERVAL = 40;

    private final CitizenEntity citizen;
    private final double speedModifier;

    @Nullable private BlockPos targetTrough;
    private int cooldown = 0;
    private int scanCooldown = 0;
    private int ticksRunning = 0;
    private int drinkTicks = 0;
    private boolean drinking = false;

    public GrogDrinkGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) { cooldown--; return false; }
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        if (!citizen.isAiActive()) return false;
        if (citizen.isPassenger() || citizen.isChild()) return false;   // kids don't drink grog
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        if (settlement.activeCrisis() != null) return false;            // no tavern runs mid-crisis
        // Throttle the (settlement-wide) block-entity scan onto think ticks.
        if (!citizen.isThinkTick()) return false;
        if (scanCooldown > 0) { scanCooldown--; return false; }
        scanCooldown = SCAN_INTERVAL;

        BlockPos trough = findTrough(sl, settlement);
        if (trough == null) return false;
        this.targetTrough = trough;
        return true;
    }

    @Override
    public void start() {
        ticksRunning = 0;
        drinkTicks = 0;
        drinking = false;
        if (targetTrough != null) {
            citizen.getNavigation().moveTo(
                targetTrough.getX() + 0.5, targetTrough.getY(), targetTrough.getZ() + 0.5, speedModifier);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return targetTrough != null;
    }

    @Override
    public void tick() {
        if (targetTrough == null || !(citizen.level() instanceof ServerLevel sl)) return;
        ticksRunning++;

        if (!(sl.getBlockEntity(targetTrough) instanceof FermentationTroughBlockEntity)) {
            targetTrough = null;                            // trough broken / changed → bail
            return;
        }

        if (drinking) {
            faceTrough();
            drinkTicks--;
            if (drinkTicks == DRINK_DURATION / 2) {
                playSip(sl);                                // second gulp, mid-drink
            }
            if (drinkTicks <= 0) {
                if (FermentationTroughBlock.takeServing(sl, targetTrough)) {
                    onDrank(sl);
                }
                targetTrough = null;                        // done (stop() rolls the cooldown)
            }
            return;
        }

        double d2 = citizen.position().distanceToSqr(
            targetTrough.getX() + 0.5, targetTrough.getY() + 0.5, targetTrough.getZ() + 0.5);
        if (d2 <= DRINK_REACH_SQ) {
            if (!FermentationTroughBlock.hasReadyServing(sl, targetTrough)) {
                targetTrough = null;                        // someone drained it before we arrived
                return;
            }
            citizen.getNavigation().stop();
            faceTrough();
            drinking = true;
            drinkTicks = DRINK_DURATION;
            playSip(sl);                                    // first gulp, the moment they reach it
        } else if (citizen.getNavigation().isDone()) {
            citizen.getNavigation().moveTo(
                targetTrough.getX() + 0.5, targetTrough.getY(), targetTrough.getZ() + 0.5, speedModifier);
        }
        if (ticksRunning > WALK_TIMEOUT) {                  // couldn't get there — give up this round
            targetTrough = null;
        }
    }

    @Override
    public void stop() {
        citizen.getNavigation().stop();
        targetTrough = null;
        drinking = false;
        drinkTicks = 0;
        cooldown = rollCooldown();
    }

    /** The morale + flavour payoff once the drink finishes: a positive mood and a little tipsy
     *  Slowness. (The audible gulps play during the drink via {@link #playSip}.) */
    private void onDrank(ServerLevel sl) {
        long now = sl.getGameTime();
        citizen.getThoughts().add(AntiquityThoughts.ENJOYED_GROG, null, now, sl.random);
        citizen.recomputeHappiness();
        // Light, brief "tipsy" debuff for flavour — Slowness I for ~4s, ambient, no swirl particles.
        citizen.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 0, true, false));
    }

    /** An audible gulp at the trough — played at the start and halfway through the drink so it reads
     *  as actively drinking rather than staring. */
    private void playSip(ServerLevel sl) {
        if (targetTrough == null) return;
        sl.playSound(null, targetTrough, SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL,
            0.6F, 0.9F + sl.random.nextFloat() * 0.2F);
    }

    private void faceTrough() {
        if (targetTrough == null) return;
        citizen.getLookControl().setLookAt(
            targetTrough.getX() + 0.5, targetTrough.getY() + 0.5, targetTrough.getZ() + 0.5);
    }

    /** Nearest claimed-chunk trough with a finished serving, within {@link #SEARCH_RADIUS}. */
    @Nullable
    private BlockPos findTrough(ServerLevel sl, Settlement settlement) {
        BlockPos origin = citizen.blockPosition();
        double bestSq = (double) SEARCH_RADIUS * SEARCH_RADIUS;
        BlockPos best = null;
        for (long packed : settlement.claimedChunks()) {
            ChunkPos cp = new ChunkPos(packed);
            LevelChunk chunk = sl.getChunkSource().getChunkNow(cp.x, cp.z);
            if (chunk == null) continue;
            for (var entry : chunk.getBlockEntities().entrySet()) {
                BlockEntity be = entry.getValue();
                if (!(be instanceof FermentationTroughBlockEntity)) continue;
                BlockPos p = entry.getKey();
                if (!FermentationTroughBlock.hasReadyServing(sl, p)) continue;
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
