package com.bannerbound.core.client;

import java.util.List;

import com.bannerbound.core.api.faith.DeityDomain;
import com.bannerbound.core.client.sky.ClientConstellationState;
import com.bannerbound.core.network.ConstellationsSyncPayload;
import com.bannerbound.core.network.ForgetConstellationPayload;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The pantheon roster (FAITH_PLAN Part 6): every drawn god — deity, constellation,
 * domain — with a governance-gated Forget action per row (the server re-validates;
 * non-leaders get the leader-only message). Forgetting frees the stars; no refund.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class PantheonScreen extends PolishedScreen {
    private static final int PANEL_W = 280;
    private static final int ROW_H = 26;

    @Nullable private final Screen parent;

    public PantheonScreen(@Nullable Screen parent) {
        super(Component.translatable("bannerbound.pantheon.list.title"));
        this.parent = parent;
    }

    private int panelH() {
        return 64 + Math.max(1, ClientConstellationState.count()) * ROW_H;
    }

    /** "+0.19 food/s · +0.10 science/s" for a god's domain profile (client-computed from
     *  the shared effect table). */
    private static Component effectText(int primaryOrdinal, int secondaryOrdinal) {
        com.bannerbound.core.api.faith.DeityDomain primary =
            com.bannerbound.core.api.faith.DeityDomain.fromOrdinal(primaryOrdinal);
        com.bannerbound.core.api.faith.DeityDomain secondary = secondaryOrdinal < 0
            ? null : com.bannerbound.core.api.faith.DeityDomain.fromOrdinal(secondaryOrdinal);
        java.util.List<com.bannerbound.core.api.faith.FaithEffects.FaithEffect> effects =
            com.bannerbound.core.api.faith.FaithEffects.effectsFor(primary, secondary);
        if (effects.isEmpty()) {
            return Component.translatable("bannerbound.faith.effect.none");
        }
        net.minecraft.network.chat.MutableComponent out = Component.empty();
        for (int i = 0; i < effects.size(); i++) {
            com.bannerbound.core.api.faith.FaithEffects.FaithEffect e = effects.get(i);
            if (i > 0) out.append(Component.literal(" · "));
            out.append(Component.literal(String.format("+%.2f ", e.value()))
                .append(Component.translatable("bannerbound.faith.effecttype."
                    + e.type().name().toLowerCase(java.util.Locale.ROOT))));
        }
        return out;
    }

    @Override
    protected void init() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelH()) / 2;
        List<ConstellationsSyncPayload.Entry> entries = ClientConstellationState.entries();
        int y = panelY + 30;
        for (ConstellationsSyncPayload.Entry entry : entries) {
            final String id = entry.id();
            addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.pantheon.list.forget")
                    .withStyle(ChatFormatting.RED),
                b -> {
                    PacketDistributor.sendToServer(new ForgetConstellationPayload(id));
                    this.onClose();
                }
            ).bounds(panelX + PANEL_W - 70, y + 2, 58, 18).build());
            y += ROW_H;
        }
        addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.back"),
            b -> this.onClose()
        ).bounds(panelX + (PANEL_W - 160) / 2, panelY + panelH() - 26, 160, 20).build());
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelH()) / 2;
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH(), 0xC0202020);
        g.renderOutline(panelX, panelY, PANEL_W, panelH(), 0xFFE8D9A0);
        g.drawCenteredString(this.font,
            Component.translatable("bannerbound.pantheon.list.title").withStyle(ChatFormatting.GOLD),
            panelX + PANEL_W / 2, panelY + 10, 0xFFFFFFFF);

        List<ConstellationsSyncPayload.Entry> entries = ClientConstellationState.entries();
        if (entries.isEmpty()) {
            g.drawCenteredString(this.font,
                Component.translatable("bannerbound.pantheon.list.empty").withStyle(ChatFormatting.GRAY),
                panelX + PANEL_W / 2, panelY + 36, 0xFFCCCCCC);
            return;
        }
        int y = panelY + 30;
        for (ConstellationsSyncPayload.Entry entry : entries) {
            Component domain = Component.translatable("bannerbound.domain."
                + DeityDomain.fromOrdinal(entry.primaryDomain()).name().toLowerCase(java.util.Locale.ROOT));
            Component line = Component.literal(entry.deityName()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" — " + entry.name() + " (").withStyle(ChatFormatting.WHITE))
                .append(domain)
                .append(Component.literal(")").withStyle(ChatFormatting.WHITE));
            g.drawString(this.font, line, panelX + 12, y + 3, 0xFFFFFFFF);
            g.drawString(this.font, effectText(entry.primaryDomain(), entry.secondaryDomain())
                .copy().withStyle(ChatFormatting.GRAY), panelX + 12, y + 14, 0xFFAAAAAA);
            y += ROW_H;
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
