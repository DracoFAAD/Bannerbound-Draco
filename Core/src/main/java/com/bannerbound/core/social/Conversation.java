package com.bannerbound.core.social;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/**
 * Transient server-side coordinator for one in-progress chat between two citizens. Held on
 * {@code Settlement.activeConversations} so both participating {@code ConversationGoal}s can
 * find it via the partner's UUID and share state.
 * <p>
 * <b>Initiator-owns-progress.</b> Both goals tick on the server main thread one at a time, so
 * there's no concurrency hazard, but to avoid double-counting on shared fields the
 * <em>initiator</em> (citizen {@link #a}) is the sole writer of {@link #phaseTimer}, {@link
 * #phase}, {@link #matches}, and the {@link #outcomeApplied} flag. The joiner only mutates its
 * own facing, its own navigation, its own {@code DATA_BUBBLE} slot, and the {@code bArrived}
 * flag on this object.
 * <p>
 * Never persisted. If a save lands mid-conversation, the conversation is silently dropped on
 * world load — citizens fall through to patrol behaviour and a fresh conversation may spawn.
 */
public final class Conversation {
    // ─── Tunables — one place to change the entire pacing ─────────────────────────────────────
    /** Max distance from the town hall a citizen can be and still join the conversation pool. */
    public static final double SOCIAL_RADIUS = 10.0;
    /** Squared distance from meetPos that counts as "arrived". */
    public static final double MEET_REACH_SQ = 1.5 * 1.5;
    /** Bail if not both arrived after this many ticks. */
    public static final int WALK_TIMEOUT_TICKS = 200;
    /** Quiet pause facing each other before bubble 1. */
    public static final int FACE_OFF_TICKS = 20;
    /** Bubble visible for this many ticks — 4 seconds, matching the scale-in + hold + fade-out
     *  animation the client renderer plays. */
    public static final int BUBBLE_TICKS = 80;
    /** Blank between bubbles. */
    public static final int GAP_TICKS = 20;
    /** Resolve outcome, then linger this long before stopping. */
    public static final int RESOLVING_TICKS = 30;

    // ─── Outcome → score delta (matches: 0..3) ────────────────────────────────────────────────
    public static final int FIGHT_DELTA     = -10; // 0 matches
    public static final int ARGUMENT_DELTA  = -3;  // 1 match
    public static final int NEUTRAL_DELTA   = +1;  // 2 matches
    public static final int AGREEMENT_DELTA = +5;  // 3 matches

    public enum Phase { WALK_TO_MEET, FACE_OFF, BUBBLE, GAP, RESOLVING, DONE }

    public final UUID a;
    public final UUID b;
    public final BlockPos meetPosA;
    public final BlockPos meetPosB;
    public final ConversationTopic[] topicsA;
    public final ConversationTopic[] topicsB;

    public Phase phase = Phase.WALK_TO_MEET;
    /** Server-game-tick at which the current phase began. -1 until the initiator's first tick.
     *  Both citizens compute their own "time in phase" as {@code currentGameTime - this}; using
     *  a shared start tick instead of an incrementing counter sidesteps tick-order races between
     *  the two participants' goal ticks within the same game tick. */
    public long phaseStartGameTime = -1L;
    public int currentBubble = 0; // 0..2
    public int matches = 0;        // 0..3
    public boolean outcomeApplied = false;

    public boolean aArrived = false;
    public boolean bArrived = false;

    private Conversation(UUID a, UUID b, BlockPos meetPosA, BlockPos meetPosB,
                          ConversationTopic[] topicsA, ConversationTopic[] topicsB) {
        this.a = a;
        this.b = b;
        this.meetPosA = meetPosA;
        this.meetPosB = meetPosB;
        this.topicsA = topicsA;
        this.topicsB = topicsB;
    }

