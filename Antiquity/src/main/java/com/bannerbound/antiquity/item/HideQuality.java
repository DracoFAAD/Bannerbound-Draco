package com.bannerbound.antiquity.item;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;

/**
 * Quality of a raw animal hide (the tannery's input), set at the moment the animal is obtained:
 * by hunting (the weapon used vs the animal's preferred weapon) or by herding (living conditions +
 * herder skill). Three tiers only — POOR / STANDARD / GREAT — and quality is <b>realized as the
 * QUANTITY of {@code scraped_hide}</b> the rack yields ({@link #scrapedYield()}); downstream tannery
 * items carry no quality. Distinct from Core's {@link com.bannerbound.core.api.quality.QualityTier}
 * (tool craftsmanship): this is Antiquity-local because nothing in Core reads it (the herder cull
 * path receives an already-tagged stack via {@code HerderHooks}).
 */
public enum HideQuality implements StringRepresentable {
    POOR("poor", 1, ChatFormatting.RED, 0xFFFF5555),
    STANDARD("standard", 2, ChatFormatting.GRAY, 0xFFAAAAAA),
    GREAT("great", 3, ChatFormatting.GREEN, 0xFF55FF55);

    /** Codec for persistence (the data component) — stored by serialized name. */
    public static final Codec<HideQuality> CODEC = StringRepresentable.fromEnum(HideQuality::values);
    /** Stream codec for network sync (ordinal-based; compact and stable for an append-only enum). */
    public static final StreamCodec<ByteBuf, HideQuality> STREAM_CODEC =
        ByteBufCodecs.VAR_INT.map(i -> values()[i], HideQuality::ordinal);

    private final String name;
    private final int scrapedYield;
    private final ChatFormatting format;
    private final int color;

    HideQuality(String name, int scrapedYield, ChatFormatting format, int color) {
        this.name = name;
        this.scrapedYield = scrapedYield;
        this.format = format;
        this.color = color;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** How many {@code scraped_hide} this hide yields at the rack (POOR=1, STANDARD=2, GREAT=3). */
    public int scrapedYield() {
        return scrapedYield;
    }

    /** ChatFormatting for the tier word in tooltips. */
    public ChatFormatting format() {
        return format;
    }

    /** Packed ARGB color for HUD/render use (matches {@link #format()}). */
    public int color() {
        return color;
    }

    /** Localized tier name — {@code bannerboundantiquity.hide_quality.<name>}. */
    public Component displayName() {
        return Component.translatable("bannerboundantiquity.hide_quality." + name).withStyle(format);
    }

    /** The quality carried by {@code stack}, or {@link #STANDARD} when it has none. */
    public static HideQuality of(ItemStack stack) {
        HideQuality q = stack.get(
            com.bannerbound.antiquity.BannerboundAntiquity.HIDE_QUALITY.get());
        return q == null ? STANDARD : q;
    }
}
