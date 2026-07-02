package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * <b>Preview-only</b> Town Hall reskin in the WorldBox "carved stone slab" idiom — a study in
 * era-specific GUI for the Ancient era. The chrome (panel, troughs, buttons) is drawn from a
 * handful of 64×/32×/16× <em>nine-slice</em> sprites under {@code textures/gui/sprites/ancient/}
 * (see the matching {@code .png.mcmeta} files) rather than baked full-panel PNGs, so the same
 * olive-stone slab stretches to any size.
 *
 * <p>This screen is <b>not</b> wired into the live Town Hall — it is opened only by
 * {@code /bannerbound gui ancient} so the look can be evaluated in isolation. Buttons are visual
 * (click feedback only) except <i>Cancel</i>/Esc which close. Numbers are read live from
 * {@link ClientPopulationState} when a settlement is loaded, otherwise sample values are shown so
 * the layout reads correctly anywhere.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class AncientWorldBoxScreen extends PolishedScreen {

    // ── Nine-slice sprites ───────────────────────────────────────────────────────────────
    private static final ResourceLocation PANEL        = spr("panel");
    private static final ResourceLocation INSET        = spr("inset");
    private static final ResourceLocation BUTTON       = spr("button");
    private static final ResourceLocation BUTTON_HOVER = spr("button_hover");
    private static final ResourceLocation FOOD_ICON =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/food_antiquity.png");
    private static final ResourceLocation CULTURE_ICON =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/culture_antiquity.png");

    // ── WorldBox olive-stone palette (text inks) ─────────────────────────────────────────
    private static final int GOLD      = 0xFFE6B24A;   // title / headings
    private static final int CREAM     = 0xFFF0E8CC;   // button + tab labels
    private static final int INK       = 0xFF2A331E;   // body text on the lit stone face
    private static final int SUBTITLE  = 0xFFD8E0BE;   // era/rank subtitle (light, w/ shadow)
    private static final int DANGER    = 0xFFD8602E;   // disband label
    private static final int FOOD_HI   = 0xFFE6A445, FOOD_LO  = 0xFF9A5A23;
    private static final int CULT_HI   = 0xFFB874D0, CULT_LO  = 0xFF5A2C72;

    // ── Geometry ─────────────────────────────────────────────────────────────────────────
    private static final int PANEL_W = 240, PANEL_H = 360;
    private static final int TAB_W = 100, TAB_H = 18;
    private static final int BTN_W = 192, BTN_H = 18;
    private static final int BTN_PITCH = 24;
    private static final int TROUGH_X = 24, TROUGH_W = PANEL_W - 48;

    private enum TopTab { MAIN, STATUSES }

    private record Hotspot(int x, int y, int w, int h, Runnable action) {
        boolean hit(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private record ActionBtn(int x, int y, String label, boolean danger, boolean disabled,
                             Runnable action) { }

    private final String settlementName;
    private int panelX, panelY;
    private TopTab tab = TopTab.MAIN;
    private final List<Hotspot> hotspots = new ArrayList<>();
    private final List<ActionBtn> actions = new ArrayList<>();

    public AncientWorldBoxScreen() {
        super(Component.translatable("bannerbound.townhall.menu.title"));
        // Preview-only: a readable sample name (the live id is a raw UUID, not a display name).
        this.settlementName = "Stonehearth";
    }

    @Override
    protected void init() {
        this.panelX = (this.width - PANEL_W) / 2;
        this.panelY = (this.height - PANEL_H) / 2;
        hotspots.clear();
        actions.clear();

        hotspots.add(new Hotspot(panelX + 19, panelY + 92, TAB_W, TAB_H, () -> tab = TopTab.MAIN));
        hotspots.add(new Hotspot(panelX + 121, panelY + 92, TAB_W, TAB_H, () -> tab = TopTab.STATUSES));

        int bx = panelX + (PANEL_W - BTN_W) / 2, by = panelY + 214;
        addAction(bx, by,                 "Research",          false, false, this::clickFeedback);
        addAction(bx, by + BTN_PITCH,     "Citizens",          false, false, this::clickFeedback);
        addAction(bx, by + BTN_PITCH * 2, "Registration Tablet · 3 / 5", false, false, this::clickFeedback);
        addAction(bx, by + BTN_PITCH * 3, "Expand Territory",  false, false, this::clickFeedback);
        addAction(bx, by + BTN_PITCH * 4, "Disband Settlement", true,  false, this::clickFeedback);
        addAction(bx, by + BTN_PITCH * 5, "Cancel",            false, false, this::onClose);
    }

    private void clickFeedback() { /* preview: visual only */ }

    /** Skip the vanilla blurred menu background (it reads as a tiled, blocky smear behind the
     *  panel) — we paint a clean flat dim in {@link #renderPolishedBackdrop} instead. */
    @Override
    protected boolean drawsDimmedBackground() {
        return false;
    }

    private void addAction(int x, int y, String label, boolean danger, boolean disabled,
                           Runnable action) {
        actions.add(new ActionBtn(x, y, label, danger, disabled, action));
        if (!disabled) hotspots.add(new Hotspot(x, y, BTN_W, BTN_H, action));
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Clean flat dim over the live world (we skipped the vanilla blur in drawsDimmedBackground).
        g.fill(0, 0, this.width, this.height, 0xC0121512);

        // The stone slab.
        g.blitSprite(PANEL, panelX, panelY, PANEL_W, PANEL_H);

        int cx = panelX + PANEL_W / 2;

        // Title trough + settlement name (gold) + era subtitle.
        g.blitSprite(INSET, panelX + 18, panelY + 16, PANEL_W - 36, 34);
        drawScaled(g, settlementName, cx, panelY + 23, 1.6f, GOLD);
        centeredShadow(g, "Ancient Era · "
            + Component.translatable(ClientPopulationState.getTitleKey()).getString(),
            cx, panelY + 56, SUBTITLE);

        // Folio tabs (raised slabs).
        drawTab(g, panelX + 19,  panelY + 92, "Main",     tab == TopTab.MAIN,     mouseX, mouseY);
        drawTab(g, panelX + 121, panelY + 92, "Statuses", tab == TopTab.STATUSES, mouseX, mouseY);

        // Population + two resource gauges.
        int pop = sampleInt(ClientPopulationState.getPopulation(), 6);
        int popMax = sampleInt(ClientPopulationState.getPopulationMax(), 8);
        g.drawString(this.font, "Population · " + pop + " / " + popMax,
            panelX + TROUGH_X, panelY + 122, INK, false);

        double foodCap = pick(ClientPopulationState.getFoodCap(), ClientPopulationState.getNextFoodCost(), 60);
        double cultCap = pick(ClientPopulationState.getCultureCap(), ClientPopulationState.getNextCultureCost(), 40);
        drawGauge(g, panelY + 136, FOOD_ICON, "Food",
            sampleD(ClientPopulationState.getFoodStored(), 38), foodCap,
            GOLD, FOOD_HI, FOOD_LO);
        drawGauge(g, panelY + 168, CULTURE_ICON, "Culture",
            sampleD(ClientPopulationState.getCultureStored(), 14), cultCap,
            CULT_HI, CULT_HI, CULT_LO);

        // Carved action buttons.
        for (ActionBtn b : actions) {
            boolean hover = !b.disabled() && mouseX >= b.x() && mouseX < b.x() + BTN_W
                         && mouseY >= b.y() && mouseY < b.y() + BTN_H;
            g.blitSprite(hover ? BUTTON_HOVER : BUTTON, b.x(), b.y(), BTN_W, BTN_H);
            int textColor = b.danger() ? DANGER : CREAM;
            centeredShadow(g, b.label(), b.x() + BTN_W / 2, b.y() + (BTN_H - 8) / 2, textColor);
        }
    }

    private void drawTab(GuiGraphics g, int x, int y, String label, boolean active,
                         int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + TAB_W && mouseY >= y && mouseY < y + TAB_H;
        g.blitSprite(active || hover ? BUTTON_HOVER : BUTTON, x, y, TAB_W, TAB_H);
        centeredShadow(g, label, x + TAB_W / 2, y + (TAB_H - 8) / 2, active ? GOLD : CREAM);
    }

    private void drawGauge(GuiGraphics g, int y, ResourceLocation icon, String label,
                           double value, double max, int labelColor, int fillHi, int fillLo) {
        int x = panelX + TROUGH_X;
        g.blit(icon, x, y - 2, 12, 12, 0f, 0f, 32, 32, 32, 32);
        g.drawString(this.font, label, x + 16, y, labelColor, false);
        // Readout on the label row, right-aligned over the empty stone (never over the bar).
        String readout = value > 0 ? trim(value) + " / " + trim(max) : "—";
        g.drawString(this.font, readout, x + TROUGH_W - this.font.width(readout), y, INK, false);
        // Recessed trough + painted pigment fill.
        int troughY = y + 12;
        g.blitSprite(INSET, x, troughY, TROUGH_W, 11);
        double pct = max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
        int fillW = (int) Math.round((TROUGH_W - 6) * pct);
        if (fillW > 0) g.fillGradient(x + 3, troughY + 3, x + 3 + fillW, troughY + 9, fillHi, fillLo);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────
    private void centeredShadow(GuiGraphics g, String text, int cx, int y, int textColor) {
        g.drawString(this.font, text, cx - this.font.width(text) / 2, y, textColor, true);
    }

    private void drawScaled(GuiGraphics g, String text, float anchorX, float anchorY,
                            float scale, int textColor) {
        g.pose().pushPose();
        g.pose().translate(anchorX - this.font.width(text) * scale / 2f, anchorY, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(this.font, text, 0, 0, textColor, false);
        g.pose().popPose();
    }

    private static int sampleInt(int live, int fallback) { return live > 0 ? live : fallback; }
    private static double sampleD(double live, double fallback) { return live > 0.001 ? live : fallback; }
    private static double pick(double a, double b, double fallback) {
        if (a > 0.01) return a;
        if (b > 0.01) return b;
        return fallback;
    }

    private static String trim(double v) {
        if (Math.abs(v - Math.round(v)) < 0.05) return String.valueOf(Math.round(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static ResourceLocation spr(String name) {
        return ResourceLocation.fromNamespaceAndPath("bannerbound", "ancient/" + name);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Hotspot h : hotspots) {
                if (h.hit(mx, my)) {
                    if (this.minecraft != null && this.minecraft.player != null) {
                        this.minecraft.player.playSound(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1f, 1f);
                    }
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
