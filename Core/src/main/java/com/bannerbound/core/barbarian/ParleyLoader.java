package com.bannerbound.core.barbarian;

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

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads per-camp-type parley offers from {@code data/<namespace>/barbarian_parley/*.json} (shipped by
 * Antiquity, loaded in Core so the messenger/parley code can resolve them without a Core→Antiquity
 * dependency — same arrangement as {@link BarbarianLoadoutLoader}). One file per {@link CampType}:
 * <pre>
 * { "type": "raider",
 *   "greeting": "bannerbound.barbarian.parley.greeting.raider",
 *   "demands": [ { "item": "minecraft:beef", "count": 6 } ],
 *   "trades":  [ { "give": "minecraft:wheat", "give_count": 5, "get": "minecraft:leather", "get_count": 2 } ] }
 * </pre>
 * Demands are what the camp wants handed over (accepting buys peace/cooldown); trades are optional
 * item-for-item swaps a neutral/friendly camp offers.
 */
public final class ParleyLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "barbarian_parley";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, Def> ENTRIES = Map.of();

    public record Demand(String item, int count) {}
    public record Trade(String giveItem, int giveCount, String getItem, int getCount) {}
    public record Def(String type, String greetingKey, List<Demand> demands, List<Trade> trades) {}

    private static final Def EMPTY = new Def("", "bannerbound.barbarian.parley.greeting.generic",
        List.of(), List.of());

    public ParleyLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<String, Def> map = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : resources.entrySet()) {
            try {
                JsonObject obj = e.getValue().getAsJsonObject();
                String type = GsonHelper.getAsString(obj, "type", "").toLowerCase(java.util.Locale.ROOT);
                String greeting = GsonHelper.getAsString(obj, "greeting",
                    "bannerbound.barbarian.parley.greeting.generic");
                List<Demand> demands = new ArrayList<>();
                for (JsonElement d : GsonHelper.getAsJsonArray(obj, "demands", new JsonArray())) {
                    JsonObject o = d.getAsJsonObject();
                    demands.add(new Demand(GsonHelper.getAsString(o, "item"),
                        Math.max(1, GsonHelper.getAsInt(o, "count", 1))));
                }
                List<Trade> trades = new ArrayList<>();
                for (JsonElement t : GsonHelper.getAsJsonArray(obj, "trades", new JsonArray())) {
                    JsonObject o = t.getAsJsonObject();
                    trades.add(new Trade(GsonHelper.getAsString(o, "give"),
                        Math.max(1, GsonHelper.getAsInt(o, "give_count", 1)),
                        GsonHelper.getAsString(o, "get"),
                        Math.max(1, GsonHelper.getAsInt(o, "get_count", 1))));
                }
                if (!type.isEmpty()) map.put(type, new Def(type, greeting, demands, trades));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse barbarian parley {}", e.getKey(), ex);
            }
        }
        ENTRIES = Collections.unmodifiableMap(map);
        BannerboundCore.LOGGER.info("Loaded {} barbarian parley definitions", map.size());
    }

    /** The parley offer for a camp type, or an empty default if none is authored. */
    public static Def forType(CampType type) {
        if (type == null) return EMPTY;
        return ENTRIES.getOrDefault(type.name().toLowerCase(java.util.Locale.ROOT), EMPTY);
    }
}
