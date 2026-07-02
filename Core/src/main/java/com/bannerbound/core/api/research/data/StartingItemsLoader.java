package com.bannerbound.core.api.research.data;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
import net.minecraft.world.item.Item;

/**
 * Loads the global "starting items" set from data/&lt;namespace&gt;/starting_items/*.json. Every
 * bannerbound knows these items by default, regardless of era. All other items are obfuscated
 * until unlocked by research. Datapacks can drop additional files into the folder; their item
 * lists are union'd into one flat set.
 */
public class StartingItemsLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "starting_items";
    private static final Gson GSON = new Gson();
    private static volatile Set<String> STARTING = Set.of();

    public StartingItemsLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Set<String> items = new HashSet<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                JsonArray arr = GsonHelper.getAsJsonArray(obj, "items");
                for (JsonElement el : arr) {
                    items.add(el.getAsString());
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse starting_items {}", entry.getKey(), ex);
            }
        }
        STARTING = items;
        BannerboundCore.LOGGER.info("Loaded {} starting items", items.size());
    }

    public static Set<String> getAll() {
        return STARTING;
    }

    public static boolean contains(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id != null && STARTING.contains(id.toString());
    }
}
