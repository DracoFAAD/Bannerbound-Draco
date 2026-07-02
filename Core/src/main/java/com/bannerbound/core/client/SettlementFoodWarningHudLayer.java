package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.SettlementFoodWarningPayload;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Centre-top HUD banner shown to EVERY settlement member when food is running out. Driven by the
 * server-broadcast {@link SettlementFoodWarningPayload} held in {@link ClientFoodWarningState}:
 * amber "Food running low" while food is below the low threshold, red "Your settlement is STARVING"
 * at zero. Honours the vanilla GUI scale (positions off {@code graphics.guiWidth()}).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class SettlementFoodWarningHudLayer implements LayeredDraw.Layer {
    public static final SettlementFoodWarningHudLayer INSTANCE = new SettlementFoodWarningHudLayer();

    private SettlementFoodWarningHudLayer() {}

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        int level = ClientFoodWarningState.level();
        if (level == SettlementFoodWarningPayload.LEVEL_OK) return;

        boolean starving = level == SettlementFoodWarningPayload.LEVEL_STARVING;
        Component msg = Component.translatable(starving
            ? "bannerbound.food.warning.starving"
            : "bannerbound.food.warning.low");
        int barColor = starving ? 0xFFE03030 : 0xFFE0A030;

        // Pulse the starving banner so it can't be ignored; the low banner stays steady.
        int alpha = 0xFF;
        if (starving) {
            float pulse = 0.65f + 0.35f * (float) Math.sin(System.currentTimeMillis() / 250.0);
            alpha = Math.min(255, Math.max(0, (int) (pulse * 255.0f)));
        }
        int textColor = (alpha << 24) | (barColor & 0xFFFFFF);

        int textW = mc.font.width(msg);
        int width = textW + 20;
        int x = (graphics.guiWidth() - width) / 2;
        int y = 24;
        graphics.fill(x, y, x + width, y + 16, 0xB0000000);
        graphics.fill(x, y, x + 3, y + 16, barColor);
        graphics.fill(x + width - 3, y, x + width, y + 16, barColor);
        graphics.drawString(mc.font, msg, x + (width - textW) / 2, y + 4, textColor, true);
    }
}
