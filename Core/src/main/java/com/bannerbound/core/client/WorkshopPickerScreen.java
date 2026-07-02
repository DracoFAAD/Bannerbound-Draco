package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkBlockRegistry;
import com.bannerbound.core.network.AssignWorkshopWorkerPayload;
import com.bannerbound.core.network.OpenWorkshopPickerPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The "where does this crafter work?" picker, opened when the Job tab assigns <i>Crafter</i>. One
 * row per settlement workshop — name (custom or derived type), type, occupancy — with full or
 * invalid workshops disabled. Picking one sends {@code AssignWorkshopWorkerPayload}, which performs
 * the real assignment and then opens that workshop's menu as confirmation.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class WorkshopPickerScreen extends PolishedScreen {
    private static final int PANEL_W = 280;
    private static final int ROW_H = 22;

    private final OpenWorkshopPickerPayload data;
    private int panelH;

    public WorkshopPickerScreen(OpenWorkshopPickerPayload data) {
        super(Component.translatable("bannerbound.workshop.picker_title"));
        this.data = data;
    }

    private int panelX() {
        return (this.width - PANEL_W) / 2;
    }

    private int panelY() {
        return (this.height - panelH) / 2;
    }

    @Override
    protected void init() {
        panelH = 58 + data.workshopIds().size() * ROW_H + 30;
        int x = panelX();
        int y = panelY();
        int rowY = y + 28;
        for (int i = 0; i < data.workshopIds().size(); i++) {
            final String workshopId = data.workshopIds().get(i);
            boolean valid = Workshop.Status.fromOrdinalOrDefault(data.statusOrdinals().get(i))
                == Workshop.Status.VALID;
            boolean hasRoom = data.assignedCounts().get(i) < data.capacities().get(i);
            // A workshop whose craft isn't researched can't be staffed (mirrors the server gate) —
            // disabled here with a tooltip so a station built on old ruins isn't a silent dead end.
            boolean known = ClientResearchState.isWorkshopTypeKnown(data.typeIds().get(i));
            PolishButton.Builder rowBtn = PolishButton.polished(rowLabel(i, known),
                    b -> {
                        PacketDistributor.sendToServer(new AssignWorkshopWorkerPayload(
                            workshopId, data.citizenId(), true, data.jobTypeId()));
                        this.onClose();
                    })
                .bounds(x + 12, rowY, PANEL_W - 24, 20)
                .accent(primaryAccent());
            if (!known) {
                rowBtn.tooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("bannerbound.workshop.assign_locked_tip")));
            }
            PolishButton btn = rowBtn.build();
            btn.active = valid && hasRoom && known;
            this.addRenderableWidget(btn);
            rowY += ROW_H;
        }
        this.addRenderableWidget(PolishButton.polished(Component.translatable("gui.cancel"),
                b -> this.onClose())
            .bounds(x + 12, y + panelH - 26, PANEL_W - 24, 20)
            .accent(primaryAccent())
            .build());
    }

    private Component rowLabel(int i, boolean known) {
        String custom = data.customNames().get(i);
        // Unknown craft: the derived-type name is hidden behind "Unknown Workshop" (a player-set
        // custom name is still shown — it reveals nothing about the unresearched craft).
        Component name = !custom.isEmpty()
            ? Component.literal(custom)
            : known
                ? Component.translatable(WorkBlockRegistry.displayKey(data.typeIds().get(i)))
                : Component.translatable("bannerbound.workshop.type_unknown");
        return name.copy().append(Component.literal(
                "  " + data.assignedCounts().get(i) + "/" + data.capacities().get(i))
            .withStyle(ChatFormatting.GRAY));
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = panelX();
        int y = panelY();
        drawIdentityPanel(g, x, y, PANEL_W, panelH, identityAccents);
        g.drawCenteredString(this.font, this.title, this.width / 2, y + 10, GuiPalette.TITLE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
