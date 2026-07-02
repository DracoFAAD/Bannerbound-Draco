package com.bannerbound.core.client;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Home;
import com.bannerbound.core.network.AssignCitizenToHomePayload;
import com.bannerbound.core.network.OpenHouseStatusPayload;
import com.bannerbound.core.network.RequestHomeCitizenListPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Home status panel — opened by shift-right-clicking inside a home with the Housing Orders rod.
 * Shows the home's validation status (colour-coded), an occupancy bar (residents / beds), the
 * appeal score + beauty tier (tier-coloured), the <b>crowdedness</b> (interior space per bed), and
 * the residents — a scrolling list with a per-resident unassign (✘) control plus an Assign button
 * onto the resident picker. Snapshot of server state at open time; after an inline unassign the
 * server re-sends {@link OpenHouseStatusPayload} and {@link #refresh} updates this panel in place.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class HouseStatusScreen extends PolishedScreen {
    private static final int PANEL_WIDTH = 244;
    private static final int PANEL_HEIGHT = 340;
    /** Resident row pitch — matches the old static list spacing. */
    private static final int ROW_HEIGHT = 12;
    private static final int SCROLL_TRACK_WIDTH = 4;

    // Shared palette — matches the SelectionRenderer / appeal colour language.
    private static final int GREEN = 0xFF7BCB6F;
    private static final int YELLOW = 0xFFE9D24A;
    private static final int RED = 0xFFE57761;
    private static final int RED_HOVER = 0xFFFF9A86;
    private static final int AQUA = 0xFF6FC7CB;
    private static final int LABEL = 0xFFB8B8B8;
    private static final int WHITE = 0xFFFFFFFF;

    private final UUID homeId;
    // Snapshot fields — non-final so refresh() can swap in a fresh server snapshot in place.
    private Component statusText;
    private Home.Status status;
    private int bedCount;
    private int residentCount;
    private double appealScore;
    private Component beautyText;
    private int beautyTier;
    private int interiorVolume;
    private int homeHappiness;
    private List<OpenHouseStatusPayload.DemandView> demands;
    private List<Component> residentNames;
    private List<UUID> residentIds;

    /** Top-resident index shown in the scroll window. Clamped to {@code [0, residentCount - rows]}. */
    private int scrollOffset = 0;
    /** Recomputed every frame during the panel draw (the residents start Y is dynamic — it sits
     *  below the variable-length demands list), then read by the scroll/click handlers. */
    private int maxVisibleRows = 1;

    /** Unassign (✘) hit-targets for the visible rows this frame: {residentIndex, x0, y0, x1, y1}.
     *  Rebuilt during the panel draw, consulted in {@link #mouseClicked}. */
    private final java.util.List<int[]> unassignHits = new java.util.ArrayList<>(8);

    /** Tooltip lines for the row/control under the cursor this frame, or null. Set during the panel
     *  draw, rendered on top in {@link #render}. */
    private List<Component> hoverTooltip;

    public HouseStatusScreen(OpenHouseStatusPayload payload) {
        super(Component.translatable("bannerbound.house.screen.title"));
        this.homeId = payload.homeId();
        apply(payload);
    }

    /** Swap in a fresh server snapshot for an open panel (used after an inline unassign) and re-lay
     *  out, preserving the scroll position where the list is still long enough to need it. */
    public void refresh(OpenHouseStatusPayload payload) {
        apply(payload);
        clampScroll();
        this.rebuildWidgets();
    }

    private void apply(OpenHouseStatusPayload payload) {
        this.statusText = payload.statusText();
        this.status = Home.Status.fromOrdinalOrDefault(payload.statusOrdinal());
        this.bedCount = payload.bedCount();
        this.residentCount = payload.residentCount();
        this.appealScore = payload.appealScore();
        this.beautyText = payload.beautyText();
        this.beautyTier = payload.beautyTier();
        this.interiorVolume = payload.interiorVolume();
        this.homeHappiness = payload.homeHappiness();
        this.demands = payload.demands();
        this.residentNames = payload.residentNames();
        this.residentIds = payload.residentIds();
    }

    private int maxScroll() {
        return Math.max(0, residentNames.size() - maxVisibleRows);
    }

    private void clampScroll() {
        if (scrollOffset < 0) scrollOffset = 0;
        int max = maxScroll();
        if (scrollOffset > max) scrollOffset = max;
    }

    @Override
    protected void init() {
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = (this.height - PANEL_HEIGHT) / 2;
        final int btnWidth = PANEL_WIDTH - 24;
        final int rowX = panelX + 14;
        final int innerRight = panelX + PANEL_WIDTH - 14;

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            // Panel chrome: body + header strip + identity border.
            drawIdentityPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, identityAccents);
            graphics.fill(panelX + 1, panelY + 1, panelX + PANEL_WIDTH - 1, panelY + 24, 0xFF1B1B1B);
            graphics.drawCenteredString(this.font,
                Component.translatable("bannerbound.house.screen.title").withStyle(ChatFormatting.WHITE),
                panelX + PANEL_WIDTH / 2, panelY + 8, GuiPalette.TITLE);

            int y = panelY + 32;

            // ── Status: label + a colour-coded, wrapped message. ──────────────────────────────
            int statusColor = switch (status) {
                case VALID -> GREEN;
                case NO_BEDS, UNMARKED -> YELLOW;
                default -> RED; // BROKEN_*
            };
            graphics.drawString(this.font,
                Component.translatable("bannerbound.house.screen.status_label"), rowX, y, LABEL, false);
            int valueX = rowX + 64;
            List<FormattedCharSequence> statusLines = this.font.split(statusText, innerRight - valueX);
            int ly = y;
            for (FormattedCharSequence line : statusLines) {
                graphics.drawString(this.font, line, valueX, ly, statusColor, false);
                ly += this.font.lineHeight + 1;
            }
            y = Math.max(y + this.font.lineHeight + 2, ly + 4);

            // ── Home happiness: the headline value (appeal + demands) + a bar. ────────────────
            graphics.drawString(this.font,
                Component.translatable("bannerbound.house.screen.happiness_label"), rowX, y, LABEL, false);
            int hhColor = homeHappiness >= 70 ? GREEN : homeHappiness >= 45 ? YELLOW : RED;
            graphics.drawString(this.font, Component.literal(homeHappiness + " / 100"), valueX, y, hhColor, false);
            y += 13;
            drawValueBar(graphics, rowX, y, innerRight - rowX, 6, homeHappiness / 100f, hhColor);
            y += 12;

            // ── Occupancy: "N / M" + a segmented fill bar. ────────────────────────────────────
            graphics.drawString(this.font,
                Component.translatable("bannerbound.house.screen.beds_label"), rowX, y, LABEL, false);
            graphics.drawString(this.font, Component.literal(residentCount + " / " + bedCount),
                valueX, y, WHITE, false);
            y += 13;
            drawOccupancyBar(graphics, rowX, y, innerRight - rowX, 6);
            y += 12;

            // ── Appeal: score (signed colour) + beauty-tier pill. ─────────────────────────────
            graphics.drawString(this.font,
                Component.translatable("bannerbound.house.screen.appeal_label"), rowX, y, LABEL, false);
            int appealColor = appealScore >= 5.0 ? GREEN : appealScore >= -1.0 ? YELLOW : RED;
            graphics.drawString(this.font, Component.literal(String.format("%.1f", appealScore)),
                valueX, y, appealColor, false);
            int beautyColor = beautyTier > 0 ? AQUA : beautyTier < 0 ? RED : LABEL;
            graphics.drawString(this.font, beautyText, valueX + 34, y, beautyColor, false);
            y += 15;

            // ── Living space (crowdedness): tier word + "(N blocks each)". Hover explains it. ──
            graphics.drawString(this.font,
                Component.translatable("bannerbound.house.screen.space_label"), rowX, y, LABEL, false);
            if (bedCount > 0 && interiorVolume > 0) {
                int perBed = interiorVolume / bedCount;
                String tierKey;
                int tierColor;
                if (perBed >= 25) { tierKey = "spacious"; tierColor = GREEN; }
                else if (perBed >= 12) { tierKey = "comfortable"; tierColor = GREEN; }
                else if (perBed >= 6) { tierKey = "snug"; tierColor = YELLOW; }
                else { tierKey = "cramped"; tierColor = RED; }
                Component tier = Component.translatable("bannerbound.house.crowd." + tierKey);
                graphics.drawString(this.font, tier, valueX, y, tierColor, false);
                graphics.drawString(this.font,
                    Component.translatable("bannerbound.house.screen.per_bed", perBed)
                        .withStyle(ChatFormatting.DARK_GRAY),
                    valueX + this.font.width(tier) + 6, y, 0xFF808080, false);
                if (mouseX >= rowX && mouseX <= innerRight && mouseY >= y - 1 && mouseY <= y + 9) {
                    java.util.List<Component> tip = new java.util.ArrayList<>(4);
                    tip.add(Component.translatable("bannerbound.house.space.tip.title").withStyle(ChatFormatting.WHITE));
                    tip.add(Component.translatable("bannerbound.house.space.tip.value", interiorVolume, bedCount, perBed)
                        .withStyle(ChatFormatting.GRAY));
                    tip.add(Component.translatable("bannerbound.house.space.tip.explain").withStyle(ChatFormatting.DARK_GRAY));
                    this.hoverTooltip = tip;
                }
            } else {
                graphics.drawString(this.font, Component.literal("—"), valueX, y, 0xFF808080, false);
            }
            y += 17;

            // ── Demands checklist: one row per active (researched) demand, ✓ green / ✗ red. ───
            if (!demands.isEmpty()) {
                graphics.fill(rowX, y - 4, innerRight, y - 3, 0xFF2A2A2A);
                graphics.drawString(this.font,
                    Component.translatable("bannerbound.house.screen.demands_label").withStyle(ChatFormatting.GRAY),
                    rowX, y, LABEL, false);
                y += 13;
                for (OpenHouseStatusPayload.DemandView d : demands) {
                    int c = d.met() ? GREEN : RED;
                    graphics.drawString(this.font, Component.literal(d.met() ? "✔" : "✘"),
                        rowX + 4, y, c, false);
                    graphics.drawString(this.font,
                        Component.translatable("bannerbound.house.demand." + d.suffix()), rowX + 18, y, c, false);
                    // Hover → a tooltip explaining how this need is met (rendered on top in render()).
                    if (mouseX >= rowX && mouseX <= innerRight && mouseY >= y - 1 && mouseY <= y + 9) {
                        java.util.List<Component> tip = new java.util.ArrayList<>(3);
                        tip.add(Component.translatable("bannerbound.house.demand." + d.suffix())
                            .withStyle(d.met() ? ChatFormatting.GREEN : ChatFormatting.RED));
                        tip.add(Component.translatable("bannerbound.house.demand." + (d.met() ? "met" : "unmet"))
                            .withStyle(d.met() ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
                        tip.add(Component.translatable("bannerbound.house.demand." + d.suffix() + ".tip")
                            .withStyle(ChatFormatting.GRAY));
                        this.hoverTooltip = tip;
                    }
                    y += 11;
                    if (y > panelY + PANEL_HEIGHT - 100) break;
                }
                y += 4;
            }

            // ── Residents list (scrolling window with a per-row unassign control). ────────────
            graphics.fill(rowX, y - 4, innerRight, y - 3, 0xFF2A2A2A);
            graphics.drawString(this.font,
                Component.translatable("bannerbound.house.screen.residents_label").withStyle(ChatFormatting.GRAY),
                rowX, y, LABEL, false);
            y += 13;

            this.unassignHits.clear();
            if (residentNames.isEmpty()) {
                graphics.drawString(this.font,
                    Component.translatable("bannerbound.house.screen.no_residents").withStyle(ChatFormatting.DARK_GRAY),
                    rowX + 10, y, 0xFF888888, false);
                this.maxVisibleRows = 1;
            } else {
                int listTop = y;
                int listBottom = panelY + PANEL_HEIGHT - 56; // stay clear of the Assign button
                int rows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
                this.maxVisibleRows = rows;
                clampScroll();

                boolean overflow = residentNames.size() > rows;
                int trackX = panelX + PANEL_WIDTH - 8;
                // The unassign glyph column; nudged left of the scroll track when one is shown.
                int unX = innerRight - (overflow ? 4 : 0) - 7;

                int first = scrollOffset;
                int last = Math.min(residentNames.size(), first + rows);
                for (int i = first; i < last; i++) {
                    int rowY = listTop + (i - first) * ROW_HEIGHT;
                    graphics.drawString(this.font, Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY),
                        rowX + 4, rowY, 0xFF808080, false);
                    graphics.drawString(this.font, residentNames.get(i), rowX + 14, rowY, WHITE, false);

                    // Unassign control: a red ✘ at the row's right edge, brightening on hover.
                    boolean hot = mouseX >= unX - 3 && mouseX <= unX + 9
                        && mouseY >= rowY - 1 && mouseY <= rowY + 9;
                    graphics.drawString(this.font, Component.literal("✘"), unX, rowY,
                        hot ? RED_HOVER : RED, false);
                    this.unassignHits.add(new int[]{i, unX - 3, rowY - 1, unX + 9, rowY + 9});
                    if (hot) {
                        java.util.List<Component> tip = new java.util.ArrayList<>(1);
                        tip.add(Component.translatable("bannerbound.house.screen.unassign_tip",
                            residentNames.get(i)).withStyle(ChatFormatting.RED));
                        this.hoverTooltip = tip;
                    }
                }

                // Scroll-track + thumb on the right edge, only when the list overflows the window.
                if (overflow) {
                    int trackH = rows * ROW_HEIGHT - 2;
                    graphics.fill(trackX, listTop, trackX + SCROLL_TRACK_WIDTH, listTop + trackH, 0xFF2A2A2A);
                    int max = maxScroll();
                    int thumbH = Math.max(8, trackH * rows / residentNames.size());
                    int thumbY = listTop + (max <= 0 ? 0 : (trackH - thumbH) * scrollOffset / max);
                    graphics.fill(trackX, thumbY, trackX + SCROLL_TRACK_WIDTH, thumbY + thumbH, 0xFFB0B0B0);
                }
            }
        });

        // Assign — opens the resident picker (server replies with HomeCitizenListPayload).
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.house.screen.assign"),
            btn -> PacketDistributor.sendToServer(new RequestHomeCitizenListPayload(homeId)))
            .bounds(panelX + 12, panelY + PANEL_HEIGHT - 52, btnWidth, 20)
            .accent(primaryAccent())
            .build());

        // Cancel / close.
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.cancel"),
            btn -> this.onClose())
            .bounds(panelX + 12, panelY + PANEL_HEIGHT - 28, btnWidth, 20)
            .accent(primaryAccent())
            .build());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (int[] hit : this.unassignHits) {
                if (mouseX >= hit[1] && mouseX <= hit[3] && mouseY >= hit[2] && mouseY <= hit[4]) {
                    int idx = hit[0];
                    if (idx >= 0 && idx < residentIds.size()) {
                        PacketDistributor.sendToServer(new AssignCitizenToHomePayload(
                            homeId, residentIds.get(idx), false, true));
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 1 row per wheel notch. Wheel toward the user (negative scrollY) scrolls down the list.
        if (maxScroll() > 0) {
            int prev = scrollOffset;
            scrollOffset -= (int) Math.signum(scrollY);
            clampScroll();
            return scrollOffset != prev || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.hoverTooltip = null;                 // recomputed by the panel draw inside super.render
        super.render(graphics, mouseX, mouseY, partialTick);
        if (this.hoverTooltip != null) {
            graphics.renderComponentTooltip(this.font, this.hoverTooltip, mouseX, mouseY);
        }
    }

    /** A simple horizontal value bar: dark track with a {@code frac}-wide fill in {@code color}. */
    private void drawValueBar(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, int w, int h,
                              float frac, int color) {
        graphics.fill(x, y, x + w, y + h, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF2C2C2C);
        int fw = Math.round((w - 2) * Math.max(0f, Math.min(1f, frac)));
        if (fw > 0) graphics.fill(x + 1, y + 1, x + 1 + fw, y + h - 1, color);
    }

    /** Horizontal bar split into {@code bedCount} cells, the first {@code residentCount} filled.
     *  Falls back to a plain proportional fill when there are too many beds to show distinct cells. */
    private void drawOccupancyBar(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF2C2C2C);
        if (bedCount <= 0) return;
        int filled = Math.max(0, Math.min(residentCount, bedCount));
        if (bedCount <= 16) {
            float cell = (w - 2) / (float) bedCount;
            for (int i = 0; i < bedCount; i++) {
                int cx0 = x + 1 + Math.round(i * cell);
                int cx1 = x + 1 + Math.round((i + 1) * cell) - 1;
                graphics.fill(cx0, y + 1, Math.max(cx0 + 1, cx1), y + h - 1, i < filled ? AQUA : 0xFF3A3A3A);
            }
        } else {
            int fw = Math.round((w - 2) * (filled / (float) bedCount));
            if (fw > 0) graphics.fill(x + 1, y + 1, x + 1 + fw, y + h - 1, AQUA);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
