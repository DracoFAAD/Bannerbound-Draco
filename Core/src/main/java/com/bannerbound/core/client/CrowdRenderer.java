package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.Era;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Renders the {@code /bannerbound simulate} decorative crowd ({@link ClientSimulationState}) as
 * real animated {@link HumanoidModel}s wearing the actual citizen skins — visually indistinguishable
 * from the real citizens, just non-interactive and entirely client-simulated. Purely decorative; the
 * server has no idea these exist (the whole thesis of the stress test).
 *
 * <p>v2 replaced the v1 flat billboards (which rendered as black blobs up close — wrong skin UVs).
 * Movers now read as people at every distance the {@link ClientSimulationState#MAX_MOVERS} cap
 * reaches; a genuinely-cheap far tier (texture-res LOD / billboard) is a deferred optimization.
 *
 * <p>No backing entities means we can't call {@code setupAnim(null,…)} (it NPEs) — the walk cycle is
 * written onto the {@link net.minecraft.client.model.geom.ModelPart}s directly with the vanilla math.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class CrowdRenderer {
    /** Leg-swing frequency in radians per block walked: one full cycle (two steps) per ~1.6 blocks.
     *  Distance-driven so the feet always match ground speed. */
    private static final double STEP_PER_BLOCK = (Math.PI * 2.0) / 1.6;

    private static HumanoidModel<LivingEntity> wideModel;
    private static HumanoidModel<LivingEntity> slimModel;

    private CrowdRenderer() {
    }

    @SubscribeEvent
    public static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSimulationState.reset();
    }

    /** Advance the stateful crowd one tick (movement/steering lives in {@link ClientCrowd}). */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientCrowd.tick();
    }

    private static void ensureModels() {
        if (wideModel != null) return;
        var set = Minecraft.getInstance().getEntityModels();
        wideModel = new HumanoidModel<>(set.bakeLayer(ModelLayers.PLAYER));
        slimModel = new HumanoidModel<>(set.bakeLayer(ModelLayers.PLAYER_SLIM));
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!ClientSimulationState.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        ensureModels();

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float yaw = camera.getYRot();
        float pitch = camera.getXRot();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double tSeconds = (mc.level.getGameTime() + partial) / 20.0;
        Era era = ClientSimulationState.era();

        // Camera forward (cheap behind-the-camera cull).
        double fy = Math.toRadians(yaw);
        double fp = Math.toRadians(pitch);
        double fx = -Math.sin(fy) * Math.cos(fp);
        double fz = Math.cos(fy) * Math.cos(fp);

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        int near = 0, mid = 0, far = 0, culled = 0, rendered = 0;
        for (ClientCrowd.Agent a : ClientCrowd.agents()) {
            // Interpolate position, facing and gait between ticks for smooth 60fps motion.
            double rx = a.prevX + (a.x - a.prevX) * partial;
            double ry = a.prevY + (a.y - a.prevY) * partial;
            double rz = a.prevZ + (a.z - a.prevZ) * partial;
            float facingR = a.prevFacing + ClientCrowd.wrapDeg(a.facing - a.prevFacing) * partial;
            double gaitR = a.prevGait + (a.gait - a.prevGait) * partial;
            float headYawR = a.prevHeadYaw + (a.headYaw - a.prevHeadYaw) * partial;
            float amountR = a.prevGaitAmount + (a.gaitAmount - a.prevGaitAmount) * partial;

            double dx = rx - cam.x, dz = rz - cam.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > ClientSimulationState.CULL_DISTANCE) { culled++; continue; }
            if (dist > 0.01 && (dx * fx + dz * fz) / dist < -0.2) { culled++; continue; }
            if (rendered >= ClientSimulationState.renderCap()) { culled++; continue; }

            boolean male = (a.vseed & 1L) == 0L;
            CitizenGender gender = male ? CitizenGender.MALE : CitizenGender.FEMALE;
            HumanoidModel<LivingEntity> model = male ? wideModel : slimModel;
            int variant = (int) ((a.vseed >>> 1) & 0x7fffffff);
            ResourceLocation skin = CitizenSkins.texture(gender, era, variant);

            poseAgent(model, gaitR, amountR, headYawR, tSeconds, a.idlePhase);

            int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(rx, ry + 1, rz));
            VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(skin));

            pose.pushPose();
            pose.translate(rx - cam.x, ry - cam.y, rz - cam.z);
            pose.mulPose(Axis.YP.rotationDegrees(180.0f - facingR));
            pose.scale(-1.0f, -1.0f, 1.0f);
            pose.translate(0.0f, -1.501f, 0.0f);
            model.renderToBuffer(pose, vc, light, OverlayTexture.NO_OVERLAY, -1);
            pose.popPose();

            if (dist < ClientSimulationState.NEAR_BAND) near++;
            else if (dist < ClientSimulationState.MID_BAND) mid++;
            else far++;
            rendered++;
        }
        buffer.endBatch();

        ClientSimulationState.lastNear = near;
        ClientSimulationState.lastMid = mid;
        ClientSimulationState.lastFar = far;
        ClientSimulationState.lastCulled = culled;
    }

    /** Wrap a (possibly huge) double phase into [0, 2π) BEFORE casting to float. Computing the
     *  modulo in double avoids the float-precision freeze when gameTime is large. */
    private static float wrap(double phase) {
        double w = phase % (Math.PI * 2.0);
        if (w < 0) w += Math.PI * 2.0;
        return (float) w;
    }

    /** One continuous pose that BLENDS a calm distance-driven walk with a standing/idle gesture by
     *  {@code amount} (0 = standing, 1 = walking) — so starting/stopping eases with no pose pop.
     *  Legs swing scale with amount; arms lerp from an idle rest+gesture to the walk swing; the head
     *  carries its lead toward the heading plus an idle glance that fades as the agent gets moving. */
    private static void poseAgent(HumanoidModel<LivingEntity> model, double gaitBlocks, float amount,
                                  float headYawDeg, double tSeconds, double idlePhase) {
        model.young = false;
        model.crouching = false;
        model.riding = false;
        model.attackTime = 0f;

        float p = wrap(gaitBlocks * STEP_PER_BLOCK);
        model.rightLeg.xRot = Mth.cos(p) * 0.85f * amount;
        model.leftLeg.xRot = Mth.cos(p + Mth.PI) * 0.85f * amount;
        model.rightLeg.yRot = 0f; model.rightLeg.zRot = 0f;
        model.leftLeg.yRot = 0f; model.leftLeg.zRot = 0f;

        float gesture = Mth.cos(wrap(tSeconds * 2.0 + idlePhase)) * 0.12f;
        float idleR = -0.12f + gesture, idleL = -0.12f - gesture;
        float walkR = Mth.cos(p + Mth.PI) * 0.62f, walkL = Mth.cos(p) * 0.62f;
        model.rightArm.xRot = Mth.lerp(amount, idleR, walkR);
        model.leftArm.xRot = Mth.lerp(amount, idleL, walkL);
        model.rightArm.zRot = 0.06f * (1f - amount); model.leftArm.zRot = -0.06f * (1f - amount);
        model.rightArm.yRot = 0f; model.leftArm.yRot = 0f;

        float glance = Mth.cos(wrap(tSeconds * 0.7 + idlePhase)) * 0.30f * (1f - amount);
        model.head.xRot = 0f;
        model.head.yRot = (float) Math.toRadians(headYawDeg) + glance;
        model.head.zRot = 0f;
        model.hat.copyFrom(model.head);
        model.body.xRot = 0f; model.body.yRot = 0f; model.body.zRot = 0f;
    }
}
