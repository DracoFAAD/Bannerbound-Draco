package com.bannerbound.antiquity.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import org.joml.Matrix4f;

import com.bannerbound.antiquity.block.entity.CraftingStoneBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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

/**
 * Renders the Crafting Stone's contents: each DISTINCT item kind gets one cell of a 3×3 grid on the
 * stone's top, and multiples of the same kind pile up vertically in that cell (Create-depot style —
 * three planks read as a stack of three, not three copies side by side). When the pile matches a
 * recipe the result floats and spins above it. The most-recently-placed item (the touched stack's
 * top layer) slides in from the side the player placed it from. The stone block itself renders from
 * its blockstate model; this only draws the items.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class CraftingStoneRenderer implements BlockEntityRenderer<CraftingStoneBlockEntity> {
    /** Items sit on the stone's top (~7px) and are small so up to 9 fit in the 3×3 grid. */
    private static final double TOP_Y = 0.5;
    private static final double CELL = 0.2;
    /** Block items render small (a full block would dwarf the stone); flat items render larger. */
    private static final float BLOCK_SCALE = 0.2F;
    private static final float ITEM_SCALE = 0.3F;

    private final ItemRenderer itemRenderer;
    private final Font font;

    public CraftingStoneRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
        this.font = context.getFont();
    }

    @Override
    public void render(CraftingStoneBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        List<ItemStack> contents = be.getContents();
        Direction dir = be.getInsertDir();
        int slideCell = be.getLastSlideCell();
        float slide = 0.0F;
        if (be.getInsertAnimTicks() > 0) {
            float f = Math.max(0.0F,
                (be.getInsertAnimTicks() - partialTick) / CraftingStoneBlockEntity.SLIDE_TICKS);
            slide = f * f * 0.6F;
        }

        // One cell per stack; multiples pile up in that cell. Each layer gets a small deterministic
        // scatter rotation so a pile reads as a pile instead of z-fighting copies.
        for (int cell = 0; cell < contents.size() && cell < 9; cell++) {
            ItemStack s = contents.get(cell);
            if (s.isEmpty()) continue;
            double ox = ((cell % 3) - 1) * CELL;
            double oz = ((cell / 3) - 1) * CELL;
            boolean isBlock = s.getItem() instanceof BlockItem;
            float scale = isBlock ? BLOCK_SCALE : ITEM_SCALE;
            // Blocks stack by their scaled height; flat items by their sprite thickness.
            double layerH = isBlock ? scale + 0.005 : 0.022;
            int topLayer = Math.min(s.getCount(), 9) - 1;
            for (int layer = 0; layer < s.getCount() && layer < 9; layer++) {
                boolean slides = cell == slideCell && layer == s.getCount() - 1;
                double sx = slides ? dir.getStepX() * slide : 0.0;
                double sz = slides ? dir.getStepZ() * slide : 0.0;
                pose.pushPose();
                pose.translate(0.5 + ox + sx, TOP_Y + layer * layerH, 0.5 + oz + sz);
                pose.scale(scale, scale, scale);
                pose.mulPose(Axis.YP.rotationDegrees(((cell * 61 + layer * 97) % 41) - 20));
                if (!isBlock) {
                    pose.mulPose(Axis.XP.rotationDegrees(90.0F)); // lay flat items down
                }
                itemRenderer.renderStatic(s, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
                    pose, buffers, be.getLevel(), 0);
                pose.popPose();
            }
            // Vanilla-style stack count, billboarded over the top of the pile.
            drawCount(pose, buffers, s.getCount(), 0xFFFFFFFF,
                0.5 + ox, TOP_Y + topLayer * layerH + 0.12, 0.5 + oz);
        }

        // Ghost preview: when the pile is partway to a recipe, the still-missing ingredients render
        // as low-alpha silhouettes — piling on top of a partially-placed stack's cell, or claiming
        // the next free cells — and the candidate's result floats ghosted where the real preview
        // will appear once the pile is complete. Can coexist with a solid result (locked recipe +
        // incidental exact match — the solid one floats higher, see below).
        ItemStack ghostResult = be.getGhostResult();
        ItemStack result = be.getResult();
        if (!ghostResult.isEmpty()) {
            MultiBufferSource ghostBuffers = GhostItemRenderer.wrap(buffers);
            int nextFree = contents.size();
            for (ItemStack g : be.getGhostIngredients()) {
                int cell = -1;
                int baseLayer = 0;
                for (int i = 0; i < contents.size(); i++) {
                    if (contents.get(i).is(g.getItem())) {
                        cell = i;
                        baseLayer = contents.get(i).getCount();
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
                int ghostTop = baseLayer;
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
                    ghostTop = layer;
                }
                // "Still needed" count over the ghost pile, tinted amber so it reads as a requirement.
                drawCount(pose, buffers, g.getCount(), 0xFFFFD27F,
                    0.5 + ox, TOP_Y + ghostTop * layerH + 0.12, 0.5 + oz);
            }
            // Ghosted result — same spot and motion as the real preview, so completing the pile
            // reads as the ghost "solidifying" in place.
            long time = be.getLevel() != null ? be.getLevel().getGameTime() : 0L;
            float t = time + partialTick;
            pose.pushPose();
            pose.translate(0.5, 1.05 + (float) Math.sin(t * 0.1F) * 0.04F, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(t * 3.0F));
            pose.scale(0.5F, 0.5F, 0.5F);
            itemRenderer.renderStatic(ghostResult, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, pose, ghostBuffers, be.getLevel(), 0);
            pose.popPose();
            GhostArrowRenderer.render(be, pose, buffers);
        }

        // Result preview: float + spin above the stone at full brightness when a recipe matches.
        // When a locked ghost is also showing (the pile incidentally matches a different recipe),
        // the solid result floats ABOVE the ghost so the player's chosen recipe keeps its spot.
        if (!result.isEmpty()) {
            double resultY = ghostResult.isEmpty() ? 1.05 : 1.5;
            long time = be.getLevel() != null ? be.getLevel().getGameTime() : 0L;
            float t = time + partialTick;
            float spin = t * 3.0F;
            float bob = (float) Math.sin(t * 0.1F) * 0.04F;
            pose.pushPose();
            pose.translate(0.5, resultY + bob, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(spin));
            pose.scale(0.5F, 0.5F, 0.5F);
            itemRenderer.renderStatic(result, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
            pose.popPose();
        }
    }

    /** A small vanilla-style count, billboarded to face the camera, drawn at the block-local point
     *  {@code (lx, ly, lz)}. Skipped for counts of 1 (matches inventory behaviour). The shadow is a
     *  dark copy offset 1px behind the white glyphs so it reads at this tiny world scale. */
    private void drawCount(PoseStack pose, MultiBufferSource buffers, int count, int color,
                           double lx, double ly, double lz) {
        if (count <= 1 || font == null) return;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        String s = Integer.toString(count);
        float fs = 0.0125F; // 1 font px → ~inventory-digit proportion on the stone
        float x = -font.width(s) / 2.0F;
        float y = -font.lineHeight / 2.0F;
        pose.pushPose();
        pose.translate(lx, ly, lz);
        pose.mulPose(camera.rotation());
        pose.scale(fs, -fs, fs); // font space: +Y is down → flip Y
        Matrix4f mat = pose.last().pose();
        font.drawInBatch(s, x + 0.6F, y + 0.6F, 0xD0000000, false,
            mat, buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        font.drawInBatch(s, x, y, color, false,
            mat, buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
