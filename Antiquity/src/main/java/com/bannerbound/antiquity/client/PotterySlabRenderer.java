package com.bannerbound.antiquity.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.PotterySlabBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Renders the pottery slab's clay pile, selectable output, and spinning work-in-progress clay. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class PotterySlabRenderer implements BlockEntityRenderer<PotterySlabBlockEntity> {
    private static final double TOP_Y = 0.53;
    private static final double CELL = 0.2;
    private static final float BLOCK_SCALE = 0.22F;
    private static final float ITEM_SCALE = 0.32F;

    private final ItemRenderer itemRenderer;

    public PotterySlabRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(PotterySlabBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        long time = be.getLevel() != null ? be.getLevel().getGameTime() : 0L;
        float t = time + partialTick;
        ItemStack inProgress = be.getInProgress();
        if (inProgress.isEmpty()) {
            renderPile(be, partialTick, pose, buffers, light);
        }
        if (!inProgress.isEmpty()) {
            pose.pushPose();
            float fallback = t * 8.0F;
            float spin = PotterySpinState.angleDegrees(be.getBlockPos(), fallback);
            pose.translate(0.5, TOP_Y + 0.19 + (PotterySpinState.activeFor(be.getBlockPos()) ? 0.0 : Math.sin(t * 0.2F) * 0.01), 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(spin));
            pose.scale(0.42F, 0.42F, 0.42F);
            itemRenderer.renderStatic(inProgress, ItemDisplayContext.NONE, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
            pose.popPose();
            return;
        }

        ItemStack ghostResult = be.getGhostResult();
        ItemStack result = be.getResult();
        boolean exactSelection = !result.isEmpty()
            && !ghostResult.isEmpty()
            && be.getGhostIngredients().isEmpty()
            && result.is(ghostResult.getItem());

        if (!ghostResult.isEmpty() && !exactSelection) {
            renderGhost(be, pose, buffers, light, ghostResult, t);
        }
        if (!ghostResult.isEmpty() && be.getGhostCandidateCount() >= 2) {
            GhostArrowRenderer.render(be, pose, buffers);
        }
        if (!result.isEmpty()) {
            pose.pushPose();
            pose.translate(0.5, 1.08 + (float) Math.sin(t * 0.1F) * 0.04F, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(t * 3.0F));
            pose.scale(0.5F, 0.5F, 0.5F);
            itemRenderer.renderStatic(result, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
            pose.popPose();
        }
    }

    private void renderPile(PotterySlabBlockEntity be, float partialTick, PoseStack pose,
                            MultiBufferSource buffers, int light) {
        List<ItemStack> contents = be.getContents();
        Direction dir = be.getInsertDir();
        int slideCell = be.getLastSlideCell();
        float slide = 0.0F;
        if (be.getInsertAnimTicks() > 0) {
            float f = Math.max(0.0F,
                (be.getInsertAnimTicks() - partialTick) / PotterySlabBlockEntity.SLIDE_TICKS);
            slide = f * f * 0.6F;
        }

        for (int cell = 0; cell < contents.size() && cell < 9; cell++) {
            ItemStack s = contents.get(cell);
            if (s.isEmpty()) continue;
            double ox = ((cell % 3) - 1) * CELL;
            double oz = ((cell / 3) - 1) * CELL;
            boolean isBlock = s.getItem() instanceof BlockItem;
            float scale = isBlock ? BLOCK_SCALE : ITEM_SCALE;
            double layerH = isBlock ? scale + 0.005 : 0.022;
            for (int layer = 0; layer < s.getCount() && layer < 9; layer++) {
                boolean slides = cell == slideCell && layer == s.getCount() - 1;
                double sx = slides ? dir.getStepX() * slide : 0.0;
                double sz = slides ? dir.getStepZ() * slide : 0.0;
                pose.pushPose();
                pose.translate(0.5 + ox + sx, TOP_Y + layer * layerH, 0.5 + oz + sz);
                pose.scale(scale, scale, scale);
                pose.mulPose(Axis.YP.rotationDegrees(((cell * 61 + layer * 97) % 41) - 20));
                if (!isBlock) {
                    pose.mulPose(Axis.XP.rotationDegrees(90.0F));
                }
                itemRenderer.renderStatic(s, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
                    pose, buffers, be.getLevel(), 0);
                pose.popPose();
            }
        }
    }

    private void renderGhost(PotterySlabBlockEntity be, PoseStack pose, MultiBufferSource buffers,
                             int light, ItemStack ghostResult, float t) {
        MultiBufferSource ghostBuffers = GhostItemRenderer.wrap(buffers);
        int nextFree = be.getContents().size();
        for (ItemStack g : be.getGhostIngredients()) {
            int cell = -1;
            int baseLayer = 0;
            for (int i = 0; i < be.getContents().size(); i++) {
                if (be.getContents().get(i).is(g.getItem())) {
                    cell = i;
                    baseLayer = be.getContents().get(i).getCount();
                    break;
                }
            }
            if (cell < 0) {
                if (nextFree >= 9) continue;
                cell = nextFree++;
            }
            double ox = ((cell % 3) - 1) * CELL;
            double oz = ((cell / 3) - 1) * CELL;
            boolean isBlock = g.getItem() instanceof BlockItem;
            float scale = isBlock ? BLOCK_SCALE : ITEM_SCALE;
            double layerH = isBlock ? scale + 0.005 : 0.022;
            for (int layer = baseLayer; layer < baseLayer + g.getCount() && layer < 9; layer++) {
                pose.pushPose();
                pose.translate(0.5 + ox, TOP_Y + layer * layerH, 0.5 + oz);
                pose.scale(scale, scale, scale);
                pose.mulPose(Axis.YP.rotationDegrees(((cell * 61 + layer * 97) % 41) - 20));
                if (!isBlock) {
                    pose.mulPose(Axis.XP.rotationDegrees(90.0F));
                }
                itemRenderer.renderStatic(g, ItemDisplayContext.NONE, light,
                    OverlayTexture.NO_OVERLAY, pose, ghostBuffers, be.getLevel(), 0);
                pose.popPose();
            }
        }
        pose.pushPose();
        pose.translate(0.5, 1.08 + (float) Math.sin(t * 0.1F) * 0.04F, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(t * 3.0F));
        pose.scale(0.5F, 0.5F, 0.5F);
        itemRenderer.renderStatic(ghostResult, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY, pose, ghostBuffers, be.getLevel(), 0);
        pose.popPose();
    }
}
