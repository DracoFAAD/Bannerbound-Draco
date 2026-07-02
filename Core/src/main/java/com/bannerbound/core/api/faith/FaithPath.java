package com.bannerbound.core.api.faith;

/**
 * The mechanical ruleset a faith runs on (FAITH_PLAN.md): paths are templates we ship,
 * faiths are player-created instances. Antiquity ships two; later ages append — NEVER
 * reorder (ordinals are persisted and synced).
 */
public enum FaithPath {
    /** Passive domain boosts from player-drawn constellations; the gods live in the sky. */
    ASTROLOGY,
    /** Active ritual boosts from the totem pole; the gods are HERE — and they make demands. */
    TOTEMIC;

    public static FaithPath fromOrdinal(int ordinal) {
        FaithPath[] vals = values();
        return ordinal >= 0 && ordinal < vals.length ? vals[ordinal] : ASTROLOGY;
    }
}
