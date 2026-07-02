package com.bannerbound.core.api.citystate.data;

import java.util.ArrayList;
import java.util.Collections;
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
 * Loads the authored city-state <b>wants ladder</b> from {@code data/<ns>/citystate_wants/*.json} —
 * the third (lowest-rank) demand-candidate pool after the computed tech-gap and biome-complement
 * pools. A want is live once the city-state's adopted tech reaches it. Entries merge across files.
 * <pre>{@code
 * { "entries": [
 *   { "item": "minecraft:bread" },
 *   { "item": "minecraft:leather", "requires_tech": "bannerboundantiquity:tanning" }
 * ]}
 * }</pre>
 * Design: repo-root {@code CITY_STATES_PLAN.md} Phase 3.
 */
public class CityStateWantsLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "citystate_wants";
    private static final Gson GSON = new Gson();
    private static volatile List<WantDef> WANTS = List.of();

    /** One authored want: live iff {@code requiresTech} is null or adopted by the city-state. */
    public record WantDef(String item, String requiresTech) {}

    public CityStateWantsLoader() {
        super(GSON, FOLDER);
    }

    public static List<WantDef> wants() {
        return WANTS;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                         ProfilerFiller profiler) {
        List<WantDef> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                JsonArray entries = GsonHelper.getAsJsonArray(obj, "entries");
                for (JsonElement e : entries) {
                    JsonObject w = e.getAsJsonObject();
                    String item = GsonHelper.getAsString(w, "item", "");
                    if (item.isEmpty() || ResourceLocation.tryParse(item) == null) {
                        BannerboundCore.LOGGER.warn("citystate_wants entry with bad item id '{}' in {}",
                            item, entry.getKey());
                        continue;
                    }
                    String tech = GsonHelper.getAsString(w, "requires_tech", "");
                    out.add(new WantDef(item, tech.isEmpty() ? null : tech));
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse citystate_wants {}", entry.getKey(), ex);
            }
        }
        WANTS = Collections.unmodifiableList(out);
        BannerboundCore.LOGGER.info("Loaded city-state wants ladder: {} entries", out.size());
    }
}
