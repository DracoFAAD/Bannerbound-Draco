package com.bannerbound.core.client.sky;

import com.bannerbound.core.api.faith.DeityDomain;
import com.bannerbound.core.celestial.SkyField;
import com.bannerbound.core.client.ClientFaithTreeState;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Pantheon-mode HUD strip (FAITH_PLAN Part 3): chain size, the key legend, and — once
 * Star Charts is researched — the hovered star's type/domain. Hidden outside the mode.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class PantheonHudLayer implements LayeredDraw.Layer {
    public static final PantheonHudLayer INSTANCE = new PantheonHudLayer();
    private static final String STAR_CHARTS_NODE = "bannerboundantiquity:star_charts";

    private PantheonHudLayer() {
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!PantheonMode.isActive() || mc.options.hideGui || mc.player == null) return;

        Component title = Component.translatable("bannerbound.pantheon.hud.title",
                PantheonMode.chain().size(), PantheonMode.MAX_STARS)
            .withStyle(ChatFormatting.GOLD);
        Component legend = Component.translatable("bannerbound.pantheon.hud.legend")
            .withStyle(ChatFormatting.GRAY);

        int cx = graphics.guiWidth() / 2;
        int y = 8;
        int w = Math.max(mc.font.width(title), mc.font.width(legend)) + 12;
        graphics.fill(cx - w / 2, y - 3, cx + w / 2, y + 24, 0x90000000);
        graphics.drawCenteredString(mc.font, title, cx, y, 0xFFFFFFFF);
        graphics.drawCenteredString(mc.font, legend, cx, y + 12, 0xFFCCCCCC);

        // Hovered star readout — type knowledge is Star Charts gated (FAITH_PLAN).
        int hovered = PantheonMode.hoveredStarId();
        SkyField sky = ClientSkyState.field();
        if (hovered >= 0 && sky != null && ClientFaithTreeState.isCompleted(STAR_CHARTS_NODE)) {
            SkyField.Star typed = sky.typedStar(hovered);
            Component info = typed == null
                ? Component.translatable("bannerbound.pantheon.hud.common").withStyle(ChatFormatting.WHITE)
                : Component.translatable("bannerbound.domain."
                        + DeityDomain.fromStarType(typed.type).name().toLowerCase(java.util.Locale.ROOT))
                    .withStyle(ChatFormatting.YELLOW);
            graphics.drawCenteredString(mc.font, info, cx, y + 26, 0xFFFFFFFF);
        }
    }
}
