package com.bannerbound.core.api;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;

/**
 * Single source of truth for the mod's bitmap-font glyph codepoints and {@link Component}
 * builders that work on EITHER side (server or client). {@code Icons} (client-only) covers
 * the same set with convenience methods, but server-side code can't reach Icons due to its
 * {@code @OnlyIn(Dist.CLIENT)} guard — so the chief scoreboard-team prefix (built on the
 * server) lives here instead.
 *
 * <p>Add a new glyph by registering the bitmap in {@code assets/bannerbound/font/icons.json}
 * at a PUA codepoint, then pinning the codepoint as a {@code public static final char}
 * here. Both client and server can reference the codepoint without duplication.
 */
@ApiStatus.Internal
public final class Glyphs {
    /** Font {@link ResourceLocation} all Bannerbound glyphs route through. */
    public static final ResourceLocation ICONS_FONT =
        ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "icons");

    /** White-on-anything style for inline glyph components — same Style {@code Icons} uses
     *  client-side, here so server-side code can build matching components. */
    public static final Style ICONS_STYLE = Style.EMPTY
        .withFont(ICONS_FONT)
        .withColor(TextColor.fromRgb(0xFFFFFF));

    /** Crown glyph codepoint (matches the provider in {@code font/icons.json} keyed at U+E103). */
    public static final char CROWN = (char) 0xE103;

    private Glyphs() {
    }

    /** A ready-to-use crown component — used by both the client {@code Icons.crown()} and the
     *  server-side chief scoreboard-team prefix. Single definition keeps the chief glyph
     *  identical everywhere (chat, nametag, TAB list, scoreboard prefix). */
    public static MutableComponent crown() {
        return Component.literal(String.valueOf(CROWN)).withStyle(ICONS_STYLE);
    }
}
