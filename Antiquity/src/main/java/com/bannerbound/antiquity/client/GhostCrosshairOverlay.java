package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Draws a small green plus over the crosshair whenever it's aimed at a clickable floating ghost target
 * (a workstation's recipe preview / browse arrows) — the affordance that says "you can right-click
 * this". Reuses {@link GhostClickTargets#findHovered} so it lights up under exactly the same conditions
 * the click handler ({@code GhostClickEvents}) acts, for every ghost workstation (crafting stone,
 * fletching station, carpenter's table).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class GhostCrosshairOverlay {
    private static final int GREEN = 0xFF53E85A;

    private GhostCrosshairOverlay() {}

    @SubscribeEvent
    static void onCrosshair(RenderGuiLayerEvent.Post event) {
        if (!VanillaGuiLayers.CROSSHAIR.equals(event.getName())) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.options.getCameraType() != CameraType.FIRST_PERSON) return;
        // Lights up for the shared ghost picker/arrows AND the carpenter's-table queue chips.
        if (GhostClickTargets.findHovered(mc) == null
                && CarpentryReadoutRenderer.findHoveredQueue(mc) == null) return;

        GuiGraphics g = event.getGuiGraphics();
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;
        g.fill(cx - 6, cy - 1, cx + 6, cy + 1, GREEN);
        g.fill(cx - 1, cy - 6, cx + 1, cy + 6, GREEN);
    }
}
