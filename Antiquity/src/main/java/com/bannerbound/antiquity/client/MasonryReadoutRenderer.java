package com.bannerbound.antiquity.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.MasonsBenchBlock;
import com.bannerbound.antiquity.block.entity.MasonsBenchBlockEntity;
import com.bannerbound.antiquity.network.GhostActionPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import org.joml.Matrix4f;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The Mason's Bench's in-world readout — the stone analogue of {@code CarpentryReadoutRenderer}: a
 * flat bench-top budget numeral (total uncommitted stone), floating queued outputs with their
 * produced count, and the picker's per-craft yield count. The picker item + browse arrows are drawn
 * by the block-entity renderer at {@code ghostPreviewY}. Drawn in a level-stage pass with an
 * explicit buffer flush so the count glyphs actually flush.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class MasonryReadoutRenderer {
    private static final int FULLBRIGHT = 0x00F000F0;
    private static final double QUEUE_Y = 1.08;
    private static final double SPACING = 0.34;
    private static final float CHIP = 0.24F;
    private static final float PICKER_CHIP = 0.34F;
    private static final float BUDGET_SCALE = 0.035F;
    private static final float HOVER_LABEL_SCALE = 0.0075F;
    private static final int LABEL_BG = 0x00000000;
    static final double CHIP_BOX = 0.28;
    static final double SCAN = 7.0;
    private static final int MAX_CHIPS = 7;

    private MasonryReadoutRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        List<MasonsBenchBlockEntity> benches = nearbyBenches(mc);
        if (benches.isEmpty()) return;

        Camera camera = event.getCamera();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        ItemRenderer ir = mc.getItemRenderer();
        float t = (float) mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        GhostClickTargets.Hover ghostHover = GhostClickTargets.findHovered(mc);
        QueueHit queueHover = findHoveredQueue(mc);

        for (MasonsBenchBlockEntity be : benches) {
            if (MasonChiselState.activeFor(be.getBlockPos())) {
                continue; // the chisel scene owns the bench top
            }
            BlockPos p = be.getBlockPos();
            double cx = p.getX() + 0.5;
            double cz = p.getZ() + 0.5;

            if (!be.getStones().isEmpty()) {
                drawBenchBudget(pose, buffer, font, be, camera);
            }
            List<Vec3> qc = queueCenters(be, camera);
            List<MasonsBenchBlockEntity.ListEntry> queue = be.getBuildList();
            for (int i = 0; i < qc.size(); i++) {
                MasonsBenchBlockEntity.ListEntry e = queue.get(i);
                drawChip(pose, buffer, font, ir, be, camera, new ItemStack(e.output()),
                    e.units() * e.yieldPerUnit(), qc.get(i), t);
            }
            if (queueHover != null && queueHover.pos().equals(p) && queueHover.index() < qc.size()) {
                drawBillboardText(pose, buffer, font,
                    Component.translatable("bannerboundantiquity.masonry.readout.remove").getString(),
                    qc.get(queueHover.index()).add(0.0, 0.22, 0.0), camera,
                    HOVER_LABEL_SCALE, 0xFFE8E0C8, LABEL_BG);
            }

            ItemStack ghost = be.getGhostResult();
            if (!ghost.isEmpty() && ghost.getCount() > 1) {
                drawPickerCount(pose, buffer, font, ghost.getCount(),
                    new Vec3(cx, p.getY() + be.ghostPreviewY(), cz), camera);
            }
            if (!ghost.isEmpty() && ghostHover != null && ghostHover.pos().equals(p)) {
                if (ghostHover.picked().target().action() == GhostActionPayload.FILL) {
                    drawBillboardText(pose, buffer, font,
                        Component.translatable("bannerboundantiquity.masonry.readout.queue").getString(),
                        ghostHover.picked().target().center().add(0.0, 0.28, 0.0), camera,
                        HOVER_LABEL_SCALE, 0xFFFFFFFF, LABEL_BG);
                }
            }
        }
        buffer.endBatch();
    }

    /** World centers of the queue chips for {@code be} — over the bench's SECONDARY cell. */
    static List<Vec3> queueCenters(MasonsBenchBlockEntity be, Camera camera) {
        BlockPos sec = be.getBlockPos().relative(be.getBlockState().getValue(MasonsBenchBlock.FACING));
        return rowCenters(Math.min(be.getBuildList().size(), MAX_CHIPS),
            sec.getX() + 0.5, sec.getY() + QUEUE_Y, sec.getZ() + 0.5, horizontalRight(camera));
    }

    private static List<Vec3> rowCenters(int n, double cx, double y, double cz, Vec3 right) {
        List<Vec3> out = new ArrayList<>(Math.max(0, n));
        n = Math.min(n, MAX_CHIPS);
        double start = -(n - 1) * SPACING / 2.0;
        for (int i = 0; i < n; i++) {
            double off = start + i * SPACING;
            out.add(new Vec3(cx + right.x * off, y, cz + right.z * off));
        }
        return out;
    }

    /** Mason's benches within {@link #SCAN} blocks of the player (chunk-scoped, cheap). */
    static List<MasonsBenchBlockEntity> nearbyBenches(Minecraft mc) {
        List<MasonsBenchBlockEntity> out = new ArrayList<>();
        if (mc.player == null || mc.level == null) return out;
        Vec3 pp = mc.player.position();
        int pcx = mc.player.chunkPosition().x;
        int pcz = mc.player.chunkPosition().z;
        double r2 = SCAN * SCAN;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (BlockEntity be : mc.level.getChunk(pcx + dx, pcz + dz).getBlockEntities().values()) {
                    if (be instanceof MasonsBenchBlockEntity t
                            && t.getBlockPos().getCenter().distanceToSqr(pp) <= r2) {
                        out.add(t);
                    }
                }
            }
        }
        return out;
    }

    private static Vec3 horizontalRight(Camera camera) {
        Vec3 left = new Vec3(camera.getLeftVector().x(), 0.0, camera.getLeftVector().z());
        left = left.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : left.normalize();
        return left.scale(-1.0);
    }

    static AABB boxAt(Vec3 center) {
        return AABB.ofSize(center, CHIP_BOX, CHIP_BOX, CHIP_BOX);
    }

    /** A ray-picked queue chip: the bench + the queue slot the crosshair is on. */
    public record QueueHit(BlockPos pos, int index) {}

    /** The queue chip the player is aiming at (nearest, and only when it beats the vanilla block). */
    public static QueueHit findHoveredQueue(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.screen != null || mc.player.isSpectator()) return null;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = mc.player.getEyePosition();
        Vec3 to = eye.add(mc.player.getViewVector(1.0F).normalize().scale(mc.player.blockInteractionRange()));
        QueueHit best = null;
        double bestDist = Double.MAX_VALUE;
        for (MasonsBenchBlockEntity be : nearbyBenches(mc)) {
            List<Vec3> centers = queueCenters(be, camera);
            for (int i = 0; i < centers.size(); i++) {
                Optional<Vec3> hit = boxAt(centers.get(i)).clip(eye, to);
                if (hit.isPresent()) {
                    double d = hit.get().distanceToSqr(eye);
                    if (d < bestDist) {
                        bestDist = d;
                        best = new QueueHit(be.getBlockPos(), i);
                    }
                }
            }
        }
        if (best == null) return null;
        HitResult vanilla = mc.hitResult;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS
                && vanilla.getLocation().distanceToSqr(eye) < bestDist) {
            return null;
        }
        return best;
    }

    private static void drawChip(PoseStack pose, MultiBufferSource buffer, Font font, ItemRenderer ir,
                                 BlockEntity be, Camera camera, ItemStack stack, int count, Vec3 worldCenter,
                                 float t) {
        Vec3 cam = camera.getPosition();
        pose.pushPose();
        pose.translate(worldCenter.x - cam.x, worldCenter.y - cam.y, worldCenter.z - cam.z);
        pose.mulPose(Axis.YP.rotationDegrees(t * 3.0F));
        pose.scale(CHIP, CHIP, CHIP);
        ir.renderStatic(stack, ItemDisplayContext.NONE, FULLBRIGHT, OverlayTexture.NO_OVERLAY,
            pose, buffer, be.getLevel(), 0);
        pose.popPose();
        drawCountAtCorner(pose, buffer, font, count, worldCenter, camera, CHIP);
    }

    private static void drawBillboardText(PoseStack pose, MultiBufferSource buffer, Font font, String text,
                                          Vec3 worldCenter, Camera camera, float scale,
                                          int color, int backgroundColor) {
        if (text == null || text.isEmpty()) return;
        Vec3 cam = camera.getPosition();
        pose.pushPose();
        pose.translate(worldCenter.x - cam.x, worldCenter.y - cam.y, worldCenter.z - cam.z);
        pose.mulPose(camera.rotation());
        float x = -font.width(text) / 2.0F;
        float y = -font.lineHeight / 2.0F;
        pose.pushPose();
        pose.translate(0.0, 0.0, -0.065);
        pose.scale(scale, -scale, scale);
        Matrix4f mat = pose.last().pose();
        font.drawInBatch(text, x + 1.0F, y + 1.0F, 0xD0140F0A, false,
            mat, buffer, Font.DisplayMode.NORMAL, 0, FULLBRIGHT);
        pose.popPose();
        pose.pushPose();
        pose.translate(0.0, 0.0, -0.035);
        pose.scale(scale, -scale, scale);
        mat = pose.last().pose();
        font.drawInBatch(text, x, y, color, false,
            mat, buffer, Font.DisplayMode.NORMAL, backgroundColor, FULLBRIGHT);
        pose.popPose();
        pose.popPose();
    }

    /** Total uncommitted stone painted flat onto the master half, snapped to front/left/right/back. */
    private static void drawBenchBudget(PoseStack pose, MultiBufferSource buffer, Font font,
                                        MasonsBenchBlockEntity be, Camera camera) {
        int remaining = be.remainingTotal();
        if (remaining <= 0) return;
        String text = Integer.toString(remaining);
        BlockPos p = be.getBlockPos();
        Vec3 cam = camera.getPosition();
        double cx = p.getX() + 0.5;
        double cz = p.getZ() + 0.5;
        float yaw = MasonsBenchRenderer.snappedYawTowardCamera(p, cam);
        double ox = MasonsBenchRenderer.snappedOffsetX(yaw, 0.22);
        double oz = MasonsBenchRenderer.snappedOffsetZ(yaw, 0.22);
        pose.pushPose();
        pose.translate(cx + ox - cam.x, p.getY() + MasonsBenchRenderer.TOP_Y + 0.012 - cam.y,
            cz + oz - cam.z);
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        float x = -font.width(text) / 2.0F;
        float y = -font.lineHeight / 2.0F;
        pose.pushPose();
        pose.translate(0.0, 0.0, 0.018);
        pose.scale(BUDGET_SCALE, -BUDGET_SCALE, BUDGET_SCALE);
        Matrix4f mat = pose.last().pose();
        font.drawInBatch(text, x + 1.0F, y + 1.0F, 0xE02A2620, false,
            mat, buffer, Font.DisplayMode.NORMAL, 0, FULLBRIGHT);
        pose.popPose();
        pose.pushPose();
        pose.translate(0.0, 0.0, 0.002);
        pose.scale(BUDGET_SCALE, -BUDGET_SCALE, BUDGET_SCALE);
        mat = pose.last().pose();
        font.drawInBatch(text, x, y, 0xFFE8E0C8, false,
            mat, buffer, Font.DisplayMode.NORMAL, 0, FULLBRIGHT);
        pose.popPose();
        pose.popPose();
    }

    private static void drawCountAtCorner(PoseStack pose, MultiBufferSource buffer, Font font, int count,
                                          Vec3 worldCenter, Camera camera, float chip) {
        if (count <= 1) return;
        Vec3 cam = camera.getPosition();
        String s = count > 999 ? String.format("%.1fk", count / 1000.0) : Integer.toString(count);
        float fs = chip / 16.0F;
        int x = -font.width(s);
        int y = -font.lineHeight;
        pose.pushPose();
        pose.translate(worldCenter.x - cam.x, worldCenter.y - cam.y, worldCenter.z - cam.z);
        pose.mulPose(camera.rotation());
        pose.translate(chip * 0.55, -chip * 0.52, -0.06);
        pose.scale(fs, -fs, fs);
        Matrix4f mat = pose.last().pose();
        font.drawInBatch(s, x, y, 0xFFFFFFFF, false, mat, buffer, Font.DisplayMode.NORMAL, 0, FULLBRIGHT);
        pose.popPose();
    }

    private static void drawPickerCount(PoseStack pose, MultiBufferSource buffer, Font font, int count,
                                        Vec3 worldCenter, Camera camera) {
        if (count <= 1) return;
        Vec3 cam = camera.getPosition();
        String s = count > 999 ? String.format("%.1fk", count / 1000.0) : Integer.toString(count);
        float fs = PICKER_CHIP / 14.0F;
        int x = -font.width(s) / 2;
        int y = -font.lineHeight / 2;
        pose.pushPose();
        pose.translate(worldCenter.x - cam.x, worldCenter.y - cam.y, worldCenter.z - cam.z);
        pose.mulPose(camera.rotation());
        pose.translate(PICKER_CHIP * 1.05, PICKER_CHIP * 0.16, -0.02);
        pose.scale(fs, -fs, fs);
        Matrix4f mat = pose.last().pose();
        font.drawInBatch(s, x, y, 0xFFFFFFFF, false, mat, buffer, Font.DisplayMode.NORMAL, 0, FULLBRIGHT);
        pose.popPose();
    }
}
