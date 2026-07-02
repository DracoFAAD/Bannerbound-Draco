package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.entity.BlowdartProjectile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Renders the blowdart as a vanilla-arrow-shaped projectile (oriented along flight, sticks in blocks)
 * in TWO layers, like a tipped arrow: the base {@code dart.png}, then {@code dart_poison_layer.png}
 * tinted by the dart's {@link com.bannerbound.antiquity.poison.PoisonType#tintColor()} (so each poison's
 * coating reads as its own colour). Geometry mirrors vanilla {@code ArrowRenderer}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class BlowdartRenderer extends EntityRenderer<BlowdartProjectile> {
    private static final ResourceLocation DART = ResourceLocation.fromNamespaceAndPath(
        BannerboundAntiquity.MODID, "textures/projectiles/dart.png");
    private static final ResourceLocation POISON_LAYER = ResourceLocation.fromNamespaceAndPath(
        BannerboundAntiquity.MODID, "textures/projectiles/dart_poison_layer.png");

    public BlowdartRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(BlowdartProjectile entity) {
        return DART; // layers bind their own textures in render(); this satisfies the abstract method
    }

    @Override
    public void render(BlowdartProjectile entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // Base dart (dart.png) first, then the poison coating (dart_poison_layer.png) tinted per poison
        // laid OVER the same geometry. Because the painted poison pixels share the dart's shaft texels,
        // the coating is lifted a fixed amount along each face's normal (NOT scaled — a scale gives ~0
        // separation at the dart's centre, exactly where the pixels live) so it never z-fights.
        renderDart(entity, partialTicks, poseStack, buffer, packedLight, entity.getPoison().tintColor());
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    /** u (and matching local x) where the dart's green shaft ends and the painted poison tip begins.
     *  The poison pixels sit in texel columns 7-9 of the 32px arrow strip, so the split is at u=7/32;
     *  on the fin quad u maps x[-8,8]→u[0,0.5], so u=7/32 is local x=-1. */
    private static final float U_SPLIT = 7.0F / 32.0F;
    private static final int X_SPLIT = -1;

    private void renderDart(BlowdartProjectile entity, float partialTicks, PoseStack poseStack,
                            MultiBufferSource buffer, int light, int tipColor) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, entity.yRotO, entity.getYRot()) - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTicks, entity.xRotO, entity.getXRot())));
        float shake = (float) entity.shakeTime - partialTicks;
        if (shake > 0.0F) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(-Mth.sin(shake * 3.0F) * shake));
        }
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0F));
        poseStack.scale(0.05625F, 0.05625F, 0.05625F);
        poseStack.translate(-4.0F, 0.0F, 0.0F);
        // Each fin is split at X_SPLIT into a shaft half (dart.png) and a tip half (poison layer, tinted).
        // The two halves are COPLANAR and ADJACENT — they meet edge-to-edge, never overlap — so the tip
        // just changes colour in place: no z-fighting, no raised shell.
        // ---- BASE pass: arrowhead cross + the shaft half of each fin, from dart.png. The entity
        // BufferSource shares one builder across cutout layers, so write this layer FULLY before
        // fetching the tip buffer (fetching the next ends this one → "Not building" crash).
        VertexConsumer base = buffer.getBuffer(RenderType.entityCutout(DART));
        PoseStack.Pose ph = poseStack.last();
        vertex(ph, base, -7, -2, -2, 0.0F, 0.15625F, -1, 0, 0, light, -1);
        vertex(ph, base, -7, -2, 2, 0.15625F, 0.15625F, -1, 0, 0, light, -1);
        vertex(ph, base, -7, 2, 2, 0.15625F, 0.3125F, -1, 0, 0, light, -1);
        vertex(ph, base, -7, 2, -2, 0.0F, 0.3125F, -1, 0, 0, light, -1);
        vertex(ph, base, -7, 2, -2, 0.0F, 0.15625F, 1, 0, 0, light, -1);
        vertex(ph, base, -7, 2, 2, 0.15625F, 0.15625F, 1, 0, 0, light, -1);
        vertex(ph, base, -7, -2, 2, 0.15625F, 0.3125F, 1, 0, 0, light, -1);
        vertex(ph, base, -7, -2, -2, 0.0F, 0.3125F, 1, 0, 0, light, -1);
        // Shaft halves (four fins). This loop rotates 4×90° = 360°, leaving the pose as it began.
        for (int j = 0; j < 4; j++) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            PoseStack.Pose pf = poseStack.last();
            vertex(pf, base, -8, -2, 0, 0.0F, 0.0F, 0, 1, 0, light, -1);
            vertex(pf, base, X_SPLIT, -2, 0, U_SPLIT, 0.0F, 0, 1, 0, light, -1);
            vertex(pf, base, X_SPLIT, 2, 0, U_SPLIT, 0.15625F, 0, 1, 0, light, -1);
            vertex(pf, base, -8, 2, 0, 0.0F, 0.15625F, 0, 1, 0, light, -1);
        }
        // ---- TIP pass: the tip half of each fin, from the poison layer, tinted. Same plane as the
        // shaft halves, picking up where they end (X_SPLIT / U_SPLIT).
        VertexConsumer tip = buffer.getBuffer(RenderType.entityCutout(POISON_LAYER));
        for (int j = 0; j < 4; j++) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            PoseStack.Pose pf = poseStack.last();
            vertex(pf, tip, X_SPLIT, -2, 0, U_SPLIT, 0.0F, 0, 1, 0, light, tipColor);
            vertex(pf, tip, 8, -2, 0, 0.5F, 0.0F, 0, 1, 0, light, tipColor);
            vertex(pf, tip, 8, 2, 0, 0.5F, 0.15625F, 0, 1, 0, light, tipColor);
            vertex(pf, tip, X_SPLIT, 2, 0, U_SPLIT, 0.15625F, 0, 1, 0, light, tipColor);
        }
        poseStack.popPose();
    }

    private void vertex(PoseStack.Pose pose, VertexConsumer vc, int x, int y, int z,
                        float u, float v, int nx, int ny, int nz, int light, int color) {
        vc.addVertex(pose, (float) x, (float) y, (float) z)
            .setColor(color)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, (float) nx, (float) nz, (float) ny);
    }
}
