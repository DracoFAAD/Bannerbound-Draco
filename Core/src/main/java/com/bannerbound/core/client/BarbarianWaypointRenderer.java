package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.bannerbound.core.network.JournalSyncPayload;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws a screen-space waypoint (camp name + live distance) for each barbarian-camp QUEST whose
 * "reach the camp" objective isn't done yet, using the entry's {@code targetPos}. Rendered as a HUD
 * layer (world position projected to the screen), NOT world-space geometry, so it ALWAYS shows on top
 * of terrain and is immune to Iris/shader depth quirks. Off-screen targets clamp to the screen edge.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class BarbarianWaypointRenderer implements LayeredDraw.Layer {
    public static final BarbarianWaypointRenderer INSTANCE = new BarbarianWaypointRenderer();
    private static final float MARGIN = 14f;
    private static final double MAX_DRAW = 1200.0; // don't bother past this (blocks)

    private BarbarianWaypointRenderer() {}

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        // Rebuild the world perspective ourselves (GameRenderer.getFov is protected). Uses the base FOV
        // setting — fine for a waypoint; tiny mismatch only during transient FOV effects (sprint/zoom).
        float fovDeg = mc.options.fov().get().floatValue();
        float aspect = (float) mc.getWindow().getWidth() / (float) mc.getWindow().getHeight();
        Matrix4f proj = new Matrix4f().perspective(fovDeg * ((float) Math.PI / 180f), aspect, 0.05f, 1000f);
        // View rotation, matching vanilla GameRenderer (XP pitch then YP yaw+180; roll ignored).
        Matrix4f view = new Matrix4f();
        view.rotate(com.mojang.math.Axis.XP.rotationDegrees(camera.getXRot()));
        view.rotate(com.mojang.math.Axis.YP.rotationDegrees(camera.getYRot() + 180.0f));

        int w = g.guiWidth();
        int h = g.guiHeight();
        for (JournalSyncPayload.Entry entry : ClientJournalState.hudEntries()) {
            if (!"barbarian_camp".equals(entry.sourceType()) || entry.targetPos() == 0L) continue;
            if (reachedCamp(entry)) continue; // objective done — stop guiding there
            BlockPos c = BlockPos.of(entry.targetPos());
            double tx = c.getX() + 0.5, ty = c.getY() + 2.5, tz = c.getZ() + 0.5;
            double dist = mc.player.position().distanceTo(new Vec3(tx, ty, tz));
            if (dist > MAX_DRAW) continue;

            Vector4f p = new Vector4f((float) (tx - camPos.x), (float) (ty - camPos.y),
                (float) (tz - camPos.z), 1.0f);
            view.transform(p);
            proj.transform(p);
            if (Math.abs(p.w) < 1.0e-4f) continue;
            boolean behind = p.w <= 0f;
            float ndcX = p.x / p.w;
            float ndcY = p.y / p.w;
            if (behind) { ndcX = -ndcX; ndcY = -ndcY; } // mirror so the edge-clamp points the right way
            float sx = (ndcX * 0.5f + 0.5f) * w;
            float sy = (0.5f - ndcY * 0.5f) * h;
            // Clamp off-screen / behind targets to the screen edge so the marker is always a guide.
            sx = Math.max(MARGIN, Math.min(w - MARGIN, sx));
            sy = behind ? h - MARGIN : Math.max(MARGIN, Math.min(h - MARGIN, sy));

            String name = entry.title();
            String d = (int) Math.round(dist) + "m";
            g.drawCenteredString(mc.font, name, (int) sx, (int) sy - 5, 0xFFE05050);
            g.drawCenteredString(mc.font, d, (int) sx, (int) sy + 5, 0xFFE8E8E8);
        }
    }

    private static boolean reachedCamp(JournalSyncPayload.Entry entry) {
        for (JournalSyncPayload.Objective o : entry.objectives()) {
            if ("find_camp".equals(o.id())) return o.complete();
        }
        return false;
    }
}
