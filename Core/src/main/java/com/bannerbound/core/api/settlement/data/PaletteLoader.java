package com.bannerbound.core.api.settlement.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Palette;
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
import net.minecraft.world.level.block.Block;

/**
 * Loads culture <b>palettes</b> from {@code data/<ns>/palettes/*.json} and doubles as the palette
 * registry. Each file is one palette:
 *
 * <pre>{@code
 * { "id": "brown_haven", "name": "Brown Haven",
 *   "blocks": { "minecraft:dirt": 0.15, "minecraft:coarse_dirt": 0.10, "minecraft:oak_log": 0.20 } }
 * }</pre>
 *
 * <p>Block ids resolve to {@link Block} instances at load time; bonuses are clamped to
 * {@code [-1, 1]}; the {@code blocks} map preserves authoring order (so the UI icon row is stable).
 * Mirror of {@link BlockAppealLoader} / {@link CultureStyleLoader}. A palette is <i>available</i> to
 * a settlement when a completed research lists its unlock flag — see {@link #availableFor}.
 */
public class PaletteLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "palettes";
    /** Flag prefix a culture/research node sets via {@code "unlocks": {"palette": ["id"]}}. */
    public static final String UNLOCK_FLAG_PREFIX = "unlock.palette.";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, Palette> BY_ID = Collections.emptyMap();

    public PaletteLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<String, Palette> map = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation key = entry.getKey();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                // Default the id to the file stem so a missing "id" still works.
                String stem = key.getPath();
                int slash = stem.lastIndexOf('/');
                if (slash >= 0) stem = stem.substring(slash + 1);
                String id = GsonHelper.getAsString(obj, "id", stem);
                String name = GsonHelper.getAsString(obj, "name", id);

                Map<Block, Float> bonuses = new LinkedHashMap<>();
                JsonObject blocks = GsonHelper.getAsJsonObject(obj, "blocks");
                for (Map.Entry<String, JsonElement> b : blocks.entrySet()) {
                    ResourceLocation blockRl = ResourceLocation.tryParse(b.getKey());
                    if (blockRl == null || !BuiltInRegistries.BLOCK.containsKey(blockRl)) {
                        BannerboundCore.LOGGER.warn("Unknown block '{}' in palette {}", b.getKey(), key);
                        continue;
                    }
                    float bonus = Math.max(-1f, Math.min(1f, b.getValue().getAsFloat()));
                    bonuses.put(BuiltInRegistries.BLOCK.get(blockRl), bonus);
                }
                map.put(id, new Palette(id, name, bonuses));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse palette {}", key, ex);
            }
        }
        BY_ID = Collections.unmodifiableMap(map);
        BannerboundCore.LOGGER.info("Loaded {} palette definitions", map.size());
    }

    public static Palette get(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    public static Map<String, Palette> all() {
        return BY_ID;
    }

    /** Ordered list of palette ids currently available to {@code settlement} — i.e. those a
     *  completed science/culture research has unlocked. Drives the "Available" list in the UI. */
    public static List<String> availableFor(Settlement settlement) {
        List<String> out = new ArrayList<>();
        if (settlement == null) return out;
        for (String id : BY_ID.keySet()) {
            if (ResearchManager.hasFlagEitherTree(settlement, UNLOCK_FLAG_PREFIX + id)) {
                out.add(id);
            }
        }
        return out;
    }
}
