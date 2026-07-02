package com.bannerbound.antiquity.tannery;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;

/**
 * Loads the per-species preferred-weapon table from {@code data/<ns>/hide_preferences/*.json}.
 * Killing an animal with its preferred weapon category yields a GREAT hide (RDR2-style). Files merge
 * (later wins per species):
 *
 * <pre>{@code
 * { "animals": { "minecraft:cow": "spear", "minecraft:sheep": "blunt", ... } }
 * }</pre>
 *
 * Values are one of blade / spear / arrow / blunt ({@link WeaponCategory}). An unlisted species has
 * no preference (a valid weapon then yields STANDARD, never GREAT).
 */
public class HidePreferenceLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "hide_preferences";
    private static final Gson GSON = new Gson();
    private static volatile Map<EntityType<?>, WeaponCategory> PREFS = Collections.emptyMap();

    public HidePreferenceLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<EntityType<?>, WeaponCategory> map = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation key = entry.getKey();
            try {
                JsonObject animals = GsonHelper.getAsJsonObject(entry.getValue().getAsJsonObject(), "animals");
                for (Map.Entry<String, JsonElement> a : animals.entrySet()) {
                    ResourceLocation typeRl = ResourceLocation.tryParse(a.getKey());
                    if (typeRl == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(typeRl)) {
                        BannerboundAntiquity.LOGGER.warn("Unknown entity '{}' in hide_preferences {}",
                            a.getKey(), key);
                        continue;
                    }
                    WeaponCategory cat = parseCategory(a.getValue().getAsString());
                    if (cat == null) {
                        BannerboundAntiquity.LOGGER.warn("Unknown weapon category '{}' in hide_preferences {}",
                            a.getValue().getAsString(), key);
                        continue;
                    }
                    map.put(BuiltInRegistries.ENTITY_TYPE.get(typeRl), cat);
                }
            } catch (Exception ex) {
                BannerboundAntiquity.LOGGER.error("Failed to parse hide_preferences {}", key, ex);
            }
        }
        PREFS = map;
        BannerboundAntiquity.LOGGER.info("Loaded {} hide weapon-preferences", map.size());
    }

    @Nullable
    private static WeaponCategory parseCategory(String s) {
        try {
            return WeaponCategory.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The preferred weapon category for {@code type}, or {@code null} if unlisted. */
    @Nullable
    public static WeaponCategory preferred(EntityType<?> type) {
        return PREFS.get(type);
    }
}
