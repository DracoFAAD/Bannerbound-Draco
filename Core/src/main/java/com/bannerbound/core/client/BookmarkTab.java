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
 * THE house tab style: a file-folder / bookmark tab protruding above a panel's top edge
 * (born on the Town Hall, extracted so every tabbed screen shares one look). The selected tab
 * is full-height, panel-coloured, carries a settlement-identity ribbon (primary→secondary
 * sweep) along its top, and opens its bottom border into the panel so the two read as one
 * piece. Unselected tabs sit 3px lower, darker, with a closed bottom border resting on the
 * panel edge, and glide 2px toward it on hover (instant when {@link Config#UI_ANIMATIONS} off).
 *
 * <p>Tabs are NOT buttons: don't build tab strips out of PolishButtons with the active one
 * disabled. Use {@link #addRow} for the standard evenly-split strip, or the constructor
 * directly for bespoke layouts. Remember to shift the panel down by {@link #HEIGHT} (center
 * panel + strip as one block) so the protruding tabs stay on-screen — see TownHallScreen's
 * {@code panelY} math.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class BookmarkTab extends Button {
    /** Standard strip height. Panels with a tab row shift down by this much. */
    public static final int HEIGHT = 16;
    private static final int EDGE_PAD = 6;
    private static final int GAP = 3;

    private final boolean selected;
    private final int accent;
    private final int accent2;
    private final int panelTopY;
    /** 0→1 hover ease — per-frame exponential approach (see class doc). */
    private float hoverEase = 0f;

    public BookmarkTab(int x, int y, int w, int h, int panelTopY, Component label,
                       boolean selected, int accent, int accent2, Runnable onClick) {
        super(x, y, w, h, label, b -> onClick.run(), DEFAULT_NARRATION);
        this.selected = selected;
        this.accent = accent;
        this.accent2 = accent2;
        this.panelTopY = panelTopY;
    }

    /** Standard strip: {@code labels} split evenly across the panel's top edge (the last tab
     *  absorbs integer-division rounding), each wired to {@code onSelect(index)}. Accents come
     *  from the screen's identity list ({@code primaryAccent()} / second color, or pass the
     *  same value twice for a flat ribbon). Feed each created tab to {@code add}
     *  (normally {@code this::addRenderableWidget}). */
    public static void addRow(java.util.function.Consumer<BookmarkTab> add,
                              int panelX, int panelWidth, int panelY,
                              java.util.List<Component> labels, int activeIndex,
                              int accent, int accent2,
                              java.util.function.IntConsumer onSelect) {
        int count = labels.size();
        if (count == 0) return;
        int tabW = (panelWidth - 2 * EDGE_PAD - (count - 1) * GAP) / count;
        int y = panelY - HEIGHT;
        for (int i = 0; i < count; i++) {
            final int index = i;
            int x = panelX + EDGE_PAD + i * (tabW + GAP);
            int w = (i == count - 1) ? (panelX + panelWidth - EDGE_PAD) - x : tabW;
            add.accept(new BookmarkTab(x, y, w, HEIGHT, panelY, labels.get(i),
                activeIndex == i, accent, accent2, () -> onSelect.accept(index)));
        }
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int w = getWidth();
        int border = GuiPalette.PANEL_BORDER;
        boolean hovered = isHovered();
        if (Config.UI_ANIMATIONS.get()) {
            hoverEase += ((hovered ? 1f : 0f) - hoverEase) * 0.25f;
        } else {
            hoverEase = hovered ? 1f : 0f;
        }
        int top = selected ? getY() : getY() + 3 - Math.round(2f * hoverEase);
        int bottom = panelTopY;

        int fill = selected ? GuiPalette.PANEL_BG : (hovered ? 0xFF262626 : GuiPalette.WELL_BG);
        g.fill(x, top, x + w, bottom, fill);
        // Top + side borders.
        g.fill(x, top, x + w, top + 1, border);
        g.fill(x, top, x + 1, bottom, border);
        g.fill(x + w - 1, top, x + w, bottom, border);
        if (selected) {
            // Identity ribbon under the top border — the "bookmark" — sweeping
            // primary→secondary across its width.
            PolishedScreen.drawHorizontalGradient(g, x + 1, top + 1, w - 2, 1, accent, accent2);
            // Open the bottom: paint over the panel's top border beneath this tab so the tab
            // and body read as one continuous piece.
            g.fill(x + 1, bottom, x + w - 1, bottom + 1, GuiPalette.PANEL_BG);
        } else {
            // Closed bottom border, resting on the panel's top edge.
            g.fill(x, bottom - 1, x + w, bottom, border);
        }
        Font font = Minecraft.getInstance().font;
        int textColor = selected ? GuiPalette.TITLE : (hovered ? 0xFFE8E8E8 : GuiPalette.LABEL);
        int textY = (top + bottom) / 2 - font.lineHeight / 2;
        g.drawCenteredString(font, clip(font, getMessage(), w - 6), x + w / 2, textY, textColor);
    }

    /** Ellipsis-clips a label to {@code maxWidth} so long words can't spill out of the tab. */
    static Component clip(Font font, Component c, int maxWidth) {
        String s = c.getString();
        if (font.width(s) <= maxWidth) return c;
        return Component.literal(font.plainSubstrByWidth(s, maxWidth - font.width("..")) + "..")
            .withStyle(c.getStyle());
    }
}
