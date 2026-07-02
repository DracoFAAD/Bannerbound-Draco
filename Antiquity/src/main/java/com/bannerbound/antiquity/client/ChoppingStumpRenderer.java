package com.bannerbound.antiquity.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.ChoppingStumpBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Renders the Chopping Stump using the AUTHORED {@code chopping_stump} model (its geometry + UV
 * layout) but re-textured with the source log's sprites — so an oak stump shows oak bark/end-grain,
 * birch shows birch, etc., without stretching the texture the way a squished log cube did. Each of
 * the stump model's quads is re-sprited: top-face quads → the log's end (ring) sprite, the rest →
 * the log's side (bark) sprite, remapping the UVs proportionally so the stump's UV mapping is kept.
 * Then the deposited logs render on top, sliding in from the side the player deposited from.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class ChoppingStumpRenderer implements BlockEntityRenderer<ChoppingStumpBlockEntity> {
    /** Logs rest just above the stump top (6/16). */
    private static final double TOP_Y = 6.0 / 16.0;
    /** Vertex layout of {@code DefaultVertexFormat.BLOCK}: 8 ints/vertex, texture UV at ints 4–5. */
    private static final int VERTEX_INTS = 8;
    private static final int UV_INT_OFFSET = 4;
    private static final RandomSource RANDOM = RandomSource.create();

    private final ItemRenderer itemRenderer;

    public ChoppingStumpRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(ChoppingStumpBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        renderStumpBody(be, pose, buffers, light, overlay);
        renderDepositedLogs(be, partialTick, pose, buffers, light);
    }

    /** Draws the authored stump model with the source log's sprites swapped in. */
    private void renderStumpBody(ChoppingStumpBlockEntity be, PoseStack pose,
                                 MultiBufferSource buffers, int light, int overlay) {
        BlockRenderDispatcher brd = Minecraft.getInstance().getBlockRenderer();
        BlockState stumpState = be.getBlockState();
        BlockState logState = be.getLogType().defaultBlockState();

        BakedModel stumpModel = brd.getBlockModel(stumpState);
        BakedModel logModel = brd.getBlockModel(logState);

        // The log is a full cube, so its end (up) and side (north) faces are culled into those
        // buckets. The stump's faces aren't on block boundaries, so they live in the null bucket —
        // we classify each by its geometric direction instead (up/down → end, sides → bark).
        TextureAtlasSprite logEnd = firstSprite(logModel, logState, Direction.UP);
        TextureAtlasSprite logSide = firstSprite(logModel, logState, Direction.NORTH);
        if (logEnd == null) logEnd = logSide;
        if (logSide == null) logSide = logEnd;
        if (logSide == null) return; // log model had no usable sprites — nothing to draw

        VertexConsumer vc = buffers.getBuffer(ItemBlockRenderTypes.getRenderType(logState, false));
        var posePose = pose.last();
        for (Direction cull : DIRECTIONS_AND_NULL) {
            List<BakedQuad> quads = stumpModel.getQuads(stumpState, cull, RANDOM, ModelData.EMPTY, null);
            for (BakedQuad quad : quads) {
                Direction qd = quad.getDirection();
                TextureAtlasSprite target = (qd == Direction.UP || qd == Direction.DOWN) ? logEnd : logSide;
                vc.putBulkData(posePose, resprite(quad, target), 1.0F, 1.0F, 1.0F, 1.0F, light, overlay);
            }
        }
    }

    private void renderDepositedLogs(ChoppingStumpBlockEntity be, float partialTick, PoseStack pose,
                                     MultiBufferSource buffers, int light) {
        ItemStack logs = be.getLogs();
        if (logs.isEmpty()) return;

        Direction dir = be.getInsertDir();
        float slide = 0.0F;
        if (be.getInsertAnimTicks() > 0) {
            float f = Math.max(0.0F,
                (be.getInsertAnimTicks() - partialTick) / ChoppingStumpBlockEntity.SLIDE_TICKS);
            slide = f * f * 0.6F;
        }

        pose.pushPose();
        pose.translate(0.5 + dir.getStepX() * slide, TOP_Y + 0.12, 0.5 + dir.getStepZ() * slide);
        pose.scale(0.55F, 0.55F, 0.55F);
        pose.mulPose(Axis.XP.rotationDegrees(90.0F)); // lay the log on its side
        int copies = logs.getCount() > 1 ? 2 : 1;
        for (int i = 0; i < copies; i++) {
            pose.pushPose();
            if (i == 1) {
                pose.translate(0.28, 0.28, 0.16); // second copy offset so the two don't z-fight
            }
            itemRenderer.renderStatic(logs, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY,
                pose, buffers, be.getLevel(), 0);
            pose.popPose();
        }
        pose.popPose();
    }

    private static final Direction[] DIRECTIONS_AND_NULL = {
        Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null
    };

    private static TextureAtlasSprite firstSprite(BakedModel model, BlockState state, Direction face) {
        List<BakedQuad> quads = model.getQuads(state, face, RANDOM, ModelData.EMPTY, null);
        return quads.isEmpty() ? null : quads.get(0).getSprite();
    }

    /** Copy {@code quad} but remap its UVs from its own sprite into {@code target} (proportionally),
     *  so the stump model's UV layout is preserved while drawing the log's texture. */
    private static BakedQuad resprite(BakedQuad quad, TextureAtlasSprite target) {
        TextureAtlasSprite from = quad.getSprite();
        int[] verts = quad.getVertices().clone();
        float fu0 = from.getU0(), fu1 = from.getU1(), fv0 = from.getV0(), fv1 = from.getV1();
        float tu0 = target.getU0(), tu1 = target.getU1(), tv0 = target.getV0(), tv1 = target.getV1();
        for (int v = 0; v < 4; v++) {
            int u = v * VERTEX_INTS + UV_INT_OFFSET;
            float uu = Float.intBitsToFloat(verts[u]);
            float vv = Float.intBitsToFloat(verts[u + 1]);
            float u01 = fu1 == fu0 ? 0.0F : (uu - fu0) / (fu1 - fu0);
            float v01 = fv1 == fv0 ? 0.0F : (vv - fv0) / (fv1 - fv0);
            verts[u] = Float.floatToRawIntBits(tu0 + u01 * (tu1 - tu0));
            verts[u + 1] = Float.floatToRawIntBits(tv0 + v01 * (tv1 - tv0));
        }
        return new BakedQuad(verts, quad.getTintIndex(), quad.getDirection(), target, quad.isShade());
    }
}
