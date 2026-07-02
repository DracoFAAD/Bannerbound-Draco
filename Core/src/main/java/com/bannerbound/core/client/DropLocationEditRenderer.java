package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * While {@link DropLocationEditState} is active: draws a one-block colored wireframe at whatever
 * block the crosshair is on and re-issues the settlement-colored action-bar prompt every frame.
 * Mirrors {@link SelectionRenderer}'s line-rendering scaffolding.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class DropLocationEditRenderer {
    private DropLocationEditRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!DropLocationEditState.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        // Action-bar prompt: "EDITING DROP LOCATION / SEEDS STORAGE FOR: <name> (<job>)" in settlement color.
        int rgb = DropLocationEditState.settlementRgb();
        Component prompt = Component.translatable(
                DropLocationEditState.isSeed()
                    ? "bannerbound.job.editing_seed_source" : "bannerbound.job.editing_drop_location",
                DropLocationEditState.name(), DropLocationEditState.jobTitle())
            .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
        player.displayClientMessage(prompt, true);

        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhit) || bhit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = bhit.getBlockPos();

        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        var consumer = buffer.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, consumer,
            pos.getX(), pos.getY(), pos.getZ(),
            pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
            r, g, b, 0.9f);
        buffer.endBatch(RenderType.lines());
        pose.popPose();
    }
}
