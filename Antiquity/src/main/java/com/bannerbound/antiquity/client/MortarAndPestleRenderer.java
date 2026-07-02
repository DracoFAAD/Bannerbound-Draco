package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;
import org.joml.Vector3f;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.MortarAndPestleBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Block entity renderer for the Mortar and Pestle. Draws the body from {@link MortarAndPestleModel},
 * the liquid surface as a translucent tinted quad ({@link MortarLiquids}), the ingredient resting
 * in the bowl, and — during a grind — the "Mix" pestle animation driven by the mix timer.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class MortarAndPestleRenderer implements BlockEntityRenderer<MortarAndPestleBlockEntity> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
        BannerboundAntiquity.MODID, "textures/liquid_mortar_and_pestle.png");

    private final MortarAndPestleModel model;
    private final ItemRenderer itemRenderer;
    private final Vector3f animationCache = new Vector3f();

    public MortarAndPestleRenderer(BlockEntityRendererProvider.Context context) {
        this.model = new MortarAndPestleModel(context.bakeLayer(MortarAndPestleModel.LAYER_LOCATION));
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(MortarAndPestleBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        // Entity-style models are built head-up around a y=24 baseline; this places the model
        // on the block and flips it into block space (the standard BER ↔ entity-model adapter).
        pose.translate(0.5, 1.5, 0.5);
        pose.scale(1.0F, -1.0F, -1.0F);

        // Reset to the baked default pose, then drive the pestle. A live minigame session takes
        // priority — the pestle circles with the player's grind and dips with their press; otherwise
        // a finished grind plays a short flourish from the synced mix timer for everyone nearby.
        model.root().getAllParts().forEach(ModelPart::resetPose);
        if (MortarGrindState.activeFor(be.getBlockPos())) {
            KeyframeAnimations.animate(model, MortarAndPestleAnimations.MIX,
                MortarGrindState.grindElapsedMs(), 1.0F, animationCache);
            model.pestle().y += MortarGrindState.pressDepth() * 1.2F;
        } else if (be.isMixing()) {
            float elapsedTicks = (MortarAndPestleBlockEntity.MIX_CYCLE_TICKS - be.getMixAnimTicks()) + partialTick;
            long elapsedMs = (long) (elapsedTicks * 50.0F);
            KeyframeAnimations.animate(model, MortarAndPestleAnimations.MIX, elapsedMs, 1.0F, animationCache);
        }

        model.renderBody(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), light, overlay);

        MortarLiquids.Entry liquid = MortarLiquids.get(be.getLiquidId());
        if (liquid != null) {
            renderLiquid(pose, buffers, liquid, light);
        }
        pose.popPose();

        // The ingredient is drawn in plain block space (no entity-model flip).
        ItemStack ingredient = be.getIngredient();
        if (!ingredient.isEmpty()) {
            renderIngredient(pose, buffers, ingredient, light, be.getLevel());
        }
    }

    /** Draws the ingredient sitting in the bowl. Position/scale are tuned to the model's bowl. */
    private void renderIngredient(PoseStack pose, MultiBufferSource buffers, ItemStack stack,
                                  int light, Level level) {
        pose.pushPose();
        pose.translate(0.5, 0.35, 0.5);
        pose.scale(0.34F, 0.34F, 0.34F);
        if (!(stack.getItem() instanceof BlockItem)) {
            // Lay flat items down in the bowl rather than standing them on edge.
            pose.mulPose(Axis.XP.rotationDegrees(90.0F));
        }
        itemRenderer.renderStatic(stack, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
            pose, buffers, level, 0);
        pose.popPose();
    }

    /** Draws the liquid surface: a horizontal quad at the Liquid Holder bone's top face. */
    private void renderLiquid(PoseStack pose, MultiBufferSource buffers, MortarLiquids.Entry liquid,
                              int light) {
        TextureAtlasSprite sprite = liquid.sprite();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS));

        pose.pushPose();
        model.master().translateAndRotate(pose);
        model.liquidHolder().translateAndRotate(pose);
        PoseStack.Pose p = pose.last();

        int color = liquid.tint();
        int overlay = OverlayTexture.NO_OVERLAY;
        // ModelPart.render divides cube coords by 16 internally; this hand-built quad does not,
        // so the Liquid Holder cube's pixel coords must be scaled to block space here (÷16).
        // Cube spans local x[-6,1], z[-1,6]; its visible surface is the y=-1 face.
        float s = 1.0F / 16.0F;
        float y = -1.0F * s;
        float x0 = -6.0F * s, x1 = 1.0F * s, z0 = -1.0F * s, z1 = 6.0F * s;
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();

        vertex(vc, p, x0, y, z1, u0, v1, color, light, overlay, 0.0F, -1.0F, 0.0F);
        vertex(vc, p, x1, y, z1, u1, v1, color, light, overlay, 0.0F, -1.0F, 0.0F);
        vertex(vc, p, x1, y, z0, u1, v0, color, light, overlay, 0.0F, -1.0F, 0.0F);
        vertex(vc, p, x0, y, z0, u0, v0, color, light, overlay, 0.0F, -1.0F, 0.0F);
        vertex(vc, p, x0, y, z0, u0, v0, color, light, overlay, 0.0F, 1.0F, 0.0F);
        vertex(vc, p, x1, y, z0, u1, v0, color, light, overlay, 0.0F, 1.0F, 0.0F);
        vertex(vc, p, x1, y, z1, u1, v1, color, light, overlay, 0.0F, 1.0F, 0.0F);
        vertex(vc, p, x0, y, z1, u0, v1, color, light, overlay, 0.0F, 1.0F, 0.0F);

        pose.popPose();
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z,
                               float u, float v, int color, int light, int overlay,
                               float nx, float ny, float nz) {
        vc.addVertex(p, x, y, z)
            .setColor(color)
            .setUv(u, v)
            .setOverlay(overlay)
            .setLight(light)
            .setNormal(p, nx, ny, nz);
    }
}
