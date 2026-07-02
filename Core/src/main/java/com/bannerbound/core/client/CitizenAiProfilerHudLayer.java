package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.sim.CitizenAiProfiler;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Debug overlay (toggle with {@code /bannerbound ai_profiler}) showing the server-side citizen tick
 * cost: total ms/tick across all loaded citizens, the count, and the average µs per citizen — so we
 * can directly compare a Tribe (work-scanning on) vs a Village (cheap brain) and see if it's cheaper.
 * Reads the {@link CitizenAiProfiler} snapshot directly (same JVM in single-player).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class CitizenAiProfilerHudLayer implements LayeredDraw.Layer {
    public static final CitizenAiProfilerHudLayer INSTANCE = new CitizenAiProfilerHudLayer();

    private CitizenAiProfilerHudLayer() {
    }

    /** Citizen count to extrapolate to — a busy server (≈8 settlements × ~50 loaded citizens). */
    private static final int PROJECT_CITIZENS = 400;
    /** 20 TPS budget: a server tick must finish in 50 ms or TPS drops. */
    private static final double TICK_BUDGET_MS = 50.0;
    /** Rough single-thread slowdown vs a top-tier CPU (heuristic, NOT a benchmark). */
    private static final double MID_FACTOR = 1.8;   // mid-range desktop
    private static final double LOW_FACTOR = 3.5;   // low-end / old / handheld

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        if (!CitizenAiProfiler.enabled()) return;

        Font font = mc.font;
        double usEach = CitizenAiProfiler.lastMicrosPerCitizen();
        // Project: this CPU's per-citizen cost × a busy-server count, then × slower-CPU factors.
        double projThis = usEach * PROJECT_CITIZENS / 1000.0;       // ms on THIS cpu
        double projMid = projThis * MID_FACTOR;
        double projLow = projThis * LOW_FACTOR;

        String[] lines = {
            String.format("§eCitizen AI (server)§r  %.2f ms/tick", CitizenAiProfiler.lastMsPerTick()),
            String.format("%d loaded  ·  %.1f µs / citizen", CitizenAiProfiler.lastCount(), usEach),
            String.format("§7proj. @%d citizens (of 50ms budget):§r", PROJECT_CITIZENS),
            String.format("  this CPU %s%.1f ms§r (%d%%)", col(projThis), projThis, pct(projThis)),
            String.format("  mid ×%.1f %s%.1f ms§r (%d%%)", MID_FACTOR, col(projMid), projMid, pct(projMid)),
            String.format("  low ×%.1f %s%.1f ms§r (%d%%)", LOW_FACTOR, col(projLow), projLow, pct(projLow)),
        };

        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        w += 8;
        int lineH = font.lineHeight + 1;
        int x = 4, y = graphics.guiHeight() - 8 - lineH * lines.length;
        graphics.fill(x, y, x + w, y + 3 + lineH * lines.length, 0xC0000000);
        int yy = y + 3;
        for (String l : lines) {
            graphics.drawString(font, l, x + 4, yy, 0xFFFFFFFF, true);
            yy += lineH;
        }
    }

    private static int pct(double ms) {
        return (int) Math.round(ms / TICK_BUDGET_MS * 100.0);
    }

    /** Green < 50% of budget, yellow < 100%, red over budget. */
    private static String col(double ms) {
        double frac = ms / TICK_BUDGET_MS;
        return frac < 0.5 ? "§a" : frac < 1.0 ? "§e" : "§c";
    }
}
