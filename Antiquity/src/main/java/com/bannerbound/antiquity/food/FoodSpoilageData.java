package com.bannerbound.antiquity.food;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;

/**
 * Loads how long each food keeps before it spoils, from {@code data/<ns>/food_spoilage/*.json}.
 * Each per-item number (and {@code default_food_ticks}) is the food's <b>total mean lifetime</b> in
 * ticks (20/sec, 24000 = one Minecraft day) — the average time from fresh to fully spoiled. Files are
 * merged, later files winning per item id, so datapacks can retune.
 *
 * <pre>{@code
 * {
 *   "default_food_ticks": 48000,        // any edible item with no explicit entry (~2 days total)
 *   "bland_fraction": 0.4,              // last 40% of the lifetime is spent "bland" (half food value)
 *   "salt_life_multiplier": 2.0,        // salted food keeps this many times longer
 *   "non_perishable": [ "minecraft:rotten_flesh", "minecraft:golden_apple" ],
 *   "items": { "minecraft:cooked_beef": 144000, "minecraft:bread": 96000 }
 * }
 * }</pre>
 *
 * <p>Spoilage is <i>level-based and probabilistic</i> (see {@code Spoilage} and {@link
 * com.bannerbound.antiquity.item.FoodSpoilage}): the lifetime is split into a fresh phase
 * ({@code 1 - bland_fraction} of it) and a bland phase ({@code bland_fraction} of it), and each
 * phase's mean duration sets the per-second chance a stack drops to the next level. This table is
 * consulted server-side only — the resulting freshness level rides the stack as a component, so
 * clients never need the table. Spoilage is an Antiquity (hardcore-survival) layer; Core stays vanilla
 * and knows nothing of it (COOKING_PLAN.md).
 */
public class FoodSpoilageData extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "food_spoilage";
    private static final Gson GSON = new Gson();

    /** Explicit per-item total lifetimes (ticks). */
    private static volatile Map<Item, Integer> TICKS = Collections.emptyMap();
    /** Items that never spoil even though they're edible. */
    private static volatile Set<Item> NON_PERISHABLE = Collections.emptySet();
    /** Fallback total lifetime for any edible item with no explicit entry; {@code <= 0} disables it. */
    private static volatile int defaultFoodTicks = 0;
    /** Fraction of the lifetime spent in the BLAND phase (the rest is FRESH). Default 0.4. */
    private static volatile float blandFraction = 0.4f;
    /** Salted food keeps this many times longer (lower per-roll spoil chance). Default 2.0. */
    private static volatile float saltLifeMultiplier = 2.0f;

    public FoodSpoilageData() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<Item, Integer> ticks = new HashMap<>();
        Set<Item> nonPerishable = new HashSet<>();
        int defTicks = 0;
        float blandFrac = 0.4f;
        float saltMult = 2.0f;
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation key = entry.getKey();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                if (obj.has("default_food_ticks")) {
                    defTicks = GsonHelper.getAsInt(obj, "default_food_ticks");
                }
                if (obj.has("bland_fraction")) {
                    blandFrac = Math.min(0.9f, Math.max(0.05f, GsonHelper.getAsFloat(obj, "bland_fraction")));
                }
                if (obj.has("salt_life_multiplier")) {
                    saltMult = Math.max(1.0f, GsonHelper.getAsFloat(obj, "salt_life_multiplier"));
                }
                if (obj.has("non_perishable")) {
                    for (JsonElement e : GsonHelper.getAsJsonArray(obj, "non_perishable")) {
                        Item it = resolve(e.getAsString(), key);
                        if (it != null) nonPerishable.add(it);
                    }
                }
                if (obj.has("items")) {
                    JsonObject items = GsonHelper.getAsJsonObject(obj, "items");
                    for (Map.Entry<String, JsonElement> i : items.entrySet()) {
                        Item it = resolve(i.getKey(), key);
                        if (it != null) ticks.put(it, Math.max(1, i.getValue().getAsInt()));
                    }
                }
            } catch (Exception ex) {
                BannerboundAntiquity.LOGGER.error("Failed to parse food_spoilage {}", key, ex);
            }
        }
        TICKS = ticks;
        NON_PERISHABLE = nonPerishable;
        defaultFoodTicks = defTicks;
        blandFraction = blandFrac;
        saltLifeMultiplier = saltMult;
        BannerboundAntiquity.LOGGER.info(
            "Loaded {} food-spoilage entries ({} never-spoil, default {} ticks, bland {}, salt x{})",
            ticks.size(), nonPerishable.size(), defTicks, blandFrac, saltMult);
    }

    private static Item resolve(String id, ResourceLocation file) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            BannerboundAntiquity.LOGGER.warn("Unknown item '{}' in food_spoilage {}", id, file);
            return null;
        }
        return BuiltInRegistries.ITEM.get(rl);
    }

    /**
     * Total mean lifetime of {@code item}, in ticks, or {@code -1} if it never spoils. Explicit
     * entries win; otherwise any item carrying vanilla {@link DataComponents#FOOD} uses the default.
     */
    public static int lifeTicks(Item item) {
        if (item == null || NON_PERISHABLE.contains(item)) return -1;
        Integer explicit = TICKS.get(item);
        if (explicit != null) return explicit;
        if (defaultFoodTicks > 0 && item.getDefaultInstance().has(DataComponents.FOOD)) {
            return defaultFoodTicks;
        }
        return -1;
    }

    /** Mean ticks {@code item} stays FRESH before turning bland ({@code -1} if it never spoils). */
    public static int freshTicks(Item item) {
        int total = lifeTicks(item);
        return total <= 0 ? total : Math.max(1, Math.round(total * (1f - blandFraction)));
    }

    /** Mean ticks {@code item} stays BLAND before spoiling ({@code -1} if it never spoils). */
    public static int blandTicks(Item item) {
        int total = lifeTicks(item);
        return total <= 0 ? total : Math.max(1, Math.round(total * blandFraction));
    }

    /** Whether {@code item} spoils at all. */
    public static boolean isPerishable(Item item) {
        return lifeTicks(item) > 0;
    }

    /** How many times longer salted food keeps (its per-roll spoil chance is divided by this). */
    public static float saltLifeMultiplier() {
        return saltLifeMultiplier;
    }
}
