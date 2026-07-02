package com.bannerbound.core.api.research.data;

import com.bannerbound.core.api.research.ResearchDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.faith.FaithPath;
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
 * Loads faith-tree nodes from {@code data/<namespace>/faith/<id>.json} (FAITH_PLAN.md
 * Part 2.5 â€” the THIRD tree). Same {@link ResearchDefinition} record and JSON schema as
 * science/culture, plus the {@code "faith_path"} gate ("ASTROLOGY"/"TOTEMIC") â€” the
 * faith-tree analog of {@code government_type}: path-gated branches share one tree, the
 * other path's nodes simply don't render.
 * <p>
 * Authoring convention: the faith tree grows UPWARD (y decreases as the tree ascends) â€”
 * the heavens direction. Costs are in DEVOTION points; the faith's summed member devotion
 * rate fills them (per-FAITH shared progress â€” adopting an established faith inherits its
 * climbed tree).
 */
public class FaithTreeLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "faith";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, ResearchDefinition> TREE = Map.of();

    public FaithTreeLoader() {
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
                }
                String ponderScene = GsonHelper.getAsString(obj, "ponder", "");
                boolean important = GsonHelper.getAsBoolean(obj, "important", false);
                FaithPath faithPath = parseFaithPath(obj, entry.getKey());
                map.put(id, new ResearchDefinition(id, name, desc, cost, x, y, autoUnlock, minAge,
                    prereqs, unlocksItems, unlocksFeatures, unlocksFlags, ponderScene, null,
                    false, 0, important, faithPath,
                    com.bannerbound.core.api.research.InsightDefinition.parse(obj, entry.getKey())));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse faith node {}", entry.getKey(), ex);
            }
        }
        TREE = Collections.unmodifiableMap(map);
        BannerboundCore.LOGGER.info("Loaded faith tree with {} nodes", map.size());
    }

    /** Optional {@code "faith_path"} â†’ FaithPath, null when absent (shared trunk). */
    private static FaithPath parseFaithPath(JsonObject obj, ResourceLocation key) {
        if (!obj.has("faith_path")) return null;
        String raw = GsonHelper.getAsString(obj, "faith_path");
        try {
            return FaithPath.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            BannerboundCore.LOGGER.warn("Bad faith_path in faith node {}: {}", key, raw);
            return null;
        }
    }

    private static List<String> readStringArray(JsonObject obj, String key) {
        List<String> out = new ArrayList<>();
        if (obj.has(key)) {
            JsonArray arr = GsonHelper.getAsJsonArray(obj, key);
            for (JsonElement el : arr) {
                out.add(el.getAsString());
            }
        }
        return out;
    }

    public static ResearchDefinition get(String id) {
        return TREE.get(id);
    }

    public static Map<String, ResearchDefinition> getAll() {
        return TREE;
    }
}
