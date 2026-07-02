package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;
import org.joml.Vector3f;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.BloomeryBlock;
import com.bannerbound.antiquity.block.entity.BloomeryBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Block entity renderer for the Bloomery — draws the full 1×1×2 model from the lower segment,
 * rotated to the block's facing, plays the door open/close animation, and renders whatever item
 * is held inside (one copy for a single item, two slightly-clipping copies for a stack), with a
 * short slide-in when an item is freshly inserted.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class BloomeryRenderer implements BlockEntityRenderer<BloomeryBlockEntity> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
        BannerboundAntiquity.MODID, "textures/block/bloomery/bloomery.png");

    private final BloomeryModel model;
    private final ItemRenderer itemRenderer;
    private final Vector3f animationCache = new Vector3f();

    public BloomeryRenderer(BlockEntityRendererProvider.Context context) {
        this.model = new BloomeryModel(context.bakeLayer(BloomeryModel.LAYER_LOCATION));
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(BloomeryBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        Direction facing = be.getBlockState().getValue(BloomeryBlock.FACING);

        pose.pushPose();
        // Place on the block, rotate to the facing, then apply the standard entity-model flip.
        pose.translate(0.5, 1.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.scale(1.0F, -1.0F, -1.0F);

        model.root().getAllParts().forEach(ModelPart::resetPose);
        if (be.isOpen() || be.getAnimTicks() > 0) {
            AnimationDefinition anim = be.isOpen() ? BloomeryAnimations.OPEN : BloomeryAnimations.CLOSE;
            float elapsedTicks = be.getAnimTicks() > 0
                ? (BloomeryBlockEntity.ANIM_TICKS - be.getAnimTicks()) + partialTick
                : BloomeryBlockEntity.ANIM_TICKS;
            KeyframeAnimations.animate(model, anim, (long) (elapsedTicks * 50.0F), 1.0F, animationCache);
        }

        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        model.renderBody(pose, vc, light, overlay);
        // While lit, the furnace interior glows orange at full brightness.
        boolean lit = be.isLit();
        model.renderInside(pose, vc,
            lit ? LightTexture.FULL_BRIGHT : light, overlay,
            lit ? 0xFFFF7A2E : 0xFFFFFFFF);
        pose.popPose();

        ItemStack held = be.getHeldItem();
        if (!held.isEmpty()) {
            renderHeldItem(be, held, facing, partialTick, pose, buffers, light);
        }
    }

    /** Draws the held stack inside the bloomery — one copy, or two clipping copies for a stack. */
    private void renderHeldItem(BloomeryBlockEntity be, ItemStack stack, Direction facing,
                                float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        // Slide-in through the door — ease-out (quick start, gentle settle), like Create.
        float slide = 0.0F;
        if (be.getInsertAnimTicks() > 0) {
            float f = Math.max(0.0F, (be.getInsertAnimTicks() - partialTick) / BloomeryBlockEntity.SLIDE_TICKS);
            slide = f * f * 0.4F;
        }

        boolean blockItem = stack.getItem() instanceof BlockItem;
        float scale = blockItem ? 0.25F : 0.5F; // block items render at half size

        pose.pushPose();
        pose.translate(0.5, 0.32, 0.5);                          // rest spot inside the bloomery
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot())); // orient with the bloomery
        pose.translate(0.0, 0.0, slide);                         // slide in along the door axis
        pose.scale(scale, scale, scale);

        int copies = stack.getCount() > 1 ? 2 : 1;
        for (int i = 0; i < copies; i++) {
            pose.pushPose();
            if (i == 1) {
                pose.translate(0.22, 0.14, 0.22); // second copy clips slightly into the first
            }
            if (!blockItem) {
                pose.mulPose(Axis.XP.rotationDegrees(90.0F)); // lay flat items down
            }
            itemRenderer.renderStatic(stack, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
                pose, buffers, be.getLevel(), 0);
            pose.popPose();
        }
        pose.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(BloomeryBlockEntity be) {
        // The model is two blocks tall — grow the cull box up so the top isn't clipped.
        BlockPos pos = be.getBlockPos();
        return new AABB(pos).expandTowards(0.0, 3.0, 0.0);
    }
}
