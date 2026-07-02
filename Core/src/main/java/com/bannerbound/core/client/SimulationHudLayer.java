package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Top-center debug overlay for the {@code /bannerbound simulate} stress test. The headline line is
 * <b>believed vs rendered</b> — the ratio we're trying to make convincing — plus per-tier mover
 * counts, client FPS, and the server's ms/tick (which should barely move, proving the crowd is
 * client-only). Only drawn while a simulation is active.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class SimulationHudLayer implements LayeredDraw.Layer {
    public static final SimulationHudLayer INSTANCE = new SimulationHudLayer();

    private SimulationHudLayer() {
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        if (!ClientSimulationState.isActive() || !ClientSimulationState.isDebug()) return;

        Font font = mc.font;
        int believed = ClientSimulationState.believedPopulation();
        int real = ClientSimulationState.realCount();
        int near = ClientSimulationState.lastNear;
        int mid = ClientSimulationState.lastMid;
        int far = ClientSimulationState.lastFar;
        int culled = ClientSimulationState.lastCulled;
        int renderedMovers = near + mid + far;
        int rendered = real + renderedMovers;

        String[] lines = {
            "§6CROWD SIMULATION§r   (" + ClientSimulationState.remainingSeconds() + "s left)",
            String.format("Believed: §e%,d§r    Rendered: §a%d§r (%d real + %d movers)",
                believed, rendered, real, renderedMovers),
            String.format("Movers  near §f%d§r  mid §f%d§r  far §f%d§r   culled §8%d§r",
                near, mid, far, culled),
            String.format("Client §b%d FPS§r    Server §d%.2f ms/tick§r",
                mc.getFps(), ClientSimulationState.serverMsPerTick()),
        };

        int width = 0;
        for (String l : lines) width = Math.max(width, font.width(l));
        int pad = 4;
        int boxW = width + pad * 2;
        int boxH = lines.length * (font.lineHeight + 1) + pad * 2 - 1;
        int left = (graphics.guiWidth() - boxW) / 2;
        int top = 4;
        graphics.fill(left, top, left + boxW, top + boxH, 0xA0000000);

        int y = top + pad;
        for (String l : lines) {
            graphics.drawString(font, l, left + pad, y, 0xFFFFFFFF, true);
            y += font.lineHeight + 1;
        }
    }
}
