package com.bannerbound.antiquity.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.MasonsBenchBlock;
import com.bannerbound.antiquity.block.WoodworkingTableBlock;
import com.bannerbound.antiquity.block.entity.MasonsBenchBlockEntity;
import com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity;
import com.bannerbound.antiquity.network.CarpentryActionPayload;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client event handlers for the in-world readouts of both two-cell crafting stations: the
 * Carpenter's Table and the Mason's Bench. Each readout has three parts: a budget numeral painted
 * flat on the master half's tabletop, yaw-snapped to front/left/right/back toward the camera
 * (carpentry shows a per-category "L## P## S##" logs/planks/sticks breakdown omitting empty pools;
 * masonry a single total of uncommitted stone); a row of queued outputs floating over the station's
 * SECONDARY cell (master.relative(FACING)) so the readout spreads across both halves instead of
 * towering above the master, each a spinning translucent 3D item chip with its TOTAL produced count;
 * and the picker's per-craft yield count. The picker item and browse arrows themselves are drawn by
 * each block-entity renderer at ghostPreviewY - this class only overlays glyphs and chips. The picker
 * count sits BESIDE the preview rather than tucked into its corner because tall picks (doors, fences)
 * can cover a corner count. Everything renders in the AFTER_TRANSLUCENT_BLOCKS level stage and must
 * end with an explicit bufferSource().endBatch() (PenMarkerRenderer's idiom) or the count glyphs never
 * flush. Chip drop-shadow counts are drawn MANUALLY (a dark copy offset by 1 px, then the white copy,
 * same render type + draw order so white lands cleanly on top) rather than via the font's built-in
 * dropShadow: at this tiny z-scale the built-in shadow's depth separation collapses and z-fights the
 * white glyphs. The layout helpers (queueCenters / rowCenters / boxAt) are the single source of truth
 * for where the chips sit, shared by rendering, the click routing, and the green crosshair; hover
 * scanning (findHoveredQueue / masonryFindHoveredQueue) picks the nearest ray-hit chip but yields to
 * any vanilla hit closer than it. Right-clicking a hovered carpentry chip cancels the vanilla use and
 * sends CarpentryActionPayload.REMOVE_QUEUE; the picker's add/cycle clicks reuse the shared
 * ghost-preview path (GhostRecipeClientEvents / GhostClickTargets), whose targets sit at different
 * positions so the two never collide (and onInteract bails if the ghost handler already canceled the
 * event). Readouts are suppressed for a station while its minigame scene owns the tabletop
 * (CarpentrySawState / MasonChiselState) so chips do not obscure the animation. Open: only carpentry
 * chip removal is wired in onInteract - masonry chips show the remove hover label and the server
 * handles MasonryActionPayload.REMOVE_QUEUE, but no client code sends it yet.
 *
 * <p>ghostNameOnRenderLevelStage additionally labels EVERY GhostRecipeWorkstation's floating
 * preview with the result item's name (amber while it is a ghost selection, white once the pile
 * exactly matches), so players can tell what a station is set to craft before committing. The
 * label sits above the preview at ghostPreviewY; the compact stations' transient "queue"/"remove"
 * hover labels were lifted to +0.42 so the persistent name below them never collides.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class StationReadoutEvents {

    private StationReadoutEvents() {}

    @SubscribeEvent
    static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isCanceled()) return;
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) return;
        StationReadoutEvents.QueueHit hit = StationReadoutEvents.findHoveredQueue(Minecraft.getInstance());
        if (hit == null) return;
        event.setCanceled(true);
        event.setSwingHand(true);
        PacketDistributor.sendToServer(
            new CarpentryActionPayload(hit.pos(), CarpentryActionPayload.REMOVE_QUEUE, hit.index()));
    }

    private static final int FULLBRIGHT = 0x00F000F0;
    private static final double QUEUE_Y = 1.08;
    private static final double SPACING = 0.34;
    private static final float CHIP = 0.24F;
    private static final float PICKER_CHIP = 0.34F; // must match the BER picker's render scale
    private static final float BUDGET_SCALE = 0.035F;
    private static final float HOVER_LABEL_SCALE = 0.0075F;
    private static final float NAME_LABEL_SCALE = 0.009F;
    private static final int LABEL_BG = 0x00000000;
    static final double CHIP_BOX = 0.28;
    static final double SCAN = 7.0;
    private static final int MAX_CHIPS = 7;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        List<WoodworkingTableBlockEntity> tables = nearbyTables(mc);
        if (tables.isEmpty()) return;

        Camera camera = event.getCamera();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        ItemRenderer ir = mc.getItemRenderer();
        float t = (float) mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        GhostClickTargets.Hover ghostHover = GhostClickTargets.findHovered(mc);
        QueueHit queueHover = findHoveredQueue(mc);

        for (WoodworkingTableBlockEntity be : tables) {
            if (CarpentrySawState.activeFor(be.getBlockPos())) {
                continue;
            }
            BlockPos p = be.getBlockPos();
            double cx = p.getX() + 0.5;
            double cz = p.getZ() + 0.5;

            if (!be.getLogs().isEmpty()) {
                drawTableBudget(pose, buffer, font, be, camera);
            }
            List<Vec3> qc = queueCenters(be, camera);
            List<WoodworkingTableBlockEntity.ListEntry> queue = be.getBuildList();
            for (int i = 0; i < qc.size(); i++) {
                WoodworkingTableBlockEntity.ListEntry e = queue.get(i);
                drawChip(pose, buffer, font, ir, be, camera, new ItemStack(e.output()),
                    e.units() * e.yieldPerUnit(), qc.get(i), t);
            }
            if (queueHover != null && queueHover.pos().equals(p) && queueHover.index() < qc.size()) {
                drawBillboardText(pose, buffer, font,
                    Component.translatable("bannerboundantiquity.carpentry.readout.remove").getString(),
                    qc.get(queueHover.index()).add(0.0, 0.22, 0.0), camera,
                    HOVER_LABEL_SCALE, 0xFFFFE2A8, LABEL_BG);
            }

            ItemStack ghost = be.getGhostResult();
            if (!ghost.isEmpty() && ghost.getCount() > 1) {
                drawPickerCount(pose, buffer, font, ghost.getCount(),
                    new Vec3(cx, p.getY() + be.ghostPreviewY(), cz), camera);
            }
            if (!ghost.isEmpty() && ghostHover != null && ghostHover.pos().equals(p)) {
                if (ghostHover.picked().target().action() == GhostActionPayload.FILL) {
                    drawBillboardText(pose, buffer, font,
                        Component.translatable("bannerboundantiquity.carpentry.readout.queue").getString(),
                        ghostHover.picked().target().center().add(0.0, 0.42, 0.0), camera,
                        HOVER_LABEL_SCALE, 0xFFFFFFFF, LABEL_BG);
                }
            }
        }
        buffer.endBatch();
    }

    static List<Vec3> queueCenters(WoodworkingTableBlockEntity be, Camera camera) {
        BlockPos sec = be.getBlockPos().relative(be.getBlockState().getValue(WoodworkingTableBlock.FACING));
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

    static List<WoodworkingTableBlockEntity> nearbyTables(Minecraft mc) {
        List<WoodworkingTableBlockEntity> out = new ArrayList<>();
        if (mc.player == null || mc.level == null) return out;
        Vec3 pp = mc.player.position();
        int pcx = mc.player.chunkPosition().x;
        int pcz = mc.player.chunkPosition().z;
        double r2 = SCAN * SCAN;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (BlockEntity be : mc.level.getChunk(pcx + dx, pcz + dz).getBlockEntities().values()) {
                    if (be instanceof WoodworkingTableBlockEntity t
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

    public record QueueHit(BlockPos pos, int index) {}

    public static QueueHit findHoveredQueue(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.screen != null || mc.player.isSpectator()) return null;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = mc.player.getEyePosition();
        Vec3 to = eye.add(mc.player.getViewVector(1.0F).normalize().scale(mc.player.blockInteractionRange()));
        QueueHit best = null;
        double bestDist = Double.MAX_VALUE;
        for (WoodworkingTableBlockEntity be : nearbyTables(mc)) {
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
        font.drawInBatch(text, x + 1.0F, y + 1.0F, 0xD01A1008, false,
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

    private static void drawTableBudget(PoseStack pose, MultiBufferSource buffer, Font font,
                                        WoodworkingTableBlockEntity be, Camera camera) {
        int[] net = be.remainingByCategory(); // order: [LOG, PLANK, STICK]
        StringBuilder sb = new StringBuilder();
        String[] labels = {"L", "P", "S"};
        for (int i = 0; i < net.length; i++) {
            if (net[i] <= 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(labels[i]).append(net[i]);
        }
        if (sb.length() == 0) return;
        String text = sb.toString();
        BlockPos p = be.getBlockPos();
        Vec3 cam = camera.getPosition();
        double cx = p.getX() + 0.5;
        double cz = p.getZ() + 0.5;
        float yaw = WoodworkingTableRenderer.snappedYawTowardCamera(p, cam);
        double ox = WoodworkingTableRenderer.snappedOffsetX(yaw, 0.22);
        double oz = WoodworkingTableRenderer.snappedOffsetZ(yaw, 0.22);
        pose.pushPose();
        pose.translate(cx + ox - cam.x, p.getY() + WoodworkingTableRenderer.TOP_Y + 0.012 - cam.y,
            cz + oz - cam.z);
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        float x = -font.width(text) / 2.0F;
        float y = -font.lineHeight / 2.0F;
        pose.pushPose();
        pose.translate(0.0, 0.0, 0.018);
        pose.scale(BUDGET_SCALE, -BUDGET_SCALE, BUDGET_SCALE);
        Matrix4f mat = pose.last().pose();
        font.drawInBatch(text, x + 1.0F, y + 1.0F, 0xE03A260E, false,
            mat, buffer, Font.DisplayMode.NORMAL, 0, FULLBRIGHT);
        pose.popPose();
        pose.pushPose();
        pose.translate(0.0, 0.0, 0.002);
        pose.scale(BUDGET_SCALE, -BUDGET_SCALE, BUDGET_SCALE);
        mat = pose.last().pose();
        font.drawInBatch(text, x, y, 0xFFFFE2A8, false,
            mat, buffer, Font.DisplayMode.NORMAL, 0, FULLBRIGHT);
        pose.popPose();
        pose.popPose();
    }

    private static void drawCountAtCorner(PoseStack pose, MultiBufferSource buffer, Font font, int count,
                                          Vec3 worldCenter, Camera camera, float chip) {
        if (count <= 1) return;
        Vec3 cam = camera.getPosition();
        String s = count > 999 ? String.format("%.1fk", count / 1000.0) : Integer.toString(count);
        float fs = chip / 16.0F; // 1 font px = chip/16 world -> digits match inventory proportion
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

    @SubscribeEvent
    public static void ghostNameOnRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Camera camera = event.getCamera();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        Vec3 pp = mc.player.position();
        int pcx = mc.player.chunkPosition().x;
        int pcz = mc.player.chunkPosition().z;
        double r2 = SCAN * SCAN;
        boolean drew = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (BlockEntity be : mc.level.getChunk(pcx + dx, pcz + dz).getBlockEntities().values()) {
                    if (!(be instanceof com.bannerbound.antiquity.block.entity.GhostRecipeWorkstation ws)) continue;
                    BlockPos p = be.getBlockPos();
                    if (p.getCenter().distanceToSqr(pp) > r2) continue;
                    if (CarpentrySawState.activeFor(p) || MasonChiselState.activeFor(p)) continue;
                    if (be instanceof com.bannerbound.antiquity.block.entity.PotterySlabBlockEntity pot
                            && !pot.getInProgress().isEmpty()) continue;
                    ItemStack ghost = ws.getGhostResult();
                    ItemStack shown = !ghost.isEmpty() ? ghost : ws.getResult();
                    if (shown.isEmpty()) continue;
                    boolean compact = be instanceof WoodworkingTableBlockEntity
                        || be instanceof MasonsBenchBlockEntity;
                    Vec3 center = new Vec3(p.getX() + 0.5,
                        p.getY() + ws.ghostPreviewY() + (compact ? 0.26 : 0.34), p.getZ() + 0.5);
                    drawBillboardText(pose, buffer, font, shown.getHoverName().getString(), center, camera,
                        NAME_LABEL_SCALE, ghost.isEmpty() ? 0xFFFFFFFF : 0xFFFFD27F, LABEL_BG);
                    drew = true;
                }
            }
        }
        if (drew) buffer.endBatch();
    }

    @SubscribeEvent
    public static void masonryOnRenderLevelStage(RenderLevelStageEvent event) {
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
        QueueHit queueHover = masonryFindHoveredQueue(mc);

        for (MasonsBenchBlockEntity be : benches) {
            if (MasonChiselState.activeFor(be.getBlockPos())) {
                continue;
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
                masonryDrawBillboardText(pose, buffer, font,
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
                    masonryDrawBillboardText(pose, buffer, font,
                        Component.translatable("bannerboundantiquity.masonry.readout.queue").getString(),
                        ghostHover.picked().target().center().add(0.0, 0.42, 0.0), camera,
                        HOVER_LABEL_SCALE, 0xFFFFFFFF, LABEL_BG);
                }
            }
        }
        buffer.endBatch();
    }

    static List<Vec3> queueCenters(MasonsBenchBlockEntity be, Camera camera) {
        BlockPos sec = be.getBlockPos().relative(be.getBlockState().getValue(MasonsBenchBlock.FACING));
        return rowCenters(Math.min(be.getBuildList().size(), MAX_CHIPS),
            sec.getX() + 0.5, sec.getY() + QUEUE_Y, sec.getZ() + 0.5, horizontalRight(camera));
    }

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

    public static QueueHit masonryFindHoveredQueue(Minecraft mc) {
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

    private static void masonryDrawBillboardText(PoseStack pose, MultiBufferSource buffer, Font font, String text,
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
}
