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

/** Datapack loader for Pottery Slab recipes. */
@ApiStatus.Internal
public class PotteryRecipeManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile List<PotteryRecipe> recipes = List.of();
    private static volatile Map<ResourceLocation, PotteryRecipe> byId = Map.of();

    public PotteryRecipeManager() {
        super(GSON, "pottery_recipes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    /** Parse + store the loaded entries. Public so the client-side jar loader can reuse it on remote
     *  clients, where server datapacks don't reach (see {@code ClientDatapackRecipes}). */
    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        List<PotteryRecipe> loaded = new ArrayList<>();
        Map<ResourceLocation, PotteryRecipe> ids = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            PotteryRecipe.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid pottery recipe {}: {}", entry.getKey(), error))
                .ifPresent(recipe -> {
                    loaded.add(recipe);
                    ids.put(entry.getKey(), recipe);
                });
        }
        loaded.sort(Comparator.comparing(r -> BuiltInRegistries.ITEM.getKey(r.result().getItem())));
        recipes = List.copyOf(loaded);
        byId = Map.copyOf(ids);
        BannerboundAntiquity.LOGGER.info("Loaded {} pottery recipe(s).", recipes.size());
    }

    public static List<PotteryRecipe> all() {
        return recipes;
    }

    /** Every exact recipe matching the pile, sorted by result id for stable recipe selection. */
    public static List<PotteryRecipe> exactMatches(List<ItemStack> contents) {
        Map<Item, Integer> placed = placedCounts(contents);
        if (placed.isEmpty()) return List.of();
        List<PotteryRecipe> out = new ArrayList<>();
        for (PotteryRecipe recipe : recipes) {
            if (recipe.matches(placed)) {
                out.add(recipe);
            }
        }
        out.sort(Comparator.comparing(r -> BuiltInRegistries.ITEM.getKey(r.result().getItem())));
        return out;
    }

    @Nullable
    public static PotteryRecipe find(List<ItemStack> contents) {
        List<PotteryRecipe> matches = exactMatches(contents);
        return matches.isEmpty() ? null : matches.get(0);
    }

    /** Recipes the pile could still become. Exact matches are handled as selectable outputs. */
    public static List<PotteryRecipe> candidates(List<ItemStack> contents) {
        Map<Item, Integer> placed = placedCounts(contents);
        if (placed.isEmpty()) return List.of();
        List<PotteryRecipe> out = new ArrayList<>();
        for (PotteryRecipe recipe : recipes) {
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

    @Nullable
    public static ResourceLocation idOf(PotteryRecipe recipe) {
        for (Map.Entry<ResourceLocation, PotteryRecipe> e : byId.entrySet()) {
            if (e.getValue() == recipe) return e.getKey();
        }
        return null;
    }

    @Nullable
    public static PotteryRecipe byId(ResourceLocation id) {
        return byId.get(id);
    }

    private static Map<Item, Integer> placedCounts(List<ItemStack> contents) {
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return placed;
    }
}
