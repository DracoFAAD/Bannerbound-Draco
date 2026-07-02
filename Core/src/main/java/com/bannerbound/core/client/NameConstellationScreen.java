package com.bannerbound.core.client;

import com.bannerbound.core.api.faith.DeityDomain;
import com.bannerbound.core.celestial.SkyField;
import com.bannerbound.core.client.sky.ClientSkyState;
import com.bannerbound.core.client.sky.PantheonMode;
import com.bannerbound.core.network.SubmitConstellationPayload;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The naming prompt at Pantheon-mode confirm (FAITH_PLAN Part 3): constellation name →
 * deity name → submit. Cancel returns to the mode with the draft intact. Shows the
 * domain preview (Star Charts gated — without it the heavens keep their secret).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class NameConstellationScreen extends PolishedScreen {
    private static final int PANEL_W = 240;
    private static final int PANEL_H = 168;
    private static final String STAR_CHARTS_NODE = "bannerboundantiquity:star_charts";

    private EditBox nameBox;
    private EditBox deityBox;
    private Button confirmBtn;

    public NameConstellationScreen() {
        super(Component.translatable("bannerbound.pantheon.name.title"));
    }

    @Override
    protected void init() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        int boxX = panelX + (PANEL_W - 200) / 2;

        nameBox = new EditBox(this.font, boxX, panelY + 40, 200, 18,
            Component.translatable("bannerbound.pantheon.name.constellation"));
        nameBox.setMaxLength(SubmitConstellationPayload.MAX_NAME_LENGTH);
        nameBox.setHint(Component.translatable("bannerbound.pantheon.name.constellation")
            .withStyle(ChatFormatting.DARK_GRAY));
        nameBox.setResponder(t -> refresh());
        addRenderableWidget(nameBox);

        deityBox = new EditBox(this.font, boxX, panelY + 68, 200, 18,
            Component.translatable("bannerbound.pantheon.name.deity"));
        deityBox.setMaxLength(SubmitConstellationPayload.MAX_NAME_LENGTH);
        deityBox.setHint(Component.translatable("bannerbound.pantheon.name.deity")
            .withStyle(ChatFormatting.DARK_GRAY));
        deityBox.setResponder(t -> refresh());
        addRenderableWidget(deityBox);

        confirmBtn = PolishButton.polished(
            Component.translatable("bannerbound.pantheon.name.confirm"),
            b -> {
                PacketDistributor.sendToServer(new SubmitConstellationPayload(
                    nameBox.getValue().trim(), deityBox.getValue().trim(),
                    PantheonMode.chainArray()));
                PantheonMode.exit();
                this.onClose();
            }
        ).bounds(boxX, panelY + PANEL_H - 54, 200, 20).build();
        addRenderableWidget(confirmBtn);

        addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.cancel"),
            b -> this.onClose() // draft survives — back to the sky
        ).bounds(boxX, panelY + PANEL_H - 28, 200, 20).build());

        refresh();
    }

    private void refresh() {
        if (confirmBtn != null) {
            confirmBtn.active = nameBox != null && deityBox != null
                && !nameBox.getValue().trim().isEmpty()
                && !deityBox.getValue().trim().isEmpty();
        }
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xC0202020);
        g.renderOutline(panelX, panelY, PANEL_W, PANEL_H, 0xFFE8D9A0);
        g.drawCenteredString(this.font,
            Component.translatable("bannerbound.pantheon.name.title").withStyle(ChatFormatting.GOLD),
            panelX + PANEL_W / 2, panelY + 12, 0xFFFFFFFF);
        g.drawCenteredString(this.font, domainPreview(),
            panelX + PANEL_W / 2, panelY + 96, 0xFFE8D9A0);
    }

    /** Primary (+secondary) domain from the draft's typed stars — "?" without Star Charts. */
    private Component domainPreview() {
        SkyField sky = ClientSkyState.field();
        if (sky == null || !ClientFaithTreeState.isCompleted(STAR_CHARTS_NODE)) {
            return Component.translatable("bannerbound.pantheon.name.domain_unknown")
                .withStyle(ChatFormatting.GRAY);
        }
        java.util.Map<DeityDomain, Integer> counts = new java.util.EnumMap<>(DeityDomain.class);
        for (int id : PantheonMode.chain()) {
            SkyField.Star typed = sky.typedStar(id);
            if (typed != null) {
                counts.merge(DeityDomain.fromStarType(typed.type), 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return Component.translatable("bannerbound.pantheon.name.no_typed")
                .withStyle(ChatFormatting.RED);
        }
        DeityDomain primary = null;
        int best = 0;
        for (java.util.Map.Entry<DeityDomain, Integer> e : counts.entrySet()) {
            if (e.getValue() > best) {
                primary = e.getKey();
                best = e.getValue();
            }
        }
        DeityDomain secondary = null;
        int secondBest = 0;
        for (java.util.Map.Entry<DeityDomain, Integer> e : counts.entrySet()) {
            if (e.getKey() != primary && e.getValue() >= 2 && e.getValue() > secondBest) {
                secondary = e.getKey();
                secondBest = e.getValue();
            }
        }
        Component primaryName = Component.translatable(
            "bannerbound.domain." + primary.name().toLowerCase(java.util.Locale.ROOT));
        if (secondary == null) {
            return Component.translatable("bannerbound.pantheon.name.domain_pure", primaryName);
        }
        return Component.translatable("bannerbound.pantheon.name.domain_hybrid", primaryName,
            Component.translatable("bannerbound.domain." + secondary.name().toLowerCase(java.util.Locale.ROOT)));
    }
}
