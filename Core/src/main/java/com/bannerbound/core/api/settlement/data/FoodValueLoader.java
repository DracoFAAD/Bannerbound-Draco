package com.bannerbound.core.api.settlement.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.CultureStyle;
import com.bannerbound.core.api.settlement.Settlement;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;

/**
 * Loads the base food-value table from {@code data/<ns>/food_values/*.json}. Each file's
 * {@code "items"} object is merged into one map (later files win per item id), so expansion
 * datapacks can extend or override the vanilla starter values.
 *
 * <pre>{@code
 * { "items": { "minecraft:bread": 1.0, "minecraft:cooked_beef": 1.5, ... } }
 * }</pre>
 *
 * Values are clamped at {@code 0} on the low side (no negative food). Used by both the
 * town-hall food-deposit interaction (server-side authoritative math, through
 * {@link #effective(Item, Settlement)} so culture-style overrides apply) and the green
 * "Food value" tooltip line (synced to clients via {@code FoodValueSyncPayload}, currently
 * base values only). An item with no entry has food value {@code 0} and is silently ignored
 * by both paths.
 *
 * <p>Culture-style overrides: a settlement's culture styles (Desert, Forest, …) can override
 * the base food value of any item — Desert towns value cactus higher than baseline, Forest
 * towns value berries higher. See {@link CultureStyle#foodOverrides()} and
 * {@link #effective(Item, Settlement)}. Tooltip-side awareness is a follow-up (would need the
 * style overrides synced to clients alongside ids/names).
 */
public class FoodValueLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "food_values";
    private static final Gson GSON = new Gson();
    private static volatile Map<Item, Float> BASE = Collections.emptyMap();

    public FoodValueLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<Item, Float> map = new HashMap<>();
        // Phase 1: load any universal fallback values from food_values/*.json. Each entry is a bare
        // number — the item's food VALUE on the settlement larder scale.
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation key = entry.getKey();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                JsonObject items = GsonHelper.getAsJsonObject(obj, "items");
                for (Map.Entry<String, JsonElement> i : items.entrySet()) {
                    ResourceLocation itemRl = ResourceLocation.tryParse(i.getKey());
                    if (itemRl == null || !BuiltInRegistries.ITEM.containsKey(itemRl)) {
                        BannerboundCore.LOGGER.warn("Unknown item '{}' in food_values {}",
                            i.getKey(), key);
                        continue;
                    }
                    float v = Math.max(0f, i.getValue().getAsFloat());
                    map.put(BuiltInRegistries.ITEM.get(itemRl), v);
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse food_values {}", key, ex);
            }
        }
        // Phase 2: union with the max value across every culture style's food_overrides. This is
        // what the pre-check (TownHallFoodDepositEvents) and tooltip-sync (FoodValueSyncPayload)
        // read — "is this item food in at least one culture?". Per-settlement authoritative math
        // still goes through {@link #effective(Item, Settlement)} which walks the settlement's
        // own styles in order. Load order is guaranteed: CultureStyleLoader registers before
        // FoodValueLoader (see ResearchEvents.onAddReloadListeners), so its STYLES table is
        // already populated by the time we get here.
        int unionAdds = 0;
        for (CultureStyle style : CultureStyleLoader.all().values()) {
            for (Map.Entry<Item, Float> e : style.foodOverrides().entrySet()) {
                Float prev = map.get(e.getKey());
                if (prev == null || e.getValue() > prev) {
                    map.put(e.getKey(), e.getValue());
                    if (prev == null) unionAdds++;
                }
            }
        }
        BASE = map;
        BannerboundCore.LOGGER.info("Loaded {} food value definitions ({} unioned in from {} culture styles)",
            map.size(), unionAdds, CultureStyleLoader.all().size());
    }

    /** Base food value of {@code item}; {@code 0} when undefined. */
    public static float base(Item item) {
        return BASE.getOrDefault(item, 0f);
    }

    /**
     * Effective food value of {@code item} for the given settlement: starts from the base table
     * and then walks the settlement's culture styles in order, with each style that lists the
     * item <b>overriding</b> the value outright (last style wins — mirrors
     * {@link com.bannerbound.core.api.settlement.AppealResolver#appealOf} for blocks). Clamped
     * at {@code 0} on the low side. A {@code null} settlement falls back to {@link #base(Item)}.
     *
     * <p>Use this from any server-side path that resolves a food deposit / consumption value
     * against a specific settlement — {@link #base(Item)} stays for context-free callers
     * (client tooltip cache, datapack-validation, etc.).
     */
    public static float effective(Item item, Settlement settlement) {
        float v = BASE.getOrDefault(item, 0f);
        if (settlement != null) {
            for (String styleId : settlement.cultureStyles()) {
                CultureStyle style = CultureStyleLoader.get(styleId);
                if (style != null && style.hasFoodOverride(item)) {
                    v = style.foodOverride(item);
                }
            }
        }
        return Math.max(0f, v);
    }

    public static Map<Item, Float> all() {
        return BASE;
    }
}
