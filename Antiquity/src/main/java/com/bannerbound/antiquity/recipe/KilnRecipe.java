package com.bannerbound.antiquity.recipe;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * A data-driven Kiln firing recipe — the early-game ceramics/lime counterpart to the Bloomery
 * (see {@link BloomeryRecipe}). While the kiln is lit, an {@code ingredient} stack inside is fired;
 * each item takes {@code ticks} of base time (bulk batches are discounted — see
 * {@code KilnBlockEntity}). On completion each item rolls {@code chance} to yield {@code result}.
 * Loaded from {@code data/<namespace>/kiln_recipes/*.json}.
 * <p>
 * Example — {@code data/bannerboundantiquity/kiln_recipes/brick.json}:
 * <pre>
 * { "ingredient": { "item": "minecraft:clay_ball" }, "ticks": 100,
 *   "result": { "id": "minecraft:brick", "count": 1 }, "chance": 1.0 }
 * </pre>
 */
@ApiStatus.Internal
public record KilnRecipe(Ingredient ingredient, int ticks, ItemStack result, float chance) {
    public static final Codec<KilnRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Ingredient.CODEC.fieldOf("ingredient").forGetter(KilnRecipe::ingredient),
        Codec.INT.fieldOf("ticks").forGetter(KilnRecipe::ticks),
        ItemStack.CODEC.fieldOf("result").forGetter(KilnRecipe::result),
        Codec.FLOAT.optionalFieldOf("chance", 1.0F).forGetter(KilnRecipe::chance)
    ).apply(instance, KilnRecipe::new));

    /** True if {@code stack} is this recipe's ingredient. */
    public boolean matches(ItemStack stack) {
        return ingredient.test(stack);
    }
}
