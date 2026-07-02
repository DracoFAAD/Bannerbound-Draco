package com.bannerbound.core.api.faith;

/**
 * The vocabulary of passive faith effects (FAITH_PLAN Part 2 domain table). Each type is
 * a settlement-wide lever the {@link FaithEffectBundle} aggregates and the economy reads.
 * Adding a new lever = one enum constant + one read-site that consumes it; the
 * domain/combo tables in {@link FaithEffects} then reference it by name.
 *
 * <p>Append-only is not required (these aren't persisted — bundles are recomputed from the
 * pantheon every second), but keep ids stable since the data tables key on them.
 */
public enum FaithEffectType {
    FOOD_PER_SECOND,
    SCIENCE_PER_SECOND,
    CULTURE_PER_SECOND,
    CITIZEN_SPEED,
    DEVOTION_PER_SECOND;
}
