package com.bannerbound.core.sim;

import org.jetbrains.annotations.ApiStatus;

/**
 * Tiny server-side profiler for citizen tick cost, so we can SEE whether the Village "cheap brain"
 * (work-scanning gated off) actually lowers per-citizen cost instead of guessing. {@link CitizenEntity}
 * wraps its {@code tick()} and reports the nanos; {@link #endTick()} (called once per server tick)
 * snapshots the total + count. The on-screen {@code CitizenAiProfilerHudLayer} reads the snapshot.
 *
 * <p>Accumulators are touched only on the server thread; the snapshot fields are volatile for the
 * client HUD to read (works directly in single-player — same JVM). Toggle with
 * {@code /bannerbound ai_profiler}.
 */
@ApiStatus.Internal
public final class CitizenAiProfiler {
    private static long accumNanos;
    private static int accumCount;
    private static volatile double lastMsPerTick;
    private static volatile int lastCount;
    private static volatile boolean enabled;

    private CitizenAiProfiler() {
    }

    /** Add one citizen's tick duration (server thread only). */
    public static void add(long nanos) {
        accumNanos += nanos;
        accumCount++;
    }

    /** Snapshot this tick's totals and reset the accumulators (call once per server tick). */
    public static void endTick() {
        lastMsPerTick = accumNanos / 1_000_000.0;
        lastCount = accumCount;
        accumNanos = 0;
        accumCount = 0;
    }

    public static double lastMsPerTick() { return lastMsPerTick; }
    public static int lastCount() { return lastCount; }

    /** Average microseconds spent per citizen this tick. */
    public static double lastMicrosPerCitizen() {
        return lastCount == 0 ? 0.0 : (lastMsPerTick * 1000.0) / lastCount;
    }

    public static boolean enabled() { return enabled; }
    public static void toggle() { enabled = !enabled; }
}
