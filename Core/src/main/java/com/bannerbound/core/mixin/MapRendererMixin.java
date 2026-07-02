package com.bannerbound.core.mixin;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bannerbound.core.client.ClientClaimState;
import com.bannerbound.core.api.settlement.SettlementColor;
import com.bannerbound.core.network.ClaimEntry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.MapRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.core.Holder;

@Mixin(MapRenderer.class)
@ApiStatus.Internal
public class MapRendererMixin {
    private static final Logger BANNERBOUND$LOG = LogUtils.getLogger();

    // mapId -> [centerX, centerZ] inferred once when the PLAYER marker is on this map.
    // Map centers are immutable after crafting, so once inferred we keep it forever.
    private static final Map<Integer, int[]> BANNERBOUND$CENTER_CACHE = new HashMap<>();

    private static final float BANNERBOUND$FILL_Z = -0.030f;
    private static final float BANNERBOUND$EDGE_Z = -0.035f;
    private static final float BANNERBOUND$LABEL_Z = -0.040f;
    private static final int BANNERBOUND$FILL_ALPHA = 0x40;
    private static final int BANNERBOUND$EDGE_ALPHA = 0xD0;
    private static final float BANNERBOUND$EDGE_THICKNESS = 1.0f;

    @Inject(method = "render", at = @At("TAIL"))
    private void bannerbound$renderClaimOverlay(
            PoseStack pose,
            MultiBufferSource buffer,
            MapId mapId,
            MapItemSavedData data,
            boolean inFrame,
            int packedLight,
            CallbackInfo ci) {
        Map<Long, ClaimEntry> claims = ClientClaimState.all();
        if (claims.isEmpty()) {
            return;
        }

        int blocksPerPixel = 1 << data.scale;
        net.minecraft.client.player.LocalPlayer mcPlayer = net.minecraft.client.Minecraft.getInstance().player;

        // data.centerX / centerZ are NOT synced to the client — they stay at the
        // constructor default (0). The server places the PLAYER decoration in marker
        // units (1 unit = 0.5 px = 0.5 * bpp blocks) relative to the real center, so
        // we invert that formula against the player's known world position. Cached per
        // mapId since centers are immutable and to avoid per-frame jitter from stale
        // decoration updates.
        int[] cached = BANNERBOUND$CENTER_CACHE.get(mapId.id());
        int centerX;
        int centerZ;
        if (cached != null) {
            centerX = cached[0];
            centerZ = cached[1];
        } else if (mcPlayer != null) {
            Integer inferredX = null;
            Integer inferredZ = null;
            for (MapDecoration dec : data.getDecorations()) {
                if (bannerbound$isPlayerMarker(dec.type())) {
                    inferredX = (int) Math.round(mcPlayer.getX() - dec.x() * (double) blocksPerPixel / 2.0);
                    inferredZ = (int) Math.round(mcPlayer.getZ() - dec.y() * (double) blocksPerPixel / 2.0);
                    break;
                }
            }
            if (inferredX == null) {
                return;
            }
            BANNERBOUND$CENTER_CACHE.put(mapId.id(), new int[]{inferredX, inferredZ});
            BANNERBOUND$LOG.info("[CivCore] cached map center: mapId={}, center=({},{}), scale={}",
                mapId.id(), inferredX, inferredZ, (int) data.scale);
            centerX = inferredX;
            centerZ = inferredZ;
        } else {
            return;
        }

        float size = 16.0f / blocksPerPixel;
        VertexConsumer consumer = buffer.getBuffer(RenderType.gui());
        Matrix4f matrix = pose.last().pose();

        for (Map.Entry<Long, ClaimEntry> entry : claims.entrySet()) {
            ClaimEntry claim = entry.getValue();
            SettlementColor color = SettlementColor.byIndex(claim.colorIndex());
            String name = claim.settlementName();

            ChunkPos cp = new ChunkPos(entry.getKey());
            int chunkBlockX = cp.x * 16;
            int chunkBlockZ = cp.z * 16;

            float px = (chunkBlockX - centerX) / (float) blocksPerPixel + 64.0f;
            float pz = (chunkBlockZ - centerZ) / (float) blocksPerPixel + 64.0f;

            if (px + size <= 0 || px >= 128 || pz + size <= 0 || pz >= 128) {
                continue;
            }

            int rgb = color.rgb() & 0xFFFFFF;
            int fillArgb = (BANNERBOUND$FILL_ALPHA << 24) | rgb;
            int edgeArgb = (BANNERBOUND$EDGE_ALPHA << 24) | rgb;

            bannerbound$drawClippedQuad(consumer, matrix, px, pz, px + size, pz + size, fillArgb, BANNERBOUND$FILL_Z);

            float t = BANNERBOUND$EDGE_THICKNESS;
            if (!bannerbound$isSameSettlement(cp.x, cp.z - 1, name)) {
                bannerbound$drawClippedQuad(consumer, matrix, px, pz, px + size, pz + t, edgeArgb, BANNERBOUND$EDGE_Z);
            }
            if (!bannerbound$isSameSettlement(cp.x, cp.z + 1, name)) {
                bannerbound$drawClippedQuad(consumer, matrix, px, pz + size - t, px + size, pz + size, edgeArgb, BANNERBOUND$EDGE_Z);
            }
            if (!bannerbound$isSameSettlement(cp.x - 1, cp.z, name)) {
                bannerbound$drawClippedQuad(consumer, matrix, px, pz, px + t, pz + size, edgeArgb, BANNERBOUND$EDGE_Z);
            }
            if (!bannerbound$isSameSettlement(cp.x + 1, cp.z, name)) {
                bannerbound$drawClippedQuad(consumer, matrix, px + size - t, pz, px + size, pz + size, edgeArgb, BANNERBOUND$EDGE_Z);
            }
        }

        bannerbound$renderSettlementLabels(pose, buffer, claims, centerX, centerZ, blocksPerPixel, packedLight);
    }

