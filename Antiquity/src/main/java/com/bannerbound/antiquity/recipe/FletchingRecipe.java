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
 * A data-driven Fletching Station recipe — an <b>unordered, count-based</b> pile recipe (same match
 * semantics as {@link CraftingStoneRecipe}) plus the stretch-minigame knobs that drive the quality
 * roll. Loaded from {@code data/<namespace>/fletching_recipes/*.json}.
 *
 * <p>Example — {@code .../fletching_recipes/primitive_bow.json}:
 * <pre>
 * { "ingredients": [ { "item": "minecraft:stick", "count": 3 },
 *                    { "item": "bannerboundantiquity:plant_string", "count": 3 } ],
 *   "result": { "id": "bannerboundantiquity:primitive_bow", "count": 1 },
 *   "stretches": 3, "base_zone_pct": 0.18, "zone_decay": 0.65,
 *   "min_zone_pct": 0.06, "yellow_pad_pct": 0.05 }
 * </pre>
 *
 * @param stretches    number of hold-and-release reps the minigame requires
 * @param baseZonePct  green-zone width (fraction of the bar) on the first stretch
 * @param zoneDecay    per-stretch multiplier shrinking the green zone (≤ 1 → narrows each rep)
 * @param minZonePct   floor for the green-zone width
 * @param yellowPadPct width of the "good" amber band flanking each side of the green zone
 * @param inProgress   optional display-only item shown lying on the station while this recipe's
 *                     minigame runs (set on commit, cleared on complete/cancel)
 */
@ApiStatus.Internal
public record FletchingRecipe(List<Ing> ingredients, ItemStack result,
                              int stretches, float baseZonePct, float zoneDecay,
                              float minZonePct, float yellowPadPct,
                              java.util.Optional<Item> inProgress) {

    /** One counted ingredient (a concrete item + how many of it the pile must contain). */
    public record Ing(Item item, int count) implements PileRecipes.Counted {
        public static final Codec<Ing> CODEC = RecordCodecBuilder.create(i -> i.group(
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Ing::item),
            Codec.INT.optionalFieldOf("count", 1).forGetter(Ing::count)
        ).apply(i, Ing::new));
    }

    public static final Codec<FletchingRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Ing.CODEC.listOf().fieldOf("ingredients").forGetter(FletchingRecipe::ingredients),
        ItemStack.CODEC.fieldOf("result").forGetter(FletchingRecipe::result),
        Codec.INT.optionalFieldOf("stretches", 3).forGetter(FletchingRecipe::stretches),
        Codec.FLOAT.optionalFieldOf("base_zone_pct", 0.18F).forGetter(FletchingRecipe::baseZonePct),
        Codec.FLOAT.optionalFieldOf("zone_decay", 0.65F).forGetter(FletchingRecipe::zoneDecay),
        Codec.FLOAT.optionalFieldOf("min_zone_pct", 0.06F).forGetter(FletchingRecipe::minZonePct),
        Codec.FLOAT.optionalFieldOf("yellow_pad_pct", 0.05F).forGetter(FletchingRecipe::yellowPadPct),
        BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("in_progress").forGetter(FletchingRecipe::inProgress)
    ).apply(instance, FletchingRecipe::new));

    /** The recipe's required item→count multiset. */
    public Map<Item, Integer> requiredCounts() {
        return PileRecipes.requiredCounts(ingredients);
    }

    /** Exact match: {@code placed} must equal the required multiset (same items, same counts). */
    public boolean matches(Map<Item, Integer> placed) {
        return PileRecipes.matches(ingredients, placed);
    }
}
