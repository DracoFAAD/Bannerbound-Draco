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
 * Datapack loader for Crafting Stone recipes — reads every JSON under
 * {@code data/<namespace>/crafting_stone_recipes/}. Server-side only (registered as a reload
 * listener in {@code AntiquityEvents}); results are synced to clients on the block entity itself.
 */
@ApiStatus.Internal
public class CraftingStoneRecipeManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile List<CraftingStoneRecipe> recipes = List.of();

    public CraftingStoneRecipeManager() {
        super(GSON, "crafting_stone_recipes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    /** Parse + store the loaded entries. Public so the client-side jar loader can reuse it on remote
     *  clients, where server datapacks don't reach (see {@code ClientDatapackRecipes}). */
    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        List<CraftingStoneRecipe> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            CraftingStoneRecipe.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid crafting-stone recipe {}: {}", entry.getKey(), error))
                .ifPresent(loaded::add);
        }
        recipes = List.copyOf(loaded);
        BannerboundAntiquity.LOGGER.info("Loaded {} crafting-stone recipe(s).", recipes.size());
    }

    /** Every loaded crafting-stone recipe (for the crafter NPC's "what can I craft" scan). */
    public static List<CraftingStoneRecipe> all() {
        return recipes;
    }

    /** The recipe whose ingredient counts EXACTLY equal the pile, or {@code null} if none. */
    @Nullable
    public static CraftingStoneRecipe find(List<ItemStack> contents) {
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (placed.isEmpty()) return null;
        for (CraftingStoneRecipe recipe : recipes) {
            if (recipe.matches(placed)) return recipe;
        }
        return null;
    }

    /** Recipes the pile could still BECOME — every placed item appears in the recipe at no more
     *  than its required count, and at least one ingredient is still missing (an exact match is the
     *  craftable result, not a candidate). Empty pile → no candidates (no ghost spam on an empty
     *  stone). Sorted by result id so the renderer's ghost pick is stable across recomputes. */
    public static List<CraftingStoneRecipe> candidates(List<ItemStack> contents) {
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (placed.isEmpty()) return List.of();
        List<CraftingStoneRecipe> out = new ArrayList<>();
        for (CraftingStoneRecipe recipe : recipes) {
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
}
