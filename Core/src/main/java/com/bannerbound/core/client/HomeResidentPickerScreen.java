package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.AssignCitizenToHomePayload;
import com.bannerbound.core.network.HomeCitizenListPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Resident picker opened from the House Block status panel's "Assign" button. Lists every
 * citizen in the settlement in priority order:
 * <ol>
 *   <li>Current residents of this home — "Unassign" button (red label).</li>
 *   <li>Homeless citizens — "Assign" button (green label).</li>
 *   <li>Residents of other homes in the settlement — "Assign" button + small line showing the
 *       Chebyshev distance from their current home to this one.</li>
 * </ol>
 * Each click sends an {@link AssignCitizenToHomePayload}; the server re-sends the list and
 * {@link com.bannerbound.core.network.ClientPayloadHandler#handleHomeCitizenList} refreshes
 * this screen in place, so the player can do several assignments in one session without the
 * picker bouncing closed.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class HomeResidentPickerScreen extends PolishedScreen {
    private static final int PANEL_WIDTH = 260;
    private static final int PANEL_HEIGHT = 220;
    /** Per-row height — a touch taller than WorkerPickerScreen's 22 because "other" rows need
     *  to also fit a small "lives X blocks from here" line under the action button. */
    private static final int ROW_HEIGHT = 26;
    /** Visible rows in the scroll window. The list scrolls vertically when {@code ordered.size()}
     *  exceeds this — mouse wheel moves the window in 1-row steps. */
    private static final int MAX_VISIBLE_ROWS = 6;
    /** Scroll-track width on the right edge of the list. Just a thin coloured bar — no drag
     *  handle, mouse wheel is the only scroll input. */
    private static final int SCROLL_TRACK_WIDTH = 4;

    private UUID homeId;
    /** Sorted list — set in {@link #init()} from the latest payload data. The picker is
     *  recreated wholesale via {@link #refresh} on each refresh, so this is final per-instance. */
    private List<HomeCitizenListPayload.Entry> ordered;
    /** Top-row index currently visible. Bounded to {@code [0, max(0, ordered.size() - MAX_VISIBLE_ROWS)]}. */
    private int scrollOffset = 0;

    public HomeResidentPickerScreen(HomeCitizenListPayload payload) {
        super(Component.translatable("bannerbound.house.picker.title"));
        this.homeId = payload.homeId();
        this.ordered = sortByPriority(payload.entries());
    }

    /** Replace data + re-init for an open picker. Same pattern as ExpandTerritoryScreen.refreshData.
     *  Preserves the scroll position across refreshes when possible — players doing several
     *  assignments in a row stay in the same window of the list instead of jumping back to the
     *  top after every click. Re-clamps in case the list shrank below the previous offset. */
    public void refresh(HomeCitizenListPayload payload) {
        this.homeId = payload.homeId();
        this.ordered = sortByPriority(payload.entries());
        clampScroll();
        this.rebuildWidgets();
    }

    private int maxScroll() {
        return Math.max(0, ordered.size() - MAX_VISIBLE_ROWS);
    }

    private void clampScroll() {
        if (scrollOffset < 0) scrollOffset = 0;
        int max = maxScroll();
        if (scrollOffset > max) scrollOffset = max;
    }

    /** Priority sort: RESIDENT first (alphabetically), then HOMELESS (alphabetically), then OTHER
     *  by ascending distance (closest other-home residents first — most likely candidate to
     *  poach). Stable within bucket so re-renders don't shuffle order on the player. */
    private static List<HomeCitizenListPayload.Entry> sortByPriority(List<HomeCitizenListPayload.Entry> in) {
        List<HomeCitizenListPayload.Entry> out = new ArrayList<>(in);
        out.sort(Comparator
            .comparingInt((HomeCitizenListPayload.Entry e) -> e.role().ordinal())
            .thenComparingInt(HomeCitizenListPayload.Entry::distance)
            .thenComparing(HomeCitizenListPayload.Entry::name));
        return out;
    }

    @Override
    protected void init() {
        clampScroll();
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = (this.height - PANEL_HEIGHT) / 2;
        // List body sits inset from the panel by 12 on the left, plus an extra column on the
        // right reserved for the scroll-track when there's more than one window of rows.
        final int rowX = panelX + 12;
        final boolean hasOverflow = ordered.size() > MAX_VISIBLE_ROWS;
        final int rowWidth = PANEL_WIDTH - 24 - (hasOverflow ? SCROLL_TRACK_WIDTH + 4 : 0);

        final int firstIndex = scrollOffset;
        final int visibleCount = Math.min(MAX_VISIBLE_ROWS, ordered.size() - firstIndex);

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            drawIdentityPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, identityAccents);
            graphics.drawCenteredString(this.font, this.getTitle(),
                panelX + PANEL_WIDTH / 2, panelY + 10, GuiPalette.TITLE);

            if (ordered.isEmpty()) {
                graphics.drawCenteredString(this.font,
                    Component.translatable("bannerbound.house.picker.no_citizens")
                        .withStyle(ChatFormatting.GRAY),
                    panelX + PANEL_WIDTH / 2, panelY + 60, 0xFFAAAAAA);
                return;
            }

            // Distance lines under each OTHER row in the current window.
            int listTop = panelY + 30;
            for (int i = 0; i < visibleCount; i++) {
                HomeCitizenListPayload.Entry entry = ordered.get(firstIndex + i);
                if (entry.role() != HomeCitizenListPayload.Role.OTHER) continue;
                int rowY = listTop + i * ROW_HEIGHT;
                Component distLine = Component.translatable(
                    "bannerbound.house.picker.lives_blocks_away", entry.distance())
                    .withStyle(ChatFormatting.DARK_GRAY);
                graphics.drawString(this.font, distLine,
                    rowX, rowY + 14, 0xFF808080, false);
            }

            // Scroll-track + thumb on the right edge of the list, only shown when overflow
            // exists. Thumb length proportional to the visible-fraction; thumb position
            // proportional to scrollOffset / maxScroll.
            if (hasOverflow) {
                int trackX = panelX + PANEL_WIDTH - 12 - SCROLL_TRACK_WIDTH;
                int trackY = listTop;
                int trackH = MAX_VISIBLE_ROWS * ROW_HEIGHT - 2;
                graphics.fill(trackX, trackY, trackX + SCROLL_TRACK_WIDTH, trackY + trackH, 0xFF2A2A2A);
                int max = maxScroll();
                int thumbH = Math.max(8, trackH * MAX_VISIBLE_ROWS / ordered.size());
                int thumbY = trackY + (max <= 0 ? 0 : (trackH - thumbH) * scrollOffset / max);
                graphics.fill(trackX, thumbY, trackX + SCROLL_TRACK_WIDTH, thumbY + thumbH, 0xFFB0B0B0);
            }
        });

        int listTop = panelY + 30;
        for (int i = 0; i < visibleCount; i++) {
            HomeCitizenListPayload.Entry entry = ordered.get(firstIndex + i);
            boolean isResident = entry.role() == HomeCitizenListPayload.Role.RESIDENT;
            // Action button — single line for RESIDENT and HOMELESS (height 20), shortened so
            // the "lives X blocks away" line below has room when role==OTHER.
            int btnHeight = (entry.role() == HomeCitizenListPayload.Role.OTHER) ? 14 : 20;
            Component label;
            if (isResident) {
                label = Component.translatable("bannerbound.house.picker.row_unassign", entry.name())
                    .withStyle(ChatFormatting.RED);
            } else {
                label = Component.translatable("bannerbound.house.picker.row_assign", entry.name())
                    .withStyle(ChatFormatting.GREEN);
            }
            final boolean assign = !isResident;
            this.addRenderableWidget(PolishButton.polished(
                    label,
                    btn -> {
                        PacketDistributor.sendToServer(
                            new AssignCitizenToHomePayload(homeId, entry.id(), assign, false));
                    })
                .bounds(rowX, listTop + i * ROW_HEIGHT, rowWidth, btnHeight)
                .accent(primaryAccent())
                .build());
        }

        this.addRenderableWidget(PolishButton.polished(
                Component.translatable("gui.cancel"),
                btn -> this.onClose())
            .bounds(rowX, panelY + PANEL_HEIGHT - 28, PANEL_WIDTH - 24, 20)
            .accent(primaryAccent())
            .build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 1 row per wheel notch. Negative scrollY (wheel toward user) = scroll down = higher
        // index visible at top. Re-layouts widgets so the button positions track the new window.
        if (maxScroll() > 0) {
            int prev = scrollOffset;
            scrollOffset -= (int) Math.signum(scrollY);
            clampScroll();
            if (scrollOffset != prev) {
                this.rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
