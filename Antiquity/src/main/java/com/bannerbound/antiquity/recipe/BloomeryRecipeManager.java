package com.bannerbound.antiquity.recipe;

import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.item.ItemStack;

/**
 * Datapack loader for Bloomery smelting recipes — reads every JSON under
 * {@code data/<namespace>/bloomery_recipes/}. Registered as a server-data reload listener
 * (see {@code AntiquityEvents}); the loaded set is server-side only.
 */
@ApiStatus.Internal
public class BloomeryRecipeManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile List<BloomeryRecipe> recipes = List.of();

    public BloomeryRecipeManager() {
        super(GSON, "bloomery_recipes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    /** Parse + store the loaded entries. Public so the client-side jar loader can reuse it on remote
     *  clients, where server datapacks don't reach (see {@code ClientDatapackRecipes}). */
    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        List<BloomeryRecipe> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            BloomeryRecipe.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid bloomery recipe {}: {}", entry.getKey(), error))
                .ifPresent(loaded::add);
        }
        recipes = List.copyOf(loaded);
        BannerboundAntiquity.LOGGER.info("Loaded {} bloomery recipe(s).", recipes.size());
    }

    /** Every loaded bloomery recipe (for JEI and tutorial displays). */
    public static List<BloomeryRecipe> all() {
        return recipes;
    }

    /** The recipe accepting {@code stack} as its ingredient, or {@code null} if none applies. */
    @Nullable
    public static BloomeryRecipe find(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        for (BloomeryRecipe recipe : recipes) {
            if (recipe.matches(stack)) {
                return recipe;
            }
        }
        return null;
    }
}
