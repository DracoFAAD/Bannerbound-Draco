package com.bannerbound.core.barbarian;

/**
 * A barbarian camp's stance toward a given player-settlement, derived from the numeric relationship
 * score against per-type thresholds. HOSTILE camps schedule raids; NEUTRAL/FRIENDLY camps may send
 * messengers/scouts.
 */
public enum CampRelationState {
    HOSTILE,
    NEUTRAL,
    FRIENDLY;

    public static CampRelationState fromName(String name) {
        if (name == null) return null;
        for (CampRelationState s : values()) {
            if (s.name().equalsIgnoreCase(name)) return s;
        }
        return null;
    }
}
