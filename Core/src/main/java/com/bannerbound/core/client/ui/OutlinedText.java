package com.bannerbound.core.client.ui;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Renders text with a 1-pixel outline (4 cardinal offsets in the outline color, then the fill
 * pass on top). Cheaper-to-read alternative to vanilla drop shadow for icons inline with text —
 * the outline reads cleanly against the moss-flecked stone panel where a single bottom-right
 * shadow gets lost in the texture noise.
 * <p>
 * Five draw calls per line. Negligible cost for a handful of stats lines but don't use this for
 * long-form text or large lists; for that, switch to vanilla shadow.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class OutlinedText {
    private OutlinedText() {
    }

    /** Default outline color — opaque black. Reads against any stone shade we're likely to use. */
    public static final int DEFAULT_OUTLINE = 0xFF000000;

    public static void draw(GuiGraphics g, Font font, Component text, int x, int y, int fillColor) {
        draw(g, font, text, x, y, fillColor, DEFAULT_OUTLINE);
    }

    public static void draw(GuiGraphics g, Font font, Component text, int x, int y,
                            int fillColor, int outlineColor) {
        // GuiGraphics#drawString's color arg is only the *fallback* when the Component has no
        // explicit Style color. Our stats lines DO have styles (e.g. ChatFormatting.WHITE for
        // popLine), so the outline pass would inherit the white color and produce a useless
        // white-on-white outline. Recolor the whole tree to the outline color first.
        Component outlinePass = recolor(text, outlineColor);
        g.drawString(font, outlinePass, x - 1, y, outlineColor, false);
        g.drawString(font, outlinePass, x + 1, y, outlineColor, false);
        g.drawString(font, outlinePass, x, y - 1, outlineColor, false);
        g.drawString(font, outlinePass, x, y + 1, outlineColor, false);
        g.drawString(font, text, x, y, fillColor, false);
    }

    /**
     * Returns a copy of {@code src} where every node in the component tree (this node + all
     * recursive siblings) has its Style color overridden to {@code color}. Keeps non-color style
     * (font, bold, italic) intact — important so glyphs from the {@code bannerbound:icons}
     * font still render through the right font, just in the outline color.
     */
    private static Component recolor(Component src, int color) {
        MutableComponent copy = src.copy();
        overrideRecursive(copy, color);
        return copy;
    }

    private static void overrideRecursive(MutableComponent c, int color) {
        c.setStyle(c.getStyle().withColor(TextColor.fromRgb(color)));
        List<Component> siblings = c.getSiblings();
        for (int i = 0; i < siblings.size(); i++) {
            MutableComponent reSibling = siblings.get(i).copy();
            overrideRecursive(reSibling, color);
            siblings.set(i, reSibling);
        }
    }
}
