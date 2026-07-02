package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Small radial-arc drawing helpers (extracted from the research-tree blueprint arcs) for the
 * happiness pillar gauges and any other ring UI. Integer pixel rasterisation, no anti-aliasing —
 * matches Minecraft's pixelated style. Screen space: +Y is down, so {@code -π/2} is straight up and
 * positive sweep goes clockwise.
 */
@ApiStatus.Internal
public final class Arcs {
    private Arcs() {}

    /** One pixel-wide arc: {@code drawFrac} of a {@code sweepRad} arc from {@code startRad}. */
    public static void drawArc(GuiGraphics graphics, int cx, int cy, int r,
                               double startRad, double sweepRad, float drawFrac, int color) {
        int segs = Math.max(8, (int) (Math.abs(sweepRad) / (Math.PI * 2) * Math.max(12, r * 3)));
        int upto = (int) (segs * Math.min(1f, Math.max(0f, drawFrac)));
        for (int s = 0; s < upto; s++) {
            double a0 = startRad + sweepRad * (s / (double) segs);
            double a1 = startRad + sweepRad * ((s + 1) / (double) segs);
            drawSegment(graphics,
                cx + (int) Math.round(Math.cos(a0) * r), cy + (int) Math.round(Math.sin(a0) * r),
                cx + (int) Math.round(Math.cos(a1) * r), cy + (int) Math.round(Math.sin(a1) * r), color);
        }
    }

    /** A {@code thickness}-pixel ring: concentric arcs from {@code r} inward. */
    public static void drawRing(GuiGraphics graphics, int cx, int cy, int r, int thickness,
                                double startRad, double sweepRad, float drawFrac, int color) {
        for (int t = 0; t < Math.max(1, thickness); t++) {
            drawArc(graphics, cx, cy, r - t, startRad, sweepRad, drawFrac, color);
        }
    }

    private static void drawSegment(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int steps = Math.max(dx, dy);
        if (steps == 0) { graphics.fill(x0, y0, x0 + 1, y0 + 1, color); return; }
        for (int s = 0; s <= steps; s++) {
            int x = x0 + (x1 - x0) * s / steps;
            int y = y0 + (y1 - y0) * s / steps;
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }
}
