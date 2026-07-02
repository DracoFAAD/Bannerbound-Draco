package com.bannerbound.antiquity.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Freshness riding a food ItemStack (a {@code FOOD_SPOILAGE} data component). Food keeps no clock —
 * it carries a discrete <b>freshness level</b> ({@link #FRESH} or {@link #BLAND}) plus whether it has
 * been {@link #salted}. Because the component holds no per-stack timestamp, every fresh stack of a
 * food is byte-for-byte identical and merges into one stack (and likewise every bland stack), which is
 * the whole point — time-stamped deadlines fragmented food into a new stack every few minutes.
 *
 * <p>Spoilage is probabilistic: a once-a-second roll (see {@code Spoilage}) degrades a whole stack one
 * level — {@code FRESH → BLAND}, then {@code BLAND →} the terminal {@code spoiled_food} item. The roll
 * chance is tuned so the <i>average</i> shelf life matches the food's data-driven lifetime; salt lowers
 * the chance so salted food keeps longer. {@link #BLAND} food is worth {@link #BLAND_FOOD_MULTIPLIER}
 * of its food value to both the player who eats it and the settlement larder.
 *
 * <p>The component is stamped server-side the moment a perishable item is observed in an inventory
 * (see {@code Spoilage}); it rides the stack and is network-synchronized, so the freshness tooltip and
 * the (halved) food-value line work on the client from the component alone.
 */
public record FoodSpoilage(int level, boolean salted) {

    /** Just-made food: full food value. */
    public static final int FRESH = 0;
    /** Aging food: still edible but worth {@link #BLAND_FOOD_MULTIPLIER} of its food value. */
    public static final int BLAND = 1;

    /** Fraction of its food value that bland food is worth (to the eater and to the larder). */
    public static final float BLAND_FOOD_MULTIPLIER = 0.5f;

    // {@code level} is optional (defaulting to FRESH) so food saved under the old time-deadline schema
    // — which had no {@code level} field — migrates to fresh on load instead of failing to parse.
    public static final Codec<FoodSpoilage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.INT.optionalFieldOf("level", FRESH).forGetter(FoodSpoilage::level),
        Codec.BOOL.optionalFieldOf("salted", false).forGetter(FoodSpoilage::salted)
    ).apply(instance, FoodSpoilage::new));

    public static final StreamCodec<ByteBuf, FoodSpoilage> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, FoodSpoilage::level,
        ByteBufCodecs.BOOL, FoodSpoilage::salted,
        FoodSpoilage::new);

    public boolean isFresh() {
        return level <= FRESH;
    }

    public boolean isBland() {
        return level >= BLAND;
    }

    /** Food-value multiplier for this freshness: {@code 1.0} fresh, {@link #BLAND_FOOD_MULTIPLIER} bland. */
    public float foodMultiplier() {
        return isBland() ? BLAND_FOOD_MULTIPLIER : 1.0f;
    }
}
