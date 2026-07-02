package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import com.bannerbound.core.network.AssignCitizenToWorkstationPayload;
import com.bannerbound.core.network.CitizenListPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Picker screen opened from a workstation's "Assign Worker" button. Lists every unemployed
 * citizen in the settlement; clicking one fires {@link AssignCitizenToWorkstationPayload} with
 * the citizen UUID and the workstation pos that opened this screen.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class WorkerPickerScreen extends PolishedScreen {
    private static final int PANEL_WIDTH = 220;
    private static final int PANEL_HEIGHT = 200;
    private static final int ROW_HEIGHT = 22;

    private final BlockPos workstationPos;
    private final List<CitizenListPayload.Entry> candidates;

    public WorkerPickerScreen(BlockPos workstationPos, List<CitizenListPayload.Entry> candidates) {
        super(Component.translatable("bannerbound.workstation.pick_worker"));
        this.workstationPos = workstationPos;
        this.candidates = candidates;
    }

    @Override
    protected void init() {
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = (this.height - PANEL_HEIGHT) / 2;

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            drawIdentityPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, identityAccents);
            graphics.drawCenteredString(this.font, this.getTitle(),
                panelX + PANEL_WIDTH / 2, panelY + 10, GuiPalette.TITLE);

            if (candidates.isEmpty()) {
                graphics.drawCenteredString(this.font,
                    Component.translatable("bannerbound.workstation.no_unemployed")
                        .withStyle(ChatFormatting.GRAY),
                    panelX + PANEL_WIDTH / 2, panelY + 50, 0xFFAAAAAA);
            }
        });

        int listTop = panelY + 30;
        for (int i = 0; i < candidates.size() && i < 6; i++) {
            CitizenListPayload.Entry entry = candidates.get(i);
            this.addRenderableWidget(PolishButton.polished(
                    Component.literal(entry.name()),
                    btn -> {
                        PacketDistributor.sendToServer(
                            new AssignCitizenToWorkstationPayload(workstationPos, entry.id()));
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
