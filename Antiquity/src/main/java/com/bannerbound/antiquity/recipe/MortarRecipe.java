package com.bannerbound.antiquity.recipe;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * A data-driven Mortar and Pestle grinding recipe. Grinding {@code ingredient} into the bowl's
 * {@code baseLiquid} produces a result after 5 grinds — a new liquid, an item, or both. Loaded
 * by {@link MortarRecipeManager} from {@code data/<namespace>/mortar_recipes/*.json}.
 * <p>
 * Examples:
 * <pre>
 * { "ingredient": { "item": "minecraft:ink_sac" }, "base_liquid": "water", "result_liquid": "ink" }
 * { "ingredient": { "item": "minecraft:clay_ball" }, "base_liquid": "water",
 *   "result_item": { "id": "minecraft:brick", "count": 1 } }
 * </pre>
 * {@code result_liquid} omitted (or {@code ""}) empties the bowl; {@code result_item} omitted
 * leaves no item. At least one of the two should be set.
 */
@ApiStatus.Internal
public record MortarRecipe(Ingredient ingredient, String baseLiquid, String resultLiquid,
                           ItemStack resultItem) {
    public static final Codec<MortarRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Ingredient.CODEC.fieldOf("ingredient").forGetter(MortarRecipe::ingredient),
        Codec.STRING.fieldOf("base_liquid").forGetter(MortarRecipe::baseLiquid),
        Codec.STRING.optionalFieldOf("result_liquid", "").forGetter(MortarRecipe::resultLiquid),
        ItemStack.CODEC.optionalFieldOf("result_item", ItemStack.EMPTY).forGetter(MortarRecipe::resultItem)
    ).apply(instance, MortarRecipe::new));

    /** True if {@code stack} is this recipe's ingredient. */
    public boolean matchesIngredient(ItemStack stack) {
        return ingredient.test(stack);
    }
}
