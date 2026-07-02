package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Era;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Wraps the {@code bannerbound:icons} bitmap font so we can embed icons inline in any
 * {@link Component}. This is the proper way to mix an image with text in MC — much cleaner
 * than overlay-blitting on top of a rendered tooltip.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class Icons {
    public static final ResourceLocation ICONS_FONT = ResourceLocation.fromNamespaceAndPath("bannerbound", "icons");
    public static final ResourceLocation RESOURCE_ICONS_FONT =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "resource_icons");
    // Explicit white color: in Minecraft's Component tree, a child style overrides the parent's
    // color, so pinning the icon glyph to white prevents inheritance from a parent component
    // that called withStyle(ChatFormatting.GREEN) (or any other color) from dyeing the icon.
    public static final Style ICONS_STYLE = Style.EMPTY
        .withFont(ICONS_FONT)
        .withColor(TextColor.fromRgb(0xFFFFFF));
    public static final Style RESOURCE_ICONS_STYLE = Style.EMPTY
        .withFont(RESOURCE_ICONS_FONT)
        .withColor(TextColor.fromRgb(0xFFFFFF));

    // PUA codepoints matching the providers in assets/bannerbound/font/icons.json.
    private static final String SCIENCE_GLYPH = "";
    private static final String FOOD_GLYPH = "";
    private static final String CULTURE_GLYPH = "";
    private static final String HAPPINESS_HIGH_GLYPH = "";
    private static final String HAPPINESS_MID_GLYPH = "";
    private static final String HAPPINESS_LOW_GLYPH = "";

    // Era-keyed glyph codepoints, one per Era.ordinal(). Each maps to a bitmap provider in
    // assets/bannerbound/font/icons.json: food U+E010..U+E017, culture U+E020..U+E027.
    // Every era now has its own food and culture glyph.
    private static final String[] FOOD_GLYPHS = {
        "", // ANCIENT
        "", // CLASSICAL (falls back to Ancient art until its own lands)
        "", // MEDIEVAL
        "", // RENAISSANCE
        "", // INDUSTRIAL
        "", // DIESEL
        "", // ATOMIC
        "", // MODERN
        "", // FUTURE
    };
    private static final String[] CULTURE_GLYPHS = {
        "", // ANCIENT
        "", // CLASSICAL (falls back to Ancient art until its own lands)
        "", // MEDIEVAL
        "", // RENAISSANCE
        "", // INDUSTRIAL
        "", // DIESEL
        "", // ATOMIC
        "", // MODERN
        "", // FUTURE
    };

    /** Era-keyed faith/devotion glyphs at PUA codepoints U+E050..U+E057. Only antiquity
     *  has art so far ({@code gui/faith_antiquity.png}); later eras fall back via
     *  {@link #glyphFor}'s nearest-earlier rule once their providers exist. */
    private static final String[] FAITH_GLYPHS = {
        "", // ANCIENT
        "", // CLASSICAL (falls back to Ancient art until its own lands)
        "", // MEDIEVAL
        "", // RENAISSANCE
        "", // INDUSTRIAL
        "", // DIESEL
        "", // ATOMIC
        "", // MODERN
        "", // FUTURE
    };

    /** Era-keyed science glyphs at PUA codepoints U+E030..U+E037. All providers in
     *  {@code font/icons.json} currently point at the same placeholder {@code science_icon.png};
     *  swap the providers' file paths to per-era PNGs (no Java change) when real art lands. */
    private static final String[] SCIENCE_GLYPHS = {
        "", // ANCIENT
        "", // CLASSICAL (falls back to Ancient art until its own lands)
        "", // MEDIEVAL
        "", // RENAISSANCE
        "", // INDUSTRIAL
        "", // DIESEL
        "", // ATOMIC
        "", // MODERN
        "", // FUTURE
    };

    private Icons() {
    }

    public static MutableComponent science() {
        return Component.literal(SCIENCE_GLYPH).withStyle(RESOURCE_ICONS_STYLE);
    }

    public static MutableComponent food() {
        return Component.literal(FOOD_GLYPH).withStyle(RESOURCE_ICONS_STYLE);
    }

    public static MutableComponent culture() {
        return Component.literal(CULTURE_GLYPH).withStyle(RESOURCE_ICONS_STYLE);
    }

    /** Era-keyed food icon. Falls back to the antiquity glyph for eras without a custom texture. */
    public static MutableComponent food(Era era) {
        return Component.literal(glyphFor(FOOD_GLYPHS, era)).withStyle(RESOURCE_ICONS_STYLE);
    }

    /** Era-keyed culture icon. Falls back to the antiquity glyph for eras without a custom texture. */
    public static MutableComponent culture(Era era) {
        return Component.literal(glyphFor(CULTURE_GLYPHS, era)).withStyle(RESOURCE_ICONS_STYLE);
    }

    /** Era-keyed science icon. Currently every era maps to the same placeholder; per-era art
     *  will appear once the corresponding {@code science_<era>.png} files exist. */
    public static MutableComponent science(Era era) {
        return Component.literal(glyphFor(SCIENCE_GLYPHS, era)).withStyle(RESOURCE_ICONS_STYLE);
    }

    /** Faith/devotion icon (antiquity art). */
    public static MutableComponent faith() {
        return Component.literal(FAITH_GLYPHS[0]).withStyle(ICONS_STYLE);
    }

    /** Era-keyed faith/devotion icon — falls back to antiquity until later art lands. */
    public static MutableComponent faith(Era era) {
        return Component.literal(glyphFor(FAITH_GLYPHS, era)).withStyle(ICONS_STYLE);
    }

    /** Speech bubble glyph at PUA codepoint U+E040 (backed by {@code gui/speech_bubble.png}).
     *  Used by {@link SpeechBubbleLayer} as a bitmap-font sprite — the same render path your
     *  existing food/culture/science icons use, and the path vanilla name tags use. */
    public static MutableComponent bubble() {
        return Component.literal(String.valueOf((char) 0xE040)).withStyle(ICONS_STYLE);
    }

    /** Pregnancy glyph at PUA codepoint U+E102 (backed by {@code gui/pregnancy.png}). Rendered
     *  by {@code CitizenEntity.refreshDisplayName} as a prefix on a pregnant woman's display
     *  name, sitting just before her female icon. */
    public static MutableComponent pregnant() {
        return Component.literal(String.valueOf((char) 0xE102)).withStyle(ICONS_STYLE);
    }

    /** Crown glyph at PUA codepoint U+E103 (backed by {@code gui/crown.png}). Prepended to the
     *  Chief player's display name by the nametag-format hook so the whole settlement (and the
     *  scoreboard / tab list) can see who's wearing the crown at a glance.
     *  <p>Delegates to {@link com.bannerbound.core.api.Glyphs#crown} so server-side code (which
     *  can't reach this client-only class) builds the same component from the same codepoint. */
    public static MutableComponent crown() {
        return com.bannerbound.core.api.Glyphs.crown();
    }

    private static String glyphFor(String[] table, Era era) {
        int ord = era == null ? 0 : era.ordinal();
        if (ord < 0 || ord >= table.length) ord = 0;
        // Eras without their own art (e.g. no culture_modern.png yet) fall back to the
        // nearest earlier era that does have a glyph.
        while (ord > 0 && table[ord].isEmpty()) ord--;
        return table[ord];
    }

    /** Same three-bucket face glyphs as {@link #happiness(int, int)} but addressed by bucket
     *  directly: 0 = low (red), 1 = mid (yellow), 2 = high (green). Callers that already
     *  know which bucket they want — e.g. the speech-bubble layer decoding a packed bubble id
     *  whose subType is the bucket — use this instead of recomputing the ratio. */
    public static MutableComponent happinessForBucket(int bucket) {
        String glyph = switch (bucket) {
            case 2 -> HAPPINESS_HIGH_GLYPH;
            case 1 -> HAPPINESS_MID_GLYPH;
            default -> HAPPINESS_LOW_GLYPH;
        };
        return Component.literal(glyph).withStyle(ICONS_STYLE);
    }

    /**
     * Returns the happiness icon matching the given value out of {@code max}. Thresholds are at
     * 70% (high → green) and 40% (mid → yellow); below that it's the low (red) icon.
     */
    public static MutableComponent happiness(int value, int max) {
        if (max <= 0) {
            return Component.literal(HAPPINESS_MID_GLYPH).withStyle(ICONS_STYLE);
        }
        double ratio = (double) value / max;
        String glyph;
        if (ratio >= 0.7) {
            glyph = HAPPINESS_HIGH_GLYPH;
        } else if (ratio >= 0.4) {
            glyph = HAPPINESS_MID_GLYPH;
        } else {
            glyph = HAPPINESS_LOW_GLYPH;
        }
        return Component.literal(glyph).withStyle(ICONS_STYLE);
    }
}
