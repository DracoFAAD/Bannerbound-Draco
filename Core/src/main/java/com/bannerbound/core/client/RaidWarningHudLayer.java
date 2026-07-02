package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Centre-top pulsing red banner shown to every settlement member while a barbarian raid is underway —
 * the raid analogue of the starving banner. Driven by {@link ClientRaidWarningState}. Sits just below
 * the food banner's row so the two don't overlap.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class RaidWarningHudLayer implements LayeredDraw.Layer {
    public static final RaidWarningHudLayer INSTANCE = new RaidWarningHudLayer();

    private RaidWarningHudLayer() {}

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        if (!ClientRaidWarningState.active()) return;

        Component msg = Component.translatable("bannerbound.barbarian.raid_hud");
        int barColor = 0xFFE03030;
        // Pulse so it reads as an active emergency.
        float pulse = 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 220.0);
        int alpha = Math.min(255, Math.max(0, (int) (pulse * 255.0f)));
        int textColor = (alpha << 24) | (barColor & 0xFFFFFF);

        int textW = mc.font.width(msg);
        int width = textW + 20;
        int x = (graphics.guiWidth() - width) / 2;
        int y = 44; // one row below the food banner (y=24)
        graphics.fill(x, y, x + width, y + 16, 0xB0000000);
        graphics.fill(x, y, x + 3, y + 16, barColor);
        graphics.fill(x + width - 3, y, x + width, y + 16, barColor);
        graphics.drawString(mc.font, msg, x + (width - textW) / 2, y + 4, textColor, true);
    }
}
