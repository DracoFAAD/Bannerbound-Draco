package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.Config;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Base class for the mod's panel screens: owns the open-settle polish animation (the panel zooms
 * 0.96→1 with a 10px upward drift over ~160ms ease-out) with the dim/blur background rendered
 * OUTSIDE the pose so it never zooms along. New menus get the polish for free by extending this
 * instead of {@link Screen}; {@link Config#UI_ANIMATIONS} off reverts every screen to instant
 * static rendering at once.
 *
 * <p>Subclasses that need custom drawing override the two hooks instead of {@link #render}:
 * {@link #renderPolishedBackdrop} draws BEFORE the widgets (panel fills, chrome) and
 * {@link #renderPolishedExtras} AFTER them (overlays, drag ghosts) — both ride the settle pose.
 * Vanilla widget tooltips are deferred by the engine until after {@code render()}, so they never
 * scale. Screens with bespoke camera/animation needs (TownHallScreen, ResearchScreen) keep their
 * own render overrides; world-anchored screens (ExpandTerritoryScreen) must NOT extend this — the
 * settle pose would misalign their overlay from the world behind it.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public abstract class PolishedScreen extends Screen {
    private final long openedAtMs = net.minecraft.Util.getMillis();

    /** Banner-identity accents (ARGB, most-present dye first) of the settlement the player is
     *  standing in when the screen opens — empty on unclaimed ground. Settlement-scoped screens
     *  feed these into {@link #drawIdentityPanel}; subclasses whose payload carries a color
     *  ordinal may reassign via {@link GuiPalette#identityAccents(int)} for a firmer source. */
    protected java.util.List<Integer> identityAccents = GuiPalette.localIdentityAccents();

    protected PolishedScreen(Component title) {
        super(title);
    }

    /** First identity accent, or the neutral border color outside any settlement. The standard
     *  tint for headers/selection in settlement screens. */
    protected int primaryAccent() {
        return GuiPalette.primary(identityAccents);
    }

    /** Second identity accent (the primary again on single-color banners) — the far end of
     *  tab-ribbon primary→secondary sweeps. */
    protected int secondaryAccent() {
        return identityAccents.size() > 1 ? identityAccents.get(1) : primaryAccent();
    }

    /** Override to {@code false} for transparent cinematic overlays (e.g. the tribe-vote reveal)
     *  that must show the live world — the settle animation still applies, only the dim/blur
     *  background pass is skipped. */
    protected boolean drawsDimmedBackground() {
        return true;
    }

    // ─── Text wrapping — THE house rule for panel prose ─────────────────────────────────────────
    // Long translatable lines have repeatedly overflowed panels (outpost status, stocker board
    // empty-state, research tooltips...) because drawString never wraps. Any free-prose line in a
    // panel must go through one of these instead of a raw drawString.

    /** Word-wraps {@code text} to {@code maxWidth} and draws it line by line at (x, y). Returns
     *  the y BELOW the last drawn line, so callers can stack subsequent rows. */
    public static int drawWrapped(GuiGraphics graphics, net.minecraft.client.gui.Font font,
                                  Component text, int x, int y, int maxWidth, int color) {
        for (net.minecraft.util.FormattedCharSequence line : font.split(text, maxWidth)) {
            graphics.drawString(font, line, x, y, color, false);
            y += font.lineHeight + 1;
        }
        return y;
    }

    /** How many lines {@link #drawWrapped} will use — for sizing a panel before drawing. */
    public static int wrappedLineCount(net.minecraft.client.gui.Font font, Component text, int maxWidth) {
        return Math.max(1, font.split(text, maxWidth).size());
    }

    // ─── Identity-accent helpers (banner-driven faction colors in panel chrome) ────────────

    /** Per-channel lerp of two ARGB colors: t = 0 → {@code base}, t = 1 → {@code accent}. */
    public static int blendArgb(int base, int accent, float t) {
        int a = (int) net.minecraft.util.Mth.lerp(t, (base >>> 24) & 0xFF, (accent >>> 24) & 0xFF);
        int r = (int) net.minecraft.util.Mth.lerp(t, (base >>> 16) & 0xFF, (accent >>> 16) & 0xFF);
        int g = (int) net.minecraft.util.Mth.lerp(t, (base >>> 8) & 0xFF, (accent >>> 8) & 0xFF);
        int b = (int) net.minecraft.util.Mth.lerp(t, base & 0xFF, accent & 0xFF);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** N-stop identity gradient bar across ALL of a settlement's identity colors, in banner
     *  order — as many stops as the banner has colors (a one-color identity draws flat).
     *  THE standard treatment for settlement accent dividers. */
    public static void drawIdentityGradient(GuiGraphics graphics, int x, int y, int width,
                                            int height, java.util.List<Integer> argbColors) {
        if (argbColors.isEmpty()) return;
        if (argbColors.size() == 1) {
            graphics.fill(x, y, x + width, y + height, argbColors.get(0));
            return;
        }
        int spans = argbColors.size() - 1;
        int drawn = 0;
        for (int i = 0; i < spans; i++) {
            int spanWidth = (width - drawn) / (spans - i);
            drawHorizontalGradient(graphics, x + drawn, y, spanWidth, height,
                argbColors.get(i), argbColors.get(i + 1));
            drawn += spanWidth;
        }
    }

    /** Identity-gradient PANEL BORDER as ONE continuous vertical flow, top-down: the primary
     *  color sits at the panel's top (by the title) and the run descends through the identity
     *  list, the same height → same color on both sides (no per-face gradient, no clashing
     *  corners). The top edge is solid primary, the bottom edge solid last color, and the side
     *  edges carry the full N-stop run between them. */
    public static void drawIdentityBorder(GuiGraphics graphics, int x, int y, int width,
                                          int height, java.util.List<Integer> argbColors) {
        if (argbColors.isEmpty()) return;
        if (argbColors.size() == 1) {
            graphics.renderOutline(x, y, width, height, argbColors.get(0));
            return;
        }
        int spans = argbColors.size() - 1;
        int drawnHeight = 0;
        for (int i = 0; i < spans; i++) {
            int spanHeight = (height - drawnHeight) / (spans - i);
            int spanTop = y + drawnHeight; // building top-down
            int upper = argbColors.get(i);
            int lower = argbColors.get(i + 1);
            // fillGradient paints colorFrom at the TOP of the rect, so upper color first.
            graphics.fillGradient(x, spanTop, x + 1, spanTop + spanHeight, upper, lower);
            graphics.fillGradient(x + width - 1, spanTop, x + width, spanTop + spanHeight,
                upper, lower);
            drawnHeight += spanHeight;
        }
        graphics.fill(x, y, x + width, y + 1, argbColors.get(0));
        graphics.fill(x, y + height - 1, x + width, y + height,
            argbColors.get(argbColors.size() - 1));
    }

    /** THE standard settlement panel chrome (the Town Hall treatment, canonized): near-black
     *  fill, then either the neutral outline (no identity — unclaimed ground) or the banner
     *  identity worn as the border. One call replaces every screen's hand-rolled
     *  fill+renderOutline pair, so a red nation's panels read red everywhere at once. */
    public static void drawIdentityPanel(GuiGraphics graphics, int x, int y, int width,
                                         int height, java.util.List<Integer> accents) {
        graphics.fill(x, y, x + width, y + height, GuiPalette.PANEL_BG);
        if (accents.isEmpty()) {
            graphics.renderOutline(x, y, width, height, GuiPalette.PANEL_BORDER);
        } else {
            drawIdentityBorder(graphics, x, y, width, height, accents);
        }
    }

    /** Standard title-divider under a panel header: the identity gradient when the settlement
     *  has one, a plain border line otherwise. Pairs with {@link #drawIdentityPanel}. */
    public static void drawIdentityDivider(GuiGraphics graphics, int x, int y, int width,
                                           java.util.List<Integer> accents) {
        if (accents.isEmpty()) {
            graphics.fill(x, y, x + width, y + 1, GuiPalette.PANEL_BORDER);
        } else {
            drawIdentityGradient(graphics, x, y, width, 1, accents);
        }
    }

    /** Left-to-right gradient bar (vanilla {@code fillGradient} is vertical-only): drawn as
     *  16 lerped segments — plenty for the thin accent dividers this exists for. */
    public static void drawHorizontalGradient(GuiGraphics graphics, int x, int y, int width,
                                              int height, int from, int to) {
        final int segments = Math.min(16, Math.max(1, width));
        int drawn = 0;
        for (int i = 0; i < segments; i++) {
            int segWidth = (width - drawn) / (segments - i);
            int color = blendArgb(from, to, (i + 0.5f) / segments);
            graphics.fill(x + drawn, y, x + drawn + segWidth, y + height, color);
            drawn += segWidth;
        }
    }

    // ─── Opt-in auto-fit (the Town Hall treatment, generalized) ─────────────────────────────────
    // A fixed-size panel screen returns its dimensions from these two hooks and the render pass
    // scales the whole panel (centre-anchored) to fill the window — small windows / high GUI
    // scales shrink it instead of overflowing, large windows grow it like the Town Hall does.
    // Subclasses that opt in MUST remap their mouse-event coords through virtualX/virtualY at the
    // top of mouseClicked/mouseReleased/mouseScrolled (before super) so widget hit-tests align,
    // and pre-map any enableScissor bounds through scissorX/scissorY (scissor ignores the pose).

    /** Fixed panel width for auto-fit, or 0 (default) to disable fitting entirely. */
    protected int fitPanelWidth() {
        return 0;
    }

    /** Fixed panel height for auto-fit, or 0 (default) to disable fitting entirely. */
    protected int fitPanelHeight() {
        return 0;
    }

    /** The auto-fit scale for this frame (1 when fitting is disabled). Mirrors TownHallScreen. */
    protected final float fitScale() {
        int pw = fitPanelWidth();
        int ph = fitPanelHeight();
        if (pw <= 0 || ph <= 0) return 1f;
        float byH = (this.height * 0.82f) / ph;
        float byW = (this.width * 0.90f) / pw;
        return Math.max(0.5f, Math.min(Math.min(byH, byW), 2.5f));
    }

    /** Maps a screen-space mouse coord into panel-layout space (inverse of the fit pose). */
    protected final double virtualX(double screenX) {
        return (screenX - this.width / 2.0) / fitScale() + this.width / 2.0;
    }

    protected final double virtualY(double screenY) {
        return (screenY - this.height / 2.0) / fitScale() + this.height / 2.0;
    }

    /** Maps a panel-layout coord to the real screen coord the fit pose draws it at — REQUIRED for
     *  {@code enableScissor} bounds, which are raw screen-space and ignore the pose stack. */
    protected final int scissorX(double layoutX) {
        return (int) Math.round((layoutX - this.width / 2.0) * fitScale() + this.width / 2.0);
    }

    protected final int scissorY(double layoutY) {
        return (int) Math.round((layoutY - this.height / 2.0) * fitScale() + this.height / 2.0);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (drawsDimmedBackground()) {
            this.renderBackground(graphics, mouseX, mouseY, partialTick);
        }
        // Auto-fit pose (opt-in): scale the panel to the window, and hand widgets VIRTUAL mouse
        // coords so hover/render feedback matches where things are actually drawn.
        float fit = fitScale();
        boolean fitted = Math.abs(fit - 1f) > 0.001f;
        int tmx = fitted ? (int) Math.round(virtualX(mouseX)) : mouseX;
        int tmy = fitted ? (int) Math.round(virtualY(mouseY)) : mouseY;
        if (fitted) {
            float cx = this.width / 2f;
            float cy = this.height / 2f;
            graphics.pose().pushPose();
            graphics.pose().translate(cx, cy, 0);
            graphics.pose().scale(fit, fit, 1f);
            graphics.pose().translate(-cx, -cy, 0);
        }
        boolean animate = Config.UI_ANIMATIONS.get();
        float open = animate
            ? easeOutCubic(Math.min(1f, (net.minecraft.Util.getMillis() - openedAtMs) / 160f)) : 1f;
        boolean posed = animate && open < 1f;
        if (posed) {
            float scale = 0.96f + 0.04f * open;
            float cx = this.width / 2f;
            float cy = this.height / 2f;
            graphics.pose().pushPose();
            graphics.pose().translate(cx, cy, 0);
            graphics.pose().scale(scale, scale, 1f);
            graphics.pose().translate(-cx, -cy + (1f - open) * 10f, 0);
        }
        renderPolishedBackdrop(graphics, tmx, tmy, partialTick);
        for (net.minecraft.client.gui.components.Renderable renderable : this.renderables) {
            renderable.render(graphics, tmx, tmy, partialTick);
        }
        renderPolishedExtras(graphics, tmx, tmy, partialTick);
        if (posed) {
            graphics.pose().popPose();
        }
        if (fitted) {
            graphics.pose().popPose();
        }
    }

    /** Custom drawing BEFORE the widgets (panel fills, outlines, headers) — rides the settle pose. */
    protected void renderPolishedBackdrop(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /** Custom drawing AFTER the widgets (overlays, feedback pops) — rides the settle pose. */
    protected void renderPolishedExtras(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    protected static float easeOutCubic(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }
}
