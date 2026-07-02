package com.bannerbound.core.api.settlement;

/**
 * A randomly-rolled, permanent characteristic of a citizen. Each trait carries a roll
 * {@code chance} (applied independently when the citizen immigrates) and a {@code genetic} flag.
 * <p>
 * The {@code genetic} flag is forward-looking: when babies + family trees land, genetic traits
 * will be inheritable from parents rather than rolled fresh. Non-genetic traits will keep being
 * rolled per-citizen regardless of lineage. Nothing reads the flag yet — it's recorded now so the
 * trait table doesn't need a migration later.
 * <p>
 * Stored on the entity as a bitmask of {@link #ordinal()} bits (NBT + synced data), so existing
 * entries must keep their position; only append new ones at the end.
 */
public enum CitizenTrait {
    /** Citizen holds tools/items in the left hand instead of the right. Genetic — handedness
     *  runs in families. */
    LEFT_HANDED("left_handed", 0.10f, true);

    private final String id;
    private final float chance;
    private final boolean genetic;

    CitizenTrait(String id, float chance, boolean genetic) {
        this.id = id;
        this.chance = chance;
        this.genetic = genetic;
    }

    /** Stable string id (used for lang keys / future data references). */
    public String id() {
        return id;
    }

    /** Probability [0,1] that a freshly-immigrated citizen rolls this trait. */
    public float chance() {
        return chance;
    }

    /** True if this trait will be inheritable once family trees exist. */
    public boolean genetic() {
        return genetic;
    }

    /** Bit for this trait in a citizen's packed trait mask. */
    public int bit() {
        return 1 << ordinal();
    }
}