    /** Rolls 6 topics and constructs the shared coordinator. Each citizen gets their own meet
     *  tile so they end up 1 block apart facing each other, not overlapping on the same block.
     *  Caller should add the result to {@code Settlement.activeConversations}.
     *
     *  <p><b>Happiness-weighted matching</b>: happy citizens find common ground more often.
     *  For each of the 3 bubbles a {@code commonProb} is rolled — if it hits, both citizens are
     *  forced to the same random topic for that bubble; otherwise each rolls independently.
     *  {@code commonProb = avgHappiness / 125} (clamped to [0, 0.8]), so:
     *  <ul>
     *    <li>Avg 0 → commonProb ≈ 0, per-bubble match ≈ 0.2 → expected matches ≈ 0.6 (mostly
     *        fights/arguments).</li>
     *    <li>Avg 50 → commonProb ≈ 0.4, per-bubble match ≈ 0.52 → expected matches ≈ 1.56
     *        (neutral-leaning).</li>
     *    <li>Avg 100 → commonProb ≈ 0.8, per-bubble match ≈ 0.84 → expected matches ≈ 2.52
     *        (mostly agreements). Friendships build faster when both parties are doing well.</li>
     *  </ul>
     *  Compensates for the 5-topic enum diluting natural-match probability from 1/3 to 1/5;
     *  without the bias, the average conversation would drop from +1 (neutral) to −3 (argument)
     *  purely from the topic count change. */
    public static Conversation begin(UUID initiator, UUID partner,
                                      BlockPos initiatorStand, BlockPos partnerStand,
                                      int initiatorHappiness, int partnerHappiness,
                                      boolean allowWorkTopics,
                                      RandomSource rng) {
        double avg = (initiatorHappiness + partnerHappiness) * 0.5;
        double commonProb = Math.max(0.0, Math.min(0.8, avg / 125.0));

        ConversationTopic[] ta = new ConversationTopic[3];
        ConversationTopic[] tb = new ConversationTopic[3];
        for (int i = 0; i < 3; i++) {
            if (rng.nextDouble() < commonProb) {
                ConversationTopic shared = ConversationTopic.random(rng, allowWorkTopics);
                ta[i] = shared;
                tb[i] = shared;
            } else {
                ta[i] = ConversationTopic.random(rng, allowWorkTopics);
                tb[i] = ConversationTopic.random(rng, allowWorkTopics);
            }
        }
        return new Conversation(initiator, partner, initiatorStand, partnerStand, ta, tb);
    }

    public boolean isParticipant(UUID id) {
        return a.equals(id) || b.equals(id);
    }

    /** UUID of the OTHER citizen in the pair, given one's UUID. */
    @Nullable
    public UUID otherSide(UUID self) {
        if (a.equals(self)) return b;
        if (b.equals(self)) return a;
        return null;
    }

    /** That citizen's stand position. */
    public BlockPos standFor(UUID self) {
        return a.equals(self) ? meetPosA : meetPosB;
    }

    /** That citizen's topic on the given bubble turn (0..2). */
    public ConversationTopic topicFor(UUID self, int bubbleIndex) {
        return a.equals(self) ? topicsA[bubbleIndex] : topicsB[bubbleIndex];
    }

    /** Marks the given participant as arrived at their stand tile. */
    public void markArrived(UUID self) {
        if (a.equals(self)) aArrived = true;
        else if (b.equals(self)) bArrived = true;
    }

    public boolean bothArrived() {
        return aArrived && bArrived;
    }

    /** Initiator-only — move to a new phase and reset the phase timer to {@code now}. */
    public void transitionTo(Phase next, long now) {
        this.phase = next;
        this.phaseStartGameTime = now;
    }

    /** Ticks elapsed since the current phase began, or 0 if the phase hasn't started yet. */
    public int ticksInPhase(long now) {
        if (phaseStartGameTime < 0L) return 0;
        long elapsed = now - phaseStartGameTime;
        return elapsed < 0L ? 0 : (int) elapsed;
    }

    /** Relationship delta for the resolved conversation. {@code matches} should be 0..3. */
    public static int outcomeDelta(int matches) {
        return switch (matches) {
            case 0 -> FIGHT_DELTA;
            case 1 -> ARGUMENT_DELTA;
            case 2 -> NEUTRAL_DELTA;
            case 3 -> AGREEMENT_DELTA;
            default -> 0;
        };
    }
}
