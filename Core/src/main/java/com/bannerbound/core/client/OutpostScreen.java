package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.AssignOutpostWorkerPayload;
import com.bannerbound.core.network.EstablishOutpostPayload;
import com.bannerbound.core.network.OpenOutpostScreenPayload;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Outpost Banner's management screen, v3: a "Site" status block with item icons (the deposit's
 * yield, the storage chest, the lodging bed) and a colour-coded VEIN BAR (ready faces vs the
 * deposit's total — the at-a-glance "is there work right now?"), then a divided "Workforce"
 * section naming the appointed miner with Appoint/Unassign controls. The banner — not the
 * Foreman's Rod — owns the deposit marker; every action round-trips and the server replies with a
 * fresh payload, so the screen live-updates. All prose wraps ({@link PolishedScreen#drawWrapped})
 * and the panel height is computed from wrapped line counts.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class OutpostScreen extends PolishedScreen {
    private static final int PANEL_WIDTH = 240;
    private static final int ROW_GAP = 2;
    private static final int ICON_ROW_MIN_H = 18;
    private static final int HEADER_H = 14;
    private static final int VEIN_BAR_H = 5;
    private static final int BTN_ROW_H = 24;

    /** One icon-plus-text status row. Icon may be empty (plain text row). */
    private record Row(ItemStack icon, Component text) {}

    private final OpenOutpostScreenPayload data;

    public OutpostScreen(OpenOutpostScreenPayload data) {
        super(Component.translatable("bannerbound.outpost.ui.title"));
        this.data = data;
    }

    @Override
    protected void init() {
        if (!data.established()) {
            initEstablish();
            return;
        }
        final int textX0 = 12;                       // panel-relative text margin
        final int iconTextDX = 20;                   // text offset on icon rows
        final int textW = PANEL_WIDTH - textX0 * 2;
        final int lineH = this.font.lineHeight + 1;
        final boolean hasWorker = !data.assignedName().isEmpty() || data.markerOpen();
        final boolean showVein = data.veinReady() >= 0 && data.veinTotal() > 0;

        // ── "Site" rows ─────────────────────────────────────────────────────────────────────────
        final List<Row> site = new ArrayList<>();
        Component resource = data.resourceName().isEmpty()
            ? Component.translatable("bannerbound.outpost.ui.no_resource").withStyle(ChatFormatting.DARK_GRAY)
            : Component.translatable("bannerbound.resource." + data.resourceName())
                .withStyle(ChatFormatting.AQUA);
        Component depositLine = Component.translatable("bannerbound.outpost.ui.resource", resource)
            .withStyle(ChatFormatting.GRAY);
        // Richness suffix — scouting intel: a poor vein reads gray, a rich one gold.
        if (data.richness() == 0) {
            depositLine = depositLine.copy().append(" ").append(
                Component.translatable("bannerbound.outpost.ui.poor").withStyle(ChatFormatting.DARK_GRAY));
        } else if (data.richness() == 2) {
            depositLine = depositLine.copy().append(" ").append(
                Component.translatable("bannerbound.outpost.ui.rich").withStyle(ChatFormatting.GOLD));
        }
        site.add(new Row(depositIcon(), depositLine));
        site.add(new Row(new ItemStack(Items.CHEST), data.storageSet()
            ? Component.translatable("bannerbound.outpost.ui.storage_ok").withStyle(ChatFormatting.GREEN)
            : Component.translatable("bannerbound.outpost.ui.storage_missing").withStyle(ChatFormatting.RED)));
        site.add(new Row(bedIcon(), data.roofedBeds() > 0
            ? Component.translatable("bannerbound.outpost.ui.beds", data.roofedBeds())
                .withStyle(ChatFormatting.GREEN)
            : Component.translatable("bannerbound.outpost.ui.no_beds").withStyle(ChatFormatting.YELLOW)));
        site.add(new Row(new ItemStack(Items.WHITE_BANNER),
            Component.translatable("bannerbound.outpost.ui.slots", data.outpostCount(), data.outpostMax())
                .withStyle(ChatFormatting.GRAY)));

        final Component veinText = showVein
            ? (data.veinReady() > 0
                ? Component.translatable("bannerbound.outpost.ui.vein", data.veinReady(), data.veinTotal())
                    .withStyle(ChatFormatting.GREEN)
                : Component.translatable("bannerbound.outpost.ui.vein_empty").withStyle(ChatFormatting.YELLOW))
            : null;

        final Component assigned;
        if (data.markerOpen()) {
            assigned = Component.translatable("bannerbound.outpost.ui.assigned_open")
                .withStyle(ChatFormatting.YELLOW);
        } else if (data.assignedName().isEmpty()) {
            assigned = Component.translatable("bannerbound.outpost.ui.assigned_none")
                .withStyle(ChatFormatting.YELLOW);
        } else {
            assigned = Component.translatable("bannerbound.outpost.ui.assigned", data.assignedName())
                .withStyle(ChatFormatting.GREEN);
        }

        // ── Height math (must mirror the draw pass below exactly) ──────────────────────────────
        int siteH = 0;
        for (Row r : site) siteH += rowHeight(r, textW, iconTextDX, lineH);
        final int veinH = veinText == null ? 0
            : wrappedLineCount(this.font, veinText, textW) * lineH + VEIN_BAR_H + 4 + ROW_GAP;
        final int assignedH = Math.max(ICON_ROW_MIN_H,
            wrappedLineCount(this.font, assigned, textW - iconTextDX) * lineH) + ROW_GAP;
        final int candidateRows = Math.max(1, data.candidateIds().size());
        final int panelH = 28 + siteH + veinH + HEADER_H + assignedH
            + (hasWorker ? BTN_ROW_H : 2) + HEADER_H + candidateRows * BTN_ROW_H + 34;
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = (this.height - panelH) / 2;
        // Y landmarks shared by the draw pass and button placement.
        final int workforceTop = panelY + 28 + siteH + veinH;
        final int assignedTop = workforceTop + HEADER_H;
        final int unassignTop = assignedTop + assignedH;
        final int candHeaderTop = unassignTop + (hasWorker ? BTN_ROW_H : 2);
        final int candTop = candHeaderTop + HEADER_H;

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            drawIdentityPanel(graphics, panelX, panelY, PANEL_WIDTH, panelH, identityAccents);
            graphics.drawCenteredString(this.font, this.getTitle(),
                panelX + PANEL_WIDTH / 2, panelY + 10, GuiPalette.TITLE);

            int x = panelX + textX0;
            int y = panelY + 28;
            for (Row r : site) {
                if (!r.icon().isEmpty()) {
                    graphics.renderItem(r.icon(), x, y);
                    drawWrapped(graphics, this.font, r.text(), x + iconTextDX, y + 4,
                        textW - iconTextDX, 0xFFFFFFFF);
                } else {
                    drawWrapped(graphics, this.font, r.text(), x, y + 4, textW, 0xFFFFFFFF);
                }
                y += rowHeight(r, textW, iconTextDX, lineH);
            }
            if (veinText != null) {
                y = drawWrapped(graphics, this.font, veinText, x, y, textW, 0xFFFFFFFF) + 2;
                // The vein bar: green = mineable faces, dark = worked out and waiting on a wave.
                int barW = textW;
                int fillW = (int) (barW * (data.veinReady() / (float) data.veinTotal()));
                graphics.fill(x, y, x + barW, y + VEIN_BAR_H, 0xFF2A2A2A);
                if (fillW > 0) graphics.fill(x, y, x + fillW, y + VEIN_BAR_H, 0xFF4CAF50);
                graphics.renderOutline(x, y, barW, VEIN_BAR_H, 0xFF505050);
                y += VEIN_BAR_H + 4 + ROW_GAP;
            }

            drawSectionHeader(graphics, panelX, workforceTop,
                Component.translatable("bannerbound.outpost.ui.workforce"));
            if (!data.markerOpen() && !data.assignedName().isEmpty()) {
                graphics.renderItem(workerIcon(), x, assignedTop);
                drawWrapped(graphics, this.font, assigned, x + iconTextDX, assignedTop + 4,
                    textW - iconTextDX, 0xFFFFFFFF);
            } else {
                drawWrapped(graphics, this.font, assigned, x, assignedTop + 4, textW, 0xFFFFFFFF);
            }
            drawSectionHeader(graphics, panelX, candHeaderTop,
                Component.translatable("bannerbound.outpost.ui.candidates"));
            if (data.candidateIds().isEmpty()) {
                drawWrapped(graphics, this.font,
                    Component.translatable("bannerbound.outpost.ui.no_candidates")
                        .withStyle(ChatFormatting.DARK_GRAY),
                    x, candTop + 4, textW, 0xFFFFFFFF);
            } else {
                int rowY = candTop;
                for (String name : data.candidateNames()) {
                    graphics.drawString(this.font, Component.literal(name).withStyle(ChatFormatting.WHITE),
                        x, rowY + 6, 0xFFFFFFFF, false);
                    rowY += BTN_ROW_H;
                }
            }
        });

        if (hasWorker) {
            this.addRenderableWidget(PolishButton.polished(
                    Component.translatable("bannerbound.outpost.ui.recall"),
                    btn -> PacketDistributor.sendToServer(
                        new AssignOutpostWorkerPayload(data.bannerPos(), "")))
                .bounds(panelX + textX0, unassignTop + 2, 80, 20)
                .accent(primaryAccent())
                .build());
        }
        for (int i = 0; i < data.candidateIds().size(); i++) {
            final String id = data.candidateIds().get(i);
            this.addRenderableWidget(PolishButton.polished(
                    Component.translatable("bannerbound.outpost.ui.appoint"),
                    btn -> PacketDistributor.sendToServer(
                        new AssignOutpostWorkerPayload(data.bannerPos(), id)))
                .bounds(panelX + PANEL_WIDTH - textX0 - 70, candTop + i * BTN_ROW_H, 70, 20)
                .accent(primaryAccent())
                .build());
        }

        this.addRenderableWidget(PolishButton.polished(
                Component.translatable("gui.done"),
                btn -> this.onClose())
            .bounds(panelX + textX0, panelY + panelH - 28, PANEL_WIDTH - textX0 * 2, 20)
            .accent(primaryAccent())
            .build());
    }

    /**
     * The "place then confirm" variant: this banner stands on a valid but not-yet-claimed outpost
     * site, so we show the site readout (resource / storage / lodging / slots) and a single
     * "Establish outpost here" button. Confirming sends {@link EstablishOutpostPayload}; the server
     * grants the claim and replies with the full management screen.
     */
    private void initEstablish() {
        final int textX0 = 12;
        final int iconTextDX = 20;
        final int textW = PANEL_WIDTH - textX0 * 2;
        final int lineH = this.font.lineHeight + 1;

        final List<Row> site = new ArrayList<>();
        Component resource = data.resourceName().isEmpty()
            ? Component.translatable("bannerbound.outpost.ui.no_resource").withStyle(ChatFormatting.DARK_GRAY)
            : Component.translatable("bannerbound.resource." + data.resourceName()).withStyle(ChatFormatting.AQUA);
        Component depositLine = Component.translatable("bannerbound.outpost.ui.resource", resource)
            .withStyle(ChatFormatting.GRAY);
        if (data.richness() == 0) {
            depositLine = depositLine.copy().append(" ").append(
                Component.translatable("bannerbound.outpost.ui.poor").withStyle(ChatFormatting.DARK_GRAY));
        } else if (data.richness() == 2) {
            depositLine = depositLine.copy().append(" ").append(
                Component.translatable("bannerbound.outpost.ui.rich").withStyle(ChatFormatting.GOLD));
        }
        site.add(new Row(depositIcon(), depositLine));
        site.add(new Row(new ItemStack(Items.CHEST), data.storageSet()
            ? Component.translatable("bannerbound.outpost.ui.storage_ok").withStyle(ChatFormatting.GREEN)
            : Component.translatable("bannerbound.outpost.ui.storage_missing").withStyle(ChatFormatting.RED)));
        site.add(new Row(bedIcon(), data.roofedBeds() > 0
            ? Component.translatable("bannerbound.outpost.ui.beds", data.roofedBeds()).withStyle(ChatFormatting.GREEN)
            : Component.translatable("bannerbound.outpost.ui.no_beds").withStyle(ChatFormatting.YELLOW)));
        site.add(new Row(new ItemStack(Items.WHITE_BANNER),
            Component.translatable("bannerbound.outpost.ui.slots", data.outpostCount(), data.outpostMax())
                .withStyle(ChatFormatting.GRAY)));

        int siteH = 0;
        for (Row r : site) siteH += rowHeight(r, textW, iconTextDX, lineH);
        final Component hint = Component.translatable("bannerbound.outpost.ui.establish_hint")
            .withStyle(ChatFormatting.DARK_GRAY);
        final int hintH = wrappedLineCount(this.font, hint, textW) * lineH + ROW_GAP;
        final boolean full = data.outpostCount() >= data.outpostMax();
        final int panelH = 28 + siteH + hintH + BTN_ROW_H + 34;
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = (this.height - panelH) / 2;

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            drawIdentityPanel(graphics, panelX, panelY, PANEL_WIDTH, panelH, identityAccents);
            graphics.drawCenteredString(this.font, this.getTitle(),
                panelX + PANEL_WIDTH / 2, panelY + 10, GuiPalette.TITLE);
            int x = panelX + textX0;
            int y = panelY + 28;
            for (Row r : site) {
                if (!r.icon().isEmpty()) {
                    graphics.renderItem(r.icon(), x, y);
                    drawWrapped(graphics, this.font, r.text(), x + iconTextDX, y + 4, textW - iconTextDX, 0xFFFFFFFF);
                } else {
                    drawWrapped(graphics, this.font, r.text(), x, y + 4, textW, 0xFFFFFFFF);
                }
                y += rowHeight(r, textW, iconTextDX, lineH);
            }
            drawWrapped(graphics, this.font, hint, x, y + 2, textW, 0xFFFFFFFF);
        });

        PolishButton establish = PolishButton.polished(
                Component.translatable("bannerbound.outpost.ui.establish"),
                btn -> PacketDistributor.sendToServer(new EstablishOutpostPayload(data.bannerPos())))
            .bounds(panelX + textX0, panelY + 28 + siteH + hintH, PANEL_WIDTH - textX0 * 2, 20)
            .accent(primaryAccent())
            .build();
        // Cap reached → the button is shown disabled so the limit reads as a wall, not a missing UI.
        establish.active = !full;
        this.addRenderableWidget(establish);

        this.addRenderableWidget(PolishButton.polished(
                Component.translatable("gui.done"),
                btn -> this.onClose())
            .bounds(panelX + textX0, panelY + panelH - 28, PANEL_WIDTH - textX0 * 2, 20)
            .accent(primaryAccent())
            .build());
    }

    /** Section header: small gray label with a divider line running to the panel edge. */
    private void drawSectionHeader(net.minecraft.client.gui.GuiGraphics graphics,
                                   int panelX, int y, Component label) {
        int x = panelX + 12;
        graphics.drawString(this.font, label.copy().withStyle(ChatFormatting.GRAY), x, y + 2, 0xFFAAAAAA, false);
        int lineX = x + this.font.width(label) + 6;
        graphics.fill(lineX, y + 6, panelX + PANEL_WIDTH - 12, y + 7, 0xFF3A3A3A);
    }

    /** Row height: at least the icon's 18px, or however many wrapped lines the text needs. */
    private int rowHeight(Row r, int textW, int iconTextDX, int lineH) {
        int w = r.icon().isEmpty() ? textW : textW - iconTextDX;
        return Math.max(ICON_ROW_MIN_H, wrappedLineCount(this.font, r.text(), w) * lineH + 6) + ROW_GAP;
    }

    /** Icon for the deposit row: the chunk's actual yield item (raw copper, raw tin, calcite...). */
    private ItemStack depositIcon() {
        if (data.resourceName().isEmpty()) return ItemStack.EMPTY;
        try {
            com.bannerbound.core.territory.ChunkResource type =
                com.bannerbound.core.territory.ChunkResource.valueOf(
                    data.resourceName().toUpperCase(java.util.Locale.ROOT));
            java.util.Optional<net.minecraft.world.item.Item> oreDrop =
                com.bannerbound.core.territory.BoulderLayout.dropFor(type);
            if (oreDrop.isPresent()) return new ItemStack(oreDrop.get());
            return com.bannerbound.core.territory.MaterialDepositLayout.iconDropFor(type)
                .map(ItemStack::new).orElse(ItemStack.EMPTY);
        } catch (IllegalArgumentException unknown) {
            return ItemStack.EMPTY;
        }
    }

    private ItemStack workerIcon() {
        String r = data.resourceName();
        if ("clay".equals(r) || "sand".equals(r)) return new ItemStack(Items.STONE_SHOVEL);
        return new ItemStack(Items.STONE_PICKAXE);
    }

    /** Lodging icon: the Antiquity thatch bed when present, else a vanilla bed. */
    private static ItemStack bedIcon() {
        Item thatch = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                "bannerboundantiquity", "thatch_bed")).orElse(null);
        return new ItemStack(thatch != null ? thatch : Items.RED_BED);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
