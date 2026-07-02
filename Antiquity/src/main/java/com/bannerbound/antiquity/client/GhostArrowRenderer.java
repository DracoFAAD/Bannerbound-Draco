package com.bannerbound.antiquity.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity;
import com.bannerbound.antiquity.network.GhostActionPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws the ghost preview's browse arrows: camera-facing billboards flanking the floating ghost
 * result (Core's GUI arrow textures), swapping to the highlighted variant when the crosshair ray
 * is on one. Pure presentation — the matching click logic lives in {@code GhostClickEvents}, with
 * {@link GhostClickTargets} as the single source of positions so the visuals and hitboxes agree.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class GhostArrowRenderer {
    private static final ResourceLocation LEFT =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/arrow_left.png");
    private static final ResourceLocation LEFT_HL =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/arrow_left_highlighted.png");
    private static final ResourceLocation RIGHT =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/arrow_right.png");
    /** Sic — the Core texture file is named with this typo. */
    private static final ResourceLocation RIGHT_HL =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/arrow_right_highighted.png");

    /** Half-size of the arrow billboard quad. */
    private static final float SIZE = 0.16F;

    private GhostArrowRenderer() {}

    /** Renders both browse arrows for {@code be} (no-op unless ≥2 candidates are browsable).
     *  Call from the BER with the pose at the block origin. */
    public static void render(BlockEntity be, PoseStack pose, MultiBufferSource buffers) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        List<GhostClickTargets.Target> targets = GhostClickTargets.targetsFor(be, camera);
        if (targets.size() < 3) return; // result only — nothing to browse
        GhostClickTargets.Picked hovered = GhostClickTargets.pick(targets,
            mc.player.getEyePosition(), mc.player.getViewVector(1.0F), mc.player.blockInteractionRange());
        BlockPos pos = be.getBlockPos();
        for (GhostClickTargets.Target t : targets) {
            if (t.action() == GhostActionPayload.FILL) continue;
            boolean hl = hovered != null && hovered.target() == t;
            ResourceLocation tex = t.action() == GhostActionPayload.CYCLE_LEFT
                ? (hl ? LEFT_HL : LEFT)
                : (hl ? RIGHT_HL : RIGHT);
            drawBillboard(pose, buffers, camera, pos, t.center(), tex,
                be instanceof WoodworkingTableBlockEntity ? 0.105F : SIZE);
        }
    }

    private static void drawBillboard(PoseStack pose, MultiBufferSource buffers, Camera camera,
                                      BlockPos pos, Vec3 center, ResourceLocation tex, float size) {
        pose.pushPose();
        pose.translate(center.x - pos.getX(), center.y - pos.getY(), center.z - pos.getZ());
        pose.mulPose(camera.rotation());
        // The text render type (nametag-style): no normals → no directional diffuse darkening the
        // sprite, and FULL_BRIGHT is honored, so the arrows read like the GUI texture.
        VertexConsumer vc = buffers.getBuffer(RenderType.text(tex));
        PoseStack.Pose last = pose.last();
        // Camera-rotated frame: +X = screen-right, +Y = screen-up. Emit both windings so the quad
        // shows whichever way the cull state lands.
        vertex(vc, last, -size, -size, 0.0F, 1.0F);
        vertex(vc, last, size, -size, 1.0F, 1.0F);
        vertex(vc, last, size, size, 1.0F, 0.0F);
        vertex(vc, last, -size, size, 0.0F, 0.0F);
        vertex(vc, last, -size, size, 0.0F, 0.0F);
        vertex(vc, last, size, size, 1.0F, 0.0F);
        vertex(vc, last, size, -size, 1.0F, 1.0F);
        vertex(vc, last, -size, -size, 0.0F, 1.0F);
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
