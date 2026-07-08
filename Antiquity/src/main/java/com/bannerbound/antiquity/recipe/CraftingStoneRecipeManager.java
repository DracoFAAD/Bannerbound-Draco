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
import com.bannerbound.antiquity.event.AntiquityEvents;

/**
 * Datapack loader for Crafting Stone recipes - reads every JSON under
 * {@code data/<namespace>/crafting_stone_recipes/}. Registered as a server reload listener in
 * {@code AntiquityEvents}; results are synced to clients on the block entity itself, and
 * {@code applyEntries} is public so the client jar loader ({@code ClientDatapackRecipes}) can
 * re-read the same JSONs on remote clients, where server datapacks don't reach. {@code find}
 * returns the recipe whose ingredient counts EXACTLY equal the pile; {@code candidates} returns
 * recipes the pile could still BECOME - every placed item within its required count and at least
 * one ingredient still missing (an exact match is the craftable result, not a candidate). An empty
 * pile yields no candidates (no ghost spam on an empty stone), and candidates are sorted by result
 * id so the renderer's ghost pick is stable across recomputes.
 *
 * <p>Dry fiber (the desert/mesa dead-bush harvest) is a 1:1 stand-in for plant fiber: every placed
 * pile is counted through {@code canonical()}, which folds DRY_FIBER into PLANT_FIBER before
 * matching, so any recipe listing plant fiber accepts either fiber or a mix without touching the
 * recipe JSONs. The flip side: a crafting-stone recipe can never demand dry fiber SPECIFICALLY --
 * an ingredient entry of dry_fiber is unmatchable because piles never count it under its own id.
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

    public static List<CraftingStoneRecipe> all() {
        return recipes;
    }

    private static Item canonical(Item item) {
        return item == BannerboundAntiquity.DRY_FIBER.get()
            ? BannerboundAntiquity.PLANT_FIBER.get() : item;
    }

    private static Map<Item, Integer> countPlaced(List<ItemStack> contents) {
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(canonical(s.getItem()), s.getCount(), Integer::sum);
        }
        return placed;
    }

    @Nullable
    public static CraftingStoneRecipe find(List<ItemStack> contents) {
        Map<Item, Integer> placed = countPlaced(contents);
        if (placed.isEmpty()) return null;
        for (CraftingStoneRecipe recipe : recipes) {
            if (recipe.matches(placed)) return recipe;
        }
        return null;
    }

    public static List<CraftingStoneRecipe> findMatching(List<ItemStack> contents) {
        Map<Item, Integer> placed = countPlaced(contents);

        if (placed.isEmpty()) return null;

        List<CraftingStoneRecipe> matched = new ArrayList<>();
        for (CraftingStoneRecipe recipe : recipes) {
            if (recipe.matches(placed)) matched.add(recipe);
        }

        return matched;
    }

    public static List<CraftingStoneRecipe> candidates(List<ItemStack> contents) {
        Map<Item, Integer> placed = countPlaced(contents);
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
