package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Per-screen transient "+" pop near the cursor when a vote / suggestion click is sent. The
 * vote-tally chat broadcasts were removed — this is the replacement feedback: a green plus
 * floats up over ~600ms then fades. One instance lives on each voting screen; the screen
 * calls {@link #spawn} on click and {@link #render} from its own render pass.
 *
 * <p>Lightweight by design: no entity, no scheduler, just a list of pops we age by wallclock
 * delta inside {@link #render}. Render order is whatever the screen places this call at —
 * call it AFTER the rest of the UI so the "+" sits on top.
 */
public final class TransientClickFeedback {
    /** Total lifetime of a single pop in milliseconds — short so multiple clicks don't pile up. */
    private static final long LIFETIME_MS = 600L;
    /** Pixels the pop floats upward over its lifetime. */
    private static final int FLOAT_PX = 18;

    private final List<Pop> pops = new ArrayList<>();
    private long lastRenderMs = -1L;

    public TransientClickFeedback() {}

    /** Spawn a green "+" at the given screen coordinates. */
    public void spawn(int x, int y) {
        pops.add(new Pop(x, y, 0L));
    }

    /** Spawn a "+" at the current cursor position. */
    public void spawnAtCursor() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.mouseHandler == null || mc.getWindow() == null) return;
        double mx = mc.mouseHandler.xpos()
            * (double) mc.getWindow().getGuiScaledWidth()
            / (double) Math.max(1, mc.getWindow().getScreenWidth());
        double my = mc.mouseHandler.ypos()
            * (double) mc.getWindow().getGuiScaledHeight()
            / (double) Math.max(1, mc.getWindow().getScreenHeight());
        spawn((int) mx, (int) my);
    }

    /** Age + draw all live pops. Call once per frame from the host screen's render(). */
    public void render(GuiGraphics graphics) {
        if (pops.isEmpty()) {
            lastRenderMs = -1L;
            return;
        }
        long now = System.currentTimeMillis();
        long dt = lastRenderMs < 0 ? 0L : (now - lastRenderMs);
        lastRenderMs = now;
        Font font = Minecraft.getInstance().font;
        Iterator<Pop> it = pops.iterator();
        while (it.hasNext()) {
            Pop p = it.next();
            p.ageMs += dt;
            if (p.ageMs >= LIFETIME_MS) {
                it.remove();
                continue;
            }
            float t = (float) p.ageMs / (float) LIFETIME_MS;
            // Fade out the second half of the life; first half stays full opacity for impact.
            int alpha = t < 0.5f ? 255 : (int) (255f * (1f - (t - 0.5f) * 2f));
            alpha = Math.max(0, Math.min(255, alpha));
            int color = (alpha << 24) | 0x55E055; // bright lime green
            int drawY = p.y - (int) (FLOAT_PX * t);
            PoseStack pose = graphics.pose();
            pose.pushPose();
            // Slight upscale for the "pop" effect — peaks at t=0.2 then settles.
            float scale = 1.0f + 0.6f * Math.max(0f, 1f - t * 5f);
            pose.translate(p.x, drawY, 0);
            pose.scale(scale, scale, 1f);
            Component plus = Component.literal("+");
            int w = font.width(plus);
            graphics.drawString(font, plus, -w / 2, -font.lineHeight / 2, color, true);
            pose.popPose();
        }
        // Reset Minecraft's lighting state in case we leaked a transform — defensive.
        Lighting.setupForFlatItems();
    }

    private static final class Pop {
        final int x;
        final int y;
        long ageMs;

        Pop(int x, int y, long ageMs) {
            this.x = x;
            this.y = y;
            this.ageMs = ageMs;
        }
    }
}
