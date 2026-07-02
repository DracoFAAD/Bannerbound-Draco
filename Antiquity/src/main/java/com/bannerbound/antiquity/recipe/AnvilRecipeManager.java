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
 * Datapack loader for Stone Anvil recipes — reads every JSON under {@code data/<namespace>/anvil_recipes/}.
 * A clone of the Fletching/Crafting-Stone pile-recipe loader: server-side reload listener (registered
 * in {@code AntiquityEvents}); recipes keyed by file id so the cold-hammer minigame can resolve the
 * matched recipe across the client round trip. Also jar-loaded on remote clients via
 * {@code ClientDatapackRecipes}.
 */
@ApiStatus.Internal
public class AnvilRecipeManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile List<AnvilRecipe> recipes = List.of();
    private static volatile Map<ResourceLocation, AnvilRecipe> byId = Map.of();

    public AnvilRecipeManager() {
        super(GSON, "anvil_recipes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        List<AnvilRecipe> loaded = new ArrayList<>();
        Map<ResourceLocation, AnvilRecipe> ids = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            AnvilRecipe.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid anvil recipe {}: {}", entry.getKey(), error))
                .ifPresent(recipe -> {
                    loaded.add(recipe);
                    ids.put(entry.getKey(), recipe);
                });
        }
        recipes = List.copyOf(loaded);
        byId = Map.copyOf(ids);
        BannerboundAntiquity.LOGGER.info("Loaded {} anvil recipe(s).", recipes.size());
    }

    public static List<AnvilRecipe> all() {
        return recipes;
    }

    @Nullable
    public static AnvilRecipe find(List<ItemStack> contents) {
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (placed.isEmpty()) return null;
        for (AnvilRecipe recipe : recipes) {
            if (recipe.matches(placed)) return recipe;
        }
        return null;
    }

    public static List<AnvilRecipe> candidates(List<ItemStack> contents) {
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        if (placed.isEmpty()) return List.of();
        List<AnvilRecipe> out = new ArrayList<>();
        for (AnvilRecipe recipe : recipes) {
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
    public static ResourceLocation idOf(AnvilRecipe recipe) {
        for (Map.Entry<ResourceLocation, AnvilRecipe> e : byId.entrySet()) {
            if (e.getValue() == recipe) return e.getKey();
        }
        return null;
    }

    @Nullable
    public static AnvilRecipe byId(ResourceLocation id) {
        return byId.get(id);
    }
}
