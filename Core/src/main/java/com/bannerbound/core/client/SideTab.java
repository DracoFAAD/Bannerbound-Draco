package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.Config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A bookmark tab protruding from a panel's RIGHT edge — the sideways twin of
 * {@link BookmarkTab}, for overflow/secondary tab groups when a strip no longer fits across
 * the top (the Town Hall's governance tabs). Anchored at the panel edge ({@code getX()}),
 * extending right; the selected tab protrudes fully, carries the settlement-identity ribbon
 * along its outer end, and opens its left edge into the panel body. Unselected tabs protrude
 * 3px less and glide 2px outward on hover (instant when {@link Config#UI_ANIMATIONS} is off).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class SideTab extends Button {
    private final boolean selected;
    private final int accent;
    private final int accent2;
    /** 0→1 hover ease — see {@link BookmarkTab}. */
    private float hoverEase = 0f;

    public SideTab(int panelRightX, int y, int w, int h, Component label,
                   boolean selected, int accent, int accent2, Runnable onClick) {
        super(panelRightX, y, w, h, label, b -> onClick.run(), DEFAULT_NARRATION);
        this.selected = selected;
        this.accent = accent;
        this.accent2 = accent2;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int left = getX();                       // flush with the panel's right edge
        int top = getY();
        int bottom = top + getHeight();
        boolean hovered = isHovered();
        if (Config.UI_ANIMATIONS.get()) {
            hoverEase += ((hovered ? 1f : 0f) - hoverEase) * 0.25f;
        } else {
            hoverEase = hovered ? 1f : 0f;
        }
        int right = selected ? left + getWidth()
            : left + getWidth() - 3 + Math.round(2f * hoverEase);
        int border = GuiPalette.PANEL_BORDER;

        int fill = selected ? GuiPalette.PANEL_BG : (hovered ? 0xFF262626 : GuiPalette.WELL_BG);
        g.fill(left, top, right, bottom, fill);
        // Top + bottom + outer borders.
        g.fill(left, top, right, top + 1, border);
        g.fill(left, bottom - 1, right, bottom, border);
        g.fill(right - 1, top, right, bottom, border);
        if (selected) {
            // Identity ribbon just inside the outer border — the "bookmark" — sweeping
            // primary(top)→secondary(bottom). fillGradient paints colorFrom at the top.
            g.fillGradient(right - 2, top + 1, right - 1, bottom - 1, accent, accent2);
            // Open the inner edge: paint over the panel's right border beside this tab so the
            // tab and body read as one continuous piece.
            g.fill(left - 1, top + 1, left, bottom - 1, GuiPalette.PANEL_BG);
        } else {
            // Closed inner border, resting on the panel's right edge.
            g.fill(left, top, left + 1, bottom, border);
        }
        Font font = Minecraft.getInstance().font;
        int textColor = selected ? GuiPalette.TITLE : (hovered ? 0xFFE8E8E8 : GuiPalette.LABEL);
        int textY = (top + bottom) / 2 - font.lineHeight / 2;
        // Clip the label to the tab's interior so a long word ("Suggestions") can't spill out.
        Component label = BookmarkTab.clip(font, getMessage(), (right - left) - 6);
        g.drawCenteredString(font, label, (left + right) / 2, textY, textColor);
    }
}
