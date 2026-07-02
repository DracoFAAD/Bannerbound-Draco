package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

/**
 * Single source of truth for the tribe-vote reveal animation timing. Both the server
 * (which schedules the chief/government enactment after the reveal) and the client
 * ({@link TribeVoteScreen}, which paces the row reveals) read from here so the install
 * fires exactly when the last vote lands on screen — no drift if either side is tuned.
 *
 * <p>The reveal sequence is: a per-vote delay starting at {@link #FIRST_DELAY_MS} and
 * decaying by {@link #DECAY_FACTOR} on each step, floored at {@link #MIN_DELAY_MS}.
 * {@link #revealDurationMs(int)} computes the total time the screen will spend revealing
 * {@code n} votes; the server passes this to {@code schedulePendingChief} /
 * {@code schedulePendingGovernment} so the enactment lands right after the last reveal.
 *
 * <p>Lives in the client package because the screen reads the constants directly, but the
 * class itself is plain Java with no client-only dependencies — the server imports it for
 * the duration calculation.
 */
@ApiStatus.Internal
public final class TribeVoteTiming {
    /** Delay before the first vote reveals — the "Waiting..." pause. */
    public static final long FIRST_DELAY_MS = 1000L;
    /** Each subsequent delay is multiplied by this factor (< 1 → accelerating). */
    public static final double DECAY_FACTOR = 0.65;
    /** Floor for the per-vote delay so the last few don't fire on the same frame. */
    public static final long MIN_DELAY_MS = 80L;
    /** Linger after the last reveal — gives the player a beat to read the result before
     *  the install enacts and the chat broadcast lands. */
    public static final long FINAL_HOLD_MS = 2000L;

    private TribeVoteTiming() {
    }

    /** Total ms the reveal animation takes for {@code voteCount} votes, INCLUDING the
     *  {@link #FINAL_HOLD_MS} trailing pause. Server-side code uses this to schedule the
     *  pending chief/government enactment so it fires exactly when the animation ends. */
    public static long revealDurationMs(int voteCount) {
        if (voteCount <= 0) return FINAL_HOLD_MS;
        long total = 0L;
        double delay = FIRST_DELAY_MS;
        for (int i = 0; i < voteCount; i++) {
            total += (long) delay;
            delay = Math.max(MIN_DELAY_MS, delay * DECAY_FACTOR);
        }
        return total + FINAL_HOLD_MS;
    }
}
