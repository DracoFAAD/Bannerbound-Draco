package com.bannerbound.core.citystate;

/**
 * Difficulty of AI city-states — how fast they accrue tradeable materials and advance their own
 * research. Higher difficulty = a more populous / industrious neighbour, NOT a thin-air multiplier
 * (see CITY_STATES plan §1D): the factor scales believable levers (effective population, work speed),
 * so a HARD city-state simply behaves like a bigger, busier town.
 *
 * <p>Default {@link #MEDIUM}. Configurable via {@code Config.CITY_STATE_DIFFICULTY}; per
 * {@code neoforge-config-persistence} an existing run TOML won't pick up a changed default.
 */
public enum CityStateDifficulty {
    VERY_EASY(0.5f),
    EASY(0.75f),
    MEDIUM(1.0f),
    HARD(1.5f),
    VERY_HARD(2.0f);

    private final float factor;

    CityStateDifficulty(float factor) {
        this.factor = factor;
    }

    /** Multiplier on believable economic levers (effective population / work speed). */
    public float factor() {
        return factor;
    }

    public static CityStateDifficulty fromName(String name) {
        if (name == null) return MEDIUM;
        for (CityStateDifficulty d : values()) {
            if (d.name().equalsIgnoreCase(name)) return d;
        }
        return MEDIUM;
    }
}