    private static void bannerbound$renderSettlementLabels(PoseStack pose, MultiBufferSource buffer,
                                                       Map<Long, ClaimEntry> claims,
                                                       int centerX, int centerZ, int blocksPerPixel,
                                                       int packedLight) {
        Map<String, List<ChunkPos>> bySettlement = new HashMap<>();
        Map<String, Integer> settlementColors = new HashMap<>();
        for (Map.Entry<Long, ClaimEntry> entry : claims.entrySet()) {
            ClaimEntry claim = entry.getValue();
            String name = claim.settlementName();
            bySettlement.computeIfAbsent(name, k -> new ArrayList<>()).add(new ChunkPos(entry.getKey()));
            settlementColors.put(name, claim.colorIndex());
        }

        Font font = Minecraft.getInstance().font;
        float chunkPixelSize = 16.0f / blocksPerPixel;

        for (Map.Entry<String, List<ChunkPos>> e : bySettlement.entrySet()) {
            String name = e.getKey();
            List<ChunkPos> chunks = e.getValue();
            SettlementColor color = SettlementColor.byIndex(settlementColors.get(name));

            double sumX = 0;
            double sumZ = 0;
            float minPx = Float.POSITIVE_INFINITY;
            float maxPx = Float.NEGATIVE_INFINITY;
            float minPz = Float.POSITIVE_INFINITY;
            float maxPz = Float.NEGATIVE_INFINITY;
            for (ChunkPos cp : chunks) {
                float px = ((cp.x * 16f) - centerX) / blocksPerPixel + 64f;
                float pz = ((cp.z * 16f) - centerZ) / blocksPerPixel + 64f;
                minPx = Math.min(minPx, px);
                maxPx = Math.max(maxPx, px + chunkPixelSize);
                minPz = Math.min(minPz, pz);
                maxPz = Math.max(maxPz, pz + chunkPixelSize);
                sumX += cp.x * 16 + 8;
                sumZ += cp.z * 16 + 8;
            }
            float labelX = (float) ((sumX / chunks.size() - centerX) / blocksPerPixel + 64.0);
            float labelZ = (float) ((sumZ / chunks.size() - centerZ) / blocksPerPixel + 64.0);

            if (labelX < 0 || labelX > 128 || labelZ < 0 || labelZ > 128) {
                continue;
            }

            Component textComponent = Component.literal(name);
            int textWidth = font.width(textComponent);
            float bboxW = maxPx - minPx;
            float bboxH = maxPz - minPz;
            float scaleFromWidth = bboxW * 0.7f / Math.max(1, textWidth);
            float scaleFromHeight = bboxH * 0.5f / (float) font.lineHeight;
            float textScale = Math.max(0.3f, Math.min(1.0f, Math.min(scaleFromWidth, scaleFromHeight)));

            pose.pushPose();
            pose.translate(labelX, labelZ, BANNERBOUND$LABEL_Z);
            pose.scale(textScale, textScale, 1.0f);

            float tx = -textWidth / 2.0f;
            float ty = -font.lineHeight / 2.0f;
            Matrix4f outlineMatrix = pose.last().pose();
            int black = 0xFF000000;
            int textColor = 0xFF000000 | bannerbound$lightShade(color.rgb());

            // 4-direction outline at the outline Z.
            font.drawInBatch(textComponent, tx - 1, ty, black, false, outlineMatrix, buffer, Font.DisplayMode.NORMAL, 0, packedLight);
            font.drawInBatch(textComponent, tx + 1, ty, black, false, outlineMatrix, buffer, Font.DisplayMode.NORMAL, 0, packedLight);
            font.drawInBatch(textComponent, tx, ty - 1, black, false, outlineMatrix, buffer, Font.DisplayMode.NORMAL, 0, packedLight);
            font.drawInBatch(textComponent, tx, ty + 1, black, false, outlineMatrix, buffer, Font.DisplayMode.NORMAL, 0, packedLight);

            // Main text slightly forward so LEQUAL test definitively passes over the outline.
            pose.pushPose();
            pose.translate(0.0f, 0.0f, -0.005f);
            font.drawInBatch(textComponent, tx, ty, textColor, false, pose.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
            pose.popPose();

            pose.popPose();
        }
    }

    private static int bannerbound$lightShade(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = (r + 255) / 2;
        g = (g + 255) / 2;
        b = (b + 255) / 2;
        return (r << 16) | (g << 8) | b;
    }

    @SuppressWarnings("deprecation")
    private static boolean bannerbound$isPlayerMarker(Holder<MapDecorationType> type) {
        return type.is(MapDecorationTypes.PLAYER);
    }

    private static boolean bannerbound$isSameSettlement(int chunkX, int chunkZ, String settlementName) {
        ClaimEntry e = ClientClaimState.getEntry(new ChunkPos(chunkX, chunkZ).toLong());
        return e != null && e.settlementName().equals(settlementName);
    }

    private static void bannerbound$drawClippedQuad(VertexConsumer consumer, Matrix4f matrix,
                                                float x0, float z0, float x1, float z1, int argb, float zDepth) {
        float cx0 = Math.max(0.0f, x0);
        float cz0 = Math.max(0.0f, z0);
        float cx1 = Math.min(128.0f, x1);
        float cz1 = Math.min(128.0f, z1);
        if (cx0 >= cx1 || cz0 >= cz1) {
            return;
        }
        consumer.addVertex(matrix, cx0, cz1, zDepth).setColor(argb);
        consumer.addVertex(matrix, cx1, cz1, zDepth).setColor(argb);
        consumer.addVertex(matrix, cx1, cz0, zDepth).setColor(argb);
        consumer.addVertex(matrix, cx0, cz0, zDepth).setColor(argb);
    }
}
