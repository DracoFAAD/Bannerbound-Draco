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
 * Datapack loader for Mortar and Pestle recipes — reads every JSON under
 * {@code data/<namespace>/mortar_recipes/}. Registered as a reload listener on the server
 * data manager (see {@code AntiquityEvents}); the loaded set is server-side only — recipe
 * checks happen during block interaction, which is server-authoritative.
 */
@ApiStatus.Internal
public class MortarRecipeManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile List<MortarRecipe> recipes = List.of();

    public MortarRecipeManager() {
        super(GSON, "mortar_recipes");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    /** Parse + store the loaded entries. Public so the client-side jar loader can reuse it on remote
     *  clients, where server datapacks don't reach (see {@code ClientDatapackRecipes}). */
    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        List<MortarRecipe> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            MortarRecipe.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid mortar recipe {}: {}", entry.getKey(), error))
                .ifPresent(loaded::add);
        }
        recipes = List.copyOf(loaded);
        BannerboundAntiquity.LOGGER.info("Loaded {} mortar recipe(s).", recipes.size());
    }

    /** Every loaded mortar recipe (for JEI and tutorial displays). */
    public static List<MortarRecipe> all() {
        return recipes;
    }

    /** True if any recipe accepts {@code stack} as its ingredient (used to gate insertion). */
    public static boolean hasRecipeFor(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (MortarRecipe recipe : recipes) {
            if (recipe.matchesIngredient(stack)) return true;
        }
        return false;
    }

    /** True if grinding {@code stack} can produce an ITEM (as opposed to only a liquid). Item-output
     *  recipes (bricks, poison pastes) batch — a whole stack grinds in one session; liquid-output
     *  recipes (ink, dyes) are a single bowl and stay capped at one ingredient. Independent of the
     *  current liquid so the insert cap is stable. */
    public static boolean isBatchable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (MortarRecipe recipe : recipes) {
            if (recipe.matchesIngredient(stack) && !recipe.resultItem().isEmpty()) return true;
        }
        return false;
    }

    /** The recipe matching this ingredient + base liquid, or {@code null} if none applies. An empty
     *  {@code baseLiquid} ("") matches a DRY recipe (one whose {@code base_liquid} is also "") — e.g.
     *  grinding a plant into poison paste needs no water. */
    @Nullable
    public static MortarRecipe find(ItemStack ingredient, String baseLiquid) {
        if (ingredient.isEmpty()) {
            return null;
        }
        for (MortarRecipe recipe : recipes) {
            if (recipe.matchesIngredient(ingredient) && recipe.baseLiquid().equals(baseLiquid)) {
                return recipe;
            }
        }
        return null;
    }
}
