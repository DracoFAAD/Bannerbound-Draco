package com.bannerbound.antiquity.recipe;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A data-driven Drying Rack recipe: one input item that dries, over {@code ticks}, into a result.
 * Loaded from {@code data/<namespace>/drying_recipes/*.json}. Each occupied slot on a rack runs one
 * of these independently.
 *
 * <p>Example — {@code .../drying_recipes/plant_fiber.json}:
 * <pre>
 * { "input": "bannerboundantiquity:plant_fiber",
 *   "result": { "id": "bannerboundantiquity:thatch_bundle", "count": 1 },
 *   "ticks": 600 }
 * </pre>
 */
@ApiStatus.Internal
public record DryingRackRecipe(Item input, ItemStack result, int ticks, String categoryRaw) {

    /** NPC-tending category: {@value} — the Cook tends these (jerky, dried fish). */
    public static final String FOOD = "food";
    /** NPC-tending category: {@value} — General Crafts tends these (plant fiber → thatch). */
    public static final String CRAFT = "craft";
    /** NPC-tending category: {@value} — no NPC touches it (cured hide stays the Tannery's own
     *  leather line; a generic rack still works by hand). */
    public static final String NONE = "none";

    public static final Codec<DryingRackRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        BuiltInRegistries.ITEM.byNameCodec().fieldOf("input").forGetter(DryingRackRecipe::input),
        ItemStack.CODEC.fieldOf("result").forGetter(DryingRackRecipe::result),
        Codec.INT.optionalFieldOf("ticks", 600).forGetter(DryingRackRecipe::ticks),
        Codec.STRING.optionalFieldOf("category", "").forGetter(DryingRackRecipe::categoryRaw)
    ).apply(instance, DryingRackRecipe::new));

    /** The NPC-tending category — the explicit {@code "category"} field when set, else derived from
     *  the result: edible → {@link #FOOD}, anything else → {@link #CRAFT}. Existing JSONs without
     *  the field keep working (thatch derives to craft). */
    public String category() {
        if (!categoryRaw.isEmpty()) return categoryRaw;
        return result.has(net.minecraft.core.component.DataComponents.FOOD) ? FOOD : CRAFT;
    }
}
