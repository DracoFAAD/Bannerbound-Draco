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
import net.minecraft.world.item.Item;

/**
 * Datapack loader for Drying Rack recipes — reads every JSON under
 * {@code data/<namespace>/drying_recipes/}. Server-side only (registered as a reload listener in
 * {@code AntiquityEvents}); results are synced to clients on the block entity itself, and re-read
 * jar-side on remote clients by {@code ClientDatapackRecipes}.
 */
@ApiStatus.Internal
public class DryingRackRecipeManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile List<DryingRackRecipe> recipes = List.of();

    public DryingRackRecipeManager() {
        super(GSON, "drying_recipes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    /** Parse + store the loaded entries. Public so the client jar loader can reuse it on remote
     *  clients, where server datapacks don't reach (see {@code ClientDatapackRecipes}). */
    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        List<DryingRackRecipe> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            DryingRackRecipe.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid drying recipe {}: {}", entry.getKey(), error))
                .ifPresent(loaded::add);
        }
        recipes = List.copyOf(loaded);
        BannerboundAntiquity.LOGGER.info("Loaded {} drying recipe(s).", recipes.size());
    }

    /** Every loaded drying recipe (for JEI / ghost previews). */
    public static List<DryingRackRecipe> all() {
        return recipes;
    }

    /** The recipe that dries {@code item}, or {@code null} if it can't be dried. */
    @Nullable
    public static DryingRackRecipe find(Item item) {
        for (DryingRackRecipe recipe : recipes) {
            if (recipe.input() == item) return recipe;
        }
        return null;
    }
}
