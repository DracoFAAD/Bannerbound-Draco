package com.bannerbound.antiquity.recipe;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * A data-driven Bloomery smelting recipe. While the bloomery is lit with its door shut, an
 * {@code ingredient} stack inside is processed; each item takes {@code ticks} of base time
 * (bulk batches are discounted — see {@code BloomeryBlockEntity}). On completion each item in
 * the stack rolls {@code chance} to yield {@code result}. Loaded from
 * {@code data/<namespace>/bloomery_recipes/*.json}.
 * <p>
 * Example — {@code data/bannerboundantiquity/bloomery_recipes/raw_iron.json}:
 * <pre>
 * { "ingredient": { "item": "minecraft:raw_iron" }, "ticks": 200,
 *   "result": { "id": "minecraft:iron_ingot", "count": 1 }, "chance": 1.0 }
 * </pre>
 */
@ApiStatus.Internal
public record BloomeryRecipe(Ingredient ingredient, int ticks, ItemStack result, float chance,
                            int bandLow, int bandHigh) {
    public static final Codec<BloomeryRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Ingredient.CODEC.fieldOf("ingredient").forGetter(BloomeryRecipe::ingredient),
        Codec.INT.fieldOf("ticks").forGetter(BloomeryRecipe::ticks),
        ItemStack.CODEC.fieldOf("result").forGetter(BloomeryRecipe::result),
        Codec.FLOAT.optionalFieldOf("chance", 1.0F).forGetter(BloomeryRecipe::chance),
        Codec.INT.optionalFieldOf("band_low",
            com.bannerbound.antiquity.block.entity.BloomeryHeat.DEFAULT_BAND_LOW).forGetter(BloomeryRecipe::bandLow),
        Codec.INT.optionalFieldOf("band_high",
            com.bannerbound.antiquity.block.entity.BloomeryHeat.DEFAULT_BAND_HIGH).forGetter(BloomeryRecipe::bandHigh)
    ).apply(instance, BloomeryRecipe::new));

    /** True if {@code stack} is this recipe's ingredient. */
    public boolean matches(ItemStack stack) {
        return ingredient.test(stack);
    }
}
