package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import com.bannerbound.core.network.RecallCitizenPayload;
import com.bannerbound.core.network.SettlementCitizensListPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Roster screen opened from the Town Hall menu's "Citizens" button. Shows each citizen by name
 * with a health and stamina bar plus a per-row "Recall" button that teleports them next to the
 * town hall. Header shows summed totals (count, total stamina, total health) so the player can
 * eyeball the settlement's overall workforce status at a glance.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class SettlementCitizensScreen extends PolishedScreen {
    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 260;
    private static final int ROW_HEIGHT = 26;
    private static final int MAX_ROWS = 7;
    private static final int SCROLLBAR_WIDTH = 4;

    /** Index of the first visible roster row — the scroll offset, in rows. Survives the
     *  rebuildWidgets() relayout that a scroll triggers (it's the instance, not a fresh screen). */
    private int scrollRow = 0;

    private final List<SettlementCitizensListPayload.Entry> entries;
    /** Screen to return to on close (the Town Hall when opened from its Citizens button /
     *  Suggestions tab); {@code null} closes to the world as before. */
    @org.jetbrains.annotations.Nullable
    private final net.minecraft.client.gui.screens.Screen parent;

    public SettlementCitizensScreen(List<SettlementCitizensListPayload.Entry> entries) {
        this(entries, null);
    }

    public SettlementCitizensScreen(List<SettlementCitizensListPayload.Entry> entries,
            @org.jetbrains.annotations.Nullable net.minecraft.client.gui.screens.Screen parent) {
        super(Component.translatable("bannerbound.townhall.citizens.title"));
        this.entries = entries;
        this.parent = parent;
    }

    /** The back-target, so a server-pushed roster refresh (new instance) can carry it over. */
    @org.jetbrains.annotations.Nullable
    public net.minecraft.client.gui.screens.Screen parentScreen() {
        return parent;
    }

    @Override
    public void onClose() {
        // QoL: Cancel/Esc (and the close-on-Recall) return TO the town hall, not the world.
        if (this.minecraft != null && parent != null) {
            this.minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    protected void init() {
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = (this.height - PANEL_HEIGHT) / 2;
        final int btnWidth = PANEL_WIDTH - 24;
        // Clamp the scroll window so it can't run past either end of the roster.
        final int maxScroll = Math.max(0, entries.size() - MAX_ROWS);
        if (scrollRow < 0) scrollRow = 0;
        if (scrollRow > maxScroll) scrollRow = maxScroll;
        final int firstRow = scrollRow;

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            drawIdentityPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, identityAccents);
            drawIdentityDivider(graphics, panelX + 8, panelY + 28, PANEL_WIDTH - 16, identityAccents);

            graphics.drawCenteredString(this.font, this.getTitle(),
                panelX + PANEL_WIDTH / 2, panelY + 12, GuiPalette.TITLE);

            // Summary line: count + summed stamina / health across the whole roster. Citizens
            // whose entities aren't loaded report 0 for live stats so the summary leans low —
            // not a hard rule, just a visible nudge for the player to look in-world.
            int count = entries.size();
            int totalStamina = 0, totalStaminaMax = 0;
            float totalHealth = 0, totalHealthMax = 0;
            for (SettlementCitizensListPayload.Entry e : entries) {
                totalStamina += e.stamina();
                totalStaminaMax += e.maxStamina();
                totalHealth += e.health();
                totalHealthMax += e.maxHealth();
            }
            MutableComponent summary = Component.translatable(
                "bannerbound.townhall.citizens.summary",
                count,
                String.format("%d/%d", totalStamina, totalStaminaMax),
                String.format("%.0f/%.0f", totalHealth, totalHealthMax))
                .withStyle(ChatFormatting.GRAY);
            graphics.drawString(this.font, summary, panelX + 14, panelY + 36, 0xFFCCCCCC, false);

            // Per-row content (text + job icon only — the Recall button is a real widget below).
            // Windowed by the scroll offset: only MAX_ROWS rows starting at firstRow are drawn.
            int rowsTop = panelY + 52;
            for (int row = 0; row < MAX_ROWS && firstRow + row < entries.size(); row++) {
                int idx = firstRow + row;
                SettlementCitizensListPayload.Entry e = entries.get(idx);
                int y = rowsTop + row * ROW_HEIGHT;
                graphics.fill(panelX + 12, y, panelX + PANEL_WIDTH - 12, y + ROW_HEIGHT - 4, 0xFF181818);
                graphics.renderOutline(panelX + 12, y, PANEL_WIDTH - 24, ROW_HEIGHT - 4, 0xFF303030);

                // Job icon (the settlement's current tool-age tool for this job); none = unemployed
                // or an unloaded citizen. Text shifts right to clear the 16px icon when present. The
                // tool may be undiscovered, so bypass the unknown-item "?" swap — it's a UI glyph.
                int textX = panelX + 18;
                if (e.jobIconItemId() != 0) {
                    net.minecraft.world.item.Item icon =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(e.jobIconItemId());
                    UnknownItemHelper.setBypassUnknownSwap(true);
                    graphics.renderItem(new net.minecraft.world.item.ItemStack(icon), panelX + 16, y + 3);
                    UnknownItemHelper.setBypassUnknownSwap(false);
                    textX = panelX + 38;
                }

                // Line 1: name + job role, so the player can tell who does what at a glance.
                MutableComponent line1 = Component.literal(e.name()).withStyle(ChatFormatting.WHITE)
                    .append(Component.literal("  "))
                    .append(jobLabel(e.jobTypeId()));
                graphics.drawString(this.font, line1, textX, y + 3, 0xFFFFFFFF, false);
                String stats = String.format("HP %.0f/%.0f   STA %d/%d",
                    e.health(), e.maxHealth(), e.stamina(), e.maxStamina());
                graphics.drawString(this.font, stats, textX, y + 13, 0xFFAAAAAA, false);
            }

            // Scrollbar — only when the roster overflows the visible window.
            if (maxScroll > 0) {
                int trackX = panelX + PANEL_WIDTH - 10;
                int trackY = rowsTop;
                int trackH = MAX_ROWS * ROW_HEIGHT - 4;
                graphics.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackH, 0xFF0A0A0A);
                int thumbH = Math.max(12, trackH * MAX_ROWS / entries.size());
                int thumbY = trackY + (trackH - thumbH) * firstRow / maxScroll;
                graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH, 0xFF606060);
            }
        });

        // Recall buttons — one per VISIBLE row, right-aligned. Windowed by the scroll offset; a
        // scroll triggers rebuildWidgets() which re-lays these out for the new window.
        int rowsTop = panelY + 52;
        for (int row = 0; row < MAX_ROWS && firstRow + row < entries.size(); row++) {
            SettlementCitizensListPayload.Entry e = entries.get(firstRow + row);
            int y = rowsTop + row * ROW_HEIGHT;
            this.addRenderableWidget(PolishButton.polished(
                    Component.translatable("bannerbound.townhall.citizens.recall"),
                    btn -> {
                        PacketDistributor.sendToServer(new RecallCitizenPayload(e.id()));
                    })
                .bounds(panelX + PANEL_WIDTH - 74, y + 3, 56, 18)
                .accent(primaryAccent())
                .build());
        }

        this.addRenderableWidget(PolishButton.polished(
                Component.translatable("gui.cancel"),
                btn -> this.onClose())
            .bounds(panelX + 12, panelY + PANEL_HEIGHT - 28, btnWidth, 20)
            .accent(primaryAccent())
            .build());
    }

    /** Readable, gold-tinted role name for a row. Honours the per-civ custom language when enabled
     *  (falling back to the {@code bannerbound.job.<typeId>} key), and shows "Unemployed" for an
     *  empty type id (unemployed citizen or one whose entity isn't loaded). */
    private MutableComponent jobLabel(String jobTypeId) {
        if (jobTypeId == null || jobTypeId.isEmpty()) {
            return Component.translatable("bannerbound.job.unemployed").withStyle(ChatFormatting.DARK_GRAY);
        }
        Component custom = ClientLanguageState.jobName(jobTypeId, false);
        MutableComponent name = custom instanceof MutableComponent m ? m
            : custom != null ? Component.empty().append(custom)
            : Component.translatable("bannerbound.job." + jobTypeId);
        return name.withStyle(ChatFormatting.GOLD);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (entries.size() > MAX_ROWS && scrollY != 0) {
            int before = scrollRow;
            scrollRow -= (int) Math.signum(scrollY);
            int maxScroll = Math.max(0, entries.size() - MAX_ROWS);
            scrollRow = Math.max(0, Math.min(scrollRow, maxScroll));
            if (scrollRow != before) this.rebuildWidgets();   // relayout the windowed rows + buttons
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
