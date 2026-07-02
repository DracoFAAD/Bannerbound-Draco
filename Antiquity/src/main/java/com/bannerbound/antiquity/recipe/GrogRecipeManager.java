package com.bannerbound.antiquity.recipe;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;

/**
 * Datapack loader for grog recipes — reads every JSON under {@code data/<namespace>/grog_recipes/}.
 * Server-side reload listener (registered in {@code AntiquityEvents}); re-read jar-side on remote
 * clients by {@code ClientDatapackRecipes} so the trough renderer can colour the liquid there too.
 * The map key is the recipe id (the JSON's path) — the Fermentation Trough stores that id while it
 * ferments and resolves the tint/identity back through {@link #byId(String)}.
 */
@ApiStatus.Internal
public class GrogRecipeManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile Map<ResourceLocation, GrogRecipe> recipes = Map.of();

    public GrogRecipeManager() {
        super(GSON, "grog_recipes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    /** Parse + store the loaded entries (reused by the client jar loader on remote clients). */
    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        Map<ResourceLocation, GrogRecipe> loaded = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            GrogRecipe.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid grog recipe {}: {}", entry.getKey(), error))
                .ifPresent(recipe -> loaded.put(entry.getKey(), recipe));
        }
        recipes = Map.copyOf(loaded);
        BannerboundAntiquity.LOGGER.info("Loaded {} grog recipe(s).", recipes.size());
    }

    /** The recipe stored under {@code id} (the trough's saved ferment id), or {@code null}. */
    @Nullable
    public static GrogRecipe byId(String id) {
        if (id == null || id.isEmpty()) return null;
        return recipes.get(ResourceLocation.parse(id));
    }

    /** Every item that charges a trough (some grog recipe's input) — the Brewer's pestle-set filter
     *  ("which mortar outputs are brewable") and its charge-item storage scan both key off this. */
    public static java.util.Set<Item> inputs() {
        java.util.Set<Item> out = new java.util.HashSet<>();
        for (GrogRecipe r : recipes.values()) out.add(r.input());
        return out;
    }

    /** The first recipe whose input is {@code item} (for charging a trough), or {@code null}. */
    @Nullable
    public static Map.Entry<ResourceLocation, GrogRecipe> findForInput(Item item) {
        for (Map.Entry<ResourceLocation, GrogRecipe> e : recipes.entrySet()) {
            if (e.getValue().input() == item) return e;
        }
        return null;
    }
}
