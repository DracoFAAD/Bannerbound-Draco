package com.bannerbound.antiquity.item;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * The stew identity cooked in a stone cooking pot — a snapshot of the {@code StewRecipe} (or the
 * generic fallback) it was made from. Carried by the pot's block entity while a stew is held, and
 * forward-compatible as a {@code DataComponentType} for the future bowl item (woodworking): same
 * record can ride on a portable serving once bowls exist.
 *
 * @param name           lang key for the stew's display name (e.g. {@code stew.generic})
 * @param tint           packed 0xRRGGBB liquid colour (tints the pot's stew layer)
 * @param foodPerServing food value restored by one serving (haunch scale)
 * @param servings       how many servings the full pot yields
 * @param effects        per-serving mob effects, snapshotted from the recipe
 * @param poisoned       true if any ingredient was poisoned — the whole stew is tainted
 */
public record StewContents(String name, int tint, double foodPerServing, int servings,
                           List<MobEffectInstance> effects, boolean poisoned) {

    public static final Codec<StewContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.optionalFieldOf("name", "").forGetter(StewContents::name),
        Codec.INT.optionalFieldOf("tint", 0xFFFFFF).forGetter(StewContents::tint),
        Codec.DOUBLE.optionalFieldOf("food_per_serving", 1.0).forGetter(StewContents::foodPerServing),
        Codec.INT.optionalFieldOf("servings", 1).forGetter(StewContents::servings),
        MobEffectInstance.CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(StewContents::effects),
        Codec.BOOL.optionalFieldOf("poisoned", false).forGetter(StewContents::poisoned)
    ).apply(instance, StewContents::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, StewContents> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StewContents::name,
            ByteBufCodecs.INT, StewContents::tint,
            ByteBufCodecs.DOUBLE, StewContents::foodPerServing,
            ByteBufCodecs.VAR_INT, StewContents::servings,
            MobEffectInstance.STREAM_CODEC.apply(ByteBufCodecs.list()), StewContents::effects,
            ByteBufCodecs.BOOL, StewContents::poisoned,
            StewContents::new);

    /** Total food value of a full pot of this stew. */
    public double totalFoodValue() {
        return foodPerServing * servings;
    }
}
