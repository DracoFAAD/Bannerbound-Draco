package com.bannerbound.antiquity.client;

import java.util.List;

import com.bannerbound.antiquity.block.entity.StoneAnvilBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.joml.Matrix4f;

/**
 * Renders the Stone Anvil in its two modes:
 * <ul>
 *   <li><b>Pile mode</b> — the placed-item pile (3×3 grid) + the ghost recipe preview / floating
 *       result, cloned from {@link FletchingStationRenderer} (generic over {@code GhostRecipeWorkstation}).</li>
 *   <li><b>Cast mode</b> — the placed fired mold (transparent-cavity {@code _model}) with the molten
 *       metal pooling in its cavity as you pour.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class StoneAnvilRenderer implements BlockEntityRenderer<StoneAnvilBlockEntity> {
    // Pile grid (just above the 13px anvil top).
    private static final double TOP_Y = 0.84;
    private static final double CELL = 0.2;
    private static final float BLOCK_SCALE = 0.2F;
    private static final float ITEM_SCALE = 0.3F;

    // 3D placed mold: a baked generated model (transparent cavity = a real hole) laid flat, with the
    // molten pooling in the hole. MOLD_THICK ≈ the model's extruded depth at this scale.
    private static final float MOLD_SCALE = 0.85F;
    private static final float MOLD_CENTER_Y = 0.84F;
    private static final float MOLD_THICK = 0.05F;
    private static final float MOLTEN_HALF = 0.37F * MOLD_SCALE; // a touch inside the mold's edge

    /** The standalone baked model for a placed mold of {@code shape} (registered in client setup). */
    public static net.minecraft.client.resources.model.ModelResourceLocation placedMoldModel(String shape) {
        return net.minecraft.client.resources.model.ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath("bannerboundantiquity",
                "item/fired_clay_mold_" + shape + "_model"));
    }

    private final ItemRenderer itemRenderer;

    public StoneAnvilRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(StoneAnvilBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        if (be.hasMold()) {
            renderMold(be, pose, buffers, light);
        } else if (be.isForging()) {
            renderForging(be, partialTick, pose, buffers);
        } else {
            renderPile(be, partialTick, pose, buffers, light);
        }
    }

    // ── Forging mode — the glowing workpiece on the anvil during the cold-hammer minigame ──────────
    private void renderForging(StoneAnvilBlockEntity be, float partialTick, PoseStack pose,
                               MultiBufferSource buffers) {
        long gt = be.getLevel() != null ? be.getLevel().getGameTime() : 0L;
        float heat = be.forgeHeat();

        // Heat is shown ON the workpiece itself via the entity overlay (white-flash + red), so it glows
        // white-hot when fresh and red-hot as it cools — no texture quad, full-bright. Pulses a touch.
        float pulse = 0.85F + 0.15F * Mth.sin((gt + partialTick) * 0.30F);
        float h = Mth.clamp(heat * pulse, 0F, 1F);
        int whiteU = (int) (h * 15F);            // 15 = full white-hot, 0 = none
        int redV = 3 + (int) ((1F - h) * 7F);    // 3 = red row (cooling), 10 = neutral (hottest)
        int overlay = net.minecraft.client.renderer.texture.OverlayTexture.pack(whiteU, redV);

        // The workpiece, laid on the anvil, jolting down on each strike.
        float since = (gt - be.lastStruckGameTime()) + partialTick;
        float recoil = since < 5f ? -Math.max(0f, (5f - since) / 5f) * 0.06F : 0f;
        ItemStack work = be.forgeItem();
        boolean isBlock = work.getItem() instanceof BlockItem;
        pose.pushPose();
        pose.translate(0.5, MOLD_CENTER_Y + 0.04 + recoil, 0.5);
        pose.scale(0.42F, 0.42F, 0.42F);
        if (!isBlock) pose.mulPose(Axis.XP.rotationDegrees(90.0F));
        itemRenderer.renderStatic(work, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
            overlay, pose, buffers, be.getLevel(), 0);
        pose.popPose();
    }

    // ── Cast mode ───────────────────────────────────────────────────────────────────────────────
    private void renderMold(StoneAnvilBlockEntity be, PoseStack pose, MultiBufferSource buffers, int light) {
        int blockLight = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos());
        Matrix4f mat = pose.last().pose();

        // 1) Molten fill — a quad over the mold footprint, masked to the cavity SHAPE by the hole in
        //    the cutout body (opaque parts write depth and occlude it), so it's the right size for free.
        float frac = be.fillFraction();
        if (frac > 0.0F) {
            TextureAtlasSprite molten = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "block/crucible/molten_metal"));
            int rgb = be.tintColor();
            float dim = be.molten() ? 1.0F : 0.78F;
            float r = ((rgb >> 16) & 0xFF) / 255.0F * dim;
            float g = ((rgb >> 8) & 0xFF) / 255.0F * dim;
            float b = (rgb & 0xFF) / 255.0F * dim;
            int fillLight = be.molten() ? LightTexture.FULL_BRIGHT : blockLight;
            float y = (MOLD_CENTER_Y - MOLD_THICK / 2f) + MOLD_THICK * frac;
            VertexConsumer vc = buffers.getBuffer(RenderType.translucent());
            quad(vc, mat, 0.5F - MOLTEN_HALF, y, 0.5F + MOLTEN_HALF, r, g, b, 1.0F, fillLight,
                molten.getU0(), molten.getU1(), molten.getV0(), molten.getV1());
        }

        // 2) The 3D mold body — the baked generated model (alpha-0 cavity = a real hole through the
        //    extruded thickness), laid flat on the anvil, rendered cutout.
        net.minecraft.client.resources.model.BakedModel model =
            Minecraft.getInstance().getModelManager().getModel(placedMoldModel(be.moldShape()));
        VertexConsumer body = buffers.getBuffer(net.minecraft.client.renderer.Sheets.cutoutBlockSheet());
        pose.pushPose();
        pose.translate(0.5, MOLD_CENTER_Y, 0.5);
        pose.mulPose(Axis.XP.rotationDegrees(90.0F)); // lay flat → the extruded depth sticks up
        pose.scale(MOLD_SCALE, MOLD_SCALE, MOLD_SCALE);
        pose.translate(-0.5, -0.5, -0.5); // centre the 0..1 model on the anvil
        renderModelQuads(model, pose, body, blockLight, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    /** Draws every quad of a baked model with flat white tint at the given light. */
    private static void renderModelQuads(net.minecraft.client.resources.model.BakedModel model,
                                         PoseStack pose, VertexConsumer vc, int light, int overlay) {
        PoseStack.Pose p = pose.last();
        net.minecraft.util.RandomSource rand = net.minecraft.util.RandomSource.create();
        for (Direction d : Direction.values()) {
            rand.setSeed(0L);
            for (net.minecraft.client.renderer.block.model.BakedQuad q : model.getQuads(null, d, rand)) {
                vc.putBulkData(p, q, 1.0F, 1.0F, 1.0F, 1.0F, light, overlay);
            }
        }
        rand.setSeed(0L);
        for (net.minecraft.client.renderer.block.model.BakedQuad q : model.getQuads(null, null, rand)) {
            vc.putBulkData(p, q, 1.0F, 1.0F, 1.0F, 1.0F, light, overlay);
        }
    }

    // ── Pile mode (clone of FletchingStationRenderer) ───────────────────────────────────────────
    private void renderPile(StoneAnvilBlockEntity be, float partialTick, PoseStack pose,
                            MultiBufferSource buffers, int light) {
        List<ItemStack> contents = be.getContents();
        Direction dir = be.getInsertDir();
        int slideCell = be.getLastSlideCell();
        float slide = 0.0F;
        if (be.getInsertAnimTicks() > 0) {
            float f = Math.max(0.0F,
                (be.getInsertAnimTicks() - partialTick) / StoneAnvilBlockEntity.SLIDE_TICKS);
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
                if (!isBlock) pose.mulPose(Axis.XP.rotationDegrees(90.0F));
                itemRenderer.renderStatic(s, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
                    pose, buffers, be.getLevel(), 0);
                pose.popPose();
            }
        }

        ItemStack ghostResult = be.getGhostResult();
        ItemStack result = be.getResult();
        if (!ghostResult.isEmpty()) {
            MultiBufferSource ghostBuffers = GhostItemRenderer.wrap(buffers);
            int nextFree = contents.size();
            for (ItemStack ghost : be.getGhostIngredients()) {
                int cell = -1;
                int baseLayer = 0;
                for (int i = 0; i < contents.size(); i++) {
                    if (contents.get(i).is(ghost.getItem())) {
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
                boolean isBlock = ghost.getItem() instanceof BlockItem;
                float scale = isBlock ? BLOCK_SCALE : ITEM_SCALE;
                double layerH = isBlock ? scale + 0.005 : 0.022;
                for (int layer = baseLayer; layer < baseLayer + ghost.getCount() && layer < 9; layer++) {
                    pose.pushPose();
                    pose.translate(0.5 + ox, TOP_Y + layer * layerH, 0.5 + oz);
                    pose.scale(scale, scale, scale);
                    pose.mulPose(Axis.YP.rotationDegrees(((cell * 61 + layer * 97) % 41) - 20));
                    if (!isBlock) pose.mulPose(Axis.XP.rotationDegrees(90.0F));
                    itemRenderer.renderStatic(ghost, ItemDisplayContext.NONE, light,
                        OverlayTexture.NO_OVERLAY, pose, ghostBuffers, be.getLevel(), 0);
                    pose.popPose();
                }
            }
            long time = be.getLevel() != null ? be.getLevel().getGameTime() : 0L;
            float t = time + partialTick;
            pose.pushPose();
            pose.translate(0.5, 1.3 + (float) Math.sin(t * 0.1F) * 0.04F, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(t * 3.0F));
            pose.scale(0.5F, 0.5F, 0.5F);
            itemRenderer.renderStatic(ghostResult, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, pose, ghostBuffers, be.getLevel(), 0);
            pose.popPose();
            GhostArrowRenderer.render(be, pose, buffers);
        }

        if (!result.isEmpty()) {
            double resultY = ghostResult.isEmpty() ? 1.3 : 1.75;
            long time = be.getLevel() != null ? be.getLevel().getGameTime() : 0L;
            float t = time + partialTick;
            pose.pushPose();
            pose.translate(0.5, resultY + (float) Math.sin(t * 0.1F) * 0.04F, 0.5);
            pose.mulPose(Axis.YP.rotationDegrees(t * 3.0F));
            pose.scale(0.5F, 0.5F, 0.5F);
            itemRenderer.renderStatic(result, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
            pose.popPose();
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f mat, float min, float y, float max,
                             float r, float g, float b, float a, int light,
                             float u0, float u1, float v0, float v1) {
        vertex(vc, mat, min, y, min, r, g, b, a, light, u0, v0);
        vertex(vc, mat, min, y, max, r, g, b, a, light, u0, v1);
        vertex(vc, mat, max, y, max, r, g, b, a, light, u1, v1);
        vertex(vc, mat, max, y, min, r, g, b, a, light, u1, v0);
    }

    private static void vertex(VertexConsumer vc, Matrix4f mat, float x, float y, float z,
                               float r, float g, float b, float a, int light, float u, float v) {
        vc.addVertex(mat, x, y, z)
            .setColor(r, g, b, a)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(0.0F, 1.0F, 0.0F);
    }
}
