package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;
import com.bannerbound.core.BannerboundCore;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Floating town-hall alert for unanswered crises. */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class CrisisAlertRenderer {
    private static final ResourceLocation ALERT_TEXTURE =
        ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "textures/gui/alert.png");
    private static final double MAX_DIST_SQ = 128.0 * 128.0;
    private static final float ALERT_HALF_SIZE = 0.42F;

    private CrisisAlertRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!ClientCrisisState.awaitingChoice()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        BlockPos pos = ClientCrisisState.townHallPos();
        if (pos == null) return;

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 2.6;
        double z = pos.getZ() + 0.5;
        if (cam.distanceToSqr(x, y, z) > MAX_DIST_SQ) return;

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        drawAlertIcon(pose, buffer, camera, x - cam.x, y - cam.y, z - cam.z);
        buffer.endBatch();
    }

    private static void drawAlertIcon(PoseStack pose, MultiBufferSource buffer, Camera camera,
                                      double dx, double dy, double dz) {
        pose.pushPose();
        pose.translate(dx, dy, dz);
        pose.mulPose(camera.rotation());
        VertexConsumer vc = buffer.getBuffer(RenderType.text(ALERT_TEXTURE));
        PoseStack.Pose last = pose.last();
        vertex(vc, last, -ALERT_HALF_SIZE, -ALERT_HALF_SIZE, 0.0F, 1.0F);
        vertex(vc, last, ALERT_HALF_SIZE, -ALERT_HALF_SIZE, 1.0F, 1.0F);
        vertex(vc, last, ALERT_HALF_SIZE, ALERT_HALF_SIZE, 1.0F, 0.0F);
        vertex(vc, last, -ALERT_HALF_SIZE, ALERT_HALF_SIZE, 0.0F, 0.0F);
        vertex(vc, last, -ALERT_HALF_SIZE, ALERT_HALF_SIZE, 0.0F, 0.0F);
        vertex(vc, last, ALERT_HALF_SIZE, ALERT_HALF_SIZE, 1.0F, 0.0F);
        vertex(vc, last, ALERT_HALF_SIZE, -ALERT_HALF_SIZE, 1.0F, 1.0F);
        vertex(vc, last, -ALERT_HALF_SIZE, -ALERT_HALF_SIZE, 0.0F, 1.0F);
        pose.popPose();
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose,
                               float x, float y, float u, float v) {
        vc.addVertex(pose, x, y, 0.0F)
            .setColor(255, 255, 255, 255)
            .setUv(u, v)
            .setLight(LightTexture.FULL_BRIGHT);
    }
}
