package com.bannerbound.antiquity.recipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Datapack loader for Fletching Station recipes — reads every JSON under
 * {@code data/<namespace>/fletching_recipes/}. Server-side only (registered as a reload listener in
 * {@code AntiquityEvents}); the block entity syncs its matched result to clients itself. Recipes are
 * keyed by their file id so the minigame can resolve the matched recipe across the client round trip.
 */
@ApiStatus.Internal
public class FletchingRecipeManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile List<FletchingRecipe> recipes = List.of();
    private static volatile Map<ResourceLocation, FletchingRecipe> byId = Map.of();

    public FletchingRecipeManager() {
        super(GSON, "fletching_recipes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    /** Parse + store the loaded entries. Public so the client-side jar loader can reuse it on remote
     *  clients, where server datapacks don't reach (see {@code ClientDatapackRecipes}). */
    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        List<FletchingRecipe> loaded = new ArrayList<>();
        Map<ResourceLocation, FletchingRecipe> ids = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            FletchingRecipe.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid fletching recipe {}: {}", entry.getKey(), error))
                .ifPresent(recipe -> {
                    loaded.add(recipe);
                    ids.put(entry.getKey(), recipe);
                });
        }
        recipes = List.copyOf(loaded);
        byId = Map.copyOf(ids);
        BannerboundAntiquity.LOGGER.info("Loaded {} fletching recipe(s).", recipes.size());
    }

    /** Every loaded fletching recipe (for the Fletcher NPC's "what can I craft" scan). */
    public static List<FletchingRecipe> all() {
        return recipes;
    }

    /** The recipe whose ingredient counts EXACTLY equal the pile, or {@code null} if none. */
    @Nullable
    public static FletchingRecipe find(List<ItemStack> contents) {
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (placed.isEmpty()) return null;
        for (FletchingRecipe recipe : recipes) {
            if (recipe.matches(placed)) return recipe;
        }
        return null;
    }

    /** Recipes the pile could still BECOME — every placed item appears in the recipe at no more
     *  than its required count, and at least one ingredient is still missing (an exact match is the
     *  craftable result, not a candidate). Empty pile → no candidates. Sorted by result id so the
     *  renderer's ghost pick is stable across recomputes. Mirrors the crafting stone's. */
    public static List<FletchingRecipe> candidates(List<ItemStack> contents) {
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (placed.isEmpty()) return List.of();
        List<FletchingRecipe> out = new ArrayList<>();
        for (FletchingRecipe recipe : recipes) {
            Map<Item, Integer> required = recipe.requiredCounts();
            if (required.equals(placed)) continue;
            boolean covers = true;
            for (Map.Entry<Item, Integer> e : placed.entrySet()) {
                if (required.getOrDefault(e.getKey(), 0) < e.getValue()) {
                    covers = false;
                    break;
                }
            }
            if (covers) out.add(recipe);
        }
        out.sort(Comparator.comparing(r -> BuiltInRegistries.ITEM.getKey(r.result().getItem())));
        return out;
    }

    /** The file id of {@code recipe} (for sending across the minigame round trip), or {@code null}. */
    @Nullable
    public static ResourceLocation idOf(FletchingRecipe recipe) {
        for (Map.Entry<ResourceLocation, FletchingRecipe> e : byId.entrySet()) {
            if (e.getValue() == recipe) return e.getKey();
        }
        return null;
    }

    /** Looks up a recipe by its file id (server resolves the result at minigame completion). */
    @Nullable
    public static FletchingRecipe byId(ResourceLocation id) {
        return byId.get(id);
    }
}
