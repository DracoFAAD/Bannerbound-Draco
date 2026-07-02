package com.bannerbound.core.api.citystate.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.territory.ChunkResource;
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
 * Loads the city-state <b>goods catalog</b> from {@code data/<ns>/citystate_goods/*.json} — what a
 * city-state can PRODUCE, gated three ways (biome × its own adopted tech × specialized resource
 * chunks) and weighted by the village's real job-site POIs. Entries from all files merge. Schema:
 * <pre>{@code
 * { "entries": [
 *   { "item": "bannerboundantiquity:cow_hide",
 *     "weight": 1.0,                                  // relative share of the production budget
 *     "biomes": ["plains", "meadow"],                 // substring match on the biome path; empty/absent = all
 *     "requires_tech": "bannerboundantiquity:tanning",// active only once the city-state adopts it
 *     "requires_chunk": "CATTLE",                     // ChunkResource name(s); string or array = ALL required
 *     "poi": "minecraft:butcher" }                    // each counted job-site POI boosts weight ×1.5 (cap ×3)
 * ]}
 * }</pre>
 * Food goods need no flag — anything with a {@code FoodValueLoader} value counts as food. Design:
 * repo-root {@code CITY_STATES_PLAN.md} Phase 3.
 */
public class CityStateGoodsLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "citystate_goods";
    private static final Gson GSON = new Gson();
    private static volatile List<GoodDef> GOODS = List.of();
    /** Bumped every datapack reload so cached per-city-state resolutions know to recompute. */
    private static volatile int GENERATION = 0;

    /** One producible good. {@code requiresChunks} empty = no chunk gate; otherwise ALL must be
     *  present in the city-state's scanned territory. */
    public record GoodDef(String item, double weight, List<String> biomes, String requiresTech,
                          List<ChunkResource> requiresChunks, String poi) {}

    public CityStateGoodsLoader() {
        super(GSON, FOLDER);
    }

    public static List<GoodDef> goods() {
        return GOODS;
    }

    public static int generation() {
        return GENERATION;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                         ProfilerFiller profiler) {
        List<GoodDef> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                JsonArray entries = GsonHelper.getAsJsonArray(obj, "entries");
                for (JsonElement e : entries) {
                    GoodDef def = parseEntry(e.getAsJsonObject(), entry.getKey());
                    if (def != null) out.add(def);
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse citystate_goods {}", entry.getKey(), ex);
            }
        }
        GOODS = Collections.unmodifiableList(out);
        GENERATION++;
        BannerboundCore.LOGGER.info("Loaded city-state goods catalog: {} entries", out.size());
    }

    private static GoodDef parseEntry(JsonObject obj, ResourceLocation src) {
        String item = GsonHelper.getAsString(obj, "item", "");
        if (item.isEmpty() || ResourceLocation.tryParse(item) == null) {
            BannerboundCore.LOGGER.warn("citystate_goods entry with bad item id '{}' in {}", item, src);
            return null;
        }
        double weight = GsonHelper.getAsDouble(obj, "weight", 1.0);
        List<String> biomes = new ArrayList<>();
        if (obj.has("biomes")) {
            for (JsonElement b : GsonHelper.getAsJsonArray(obj, "biomes")) biomes.add(b.getAsString());
        }
        String tech = GsonHelper.getAsString(obj, "requires_tech", "");
        List<ChunkResource> chunks = new ArrayList<>();
        if (obj.has("requires_chunk")) {
            JsonElement rc = obj.get("requires_chunk");
            if (rc.isJsonArray()) {
                for (JsonElement c : rc.getAsJsonArray()) addChunk(chunks, c.getAsString(), src);
            } else {
                addChunk(chunks, rc.getAsString(), src);
            }
        }
        String poi = GsonHelper.getAsString(obj, "poi", "");
        return new GoodDef(item, Math.max(0.01, weight), List.copyOf(biomes),
            tech.isEmpty() ? null : tech, List.copyOf(chunks), poi.isEmpty() ? null : poi);
    }

    private static void addChunk(List<ChunkResource> chunks, String name, ResourceLocation src) {
        try {
            chunks.add(ChunkResource.valueOf(name));
        } catch (IllegalArgumentException ex) {
            BannerboundCore.LOGGER.warn("Unknown chunk resource '{}' in citystate_goods {}", name, src);
        }
    }
}
