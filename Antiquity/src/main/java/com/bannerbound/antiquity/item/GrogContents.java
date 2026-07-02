package com.bannerbound.antiquity.item;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * The grog held by a filled vessel (mug / horn) — a snapshot of the {@code GrogRecipe} it was poured
 * from (GROG_PLAN.md Phase 3). Stored as a {@code DataComponentType} on the vessel item; its presence
 * means "full" (drives the {@code filled} model override + the tinted alcohol layer), its absence
 * means an empty vessel. Drinking applies {@code foodValue} + the {@code effects}, bumps intoxication
 * by {@code strength}, then clears it.
 *
 * @param name      lang key for the grog's display name (e.g. {@code grog.berry})
 * @param tint      packed 0xRRGGBB liquid colour (tints the vessel's alcohol layer)
 * @param strength  drink potency — intoxication added per sip (and saturation)
 * @param foodValue hunger restored per drink
 * @param effects   per-sip mob effects (e.g. regeneration), snapshotted from the recipe
 */
public record GrogContents(String name, int tint, int strength, int foodValue,
                           List<MobEffectInstance> effects) {

    public static final Codec<GrogContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.optionalFieldOf("name", "").forGetter(GrogContents::name),
        Codec.INT.optionalFieldOf("tint", 0xFFFFFF).forGetter(GrogContents::tint),
        Codec.INT.optionalFieldOf("strength", 1).forGetter(GrogContents::strength),
        Codec.INT.optionalFieldOf("food_value", 1).forGetter(GrogContents::foodValue),
        MobEffectInstance.CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(GrogContents::effects)
    ).apply(instance, GrogContents::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, GrogContents> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, GrogContents::name,
            ByteBufCodecs.INT, GrogContents::tint,
            ByteBufCodecs.VAR_INT, GrogContents::strength,
            ByteBufCodecs.VAR_INT, GrogContents::foodValue,
            MobEffectInstance.STREAM_CODEC.apply(ByteBufCodecs.list()), GrogContents::effects,
            GrogContents::new);
}
