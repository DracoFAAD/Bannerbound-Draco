package com.bannerbound.core.api.settlement.data;

import java.util.EnumMap;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Era;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads the era → starting-year timeline used by the world-year HUD. Reads JSON from
 * {@code data/<namespace>/era_times/*.json}; every file is parsed as a map of era key
 * (lowercase {@link Era#key}) to a {@code { "start_year": <int> }} object. Files from
 * multiple namespaces are unioned; later definitions for the same era key win, which lets
 * an expansion datapack override the base timeline without replacing the whole file.
 * <p>
 * Years are signed ints — negative = BC, positive = AD. {@code ancient} typically starts
 * deep in BC territory (e.g. -100000); {@code future} sits a century or two past the present.
 * Eras missing from the JSON fall back to {@link #DEFAULT_START_YEARS}, so the loader never
 * leaves the HUD in a broken state.
 */
public final class EraTimelineLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "era_times";
    private static final Gson GSON = new Gson();

    /** Conservative fallback so the HUD has something to show even if no JSON is loaded. */
    private static final Map<Era, Integer> DEFAULT_START_YEARS = buildDefaults();
    private static volatile Map<Era, Integer> START_YEARS = DEFAULT_START_YEARS;

    public EraTimelineLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<Era, Integer> merged = new EnumMap<>(DEFAULT_START_YEARS);
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    Era era = Era.fromName(e.getKey());
                    if (era == null) {
                        BannerboundCore.LOGGER.warn("Unknown era '{}' in era_times {}", e.getKey(), entry.getKey());
                        continue;
                    }
                    JsonObject body = e.getValue().getAsJsonObject();
                    int year = GsonHelper.getAsInt(body, "start_year");
                    merged.put(era, year);
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse era_times {}", entry.getKey(), ex);
            }
        }
        START_YEARS = merged;
        BannerboundCore.LOGGER.info("Loaded era timeline: {} eras", merged.size());
    }

    /** Start year for {@code era}. Always returns a value (defaults if unconfigured). */
    public static int getStartYear(Era era) {
        Integer y = START_YEARS.get(era);
        return y != null ? y : DEFAULT_START_YEARS.getOrDefault(era, 0);
    }

    /** Returns the full era → start_year map for sync. */
    public static Map<Era, Integer> getAll() {
        return START_YEARS;
    }

    private static Map<Era, Integer> buildDefaults() {
        Map<Era, Integer> m = new EnumMap<>(Era.class);
        m.put(Era.ANCIENT, -100000);
        m.put(Era.CLASSICAL, -800);
        m.put(Era.MEDIEVAL, 450);
        m.put(Era.RENAISSANCE, 1450);
        m.put(Era.INDUSTRIAL, 1760);
        m.put(Era.DIESEL, 1900);
        m.put(Era.ATOMIC, 1945);
        m.put(Era.MODERN, 1990);
        m.put(Era.FUTURE, 2100);
        return m;
    }
}
