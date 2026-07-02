package com.bannerbound.antiquity.carpentry;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One data-driven carpentry output row, written ONCE per variant and resolved per wood family by
 * {@link WoodFamily#variant(String)}. Loaded from {@code data/<namespace>/carpentry_outputs/*.json}.
 *
 * <p>Example — {@code .../carpentry_outputs/stairs.json}:
 * <pre>{ "variant": "stairs", "log_cost": 1, "yield": 6 }</pre>
 *
 * @param variant the family variant suffix ({@code planks}, {@code stairs}, {@code slab}, {@code door}…)
 * @param logCost theoretical logs this output costs from the budget per crafted unit
 * @param yield   how many output items one crafted unit produces
 */
@ApiStatus.Internal
public record CarpentryOutput(String variant, int logCost, int yield) {
    public static final Codec<CarpentryOutput> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("variant").forGetter(CarpentryOutput::variant),
        Codec.INT.optionalFieldOf("log_cost", 1).forGetter(CarpentryOutput::logCost),
        Codec.INT.optionalFieldOf("yield", 1).forGetter(CarpentryOutput::yield)
    ).apply(i, CarpentryOutput::new));
}
