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
 * A data-driven Crafting Stone recipe — an <b>unordered, count-based</b> recipe. The stone holds a
 * loose pile of items (placed one at a time); a recipe matches when the pile's per-item counts
 * EXACTLY equal the recipe's ingredient counts (no extras, no shortfalls). Order doesn't matter.
 * Loaded from {@code data/<namespace>/crafting_stone_recipes/*.json}.
 *
 * <p>Example — {@code .../crafting_stone_recipes/bone_pickaxe.json}:
 * <pre>
 * { "ingredients": [ { "item": "bannerboundantiquity:bone_blade", "count": 2 },
 *                    { "item": "minecraft:stick", "count": 2 },
 *                    { "item": "bannerboundantiquity:plant_fiber", "count": 1 } ],
 *   "result": { "id": "bannerboundantiquity:bone_pickaxe", "count": 1 } }
 * </pre>
 *
 * <p>When {@code "transfer_quality": true}, the highest {@code TOOL_QUALITY} found among the consumed
 * ingredients is stamped onto the result (and scales its durability). This is how a knapped tool head
 * carries its craftsmanship onto the finished vanilla stone tool at hafting — see {@code KNAPPING_PLAN.md}.
 */
@ApiStatus.Internal
public record CraftingStoneRecipe(List<Ing> ingredients, ItemStack result, boolean transferQuality) {

    /** One counted ingredient (a concrete item + how many of it the pile must contain). */
    public record Ing(Item item, int count) implements PileRecipes.Counted {
        public static final Codec<Ing> CODEC = RecordCodecBuilder.create(i -> i.group(
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Ing::item),
            Codec.INT.optionalFieldOf("count", 1).forGetter(Ing::count)
        ).apply(i, Ing::new));
    }

    public static final Codec<CraftingStoneRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Ing.CODEC.listOf().fieldOf("ingredients").forGetter(CraftingStoneRecipe::ingredients),
        ItemStack.CODEC.fieldOf("result").forGetter(CraftingStoneRecipe::result),
        Codec.BOOL.optionalFieldOf("transfer_quality", false).forGetter(CraftingStoneRecipe::transferQuality)
    ).apply(instance, CraftingStoneRecipe::new));

    /** The recipe's required item→count multiset. */
    public Map<Item, Integer> requiredCounts() {
        return PileRecipes.requiredCounts(ingredients);
    }

    /** Exact match: {@code placed} must equal the required multiset (same items, same counts). */
    public boolean matches(Map<Item, Integer> placed) {
        return PileRecipes.matches(ingredients, placed);
    }
}
