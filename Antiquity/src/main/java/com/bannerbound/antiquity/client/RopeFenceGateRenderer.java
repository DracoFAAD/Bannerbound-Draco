package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.RopeTies;
import com.bannerbound.antiquity.block.entity.RopeFenceGateBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws the ropes tied to a gate's two uprights, via the shared {@link RopeRenderer#renderHostRopes}.
 * The gate body (and its open/closed bar + per-upright coils) is the normal JSON block model.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class RopeFenceGateRenderer implements BlockEntityRenderer<RopeFenceGateBlockEntity> {

    public RopeFenceGateRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(RopeFenceGateBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        RopeRenderer.renderHostRopes(be, be, pose, buffers, light, partialTick);
    }

    @Override
    public AABB getRenderBoundingBox(RopeFenceGateBlockEntity be) {
        return RopeTies.renderBounds(be.getBlockPos(), be);
    }
}
