package com.bannerbound.core.api.territory.data;

import java.util.LinkedHashMap;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.territory.ChunkResource;
import com.bannerbound.core.territory.ChunkResourceDistribution;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads the specialized-chunk distribution from {@code data/<ns>/chunk_resources/*.json} so it can be
 * retuned by hand and {@code /reload}ed — no recompile. Schema:
 * <pre>{@code
 * {
 *   "max_relief": 5,
 *   "categories": {
 *     "plains":      { "chance": 0.12, "weights": { "CATTLE": 37, "SHEEP": 17, "HORSES": 10.5, "IRON": 3.7 } },
 *     "mountainous": { "chance": 0.15, "weights": { "COPPER": 48, "IRON": 20, "MARBLE": 16, "TIN": 9 } },
 *     "forest":      { "chance": 0.07, "weights": { "PIGS": 32, "CHICKENS": 26, "CATTLE": 12 } },
 *     "aquatic":     { "chance": 0.12, "weights": { "FISH": 100 } },
 *     "other":       { "chance": 0.04, "weights": { "COPPER": 55, "IRON": 25 } }
 *   }
 * }
 * }</pre>
 * Category keys are fixed ({@code aquatic}/{@code mountainous}/{@code plains}/{@code forest}/{@code other});
 * weight keys are {@link ChunkResource} names. If multiple files exist their categories merge (later wins).
 * With no file present, {@link ChunkResourceDistribution#defaults()} is used.
 */
public class ChunkResourceLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "chunk_resources";
    private static final Gson GSON = new Gson();
    private static volatile ChunkResourceDistribution DISTRIBUTION = ChunkResourceDistribution.defaults();

    public ChunkResourceLoader() {
        super(GSON, FOLDER);
    }

    /** The active distribution (never null — falls back to {@link ChunkResourceDistribution#defaults()}). */
    public static ChunkResourceDistribution get() {
        return DISTRIBUTION;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        if (resources.isEmpty()) {
            DISTRIBUTION = ChunkResourceDistribution.defaults();
            BannerboundCore.LOGGER.info("Chunk-resource distribution: using built-in defaults (no data files)");
            return;
        }
        int maxRelief = 5;
        Map<String, ChunkResourceDistribution.Category> categories = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation key = entry.getKey();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                maxRelief = GsonHelper.getAsInt(obj, "max_relief", maxRelief);
                JsonObject cats = GsonHelper.getAsJsonObject(obj, "categories");
                for (Map.Entry<String, JsonElement> cEntry : cats.entrySet()) {
                    ChunkResourceDistribution.Category cat = parseCategory(cEntry.getValue().getAsJsonObject(), key);
                    if (cat != null) categories.put(cEntry.getKey(), cat);
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse chunk_resources {}", key, ex);
            }
        }
        DISTRIBUTION = categories.isEmpty()
            ? ChunkResourceDistribution.defaults()
            : new ChunkResourceDistribution(maxRelief, categories);
        BannerboundCore.LOGGER.info("Loaded chunk-resource distribution: {} categories, max_relief={}",
            categories.size(), maxRelief);
    }

    private static ChunkResourceDistribution.Category parseCategory(JsonObject obj, ResourceLocation src) {
        double chance = GsonHelper.getAsDouble(obj, "chance", 0);
        JsonObject weights = GsonHelper.getAsJsonObject(obj, "weights");
        LinkedHashMap<ChunkResource, Double> menu = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> w : weights.entrySet()) {
            ChunkResource resource;
            try {
                resource = ChunkResource.valueOf(w.getKey());
            } catch (IllegalArgumentException ex) {
                BannerboundCore.LOGGER.warn("Unknown chunk resource '{}' in {}", w.getKey(), src);
                continue;
            }
            menu.put(resource, w.getValue().getAsDouble());
        }
        if (menu.isEmpty()) return null;
        return ChunkResourceDistribution.category(chance, menu);
    }
}
