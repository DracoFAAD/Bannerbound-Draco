package com.bannerbound.core.api.settlement.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.CultureStyle;
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
import net.minecraft.world.level.block.Block;

/**
 * Loads culture styles from {@code data/<ns>/culture_styles/*.json}, keyed by file stem.
 *
 * <pre>{@code
 * {
 *   "id": "desert",
 *   "name": "Desert",
 *   "image": "bannerbound:textures/gui/culture/desert.png",
 *   "overrides":      { "minecraft:sandstone": 0.6, "minecraft:oak_log": -0.5 },
 *   "food_overrides": { "minecraft:cactus": 0.8, "minecraft:apple": 0.2 }
 * }
 * }</pre>
 *
 * <p>{@code overrides} are per-block appeal values that replace the base table
 * (see {@link com.bannerbound.core.api.settlement.AppealResolver}); unknown block ids are warned
 * and skipped. {@code food_overrides} are per-item food values that replace the base table from
 * {@link FoodValueLoader} (see {@code FoodValueLoader.effective}) — same semantics for items.
 * Both blocks are optional; omit either to leave the corresponding base table untouched. The
 * {@code "id"} defaults to the file stem, {@code "name"} to the id, and {@code "image"} to
 * {@code bannerbound:textures/gui/culture/<id>.png} when omitted.
 */
public class CultureStyleLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "culture_styles";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, CultureStyle> STYLES = Collections.emptyMap();

    public CultureStyleLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<String, CultureStyle> map = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation key = entry.getKey();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                String id = GsonHelper.getAsString(obj, "id", key.getPath());
                // "name" is now a literal display string (rendered via Component.literal on the
                // settle screen), NOT a lang key. Fall back to the id when absent so a missing
                // name shows something readable rather than a dead translation key.
                String nameKey = GsonHelper.getAsString(obj, "name", id);
                // "image" is the ResourceLocation of the preview thumbnail shown in the founding
                // picker. Defaults to bannerbound:textures/gui/culture/<id>.png so a style that
                // follows the naming convention needs no explicit entry.
                String image = GsonHelper.getAsString(obj, "image",
                    "bannerbound:textures/gui/culture/" + id + ".png");

                Map<Block, Float> overrides = new HashMap<>();
                if (obj.has("overrides")) {
                    JsonObject ov = obj.getAsJsonObject("overrides");
                    for (Map.Entry<String, JsonElement> o : ov.entrySet()) {
                        ResourceLocation rl = ResourceLocation.tryParse(o.getKey());
                        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
                            BannerboundCore.LOGGER.warn("Unknown block '{}' in culture style {}",
                                o.getKey(), key);
                            continue;
                        }
                        overrides.put(BuiltInRegistries.BLOCK.get(rl), o.getValue().getAsFloat());
                    }
                }

                Map<Item, Float> foodOverrides = new HashMap<>();
                if (obj.has("food_overrides")) {
                    JsonObject fo = obj.getAsJsonObject("food_overrides");
                    for (Map.Entry<String, JsonElement> o : fo.entrySet()) {
                        ResourceLocation rl = ResourceLocation.tryParse(o.getKey());
                        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
                            BannerboundCore.LOGGER.warn("Unknown item '{}' in culture style {} food_overrides",
                                o.getKey(), key);
                            continue;
                        }
                        // Clamp at 0 — no negative food values (matches the base FoodValueLoader rule).
                        float v = Math.max(0f, o.getValue().getAsFloat());
                        foodOverrides.put(BuiltInRegistries.ITEM.get(rl), v);
                    }
                }

                map.put(id, new CultureStyle(id, nameKey, image, overrides, foodOverrides));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse culture style {}", key, ex);
            }
        }
        STYLES = map;
        BannerboundCore.LOGGER.info("Loaded {} culture styles", map.size());
    }

    public static CultureStyle get(String id) {
        return STYLES.get(id);
    }

    public static Map<String, CultureStyle> all() {
        return STYLES;
    }

    /** All style ids, sorted — used to populate the founding picker and the default fallback. */
    public static List<String> ids() {
        List<String> ids = new ArrayList<>(STYLES.keySet());
        Collections.sort(ids);
        return ids;
    }
}
