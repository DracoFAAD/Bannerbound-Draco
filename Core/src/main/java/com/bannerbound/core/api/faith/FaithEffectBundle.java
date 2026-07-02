package com.bannerbound.core.api.faith;

import java.util.EnumMap;
import java.util.Map;

/**
 * The summed passive contribution of a faith's whole pantheon to one settlement
 * (FAITH_PLAN Part 3). Transient + recomputed — never persisted; the live pantheon is
 * authoritative. The economy reads the per-stat getters; recompute replaces it wholesale.
 */
public final class FaithEffectBundle {
    private final Map<FaithEffectType, Double> values = new EnumMap<>(FaithEffectType.class);

    public void clear() {
        values.clear();
    }

    public void add(FaithEffectType type, double value) {
        values.merge(type, value, Double::sum);
    }

    public double get(FaithEffectType type) {
        return values.getOrDefault(type, 0.0);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    // Convenience getters for the read-sites.
    public double food() {
        return get(FaithEffectType.FOOD_PER_SECOND);
    }

    public double science() {
        return get(FaithEffectType.SCIENCE_PER_SECOND);
    }

    public double culture() {
        return get(FaithEffectType.CULTURE_PER_SECOND);
    }

    public double citizenSpeed() {
        return get(FaithEffectType.CITIZEN_SPEED);
    }

    public double devotion() {
        return get(FaithEffectType.DEVOTION_PER_SECOND);
    }
}
