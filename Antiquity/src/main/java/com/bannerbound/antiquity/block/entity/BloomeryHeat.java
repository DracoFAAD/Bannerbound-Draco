package com.bannerbound.antiquity.block.entity;

import com.bannerbound.antiquity.metalworking.MetalworkingData;

/**
 * The bloomery's temperature model (METALWORKING_PLAN.md Part 1). Heat is <b>fire-driven</b> and
 * independent of the contents: while lit (door shut) the temperature climbs toward a ceiling (base
 * fire + a decaying bellows boost) and decays to 0 otherwise. Progress on whatever sits inside only
 * advances when the temperature is inside that recipe's band — off-band only changes speed.
 *
 * <p>All the tuning numbers (base ceiling, bellows, melt points/bands) are <b>data-driven</b> via
 * {@link MetalworkingData}; this class just reads them. Only {@link #DEFAULT_BAND_LOW}/{@link
 * #DEFAULT_BAND_HIGH} stay constants because they're compile-time defaults for the recipe codec.
 */
public final class BloomeryHeat {
    private BloomeryHeat() {}

    /** Default band for an ore→ingot bloomery recipe that doesn't specify one (codec fallback). */
    public static final int DEFAULT_BAND_LOW = 600;
    public static final int DEFAULT_BAND_HIGH = 1300;

    public static float baseCeiling() { return MetalworkingData.bloomery().baseCeiling(); }
    public static float bellowsPerPump() { return MetalworkingData.bloomery().bellowsPerPump(); }
    public static float bellowsMax() { return MetalworkingData.bloomery().bellowsMax(); }
    public static float bellowsDecay() { return MetalworkingData.bloomery().bellowsDecay(); }
    public static float climb() { return MetalworkingData.bloomery().climb(); }
    public static float fall() { return MetalworkingData.bloomery().fall(); }

    /** Melting point of a metal (°C) — the low edge of its crucible band (data-driven). */
    public static int meltPoint(String metal) {
        return MetalworkingData.meltPoint(metal);
    }

    /** Crucible melt band for a metal: {@code [meltPoint, meltPoint + meltBandWidth]}. */
    public static int[] meltBand(String metal) {
        int m = meltPoint(metal);
        return new int[] { m, m + MetalworkingData.bloomery().meltBandWidth() };
    }

    /** Temperature-vs-band verdict, mirroring the readout labels. */
    public enum Band { NONE, NO_FIRE, TOO_LOW, OKAY, GOOD, TOO_HIGH }

    public static Band classify(boolean fireLit, boolean hasBand, float temp, int low, int high) {
        if (!hasBand) return Band.NONE;
        if (!fireLit && temp < 1f) return Band.NO_FIRE;
        if (temp < low) return Band.TOO_LOW;
        if (temp > high) return Band.TOO_HIGH;
        return temp <= low + MetalworkingData.bloomery().greenWidth() ? Band.GOOD : Band.OKAY;
    }

    /** Progress multiplier for a band verdict: full in green, slow in yellow, stalled otherwise. */
    public static float rate(Band band) {
        return switch (band) {
            case GOOD -> 1.0f;
            case OKAY -> 0.4f;
            default -> 0.0f;
        };
    }
}
