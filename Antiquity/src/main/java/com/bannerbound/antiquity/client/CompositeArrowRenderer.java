package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.entity.CompositeArrowEntity;
import com.bannerbound.antiquity.item.ArrowParts;
import com.bannerbound.antiquity.recipe.ArrowPart;
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
 * Renders the modular arrow as a vanilla-arrow-shaped projectile in THREE layers — back, then shaft,
 * then tip — each from its own {@code textures/projectiles/*.png}, picked from the parts on the
 * entity's pickup stack. The three part textures are designed to be pixel-DISJOINT (verified: their
 * union is the full arrow and no two paint the same texel), so each layer just draws the complete
 * vanilla arrow geometry and the cutout shader discards the texels it doesn't own — no UV splitting and
 * no z-fight offset are needed (unlike the {@link BlowdartRenderer}, whose poison layer repaints the
 * shaft's texels and so had to be lifted). Geometry mirrors vanilla {@code ArrowRenderer}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class CompositeArrowRenderer extends EntityRenderer<CompositeArrowEntity> {
    /** Fallback for the abstract method only — actual layer textures come from the part registry. */
    private static final ResourceLocation FALLBACK = ResourceLocation.fromNamespaceAndPath(
        BannerboundAntiquity.MODID, "textures/projectiles/flint_arrow_tip.png");

    public CompositeArrowRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(CompositeArrowEntity entity) {
        return FALLBACK; // layers bind their own data-driven textures in render(); satisfies the abstract method
    }

    @Override
    public void render(CompositeArrowEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(
            Mth.lerp(partialTicks, entity.yRotO, entity.getYRot()) - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTicks, entity.xRotO, entity.getXRot())));
        float shake = (float) entity.shakeTime - partialTicks;
        if (shake > 0.0F) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(-Mth.sin(shake * 3.0F) * shake));
        }
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0F));
        poseStack.scale(0.05625F, 0.05625F, 0.05625F);
        poseStack.translate(-4.0F, 0.0F, 0.0F);

        // Back first (lowest), then shaft, then tip on top — though disjoint texels mean order is
        // cosmetic. The entity BufferSource shares one builder across cutout layers, so each layer's
        // geometry must be written FULLY before the next layer's buffer is fetched (fetching the next
        // ends the current builder → "Not building" crash). Textures are data-driven; a part with no
        // projectile texture simply contributes no layer.
        renderLayer(poseStack, buffer, packedLight,
            ArrowParts.projectileTexture(ArrowPart.SLOT_BACK, entity.back()));
        renderLayer(poseStack, buffer, packedLight,
            ArrowParts.projectileTexture(ArrowPart.SLOT_SHAFT, entity.shaft()));
        renderLayer(poseStack, buffer, packedLight,
            ArrowParts.projectileTexture(ArrowPart.SLOT_TIP, entity.tip()));

        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    /** Draws the full vanilla arrow geometry (head cross + four fins) from one part texture; the cutout
     *  shader keeps only the texels that texture actually paints. */
    private void renderLayer(PoseStack poseStack, MultiBufferSource buffer, int light,
                             ResourceLocation texture) {
        if (texture == null) return; // an undefined part contributes no layer
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutout(texture));
        PoseStack.Pose ph = poseStack.last();
        // Arrowhead cross (two opposed faces near the point).
        vertex(ph, vc, -7, -2, -2, 0.0F, 0.15625F, -1, 0, 0, light);
        vertex(ph, vc, -7, -2, 2, 0.15625F, 0.15625F, -1, 0, 0, light);
        vertex(ph, vc, -7, 2, 2, 0.15625F, 0.3125F, -1, 0, 0, light);
        vertex(ph, vc, -7, 2, -2, 0.0F, 0.3125F, -1, 0, 0, light);
        vertex(ph, vc, -7, 2, -2, 0.0F, 0.15625F, 1, 0, 0, light);
        vertex(ph, vc, -7, 2, 2, 0.15625F, 0.15625F, 1, 0, 0, light);
        vertex(ph, vc, -7, -2, 2, 0.15625F, 0.3125F, 1, 0, 0, light);
        vertex(ph, vc, -7, -2, -2, 0.0F, 0.3125F, 1, 0, 0, light);
        // Four fins (the shaft strip) — the loop rotates 4×90° = 360°, leaving the pose as it began.
        for (int j = 0; j < 4; j++) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            PoseStack.Pose pf = poseStack.last();
            vertex(pf, vc, -8, -2, 0, 0.0F, 0.0F, 0, 1, 0, light);
            vertex(pf, vc, 8, -2, 0, 0.5F, 0.0F, 0, 1, 0, light);
            vertex(pf, vc, 8, 2, 0, 0.5F, 0.15625F, 0, 1, 0, light);
            vertex(pf, vc, -8, 2, 0, 0.0F, 0.15625F, 0, 1, 0, light);
        }
    }

    private void vertex(PoseStack.Pose pose, VertexConsumer vc, int x, int y, int z,
                        float u, float v, int nx, int ny, int nz, int light) {
        vc.addVertex(pose, (float) x, (float) y, (float) z)
            .setColor(-1)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(pose, (float) nx, (float) nz, (float) ny);
    }
}
