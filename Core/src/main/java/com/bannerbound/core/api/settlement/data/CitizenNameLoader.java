package com.bannerbound.core.api.settlement.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.Era;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads citizen first-name pools from {@code data/<namespace>/citizen_names/<gender>_<era>.json}.
 * The filename encodes the pool: {@code male_ancient.json}, {@code female_medieval.json},
 * {@code unisex_future.json}, etc. Each file is a flat {@code { "names": [ ... ] }} list.
 * <p>
 * A citizen of gender G immigrating in era E draws from the union of the {@code G_E} pool and the
 * {@code unisex_E} pool — so genuinely ambiguous names can be shared across genders without
 * duplicating them into both files. Datapacks can drop additional files into the folder; pools
 * for the same {@code <gender>_<era>} key are unioned across namespaces.
 * <p>
 * Replaces the old hard-coded {@code CitizenNames} table — name content is now fully data-driven.
 */
public class CitizenNameLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "citizen_names";
    private static final Gson GSON = new Gson();
    /** Key: {@code "<gender>_<era>"} (e.g. {@code "male_ancient"}, {@code "unisex_future"}). */
    private static volatile Map<String, List<String>> POOLS = Map.of();

    public CitizenNameLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, List<String>> pools = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            // The map key SimpleJsonResourceReloadListener hands us is the file path minus the
            // folder + .json — i.e. exactly "<gender>_<era>".
            String key = entry.getKey().getPath();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                JsonArray arr = obj.getAsJsonArray("names");
                List<String> names = pools.computeIfAbsent(key, k -> new ArrayList<>());
                for (JsonElement el : arr) {
                    names.add(el.getAsString());
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse citizen_names {}", entry.getKey(), ex);
            }
        }
        POOLS = pools;
        BannerboundCore.LOGGER.info("Loaded {} citizen name pools", pools.size());
    }

    /**
     * Picks a random first name for a citizen of {@code gender} immigrating in {@code era}. Draws
     * from the gendered pool plus the era's unisex pool. Falls back to {@code "Citizen"} if no
     * pool for the era is loaded at all (datapack misconfiguration).
     */
    public static String randomName(RandomSource rng, Era era, CitizenGender gender) {
        String eraKey = era.key();
        List<String> combined = new ArrayList<>();
        combined.addAll(POOLS.getOrDefault(gender.key() + "_" + eraKey, List.of()));
        combined.addAll(POOLS.getOrDefault("unisex_" + eraKey, List.of()));
        if (combined.isEmpty()) {
            return "Citizen";
        }
        return combined.get(rng.nextInt(combined.size()));
    }
}
