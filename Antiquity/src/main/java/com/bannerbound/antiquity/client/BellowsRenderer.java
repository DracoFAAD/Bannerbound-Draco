package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;
import org.joml.Vector3f;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.BellowsBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Block entity renderer for the Bellows Block: draws {@link BellowsModel} oriented to its facing and
 * plays the {@link BellowsAnimations#PUSH} animation while the block entity's jump-push timer runs.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class BellowsRenderer implements BlockEntityRenderer<BellowsBlockEntity> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
        BannerboundAntiquity.MODID, "textures/block/bellows/bellows.png");

    private final BellowsModel model;
    private final Vector3f animationCache = new Vector3f();

    public BellowsRenderer(BlockEntityRendererProvider.Context context) {
        this.model = new BellowsModel(context.bakeLayer(BellowsModel.LAYER_LOCATION));
    }

    @Override
    public void render(BellowsBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        pose.translate(0.5, 1.5, 0.5);
        // Orient to the block's facing, then flip into block space (entity-model adapter).
        Direction facing = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.scale(1.0F, -1.0F, -1.0F);

        model.root().getAllParts().forEach(ModelPart::resetPose);
        if (be.animTicks() > 0) {
            float elapsedTicks = (BellowsBlockEntity.PUSH_TICKS - be.animTicks()) + partialTick;
            long elapsedMs = (long) (elapsedTicks * 50.0F);
            KeyframeAnimations.animate(model, BellowsAnimations.PUSH, elapsedMs, 1.0F, animationCache);
        }
        model.root().render(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), light, overlay);
        pose.popPose();
    }
}
