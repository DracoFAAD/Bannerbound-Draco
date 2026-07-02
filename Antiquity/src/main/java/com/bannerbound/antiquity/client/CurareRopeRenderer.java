package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The kidnap rope — a fibre ribbon from a dragger to whatever curare-unconscious creature they're
 * towing. Mirrors {@link HerderRopeRenderer} exactly, but reads the synced {@code DRAGGED_BY} claim
 * (the dragger's entity id) off any rendered {@link LivingEntity} (players, citizens, animals), so it
 * draws the same plant-fibre green ribbon ({@link RopeRenderer#drawRibbon}) the herder/spear use.
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class CurareRopeRenderer {
    /** Rope attaches at ~60% of each entity's height (chest-ish), like a held lead. */
    private static final double TIE_FRACTION = 0.6;

    private CurareRopeRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        boolean drewAny = false;

        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity victim)) {
                continue;
            }
            Integer id = victim.getExistingDataOrNull(BannerboundAntiquity.DRAGGED_BY.get());
            if (id == null || id == 0) {
                continue;
            }
            Entity dragger = level.getEntity(id);
            if (dragger == null || !dragger.isAlive()) {
                continue;
            }

            Vec3 a = lerpPos(victim, partial);
            Vec3 h = lerpPos(dragger, partial);
            double ay = a.y + victim.getBbHeight() * TIE_FRACTION;
            double hy = h.y + dragger.getBbHeight() * TIE_FRACTION;
            float dx = (float) (h.x - a.x);
            float dy = (float) (hy - ay);
            float dz = (float) (h.z - a.z);
            double horiz = Math.sqrt((double) dx * dx + (double) dz * dz);
            float sag = (float) Mth.clamp(0.12 * horiz, 0.08, 0.5);

            pose.pushPose();
            pose.translate(a.x - cam.x, ay - cam.y, a.z - cam.z);
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(a.x, ay, a.z));
            RopeRenderer.drawRibbon(pose, buffers, light, dx, dy, dz, sag);
            pose.popPose();
            drewAny = true;
        }
        if (drewAny) {
            buffers.endBatch(RenderType.leash());
        }
    }

    private static Vec3 lerpPos(Entity e, float partial) {
        return new Vec3(Mth.lerp(partial, e.xOld, e.getX()),
            Mth.lerp(partial, e.yOld, e.getY()),
            Mth.lerp(partial, e.zOld, e.getZ()));
    }
}
