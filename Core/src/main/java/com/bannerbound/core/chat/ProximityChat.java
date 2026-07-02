package com.bannerbound.core.chat;

import org.jetbrains.annotations.ApiStatus;

/**
 * Shared distance → audibility maths for proximity chat. Used by both public chat
 * ({@code ServerChatEvent}) and private messages ({@code /msg|/tell|/w} via {@code CommandEvent}).
 *
 * <p>Model: a listener within {@link #CLEAR_RADIUS} blocks of the speaker hears them at full
 * clarity (alpha 1.0). Past that, audibility fades linearly down to {@link #MIN_ALPHA} at
 * {@link #MAX_RADIUS} blocks, beyond which the message isn't delivered at all. The alpha is sent
 * to the client and applied as a per-message text transparency by the chat render mixin
 * ({@code ChatComponentMixin}).
 *
 * <p>{@link #MIN_ALPHA} is deliberately {@code > 0}: the chat mixin treats a stored alpha of
 * {@code 0} as "unset → fully opaque" (the default for every ordinary message), so a genuine
 * proximity alpha must never be exactly zero.
 */
@ApiStatus.Internal
public final class ProximityChat {
    private ProximityChat() {
    }

    /** Full clarity within this many blocks of the speaker. */
    public static final double CLEAR_RADIUS = 50.0;
    /** Hard cutoff — nothing is delivered past this many blocks. */
    public static final double MAX_RADIUS = 100.0;
    /** Faintest a still-delivered message can be (at exactly {@link #MAX_RADIUS}). */
    public static final float MIN_ALPHA = 0.12f;

    /** True if a listener at {@code distance} blocks is close enough to receive the message. */
    public static boolean inRange(double distance) {
        return distance <= MAX_RADIUS;
    }

    /**
     * Text alpha (0–1) for a listener {@code distance} blocks from the speaker. 1.0 within
     * {@link #CLEAR_RADIUS}, fading linearly to {@link #MIN_ALPHA} at {@link #MAX_RADIUS}.
     * Callers should gate on {@link #inRange(double)} first; values past the cutoff clamp to
     * {@link #MIN_ALPHA}.
     */
    public static float alphaFor(double distance) {
        if (distance <= CLEAR_RADIUS) {
            return 1.0f;
        }
        if (distance >= MAX_RADIUS) {
            return MIN_ALPHA;
        }
        double t = (distance - CLEAR_RADIUS) / (MAX_RADIUS - CLEAR_RADIUS); // 0..1
        return (float) (1.0 - t * (1.0 - MIN_ALPHA));
    }
}
