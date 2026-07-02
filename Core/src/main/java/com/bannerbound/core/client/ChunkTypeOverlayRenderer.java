package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.territory.ChunkResource;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws the {@code /bannerbound chunktype <radius>} debug overlay: a billboarded resource icon + name
 * floating over the centre of each non-empty chunk in {@link ChunkTypeOverlayState}, at the chunk's
 * surface height, until the TTL expires. Mirrors {@link SeedMarkerRenderer}'s icon-billboard approach.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class ChunkTypeOverlayRenderer {
    private static final float ICON_SCALE = 0.85f;
    private static final int FULLBRIGHT = 0x00F000F0;

    private ChunkTypeOverlayRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!ChunkTypeOverlayState.isActive(mc.level.getGameTime())) return;

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float yaw = camera.getYRot();
        float pitch = camera.getXRot();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        ItemRenderer itemRenderer = mc.getItemRenderer();
        Font font = mc.font;

        int cx0 = ChunkTypeOverlayState.centerX();
        int cz0 = ChunkTypeOverlayState.centerZ();
        int r = ChunkTypeOverlayState.radius();
        byte[] ord = ChunkTypeOverlayState.ordinals();
        int side = 2 * r + 1;
        ChunkResource[] vals = ChunkResource.values();

        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int idx = (dz + r) * side + (dx + r);
                if (idx < 0 || idx >= ord.length) continue;
                int o = ord[idx];
                if (o <= 0 || o >= vals.length) continue; // 0 = NONE → skip
                ChunkResource type = vals[o];

                int wx = (cx0 + dx) * 16 + 8;
                int wz = (cz0 + dz) * 16 + 8;
                int wy = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
                double px = wx + 0.5 - cam.x;
                double py = wy + 2.0 - cam.y;
                double pz = wz + 0.5 - cam.z;

                pose.pushPose();
                pose.translate(px, py, pz);
                pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yaw));
                pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(pitch));
                pose.scale(ICON_SCALE, ICON_SCALE, ICON_SCALE);
                itemRenderer.renderStatic(icon(type), ItemDisplayContext.GROUND, FULLBRIGHT,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                    pose, buffer, mc.level, 0);
                pose.popPose();

                drawLabel(pose, buffer, font, type.name(), px, py - 0.75, pz, yaw, pitch, color(type));
            }
        }
        buffer.endBatch();
    }

    /** Billboarded text label centred at the given camera-relative offset. */
    private static void drawLabel(PoseStack pose, MultiBufferSource buffer, Font font, String text,
                                  double dx, double dy, double dz, float yaw, float pitch, int color) {
        pose.pushPose();
        pose.translate(dx, dy, dz);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yaw));
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(pitch));
        pose.scale(-0.025f, -0.025f, 0.025f); // font renders +Y down + tiny world-space scale
        org.joml.Matrix4f m = pose.last().pose();
        float x = -font.width(text) / 2f;
        font.drawInBatch(text, x, 0f, color, false, m, buffer, Font.DisplayMode.SEE_THROUGH, 0, FULLBRIGHT);
        pose.popPose();
    }

    private static ItemStack icon(ChunkResource r) {
        return switch (r) {
            case CATTLE -> new ItemStack(Items.LEATHER);
            case HORSES -> new ItemStack(Items.SADDLE);
            case PIGS -> new ItemStack(Items.PORKCHOP);
            case CHICKENS -> new ItemStack(Items.EGG);
            case SHEEP -> new ItemStack(Items.WHITE_WOOL);
            case FISH -> new ItemStack(Items.COD);
            case COPPER -> new ItemStack(Items.COPPER_INGOT);
            case TIN -> new ItemStack(Items.IRON_NUGGET);   // placeholder until a tin item exists
            case MARBLE -> new ItemStack(Items.QUARTZ);
            case IRON -> new ItemStack(Items.RAW_IRON);
            case WHEAT -> new ItemStack(Items.WHEAT);
            case CARROT -> new ItemStack(Items.CARROT);
            case BEETROOT -> new ItemStack(Items.BEETROOT);
            case POTATO -> new ItemStack(Items.POTATO);
            case STONE -> new ItemStack(Items.COBBLESTONE);
            case CLAY -> new ItemStack(Items.CLAY_BALL);
            case SAND -> new ItemStack(Items.SAND);
            case COAL -> new ItemStack(Items.COAL);
            case LIMESTONE -> new ItemStack(Items.SMOOTH_STONE);
            case ANDESITE -> new ItemStack(Items.ANDESITE);
            case DIORITE -> new ItemStack(Items.DIORITE);
            case GRANITE -> new ItemStack(Items.GRANITE);
            case NONE -> ItemStack.EMPTY;
        };
    }

    private static int color(ChunkResource r) {
        return switch (r) {
            case CATTLE -> 0xFFA0522D;
            case HORSES -> 0xFFDEB887;
            case PIGS -> 0xFFF0A0A0;
            case CHICKENS -> 0xFFFFF0B0;
            case SHEEP -> 0xFFF0F0F0;
            case FISH -> 0xFF5FA8D3;
            case COPPER -> 0xFFE07B39;
            case TIN -> 0xFFBFC9CA;
            case MARBLE -> 0xFFFFFFFF;
            case IRON -> 0xFFB0B0B0;
            case WHEAT -> 0xFFE8C85A;
            case CARROT -> 0xFFFF8A30;
            case BEETROOT -> 0xFFA8324A;
            case POTATO -> 0xFFD7B56D;
            case STONE -> 0xFF8F8F8F;
            case CLAY -> 0xFF9AA7AD;
            case SAND -> 0xFFE0C56D;
            case COAL -> 0xFF2B2B2B;
            case LIMESTONE -> 0xFFE8E4D8;
            case ANDESITE -> 0xFF9C9C9C;
            case DIORITE -> 0xFFD8D8D8;
            case GRANITE -> 0xFFA9735B;
            case NONE -> 0xFF808080;
        };
    }
}
