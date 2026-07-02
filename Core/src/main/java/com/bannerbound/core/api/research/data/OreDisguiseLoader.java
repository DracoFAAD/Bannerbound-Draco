package com.bannerbound.core.api.research.data;

import com.bannerbound.core.api.research.OreDisguise;

import java.util.Collections;
import java.util.HashMap;
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
import net.minecraft.world.level.block.Block;

/**
 * Loads ore-disguise mappings from data/&lt;namespace&gt;/ore_disguises/*.json. Each file has a
 * {@code disguises} array of {@code {ore, as, flag}} objects. Modded ores can be added by any
 * datapack — the loader unions all files into a single keyed-by-ore map.
 */
public class OreDisguiseLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "ore_disguises";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, OreDisguise> DISGUISES = Collections.emptyMap();

    public OreDisguiseLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, OreDisguise> map = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                JsonArray arr = GsonHelper.getAsJsonArray(obj, "disguises");
                for (JsonElement el : arr) {
                    JsonObject e = el.getAsJsonObject();
                    String ore = GsonHelper.getAsString(e, "ore");
                    String as = GsonHelper.getAsString(e, "as");
                    String flag = GsonHelper.getAsString(e, "flag");
                    map.put(ore, new OreDisguise(ore, as, flag));
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse ore_disguises {}", entry.getKey(), ex);
            }
        }
        DISGUISES = map;
        BannerboundCore.LOGGER.info("Loaded {} ore disguises", map.size());
    }

    public static Map<String, OreDisguise> getAll() {
        return DISGUISES;
    }

    public static OreDisguise getDisguiseFor(String oreId) {
        return DISGUISES.get(oreId);
    }

    public static OreDisguise getDisguiseFor(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? null : DISGUISES.get(id.toString());
    }
}
