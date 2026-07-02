package com.bannerbound.antiquity.recipe;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Data-driven Pottery Slab recipe: an unordered, count-based clay pile recipe plus the spin count
 * for the wheel minigame. Loaded from {@code data/<namespace>/pottery_recipes/*.json}.
 *
 * <p>The wheel is a NON-skill minigame (like the carpenter's saw): {@code spins} sets how many wheel
 * turns the shaping takes, but accuracy is never scored and pottery outputs carry no quality tier —
 * shaped vessels/bricks are deterministic. Keep it that way (see the tier-2 production design notes).
 */
@ApiStatus.Internal
public record PotteryRecipe(List<Ing> ingredients, ItemStack result,
                            int spins, Optional<Item> inProgress) {
    /** One counted ingredient (a concrete item + how many of it the slab must contain). */
    public record Ing(Item item, int count) implements PileRecipes.Counted {
        public static final Codec<Ing> CODEC = RecordCodecBuilder.create(i -> i.group(
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Ing::item),
            Codec.INT.optionalFieldOf("count", 1).forGetter(Ing::count)
        ).apply(i, Ing::new));
    }

    public static final Codec<PotteryRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Ing.CODEC.listOf().fieldOf("ingredients").forGetter(PotteryRecipe::ingredients),
        ItemStack.CODEC.fieldOf("result").forGetter(PotteryRecipe::result),
        Codec.INT.optionalFieldOf("spins", 3).forGetter(PotteryRecipe::spins),
        BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("in_progress").forGetter(PotteryRecipe::inProgress)
    ).apply(instance, PotteryRecipe::new));

    /** The recipe's required item-to-count multiset. */
    public Map<Item, Integer> requiredCounts() {
        return PileRecipes.requiredCounts(ingredients);
    }

    /** Exact match: {@code placed} must equal the required multiset. */
    public boolean matches(Map<Item, Integer> placed) {
        return PileRecipes.matches(ingredients, placed);
    }
}
