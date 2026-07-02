package com.bannerbound.antiquity.client;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.TanningRackBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

import org.joml.Matrix4f;

/**
 * Renders the hide on a tanning rack as a flat decal <b>stretched across the slanted frame</b> — raw
 * hide before scraping, and while drying a cross-fade from the cured hide to the finished leather
 * (driven by {@link TanningRackBlockEntity#dryProgress()}), settling on leather when dry. The block
 * entity lives on the master cell (bottom-left of the 2×2); the decal is rotated to match the
 * blockstate facing.
 */
public class TanningRackRenderer implements BlockEntityRenderer<TanningRackBlockEntity> {
    // Slanted hide plane in the master's local space (facing = north baseline), block units.
    private static final float X0 = 0.32F, X1 = 1.68F;
    private static final float Y_BOTTOM = 0.18F, Z_BOTTOM = 0.04F;
    private static final float Y_TOP = 1.70F, Z_TOP = 0.72F;

    public TanningRackRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(TanningRackBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        TanningRackBlockEntity.Phase phase = be.getPhase();
        if (phase == TanningRackBlockEntity.Phase.EMPTY) return;

        Direction facing = be.getBlockState().getBlock() instanceof HorizontalDirectionalBlock
            ? be.getBlockState().getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - facing.toYRot()));
        pose.translate(-0.5, -0.5, -0.5);
        Matrix4f mat = pose.last().pose();

        switch (phase) {
            case RAW -> decal(be.getRawHide(), 1.0F, mat, buffer, packedLight);
            case DRYING -> {
                // Opaque cured base (no transparency loss) with the leather fading IN on top.
                float p = be.dryProgress();
                decal(new ItemStack(BannerboundAntiquity.CURED_HIDE.get()), 1.0F, mat, buffer, packedLight);
                decal(new ItemStack(Items.LEATHER), p, mat, buffer, packedLight);
            }
            case DRY -> decal(new ItemStack(Items.LEATHER), 1.0F, mat, buffer, packedLight);
            default -> { }
        }
        pose.popPose();
    }

    private void decal(ItemStack stack, float alpha, Matrix4f mat, MultiBufferSource buffer, int light) {
        if (stack.isEmpty() || alpha <= 0.01F) return;
        BakedModel model = Minecraft.getInstance().getItemRenderer()
            .getModel(stack, Minecraft.getInstance().level, null, 0);
        TextureAtlasSprite sprite = model.getParticleIcon();
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        VertexConsumer vc = buffer.getBuffer(RenderType.translucent());
        face(vc, mat, light, alpha, u0, u1, v0, v1, false);
        face(vc, mat, light, alpha, u0, u1, v0, v1, true);
    }

    private static void face(VertexConsumer vc, Matrix4f mat, int light, float a,
                             float u0, float u1, float v0, float v1, boolean back) {
        if (!back) {
            v(vc, mat, X0, Y_BOTTOM, Z_BOTTOM, u0, v1, light, a);
            v(vc, mat, X1, Y_BOTTOM, Z_BOTTOM, u1, v1, light, a);
            v(vc, mat, X1, Y_TOP, Z_TOP, u1, v0, light, a);
            v(vc, mat, X0, Y_TOP, Z_TOP, u0, v0, light, a);
        } else {
            v(vc, mat, X0, Y_BOTTOM, Z_BOTTOM, u0, v1, light, a);
            v(vc, mat, X0, Y_TOP, Z_TOP, u0, v0, light, a);
            v(vc, mat, X1, Y_TOP, Z_TOP, u1, v0, light, a);
            v(vc, mat, X1, Y_BOTTOM, Z_BOTTOM, u1, v1, light, a);
        }
    }

    private static void v(VertexConsumer vc, Matrix4f mat, float x, float y, float z,
                          float u, float vTex, int light, float a) {
        vc.addVertex(mat, x, y, z)
            .setColor(1.0F, 1.0F, 1.0F, a)
            .setUv(u, vTex)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(0.0F, 1.0F, 0.0F);
    }

    @Override
    public boolean shouldRenderOffScreen(TanningRackBlockEntity be) {
        return true;
    }
}
