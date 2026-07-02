package com.bannerbound.antiquity.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.KilnBlock;
import com.bannerbound.antiquity.block.entity.KilnBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Block entity renderer for the Kiln — draws the authored {@code kiln} dome model (fetched as a
 * baked block model, since the block itself renders {@link net.minecraft.world.level.block.RenderShape#INVISIBLE})
 * from the controller cell, rotated about the centre of the 2×2×2 footprint so the mouth faces the
 * block's {@link KilnBlock#FACING}. The held item rests at the mouth, sliding in when freshly added.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class KilnRenderer implements BlockEntityRenderer<KilnBlockEntity> {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final Direction[] DIRECTIONS_AND_NULL = {
        Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null
    };

    private final ItemRenderer itemRenderer;

    public KilnRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(KilnBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        Direction facing = state.getValue(KilnBlock.FACING);
        // The cells are solid + invisible, so the controller's own light is dark — sample above the
        // 2-tall dome instead so the model isn't rendered pitch black.
        int modelLight = be.getLevel() == null
            ? light
            : LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().above(2));

        pose.pushPose();
        // The model is authored from the controller's min-corner toward +x/+y/+z (mouth at -Z); rotate
        // it about the footprint centre (1,1) so authored-north lands on the block's facing.
        pose.translate(1.0, 0.0, 1.0);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - facing.toYRot()));
        pose.translate(-1.0, 0.0, -1.0);

        BlockRenderDispatcher brd = Minecraft.getInstance().getBlockRenderer();
        BakedModel model = brd.getBlockModel(state);
        VertexConsumer vc = buffers.getBuffer(ItemBlockRenderTypes.getRenderType(state, false));
        var posePose = pose.last();
        for (Direction cull : DIRECTIONS_AND_NULL) {
            List<BakedQuad> quads = model.getQuads(state, cull, RANDOM, ModelData.EMPTY, null);
            for (BakedQuad quad : quads) {
                // The dome itself doesn't glow — only the mouth (its light emission + the flame
                // particles) does. Draw it at its plain neighbourhood light.
                vc.putBulkData(posePose, quad, 1.0F, 1.0F, 1.0F, 1.0F, modelLight, overlay);
            }
        }
        pose.popPose();

        ItemStack held = be.getHeldItem();
        if (!held.isEmpty()) {
            // The item sits in the mouth — render it hot/bright while the fire is lit.
            int heldLight = be.getLitTicks() > 0 ? LightTexture.FULL_BRIGHT : modelLight;
            renderHeldItem(be, held, facing, partialTick, pose, buffers, heldLight);
        }
    }

    /** Draws the held stack resting at the kiln's mouth, with a short slide-in when freshly added. */
    private void renderHeldItem(KilnBlockEntity be, ItemStack stack, Direction facing,
                                float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        float slide = 0.0F;
        if (be.getInsertAnimTicks() > 0) {
            float f = Math.max(0.0F, (be.getInsertAnimTicks() - partialTick) / KilnBlockEntity.SLIDE_TICKS);
            slide = f * f * 0.4F;
        }

        boolean blockItem = stack.getItem() instanceof BlockItem;
        float scale = blockItem ? 0.35F : 0.5F;

        pose.pushPose();
        pose.translate(1.0, 0.3 - 3.0 / 16.0, 1.0);              // footprint centre, lowered 3px into the mouth
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - facing.toYRot())); // orient with the dome
        pose.translate(0.0, 0.0, -0.55 - slide);                 // out toward the (authored-north) mouth
        pose.scale(scale, scale, scale);

        // One copy for a single item, two slightly-clipping copies for a stack — same as the bloomery.
        int copies = stack.getCount() > 1 ? 2 : 1;
        for (int i = 0; i < copies; i++) {
            pose.pushPose();
            if (i == 1) {
                pose.translate(0.22, 0.14, 0.22); // second copy clips slightly into the first
            }
            if (!blockItem) {
                pose.mulPose(Axis.XP.rotationDegrees(90.0F)); // lay flat items down
            }
            itemRenderer.renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY,
                pose, buffers, be.getLevel(), 0);
            pose.popPose();
        }
        pose.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(KilnBlockEntity be) {
        // The dome spans the full 2×2×2 from the controller corner and rotates about the footprint
        // centre — grow the cull box generously in every horizontal direction and up.
        BlockPos pos = be.getBlockPos();
        return new AABB(pos.getX() - 1, pos.getY(), pos.getZ() - 1,
            pos.getX() + 3, pos.getY() + 3, pos.getZ() + 3);
    }
}
