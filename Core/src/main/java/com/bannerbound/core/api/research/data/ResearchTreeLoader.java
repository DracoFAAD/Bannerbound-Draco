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
 * Loads research nodes from data/&lt;namespace&gt;/research/&lt;id&gt;.json. The full ResourceLocation
 * is used as the node id (e.g. "bannerboundantiquity:knapping"), so cross-pack references work.
 */
public class ResearchTreeLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "research";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, ResearchDefinition> TREE = Map.of();

    public ResearchTreeLoader() {
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
                    if (parsed != null) {
                        minAge = parsed;
                    } else {
                        BannerboundCore.LOGGER.warn("Bad min_age in {}: {}", entry.getKey(),
                            GsonHelper.getAsString(obj, "min_age"));
                    }
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
                    // "unlocks.policy": ["nightshift"] folds into a flag "unlock.policy.nightshift"
                    // so PolicyRegistry can query availability via the same hasFlag path as any
                    // other capability gate â€” no separate unlock channel needed.
                    for (String policyId : readStringArray(unlocks, "policy")) {
                        unlocksFlags.add("unlock.policy." + policyId);
                    }
                    // "unlocks.palette": ["brown_haven"] â†’ "unlock.palette.brown_haven", queried by
                    // PaletteLoader.availableFor via the same hasFlag path.
                    for (String paletteId : readStringArray(unlocks, "palette")) {
                        unlocksFlags.add("unlock.palette." + paletteId);
                    }
                    // "unlocks.policy_slot": ["ECONOMIC","DIPLOMATIC"] adds one "unlock.policy_slot.<TYPE>"
                    // flag per entry (duplicates kept on purpose — each entry grants one slot of that
                    // type). Settlement.researchGrantedPolicySlots counts these across both trees.
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
                BannerboundCore.LOGGER.error("Failed to parse research {}", entry.getKey(), ex);
            }
        }
        TREE = Collections.unmodifiableMap(map);
        BannerboundCore.LOGGER.info("Loaded research tree with {} nodes", map.size());
    }

    /** Parses an optional {@code "government_type"} string ("COUNCIL" / "CHIEFDOM" / "NONE")
     *  into a {@link com.bannerbound.core.api.settlement.Settlement.Government}, or null when
     *  absent (= visible under any government). Bad values log a warning and fall back to null. */
    @org.jetbrains.annotations.Nullable
    private static com.bannerbound.core.api.settlement.Settlement.Government parseGovernmentType(
            JsonObject obj, ResourceLocation key) {
        if (!obj.has("government_type")) return null;
        String raw = GsonHelper.getAsString(obj, "government_type");
        try {
            return com.bannerbound.core.api.settlement.Settlement.Government
                .valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            BannerboundCore.LOGGER.warn("Bad government_type in {}: {}", key, raw);
            return null;
        }
    }

    private static List<String> readStringArray(JsonObject obj, String key) {
        List<String> out = new ArrayList<>();
        if (!obj.has(key)) {
            return out;
        }
        JsonArray arr = GsonHelper.getAsJsonArray(obj, key);
        for (JsonElement el : arr) {
            out.add(el.getAsString());
        }
        return out;
    }

    public static Map<String, ResearchDefinition> getAll() {
        return TREE;
    }

    public static ResearchDefinition get(String id) {
        return TREE.get(id);
    }
}
