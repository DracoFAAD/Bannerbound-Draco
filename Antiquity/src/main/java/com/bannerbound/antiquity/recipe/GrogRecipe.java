package com.bannerbound.antiquity.recipe;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;

/**
 * A data-driven grog recipe (GROG_PLAN.md): an {@code input} fermentable, added to a water-filled
 * Fermentation Trough, ferments over {@code ferment_ticks} into a drink with its own identity —
 * display {@code name}, liquid {@code tint}, {@code strength}, {@code food_value}, and servings. Loaded
 * from {@code data/<namespace>/grog_recipes/*.json}; the file path is the recipe id stored on the
 * trough. Nothing about grog is hardcoded — modpacks mint their own by dropping in a JSON.
 *
 * <p>Example — {@code .../grog_recipes/berry_grog.json}:
 * <pre>
 * { "input": "minecraft:sweet_berries", "min_water_units": 1, "ferment_ticks": 6000,
 *   "name": "grog.berry", "tint": "8E3B5C", "strength": 1, "food_value": 2, "servings": 3 }
 * </pre>
 */
@ApiStatus.Internal
public record GrogRecipe(Item input, int minWaterUnits, int fermentTicks, String name, int tint,
                         int strength, int foodValue, int servings, List<MobEffectInstance> effects) {

    /** {@code "8E3B5C"} / {@code "0x8E3B5C"} / {@code "#8E3B5C"} ↔ {@code 0xRRGGBB}. */
    static final Codec<Integer> TINT_CODEC = Codec.STRING.xmap(
        s -> (int) (Long.parseLong(s.replace("0x", "").replace("#", ""), 16) & 0xFFFFFF),
        i -> String.format("%06X", i & 0xFFFFFF));

    /** Our own per-sip effect codec — {@code {"id","duration","amplifier"}} — instead of
     *  {@code MobEffectInstance.CODEC}, whose schema mismatch was being SILENTLY swallowed by
     *  {@code optionalFieldOf} (the effects list quietly emptied, so the grog loaded with no effects). */
    public static final Codec<MobEffectInstance> EFFECT_CODEC = RecordCodecBuilder.create(in -> in.group(
        BuiltInRegistries.MOB_EFFECT.holderByNameCodec().fieldOf("id").forGetter(MobEffectInstance::getEffect),
        Codec.INT.optionalFieldOf("duration", 200).forGetter(MobEffectInstance::getDuration),
        Codec.INT.optionalFieldOf("amplifier", 0).forGetter(MobEffectInstance::getAmplifier)
    ).apply(in, (Holder<MobEffect> id, Integer dur, Integer amp) -> new MobEffectInstance(id, dur, amp)));

    public static final Codec<GrogRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        BuiltInRegistries.ITEM.byNameCodec().fieldOf("input").forGetter(GrogRecipe::input),
        Codec.INT.optionalFieldOf("min_water_units", 1).forGetter(GrogRecipe::minWaterUnits),
        Codec.INT.optionalFieldOf("ferment_ticks", 6000).forGetter(GrogRecipe::fermentTicks),
        Codec.STRING.fieldOf("name").forGetter(GrogRecipe::name),
        TINT_CODEC.fieldOf("tint").forGetter(GrogRecipe::tint),
        Codec.INT.optionalFieldOf("strength", 1).forGetter(GrogRecipe::strength),
        Codec.INT.optionalFieldOf("food_value", 1).forGetter(GrogRecipe::foodValue),
        Codec.INT.optionalFieldOf("servings", 3).forGetter(GrogRecipe::servings),
        // Per-sip effects (e.g. berry grog → regeneration). Intoxication is generic, scaled by strength.
        EFFECT_CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(GrogRecipe::effects)
    ).apply(instance, GrogRecipe::new));
}
