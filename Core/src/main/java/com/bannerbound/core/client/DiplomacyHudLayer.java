package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.DiplomacyObjectivePayload;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class DiplomacyHudLayer implements LayeredDraw.Layer {
    public static final DiplomacyHudLayer INSTANCE = new DiplomacyHudLayer();

    private DiplomacyHudLayer() {}

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        DiplomacyObjectivePayload objective = ClientDiplomacyState.objective();
        if (objective == null || !objective.active()) return;
        int width = Math.max(mc.font.width(objective.title()), mc.font.width(objective.subtitle())) + 16;
        int x = graphics.guiWidth() - width - 10;
        int y = 34;
        graphics.fill(x, y, x + width, y + 31, 0xB0000000);
        graphics.fill(x, y, x + 3, y + 31, 0xFF000000 | (objective.colorRgb() & 0xFFFFFF));
        graphics.drawString(mc.font, Component.literal(objective.title()), x + 8, y + 6,
            0xFFFFFFFF, false);
        graphics.drawString(mc.font, Component.literal(objective.subtitle()), x + 8, y + 18,
            0xFFB8B8B8, false);
    }
}
