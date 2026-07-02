package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;

import com.bannerbound.antiquity.block.FermentationTroughBlock;
import com.bannerbound.antiquity.block.entity.FermentationTroughBlockEntity;
import com.bannerbound.antiquity.recipe.GrogRecipe;
import com.bannerbound.antiquity.recipe.GrogRecipeManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Block entity renderer for the Fermentation Trough — draws the liquid surface as a horizontal quad
 * inside the hollow, at the shared pool's fill height. Plain water tints blue; once charged, the
 * liquid ripens from a murky must to the grog recipe's full colour as it ferments ({@link
 * GrogRecipeManager}). The quad is rotated to match the block's facing and stretched to the open ends
 * so a connected pool reads as one surface. (Ambient bubbling FX live in the block's animateTick.)
 *
 * <p>Uses {@link RenderType#translucent()} (the terrain translucent layer) + the block's real light, so
 * it lights like water and doesn't glow full-bright under shaderpacks — matching {@link ClayTankRenderer}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class FermentationTroughRenderer implements BlockEntityRenderer<FermentationTroughBlockEntity> {
    private static final ResourceLocation WATER_STILL =
        ResourceLocation.withDefaultNamespace("block/water_still");

    /** Plain water tint for the greyscale {@code water_still} sprite (vanilla plains water blue). */
    private static final int WATER_COLOR = 0xCC3F76E4;

    public FermentationTroughRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(FermentationTroughBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof FermentationTroughBlock) || be.getLevel() == null) return;

        // Shared run pool: every cell draws its surface at the run's fill level (material conservation).
        float frac = FermentationTroughBlock.runFraction(be.getLevel(), be.getBlockPos());
        if (frac <= 0.001F) return;

        // Liquid extent in model pixels (cavity 2..14), opened to the rim at a connected end.
        boolean left = state.getValue(FermentationTroughBlock.LEFT);    // connected_left → +x end open
        boolean right = state.getValue(FermentationTroughBlock.RIGHT);  // connected_right → -x end open
        float x0 = (right ? 0.0F : 2.0F) / 16.0F;
        float x1 = (left ? 16.0F : 14.0F) / 16.0F;
        float z0 = 3.0F / 16.0F;
        float z1 = 13.0F / 16.0F;
        float y = (2.0F + frac * 4.5F) / 16.0F;

        // Liquid colour: plain water blue, or grog's tint once charged — ripening from a murky must to
        // the full vivid colour as it ferments, so the colour itself reads as progress.
        GrogRecipe recipe = be.isCharged() ? GrogRecipeManager.byId(be.grogRecipeId()) : null;
        int argb = WATER_COLOR;
        if (recipe != null) {
            int full = 0xFF000000 | (recipe.tint() & 0xFFFFFF);
            int must = 0xCC000000 | (mix(recipe.tint(), 0x6B675F, 0.55F) & 0xFFFFFF); // pale, watery start
            argb = lerpColor(must, full, be.fermentProgress(be.getLevel().getGameTime()));
        }
        float a = (argb >>> 24) / 255.0F;
        float r = ((argb >> 16) & 0xFF) / 255.0F;
        float g = ((argb >> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;

        // The block's own light (steady, water-like) — not the entity light that blooms under shaders.
        int packed = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos());
        TextureAtlasSprite sprite = Minecraft.getInstance()
            .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(WATER_STILL);

        pose.pushPose();
        // Match the blockstate's y-rotation (facing=north authored; +90 per clockwise step) about the
        // block centre so the liquid aligns with the wooden body.
        pose.translate(0.5, 0.0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-yRot(state.getValue(FermentationTroughBlock.FACING))));
        pose.translate(-0.5, 0.0, -0.5);
        Matrix4f mat = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.translucent());

        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        vertex(vc, mat, x0, y, z1, r, g, b, a, packed, u0, v1);
        vertex(vc, mat, x1, y, z1, r, g, b, a, packed, u1, v1);
        vertex(vc, mat, x1, y, z0, r, g, b, a, packed, u1, v0);
        vertex(vc, mat, x0, y, z0, r, g, b, a, packed, u0, v0);
        pose.popPose();
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

    /** Blend two RGB colours ({@code t} of the way from {@code a} to {@code b}); returns RGB only. */
    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int rr = (int) (ar + (br - ar) * t);
        int gg = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return (rr << 16) | (gg << 8) | bl;
    }

    /** Blend two ARGB colours, all four channels (for the must → ripe-grog transition). */
    private static int lerpColor(int a, int b, float t) {
        int aa = (a >>> 24), ba = (b >>> 24);
        int alpha = (int) (aa + (ba - aa) * t);
        return (alpha << 24) | mix(a, b, t);
    }

    private static float yRot(Direction facing) {
        return switch (facing) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F; // NORTH
        };
    }
}
