package com.bannerbound.antiquity.masonry;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One data-driven masonry output row, written ONCE per variant and resolved per stone family by
 * {@link StoneFamily#variant(String)}. Loaded from {@code data/<namespace>/masonry_outputs/*.json}.
 *
 * <p>Example — {@code .../masonry_outputs/slab.json}:
 * <pre>{ "variant": "slab", "base_cost": 1, "yield": 6 }</pre>
 *
 * @param variant  the family variant key ({@code slab}, {@code stairs}, {@code wall}, {@code bricks},
 *                 {@code polished}, {@code smooth}, {@code cut}, {@code chiseled})
 * @param baseCost base stone blocks this output costs from the budget per crafted unit
 * @param yield    how many output items one crafted unit produces
 */
@ApiStatus.Internal
public record MasonryOutput(String variant, int baseCost, int yield) {
    public static final Codec<MasonryOutput> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("variant").forGetter(MasonryOutput::variant),
        Codec.INT.optionalFieldOf("base_cost", 1).forGetter(MasonryOutput::baseCost),
        Codec.INT.optionalFieldOf("yield", 1).forGetter(MasonryOutput::yield)
    ).apply(i, MasonryOutput::new));
}
