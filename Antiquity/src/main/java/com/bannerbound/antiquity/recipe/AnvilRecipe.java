package com.bannerbound.antiquity.recipe;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A data-driven Stone Anvil recipe — an <b>unordered, count-based</b> pile recipe (same match
 * semantics as the Crafting Stone / Fletching Station) plus the number of cold-hammer strikes the
 * minigame requires. Loaded from {@code data/<namespace>/anvil_recipes/*.json}.
 *
 * <p>Example — {@code .../anvil_recipes/copper_sword.json}:
 * <pre>
 * { "ingredients": [ { "item": "bannerboundantiquity:copper_blade", "count": 1 },
 *                    { "item": "minecraft:stick", "count": 1 } ],
 *   "result": { "id": "bannerboundantiquity:copper_sword", "count": 1 },
 *   "strikes": 4 }
 * </pre>
 *
 * @param strikes    number of drag-and-release hammer strikes (one per ~50 mB of the part)
 * @param inProgress optional display-only item shown on the anvil while the minigame runs
 */
@ApiStatus.Internal
public record AnvilRecipe(List<Ing> ingredients, ItemStack result, int strikes,
                          java.util.Optional<Item> inProgress) {

    /** One counted ingredient (a concrete item + how many of it the pile must contain). */
    public record Ing(Item item, int count) implements PileRecipes.Counted {
        public static final Codec<Ing> CODEC = RecordCodecBuilder.create(i -> i.group(
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Ing::item),
            Codec.INT.optionalFieldOf("count", 1).forGetter(Ing::count)
        ).apply(i, Ing::new));
    }

    public static final Codec<AnvilRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Ing.CODEC.listOf().fieldOf("ingredients").forGetter(AnvilRecipe::ingredients),
        ItemStack.CODEC.fieldOf("result").forGetter(AnvilRecipe::result),
        Codec.INT.optionalFieldOf("strikes", 3).forGetter(AnvilRecipe::strikes),
        BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("in_progress").forGetter(AnvilRecipe::inProgress)
    ).apply(instance, AnvilRecipe::new));

    public Map<Item, Integer> requiredCounts() {
        return PileRecipes.requiredCounts(ingredients);
    }

    public boolean matches(Map<Item, Integer> placed) {
        return PileRecipes.matches(ingredients, placed);
    }
}
