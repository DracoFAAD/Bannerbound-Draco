package com.bannerbound.core.api.research;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public registry describing how insight trigger counters are evaluated. Expansion mods may
 * register a type during common setup, then call {@link InsightManager#recordEvent} when it occurs.
 */
public final class InsightTriggerRegistry {
    public enum Kind { COUNT, LEVEL, EVENT }

    public record Type(String id, Kind kind, boolean targetRequired) {}

    private static final Map<String, Type> TYPES = new LinkedHashMap<>();

    static {
        register("mine_block", Kind.COUNT, true);
        register("kill_entity", Kind.COUNT, true);
        register("place_block", Kind.COUNT, true);
        register("claim_chunk", Kind.COUNT, false);
        register("reach_population", Kind.LEVEL, false);
        // "The settlement has ≥N of an item right now." A holdings poll (InsightManager#pollObtain),
        // NOT an event — it sums settlement storage + online members' inventories, so it counts items
        // however they were obtained (picked up, crafted, pulled out of a custom workstation like a
        // drying rack, taken from a chest) and stamps nothing on items (they stack normally). LEVEL
        // semantics like reach_population: sticky once the threshold is hit. Replaced the old
        // craft_item trigger, which could not see crafts at the mod's many custom workstations.
        register("obtain_item", Kind.LEVEL, true);
        // Fires when an animal is bred. Target optional: "" matches any animal, or an entity id/#tag.
        register("breed_animal", Kind.COUNT, false);
    }

    private InsightTriggerRegistry() {}

    public static synchronized void register(String id, Kind kind, boolean targetRequired) {
        if (id == null || id.isBlank() || kind == null) {
            throw new IllegalArgumentException("Insight trigger id and kind are required");
        }
        String key = id.trim();
        Type previous = TYPES.putIfAbsent(key, new Type(key, kind, targetRequired));
        if (previous != null && (!previous.kind().equals(kind)
                || previous.targetRequired() != targetRequired)) {
            throw new IllegalStateException("Insight trigger already registered with different properties: " + key);
        }
    }

    public static synchronized Type get(String id) {
        return TYPES.get(id);
    }

    public static synchronized Map<String, Type> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(TYPES));
    }
}
