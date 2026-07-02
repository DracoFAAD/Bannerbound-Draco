package com.bannerbound.core.api.faith;

import com.bannerbound.core.celestial.StarType;

/**
 * The shared effect vocabulary of both faith paths (FAITH_PLAN Part 2) — astrology
 * expresses these as constellation passives, totemic as rituals. Append-only: ordinals
 * are persisted and synced.
 */
public enum DeityDomain {
    HARVEST,
    WAR,
    KINSHIP,
    KNOWLEDGE,
    CRAFT,
    JOURNEY,
    SEA;

    /** The star type → domain mapping (FAITH_PLAN Part 3: a constellation's domain
     *  profile comes from its typed stars). */
    public static DeityDomain fromStarType(StarType type) {
        return switch (type) {
            case GOLD -> HARVEST;
            case RED -> WAR;
            case TWIN -> KINSHIP;
            case BLUE -> KNOWLEDGE;
            case AMBER -> CRAFT;
            case LONE_PALE -> JOURNEY;
            case SEA_GREEN -> SEA;
        };
    }

    public static DeityDomain fromOrdinal(int ordinal) {
        DeityDomain[] vals = values();
        return ordinal >= 0 && ordinal < vals.length ? vals[ordinal] : HARVEST;
    }
}
