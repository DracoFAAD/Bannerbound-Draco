package com.bannerbound.core.api.territory.data;

import com.bannerbound.core.territory.ChunkClaimCostFile;

import com.bannerbound.core.api.territory.ChunkClaimCost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Loads chunk-claim cost ladders from {@code data/&lt;ns&gt;/chunk_claim_costs/&lt;era&gt;.json}.
 * Schema (sketch):
 * <pre>{@code
 * {
 *   "era": "ancient",
 *   "max_expansions": 5,
 *   "default": {
 *     "tiers": [
 *       { "population": 2, "items": [{"id":"minecraft:cobblestone","count":24}] },
 *       ...
 *     ]
 *   },
 *   "biomes": {
 *     "minecraft:desert": { "tiers": [...] }
 *   }
 * }
 * }</pre>
 * Files are keyed by their stem ("ancient", "classical", "medieval", ...). Unknown items log a warning and
 * the entry is skipped so a single bad id can't take down the whole tier.
 */
public class ChunkClaimCostLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "chunk_claim_costs";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, ChunkClaimCostFile> FILES = Collections.emptyMap();

    public ChunkClaimCostLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, ChunkClaimCostFile> map = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation key = entry.getKey();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                String era = GsonHelper.getAsString(obj, "era", key.getPath());
                int maxExpansions = GsonHelper.getAsInt(obj, "max_expansions", 0);

                List<ChunkClaimCost> defaultTiers = parseTiers(
                    GsonHelper.getAsJsonObject(obj, "default").getAsJsonArray("tiers"), key);
                Map<ResourceLocation, List<ChunkClaimCost>> biomeTiers = new HashMap<>();
                if (obj.has("biomes")) {
                    JsonObject biomesObj = obj.getAsJsonObject("biomes");
                    for (Map.Entry<String, JsonElement> bEntry : biomesObj.entrySet()) {
                        ResourceLocation biomeRl = ResourceLocation.tryParse(bEntry.getKey());
                        if (biomeRl == null) {
                            BannerboundCore.LOGGER.warn("Bad biome id '{}' in {}", bEntry.getKey(), key);
                            continue;
                        }
                        JsonObject biomeObj = bEntry.getValue().getAsJsonObject();
                        List<ChunkClaimCost> tiers = parseTiers(biomeObj.getAsJsonArray("tiers"), key);
                        biomeTiers.put(biomeRl, tiers);
                    }
                }

                map.put(era, new ChunkClaimCostFile(era, maxExpansions, defaultTiers, biomeTiers));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse chunk_claim_costs {}", key, ex);
            }
        }
        FILES = map;
        BannerboundCore.LOGGER.info("Loaded {} chunk-claim cost files", map.size());
    }

    private static List<ChunkClaimCost> parseTiers(JsonArray tiersArr, ResourceLocation src) {
        List<ChunkClaimCost> tiers = new ArrayList<>();
        for (JsonElement el : tiersArr) {
            JsonObject t = el.getAsJsonObject();
            int pop = GsonHelper.getAsInt(t, "population", 0);
            List<ChunkClaimCost.ItemCost> items = new ArrayList<>();
            JsonArray itemsArr = t.getAsJsonArray("items");
            if (itemsArr != null) {
                for (JsonElement iEl : itemsArr) {
                    JsonObject iObj = iEl.getAsJsonObject();
                    String idStr = GsonHelper.getAsString(iObj, "id");
                    int count = GsonHelper.getAsInt(iObj, "count", 1);
                    ResourceLocation itemRl = ResourceLocation.tryParse(idStr);
                    Item item = itemRl == null ? Items.AIR : BuiltInRegistries.ITEM.get(itemRl);
                    if (item == Items.AIR) {
                        BannerboundCore.LOGGER.warn("Unknown item '{}' in {}", idStr, src);
                        continue;
                    }
                    items.add(new ChunkClaimCost.ItemCost(item, count));
                }
            }
            tiers.add(new ChunkClaimCost(pop, items));
        }
        return tiers;
    }

    /** Look up the cost file for an era key (e.g. {@code "ancient"}). Null if none defined. */
    public static ChunkClaimCostFile get(String era) {
        return FILES.get(era);
    }

    public static Map<String, ChunkClaimCostFile> all() { return FILES; }
}
