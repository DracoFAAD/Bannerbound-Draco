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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws the plant-fibre green rope for a VANILLA leash — the visible half of "leash animals with a
 * fiber rope just like the vanilla lead". The mechanic is pure vanilla leashing ({@code Mob.isLeashed}
 * /{@code getLeashHolder}, set by {@link com.bannerbound.antiquity.LeashRopeEvents}); vanilla's own
 * brown ribbon is cancelled by Core's {@code MobRendererMixin}, and this draws the same green ribbon
 * ({@link RopeRenderer#drawRibbon}) the rope fences / spear-fishing / herding use in its place.
 *
 * <p>Mirrors {@link CurareRopeRenderer}/{@link HerderRopeRenderer} exactly, but reads the leash off
 * vanilla state instead of a custom attachment: the holder end uses {@code getRopeHoldPosition} (the
 * player's hand or a fence knot — matching where vanilla anchors a lead), the mob end ties chest-ish.</p>
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class LeashRopeRenderer {
    /** Rope ties to the mob at ~60% of its height (chest-ish), so it reads as a held lead. */
    private static final double TIE_FRACTION = 0.6;

    private LeashRopeRenderer() {}

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
            if (!(e instanceof Mob mob) || !mob.isLeashed()) {
                continue;
            }
            Entity holder = mob.getLeashHolder();
            if (holder == null || !holder.isAlive()) {
                continue;
            }

            Vec3 a = lerpPos(mob, partial);
            double ay = a.y + mob.getBbHeight() * TIE_FRACTION;
            Vec3 h = holder.getRopeHoldPosition(partial); // player's hand / fence knot, like vanilla
            float dx = (float) (h.x - a.x);
            float dy = (float) (h.y - ay);
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
