package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkBlockRegistry;
import com.bannerbound.core.network.AssignWorkshopWorkerPayload;
import com.bannerbound.core.network.OpenWorkshopMenuPayload;
import com.bannerbound.core.network.RenameWorkshopPayload;
import com.bannerbound.core.network.WorkshopMenu;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Workshop menu (opened by shift-right-clicking inside a workshop with the Workshop Orders
 * rod): rename field, status/type/capacity header, the assigned-worker rows and a SCROLLABLE
 * candidate list (unemployed first; mouse wheel scrolls) with each citizen's current job icon.
 * Min-stock rows arrive with Phase 3 (see CRAFTER_PLAN.md).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class WorkshopScreen extends PolishedScreen {
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 290;
    /** Candidate rows visible at once; the wheel scrolls the window. */
    private static final int VISIBLE_ROWS = 5;
    private static final int ROW_H = 18;

    private final OpenWorkshopMenuPayload data;
    /** Whether the settlement has researched this workshop's craft. When false the type reads as
     *  "Unknown Workshop" and the Assign buttons are disabled — a station pre-placed on old ruins
     *  can't be operated before the research is earned (mirrors the server-side assign gate). */
    private final boolean typeKnown;
    /** Candidate indices, unemployed first (stable within each group). */
    private final List<Integer> sortedCandidates = new ArrayList<>();
    private EditBox nameField;
    /** First visible row of the ACTIVE tab's list; mouse wheel adjusts + rebuilds. */
    private int scroll;
    /** Active tab: 0 = Workers (roster), 1 = Stock (min-stock rows). */
    private int tab;
    /** Y where the worker rows start (computed in init, used by the backdrop labels). */
    private int rosterTop;
    /** Preserves the rename field across scroll rebuilds. */
    private String pendingName;

    /** True when this screen shows the given workshop (used to carry UI state across refreshes). */
    public boolean showsWorkshop(String workshopId) {
        return data.workshopId().equals(workshopId);
    }

    /** Carries tab/scroll/typed-name from the previous screen instance when the server re-sends
     *  the menu after an edit (rename, assign, min-stock ±) — so a Stock-tab click doesn't bounce
     *  the player back to the Workers tab. */
    public void carryUiStateFrom(WorkshopScreen previous) {
        this.tab = previous.tab;
        this.scroll = previous.scroll;
        this.pendingName = previous.pendingName;
    }

    public WorkshopScreen(OpenWorkshopMenuPayload data) {
        super(Component.translatable("bannerbound.workshop.title"));
        this.data = data;
        this.typeKnown = ClientResearchState.isWorkshopTypeKnown(data.derivedTypeId());
        this.pendingName = data.customName();
        for (int i = 0; i < data.candidateIds().size(); i++) {
            if (!data.candidateEmployed().get(i)) sortedCandidates.add(i);
        }
        for (int i = 0; i < data.candidateIds().size(); i++) {
            if (data.candidateEmployed().get(i)) sortedCandidates.add(i);
        }
    }

    private int panelX() {
        return (this.width - PANEL_W) / 2;
    }

    private int panelY() {
        // Panel + bookmark-tab strip center together as one block (TownHall pattern), keeping
        // the tabs that protrude above the top edge on-screen.
        return (this.height - PANEL_H + BookmarkTab.HEIGHT) / 2;
    }

    @Override
    protected void init() {
        int x = panelX();
        int y = panelY();
        // Rename field, prefilled with the custom name (placeholder shows the derived type).
        this.nameField = new EditBox(this.font, x + 12, y + 36, PANEL_W - 80, 20,
            Component.translatable("bannerbound.workshop.name_label"));
        this.nameField.setMaxLength(WorkshopMenu.MAX_NAME_LENGTH);
        this.nameField.setValue(pendingName);
        this.nameField.setResponder(s -> pendingName = s);
        this.nameField.setHint(typeName().copy().withStyle(ChatFormatting.DARK_GRAY));
        this.addRenderableWidget(this.nameField);

        this.addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.workshop.save_name"),
                btn -> PacketDistributor.sendToServer(
                    new RenameWorkshopPayload(data.workshopId(), nameField.getValue().strip())))
            .bounds(x + PANEL_W - 60, y + 36, 48, 20)
            .accent(primaryAccent())
            .build());

        // Tabs: Workers (roster) / Stock (min-stock rows). Switching resets the scroll window.
        // Bookmark tabs above the panel's top edge — the house tab style.
        BookmarkTab.addRow(this::addRenderableWidget, x, PANEL_W, y,
            java.util.List.of(
                Component.translatable("bannerbound.workshop.tab_workers"),
                Component.translatable("bannerbound.workshop.tab_stock")),
            tab, primaryAccent(), secondaryAccent(), i -> {
                if (tab != i) { tab = i; scroll = 0; this.rebuildWidgets(); }
            });

        rosterTop = y + 134;
        if (tab == 0) {
            initWorkersTab(x);
        } else {
            initStockTab(x);
        }

        this.addRenderableWidget(PolishButton.polished(Component.translatable("gui.done"),
                btn -> this.onClose())
            .bounds(x + 12, y + PANEL_H - 28, PANEL_W - 24, 20)
            .accent(primaryAccent())
            .build());
    }

    /** Worker roster buttons: assigned rows (Unassign) then the scrolled candidate window
     *  (Assign). Names + icons are drawn in the backdrop; only the buttons are widgets. */
    private void initWorkersTab(int x) {
        int rowY = rosterTop;
        // The per-worker station chooser only appears when the workshop holds MORE than one station
        // family — a single-station workshop has nothing to choose between.
        boolean multiStation = data.stationTypeIds().size() > 1;
        for (int i = 0; i < data.workerIds().size(); i++) {
            final String id = data.workerIds().get(i);
            if (multiStation) {
                final String current = i < data.workerPositions().size()
                    ? data.workerPositions().get(i) : "";
                this.addRenderableWidget(PolishButton.polished(
                        stationLabel(current),
                        btn -> PacketDistributor.sendToServer(
                            new com.bannerbound.core.network.SetWorkshopWorkerStationPayload(
                                data.workshopId(), id, nextStation(current))))
                    .bounds(x + PANEL_W - 162, rowY - 2, 86, 16)
                    .accent(primaryAccent())
                    .build());
            }
            this.addRenderableWidget(PolishButton.polished(
                    Component.translatable("bannerbound.workshop.unassign"),
                    btn -> PacketDistributor.sendToServer(
                        new AssignWorkshopWorkerPayload(data.workshopId(), id, false)))
                .bounds(x + PANEL_W - 72, rowY - 2, 60, 16)
                .accent(primaryAccent())
                .build());
            rowY += ROW_H;
        }
        rowY += 12; // candidates header line
        boolean hasRoom = data.workerIds().size() < data.capacity();
        int end = Math.min(sortedCandidates.size(), scroll + VISIBLE_ROWS);
        for (int row = scroll; row < end; row++) {
            final String id = data.candidateIds().get(sortedCandidates.get(row));
            PolishButton.Builder assignBtn = PolishButton.polished(
                    Component.translatable("bannerbound.workshop.assign"),
                    b -> PacketDistributor.sendToServer(
                        new AssignWorkshopWorkerPayload(data.workshopId(), id, true)))
                .bounds(x + PANEL_W - 72, rowY - 2, 60, 16)
                .accent(primaryAccent());
            // Unknown craft → the assign is disabled with a tooltip saying why (the server refuses
            // it anyway; this is the visible reason so a ruins-placed station isn't a silent mystery).
            if (!typeKnown) {
                assignBtn.tooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("bannerbound.workshop.assign_locked_tip")));
            }
            PolishButton btn = assignBtn.build();
            btn.active = hasRoom && typeKnown;
            this.addRenderableWidget(btn);
            rowY += ROW_H;
        }
    }

    /** Stock rows: per craftable output, a min-stock −/+ pair (left group) and an order-queue
     *  −/+ pair (right group); shift-click steps by 8. The icon, name, configured minimum, live
     *  settlement count and queued count are drawn in the backdrop. */
    private void initStockTab(int x) {
        int rowY = rosterTop + 12; // header line first
        int end = Math.min(data.minStockItemIds().size(), scroll + VISIBLE_ROWS);
        for (int row = scroll; row < end; row++) {
            final int itemId = data.minStockItemIds().get(row);
            final int value = data.minStockValues().get(row);
            final int queued = row < data.orderCounts().size() ? data.orderCounts().get(row) : 0;
            this.addRenderableWidget(PolishButton.polished(Component.literal("-"),
                    b -> PacketDistributor.sendToServer(new com.bannerbound.core.network
                        .SetWorkshopMinStockPayload(data.workshopId(), itemId,
                            value - (hasShiftDown() ? 8 : 1))))
                .bounds(x + PANEL_W - 148, rowY - 2, 16, 16)
                .accent(primaryAccent())
                .build());
            this.addRenderableWidget(PolishButton.polished(Component.literal("+"),
                    b -> PacketDistributor.sendToServer(new com.bannerbound.core.network
                        .SetWorkshopMinStockPayload(data.workshopId(), itemId,
                            value + (hasShiftDown() ? 8 : 1))))
                .bounds(x + PANEL_W - 100, rowY - 2, 16, 16)
                .accent(primaryAccent())
                .build());
            this.addRenderableWidget(PolishButton.polished(Component.literal("-"),
                    b -> PacketDistributor.sendToServer(new com.bannerbound.core.network
                        .SetWorkshopOrderPayload(data.workshopId(), itemId,
                            queued - (hasShiftDown() ? 8 : 1))))
                .bounds(x + PANEL_W - 76, rowY - 2, 16, 16)
                .accent(primaryAccent())
                .build());
            this.addRenderableWidget(PolishButton.polished(Component.literal("+"),
                    b -> PacketDistributor.sendToServer(new com.bannerbound.core.network
                        .SetWorkshopOrderPayload(data.workshopId(), itemId,
                            queued + (hasShiftDown() ? 8 : 1))))
                .bounds(x + PANEL_W - 28, rowY - 2, 16, 16)
                .accent(primaryAccent())
                .build());
            rowY += ROW_H;
        }
    }

    /** Mouse wheel over the panel scrolls the active tab's list. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
        int listSize = tab == 0 ? sortedCandidates.size() : data.minStockItemIds().size();
        int maxScroll = Math.max(0, listSize - VISIBLE_ROWS);
        int newScroll = net.minecraft.util.Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll);
        if (newScroll != scroll) {
            scroll = newScroll;
            this.rebuildWidgets();
        }
        return true;
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = panelX();
        int y = panelY();
        drawIdentityPanel(g, x, y, PANEL_W, PANEL_H, identityAccents);

        g.drawCenteredString(this.font, this.title, this.width / 2, y + 8, GuiPalette.TITLE);
        drawIdentityDivider(g, x + 8, y + 20, PANEL_W - 16, identityAccents);

        // Status + reason (specific, color-coded, word-wrapped), then type/capacity lines.
        Workshop.Status status = Workshop.Status.fromOrdinalOrDefault(data.statusOrdinal());
        boolean valid = status == Workshop.Status.VALID;
        Component statusLine = Component.translatable("bannerbound.workshop.status_label")
            .append(" ")
            .append(Component.translatable("bannerbound.workshop.status." + status.name().toLowerCase())
                .withStyle(valid ? ChatFormatting.GREEN : ChatFormatting.RED));
        int lineY = y + 64;
        for (net.minecraft.util.FormattedCharSequence seq : this.font.split(statusLine, PANEL_W - 24)) {
            g.drawString(this.font, seq, x + 12, lineY, 0xFFFFFFFF, false);
            lineY += this.font.lineHeight + 2;
        }
        Component typeLine = Component.translatable("bannerbound.workshop.type_label")
            .append(" ").append(typeName().copy()
                .withStyle(typeKnown ? ChatFormatting.AQUA : ChatFormatting.GRAY));
        g.drawString(this.font, typeLine, x + 12, lineY, 0xFFFFFFFF);
        lineY += this.font.lineHeight + 4;
        Component capacityLine = Component.translatable("bannerbound.workshop.capacity_label",
            data.workerIds().size(), data.capacity());
        g.drawString(this.font, capacityLine, x + 12, lineY, 0xFFFFFFFF);
        // Workplace appeal, right-aligned on the capacity row: the carrot for decorating —
        // a prettier workshop means happier workers who learn their craft faster.
        if (data.appealOrdinal() >= 0) {
            com.bannerbound.core.api.settlement.ChunkBeauty beauty =
                com.bannerbound.core.api.settlement.ChunkBeauty.byNetworkId(data.appealOrdinal());
            Component appealLine = Component.translatable("bannerbound.workshop.appeal_label")
                .append(" ")
                .append(Component.translatable(beauty.langKey())
                    .withStyle(beauty.tierIndex() > 0 ? ChatFormatting.AQUA
                        : beauty.tierIndex() < 0 ? ChatFormatting.RED
                        : ChatFormatting.GRAY));
            g.drawString(this.font, appealLine,
                x + PANEL_W - 12 - this.font.width(appealLine), lineY, 0xFFFFFFFF);
        }

        if (tab == 0) {
            drawWorkersTab(g, x);
        } else {
            drawStockTab(g, x);
        }
    }

    private void drawWorkersTab(GuiGraphics g, int x) {
        int rowY = rosterTop;
        // When the station-chooser button is shown, clip worker names so they don't run under it.
        int nameClip = data.stationTypeIds().size() > 1 ? PANEL_W - 206 : 0;
        for (int i = 0; i < data.workerNames().size(); i++) {
            drawCitizenRow(g, x, rowY, data.workerNames().get(i), data.workerJobIcons().get(i),
                null, nameClip);
            rowY += ROW_H;
        }
        // Candidates header with a "shown / total" scroll hint.
        Component header = Component.translatable("bannerbound.workshop.candidates_header");
        if (sortedCandidates.size() > VISIBLE_ROWS) {
            header = header.copy().append(Component.literal(
                    "  (" + (scroll + 1) + "–" + Math.min(sortedCandidates.size(), scroll + VISIBLE_ROWS)
                        + " / " + sortedCandidates.size() + ")")
                .withStyle(ChatFormatting.DARK_GRAY));
        }
        g.drawString(this.font, header.copy().withStyle(ChatFormatting.GRAY),
            x + 12, rowY, 0xFFAAAAAA, false);
        rowY += 12;
        int end = Math.min(sortedCandidates.size(), scroll + VISIBLE_ROWS);
        for (int row = scroll; row < end; row++) {
            int i = sortedCandidates.get(row);
            boolean employed = data.candidateEmployed().get(i);
            Component tag = Component.translatable(employed
                    ? "bannerbound.workshop.candidate_employed"
                    : "bannerbound.workshop.candidate_unemployed")
                .withStyle(employed ? ChatFormatting.GOLD : ChatFormatting.GREEN);
            drawCitizenRow(g, x, rowY, data.candidateNames().get(i), data.candidateJobIcons().get(i),
                tag, 0);
            rowY += ROW_H;
        }
    }

    /** Stock rows: item icon + name + "(have)" (green when satisfied, red when in deficit),
     *  then the Min column (settlement-wide minimum) and the Orders column (queued count),
     *  each centered between its −/+ pair laid out in init(). */
    private void drawStockTab(GuiGraphics g, int x) {
        int rowY = rosterTop;
        // The scroll-range hint lives BELOW the list (drawn after the rows) — appended to the
        // header it ran under the Min/Orders column captions.
        Component header = Component.translatable("bannerbound.workshop.stock_header");
        g.drawString(this.font, header.copy().withStyle(ChatFormatting.GRAY),
            x + 12, rowY, 0xFFAAAAAA, false);
        int minCenter = x + PANEL_W - 116;
        int orderCenter = x + PANEL_W - 44;
        // Column captions over the two −/+ groups.
        g.drawCenteredString(this.font,
            Component.translatable("bannerbound.workshop.col_min").getString(),
            minCenter, rowY, 0xFFAAAAAA);
        g.drawCenteredString(this.font,
            Component.translatable("bannerbound.workshop.col_orders").getString(),
            orderCenter, rowY, 0xFFAAAAAA);
        rowY += 12;
        int end = Math.min(data.minStockItemIds().size(), scroll + VISIBLE_ROWS);
        for (int row = scroll; row < end; row++) {
            Item item = BuiltInRegistries.ITEM.byId(data.minStockItemIds().get(row));
            int min = data.minStockValues().get(row);
            int have = data.minStockCounts().get(row);
            int queued = row < data.orderCounts().size() ? data.orderCounts().get(row) : 0;
            if (item != Items.AIR) {
                g.renderItem(new ItemStack(item), x + 12, rowY - 4);
            }
            // Name + census share the (narrower) left column — clip so long names can't run
            // under the Min buttons.
            Component nameLine = Component.empty()
                .append(item.getDescription())
                .append(Component.literal(" (" + have + ")")
                    .withStyle(min > 0 && have < min ? ChatFormatting.RED : ChatFormatting.GREEN));
            var clipped = this.font.split(nameLine, PANEL_W - 188).get(0);
            g.drawString(this.font, clipped, x + 32, rowY, 0xFFFFFFFF, false);
            String minText = min <= 0 ? "—" : Integer.toString(min);
            g.drawCenteredString(this.font, minText, minCenter, rowY, 0xFFFFFFFF);
            // Player orders gold; the chain's derived auto orders ride along as a gray "+n"
            // (display-only — the ± buttons edit player orders, the chain manages its own).
            int auto = row < data.autoOrderCounts().size() ? data.autoOrderCounts().get(row) : 0;
            String orderText = (queued <= 0 && auto <= 0) ? "—"
                : queued > 0 && auto > 0 ? queued + "+" + auto
                : queued > 0 ? Integer.toString(queued)
                : "+" + auto;
            g.drawCenteredString(this.font, orderText, orderCenter, rowY,
                queued > 0 ? 0xFFFFC84A : auto > 0 ? 0xFFB0B0B0 : 0xFFFFFFFF);
            rowY += ROW_H;
        }
        if (data.minStockItemIds().size() > VISIBLE_ROWS) {
            g.drawString(this.font, Component.literal(
                    "(" + (scroll + 1) + "–" + end + " / " + data.minStockItemIds().size() + ")")
                .withStyle(ChatFormatting.DARK_GRAY), x + 12, rowY, 0xFF777777, false);
            drawScrollbar(g, x, rosterTop + 12, VISIBLE_ROWS * ROW_H,
                data.minStockItemIds().size());
        }
    }

    /** A slim scrollbar along the panel's right edge — the visible cue that the list scrolls
     *  (mouse wheel). Thumb size/position track the {@link #scroll} window. */
    private void drawScrollbar(GuiGraphics g, int x, int top, int height, int total) {
        if (total <= VISIBLE_ROWS) return;
        int trackX = x + PANEL_W - 8;
        g.fill(trackX, top - 2, trackX + 3, top - 2 + height, 0xFF2B2B2B);
        int thumbH = Math.max(10, height * VISIBLE_ROWS / total);
        int maxScroll = total - VISIBLE_ROWS;
        int thumbY = top - 2 + (height - thumbH) * scroll / maxScroll;
        g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF8B8B8B);
    }

    /** One roster row: 16px job-icon item (barrier-free blank when 0), name, optional status tag.
     *  {@code maxTextWidth > 0} clips the text so it can't run under a row's right-side buttons. */
    private void drawCitizenRow(GuiGraphics g, int x, int rowY, String name, int iconItemId,
                                Component tag, int maxTextWidth) {
        int textX = x + 12;
        if (iconItemId != 0) {
            Item item = BuiltInRegistries.ITEM.byId(iconItemId);
            if (item != Items.AIR) {
                g.renderItem(new ItemStack(item), x + 12, rowY - 4);
            }
        }
        textX += 20;
        Component row = tag == null
            ? Component.literal(name)
            : Component.literal(name).append(" ").append(tag);
        if (maxTextWidth > 0) {
            g.drawString(this.font, this.font.split(row, maxTextWidth).get(0), textX, rowY,
                0xFFFFFFFF, false);
        } else {
            g.drawString(this.font, row, textX, rowY, 0xFFFFFFFF, false);
        }
    }

    private Component typeName() {
        // An unresearched craft never reveals its real type — it reads as "Unknown Workshop"
        // everywhere typeName() is shown (the type line and the rename field's placeholder hint).
        if (!typeKnown) {
            return Component.translatable("bannerbound.workshop.type_unknown");
        }
        return Component.translatable(WorkBlockRegistry.displayKey(data.derivedTypeId()));
    }

    /** Display label for a worker's pinned station: the station family's name, or "Any" for the
     *  empty (auto-pick) pin. Shown on the station-chooser button. */
    private Component stationLabel(String typeId) {
        if (typeId == null || typeId.isEmpty()) {
            return Component.translatable("bannerbound.workshop.station_any");
        }
        return Component.translatable(WorkBlockRegistry.displayKey(typeId));
    }

    /** The next station pin in the cycle [station families…, Any]. An unknown current value (e.g.
     *  a family that just left the workshop) is treated as Any so the click still advances. */
    private String nextStation(String current) {
        List<String> opts = new ArrayList<>(data.stationTypeIds());
        opts.add(""); // "Any" — clear the pin
        int idx = opts.indexOf(current == null ? "" : current);
        if (idx < 0) idx = opts.size() - 1;
        return opts.get((idx + 1) % opts.size());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
