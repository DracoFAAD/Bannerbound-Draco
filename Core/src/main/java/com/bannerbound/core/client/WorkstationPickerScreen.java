package com.bannerbound.core.client;

import com.bannerbound.core.api.settlement.Workstation;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.UUID;

import com.bannerbound.core.network.AssignCitizenToWorkstationPayload;
import com.bannerbound.core.network.WorkstationListPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Picker screen opened from the citizen detail screen's "Assign to Workstation" button. Lists
 * every workstation in the settlement; clicking one assigns the focal citizen to that station.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class WorkstationPickerScreen extends PolishedScreen {
    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 200;
    private static final int ROW_HEIGHT = 22;

    private final UUID citizenId;
    private final List<WorkstationListPayload.Entry> stations;

    public WorkstationPickerScreen(UUID citizenId, List<WorkstationListPayload.Entry> stations) {
        super(Component.translatable("bannerbound.citizen.pick_workstation"));
        this.citizenId = citizenId;
        this.stations = stations;
    }

    @Override
    protected void init() {
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = (this.height - PANEL_HEIGHT) / 2;

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            drawIdentityPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, identityAccents);
            graphics.drawCenteredString(this.font, this.getTitle(),
                panelX + PANEL_WIDTH / 2, panelY + 10, GuiPalette.TITLE);

            if (stations.isEmpty()) {
                graphics.drawCenteredString(this.font,
                    Component.translatable("bannerbound.citizen.no_workstations")
                        .withStyle(ChatFormatting.GRAY),
                    panelX + PANEL_WIDTH / 2, panelY + 50, 0xFFAAAAAA);
            }
        });

        int listTop = panelY + 30;
        for (int i = 0; i < stations.size() && i < 6; i++) {
            WorkstationListPayload.Entry entry = stations.get(i);
            Component label = labelFor(entry);
            this.addRenderableWidget(PolishButton.polished(
                    label,
                    btn -> {
                        PacketDistributor.sendToServer(
                            new AssignCitizenToWorkstationPayload(entry.pos(), citizenId));
                        this.onClose();
                    })
                .bounds(panelX + 12, listTop + i * ROW_HEIGHT, PANEL_WIDTH - 24, 20)
                .accent(primaryAccent())
                .build());
        }

        this.addRenderableWidget(PolishButton.polished(
                Component.translatable("gui.cancel"),
                btn -> this.onClose())
            .bounds(panelX + 12, panelY + PANEL_HEIGHT - 28, PANEL_WIDTH - 24, 20)
            .accent(primaryAccent())
            .build());
    }

    /**
     * Renders an entry as "Forester's Log (worker: Magnus)" or "Forester's Log (vacant)" so the
     * player can see who they're displacing before clicking. The worker-name part is colored
     * green when it matches the focal citizen — disambiguates two same-named workers ("which
     * Vlad is at the Forester's Log?") at a glance.
     */
    private Component labelFor(WorkstationListPayload.Entry entry) {
        String stationType = stationDisplayName(entry.type());
        if (entry.currentWorker() == null) {
            return Component.literal(stationType + " (vacant)");
        }
        String workerName = entry.currentWorkerName() == null ? "?" : entry.currentWorkerName();
        MutableComponent namePart = Component.literal(workerName)
            .withStyle(entry.currentWorker().equals(citizenId) ? ChatFormatting.GREEN : ChatFormatting.WHITE);
        return Component.literal(stationType + " (worker: ")
            .append(namePart)
            .append(Component.literal(")"));
    }

    private static String stationDisplayName(String type) {
        return switch (type) {
            case "foresters_log" -> "Forester's Log";
            default -> type;
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
