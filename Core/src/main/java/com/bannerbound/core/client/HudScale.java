package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Shared scale factor for the top-left HUD cluster (era/year banner, the "Currently in …" line and
 * the journal tracker).
 * <p>
 * Each of those layers is a fixed number of GUI-scaled pixels, so its on-screen fraction depends
 * only on the scaled resolution (physicalRes / guiScale). With a fixed GUI scale a small monitor —
 * or simply a high GUI scale — gets a small scaled resolution and the panels balloon to fill it,
 * while a 4K monitor barely notices. Every layer in the cluster wraps its draw in
 * {@code pose().scale(factor(mc))} so they shrink together against a common reference and keep
 * reading as one stacked group at any GUI scale / resolution.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class HudScale {
    /** Scaled resolution at which the cluster looks right at 1.0× (the reference size). */
    public static final float REFERENCE_WIDTH = 900f;
    public static final float REFERENCE_HEIGHT = 506f;
    /** Never shrink past this fraction, or the text becomes unreadable. */
    public static final float MIN_SCALE = 0.5f;

    private HudScale() {
    }

    /**
     * The factor the top-left cluster should be scaled by for the current window: 1.0 on a
     * reference-or-larger scaled resolution, shrinking down to {@link #MIN_SCALE} on small ones.
     */
    public static float factor(Minecraft mc) {
        int rawW = mc.getWindow().getGuiScaledWidth();
        int rawH = mc.getWindow().getGuiScaledHeight();
        return Math.max(MIN_SCALE,
            Math.min(1f, Math.min(rawW / REFERENCE_WIDTH, rawH / REFERENCE_HEIGHT)));
    }
}
