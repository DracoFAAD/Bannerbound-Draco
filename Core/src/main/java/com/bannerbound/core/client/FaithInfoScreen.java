package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.faith.FaithPath;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The Faith tab skeleton (FAITH_PLAN.md Part 6, M1 form): a read-only snapshot of the
 * settlement's faith from {@link ClientFaithState} — name, path, member settlements,
 * devotion. Grows into the full Faith tab (pantheon, demands, Pantheon-mode button,
 * apostasy vote) in later milestones.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class FaithInfoScreen extends PolishedScreen {
    private static final int PANEL_W = 220;

    @Nullable private final Screen parent;

    public FaithInfoScreen(@Nullable Screen parent) {
        super(Component.translatable("bannerbound.faith.info.title"));
        this.parent = parent;
    }

    /** Astrology faiths get the Pantheon rows (enter mode + roster) — the panel grows. */
    private int panelH() {
        return ClientFaithState.pathOrdinal()
            == com.bannerbound.core.api.faith.FaithPath.ASTROLOGY.ordinal() ? 206 : 156;
    }

    @Override
    protected void init() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelH()) / 2;
        int btnX = panelX + (PANEL_W - 160) / 2;
        int y = panelY + panelH() - 28;
        addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.back"),
            b -> this.onClose()
        ).bounds(btnX, y, 160, 20).build());
        y -= 24;
        // Apostasy (FAITH_PLAN Part 1): chief/owner resolve instantly; COUNCIL members each
        // click to add a yes-vote — the server broadcasts progress and resolves at half.
        addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.faith.abandon")
                .withStyle(net.minecraft.ChatFormatting.RED),
            b -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.bannerbound.core.network.AbandonFaithPayload());
                this.onClose();
            }
        ).bounds(btnX, y, 160, 20).build());
        if (ClientFaithState.pathOrdinal()
                == com.bannerbound.core.api.faith.FaithPath.ASTROLOGY.ordinal()) {
            y -= 24;
            // The pantheon roster — gods drawn so far, with the Forget action.
            addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.faith.pantheon_list"),
                b -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new PantheonScreen(this));
                    }
                }
            ).bounds(btnX, y, 160, 20).build());
            y -= 24;
            // The doorway to the sky (FAITH_PLAN M2): close everything, look up.
            // Greyed when the pantheon is full or the stars aren't charted yet — the
            // tooltip says which.
            boolean charted = ClientFaithTreeState.isCompleted(
                com.bannerbound.core.client.sky.PantheonMode.STAR_CHARTS_NODE);
            boolean full = com.bannerbound.core.client.sky.ClientConstellationState.count()
                >= ClientFaithTreeState.pantheonCap();
            net.minecraft.client.gui.components.Button enterBtn = PolishButton.polished(
                Component.translatable("bannerbound.faith.enter_pantheon"),
                b -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(null);
                        com.bannerbound.core.client.sky.PantheonMode.enter(this.minecraft);
                    }
                }
            ).bounds(btnX, y, 160, 20).build();
            enterBtn.active = charted && !full;
            if (!charted) {
                enterBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("bannerbound.pantheon.uncharted")));
            } else if (full) {
                enterBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("bannerbound.faith.pantheon_full")));
            }
            addRenderableWidget(enterBtn);
        }
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelH()) / 2;

        g.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH(), 0xC0202020);
        g.renderOutline(panelX, panelY, PANEL_W, panelH(), 0xFFCCCCCC);

        g.drawCenteredString(this.font,
            Component.literal(ClientFaithState.faithName()).withStyle(ChatFormatting.GOLD),
            panelX + PANEL_W / 2, panelY + 14, 0xFFFFFFFF);

        String pathKey = ClientFaithState.pathOrdinal() == FaithPath.TOTEMIC.ordinal()
            ? "bannerbound.faith.path.totemic" : "bannerbound.faith.path.astrology";
        g.drawCenteredString(this.font,
            Component.translatable(pathKey).withStyle(ChatFormatting.GRAY),
            panelX + PANEL_W / 2, panelY + 30, 0xFFCCCCCC);

        g.drawCenteredString(this.font,
            Component.translatable("bannerbound.faith.info.members",
                ClientFaithState.memberSettlements()),
            panelX + PANEL_W / 2, panelY + 52, 0xFFE8E2D0);

        g.drawCenteredString(this.font,
            Component.translatable("bannerbound.faith.info.devotion",
                String.format("%.1f", ClientFaithState.devotionStored()),
                String.format("%.2f", ClientFaithState.devotionPerSecond())),
            panelX + PANEL_W / 2, panelY + 68, 0xFFE8E2D0);

        // God slots — drawn gods vs research-driven cap (astrology only).
        if (ClientFaithState.pathOrdinal()
                == com.bannerbound.core.api.faith.FaithPath.ASTROLOGY.ordinal()) {
            g.drawCenteredString(this.font,
                Component.translatable("bannerbound.faith.info.slots",
                    com.bannerbound.core.client.sky.ClientConstellationState.count(),
                    ClientFaithTreeState.pantheonCap()),
                panelX + PANEL_W / 2, panelY + 84, 0xFFE8D9A0);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && parent != null) {
            this.minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }
}
