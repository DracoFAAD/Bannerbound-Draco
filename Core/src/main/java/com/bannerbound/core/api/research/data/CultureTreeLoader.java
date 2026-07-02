package com.bannerbound.core.api.research.data;

import com.bannerbound.core.api.research.ResearchDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Era;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads culture-tree nodes from {@code data/<namespace>/culture/<id>.json}. Twin of
 * {@link ResearchTreeLoader}; same {@link ResearchDefinition} record, separate datapack
 * folder + separate static map. Lets the Culture tab in {@code ResearchScreen} render an
 * independent board while reusing every piece of the science-tree machinery (definition
 * record, JSON schema, on-disk auto-unlock flag).
 */
public class CultureTreeLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "culture";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, ResearchDefinition> TREE = Map.of();

    public CultureTreeLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, ResearchDefinition> map = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            String id = entry.getKey().toString();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                String name = GsonHelper.getAsString(obj, "name");
                String desc = GsonHelper.getAsString(obj, "description", "");
                double cost = GsonHelper.getAsDouble(obj, "cost", 0.0);
                int x = GsonHelper.getAsInt(obj, "x", 0);
                int y = GsonHelper.getAsInt(obj, "y", 0);
                boolean autoUnlock = GsonHelper.getAsBoolean(obj, "auto_unlock", false);
                Era minAge = Era.ANCIENT;
                if (obj.has("min_age")) {
                    Era parsed = Era.fromName(GsonHelper.getAsString(obj, "min_age"));
                    if (parsed != null) minAge = parsed;
                    else BannerboundCore.LOGGER.warn("Bad min_age in culture {}: {}",
                        entry.getKey(), GsonHelper.getAsString(obj, "min_age"));
                }
                List<String> prereqs = readStringArray(obj, "prerequisites");
                List<String> unlocksItems = new ArrayList<>();
                List<String> unlocksFeatures = new ArrayList<>();
                List<String> unlocksFlags = new ArrayList<>();
                if (obj.has("unlocks")) {
                    JsonObject unlocks = GsonHelper.getAsJsonObject(obj, "unlocks");
                    unlocksItems = readStringArray(unlocks, "items");
                    unlocksFeatures = readStringArray(unlocks, "features");
                    unlocksFlags = readStringArray(unlocks, "flags");
                    // Mirror ResearchTreeLoader: "unlocks.policy" â†’ "unlock.policy.<id>" flags.
                    for (String policyId : readStringArray(unlocks, "policy")) {
                        unlocksFlags.add("unlock.policy." + policyId);
                    }
                    // Mirror: "unlocks.palette" â†’ "unlock.palette.<id>" flags.
                    for (String paletteId : readStringArray(unlocks, "palette")) {
                        unlocksFlags.add("unlock.palette." + paletteId);
                    }
                    // Mirror ResearchTreeLoader: "unlocks.policy_slot" adds one
                    // "unlock.policy_slot.<TYPE>" flag per entry (duplicates kept — each grants one
                    // slot). Settlement.researchGrantedPolicySlots counts these across both trees.
                    for (String slotType : readStringArray(unlocks, "policy_slot")) {
                        com.bannerbound.core.api.settlement.PolicyType t =
                            com.bannerbound.core.api.settlement.PolicyType.byName(slotType);
                        if (t == null) {
                            BannerboundCore.LOGGER.warn("Bad unlocks.policy_slot type in {}: {}",
                                entry.getKey(), slotType);
                        } else {
                            unlocksFlags.add("unlock.policy_slot." + t.name());
                        }
                    }
                }
                String ponderScene = GsonHelper.getAsString(obj, "ponder", "");
                com.bannerbound.core.api.settlement.Settlement.Government govType =
                    parseGovernmentType(obj, entry.getKey());
                boolean requiresTribe = GsonHelper.getAsBoolean(obj, "requires_tribe", false);
                int heraldryPoints = GsonHelper.getAsInt(obj, "heraldry_points", 0);
                boolean important = GsonHelper.getAsBoolean(obj, "important", false);
                map.put(id, new ResearchDefinition(id, name, desc, cost, x, y, autoUnlock, minAge,
                    prereqs, unlocksItems, unlocksFeatures, unlocksFlags, ponderScene, govType,
                    requiresTribe, heraldryPoints, important, null,
                    com.bannerbound.core.api.research.InsightDefinition.parse(obj, entry.getKey())));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse culture {}", entry.getKey(), ex);
            }
        }
        TREE = Collections.unmodifiableMap(map);
        BannerboundCore.LOGGER.info("Loaded culture tree with {} nodes", map.size());
    }

    /** Optional {@code "government_type"} â†’ Government, null when absent. Mirror of the same
     *  helper in {@link ResearchTreeLoader}. */
    @org.jetbrains.annotations.Nullable
    private static com.bannerbound.core.api.settlement.Settlement.Government parseGovernmentType(
            JsonObject obj, ResourceLocation key) {
        if (!obj.has("government_type")) return null;
        String raw = GsonHelper.getAsString(obj, "government_type");
        try {
            return com.bannerbound.core.api.settlement.Settlement.Government
                .valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            BannerboundCore.LOGGER.warn("Bad government_type in culture {}: {}", key, raw);
            return null;
        }
    }

    private static List<String> readStringArray(JsonObject obj, String key) {
        List<String> out = new ArrayList<>();
        if (!obj.has(key)) return out;
        JsonArray arr = GsonHelper.getAsJsonArray(obj, key);
        for (JsonElement el : arr) out.add(el.getAsString());
        return out;
    }

    public static Map<String, ResearchDefinition> getAll() { return TREE; }
    public static ResearchDefinition get(String id) { return TREE.get(id); }
}
