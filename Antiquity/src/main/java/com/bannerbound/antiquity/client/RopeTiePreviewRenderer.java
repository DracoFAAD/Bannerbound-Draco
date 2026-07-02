package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.RopeAnchor;
import com.bannerbound.antiquity.RopeTies;
import com.bannerbound.antiquity.RopeTieState;
import com.bannerbound.antiquity.block.RopeFenceGateBlock;
import com.bannerbound.antiquity.block.RopeFencePostBlock;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * While the local player is mid-tie (see {@link RopeTieState}), draws a live rope paying out from the
 * selected tie point (post centre or gate upright) as a real slack rope (deep droop when the loose end
 * is near, taut near max reach). The loose end either:
 * <ul>
 *   <li><b>snaps to a post/gate you're aiming at</b> (if it's a valid, in-reach second tie point) — so
 *       you see exactly what the finished rope will look like before committing; or</li>
 *   <li>trails ~{@link #LEAD_DISTANCE} blocks ahead of your aim, clamped to the rope's max reach
 *       ({@link RopeTies#MAX_ROPE_HORIZONTAL}/{@link RopeTies#MAX_ROPE_VERTICAL}) so it visibly stops
 *       paying out at the limit.</li>
 * </ul>
 * The post's coil model is shown separately (server-authoritative blockstate). The committed rope's
 * "zip taut" settle is handled in {@link RopeRenderer}.
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class RopeTiePreviewRenderer {
    /** How far ahead of the eyes the loose end trails while you aim at nothing tie-able (blocks). */
    private static final double LEAD_DISTANCE = 1.5;

    private RopeTiePreviewRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        RopeAnchor anchor = RopeTieState.get();
        if (anchor == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        Level level = player.level();
        Vec3 tie = RopeAnchor.worldTie(level, anchor);
        // Drop the selection only if it's genuinely stale (tie host gone / wrong dimension).
        if (tie == null || !RopeTieState.isAt(anchor, level.dimension())) {
            RopeTieState.clear();
            return;
        }
        if (!holdingRope(player)) {
            return; // hide while not holding rope, but keep the selection
        }

        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        // Prefer the post/gate you're aiming at (preview where it'll attach); else trail the loose end.
        RopeAnchor target = aimedTargetAnchor(mc, level, anchor);
        Vec3 snap = target != null ? RopeAnchor.worldTie(level, target) : null;
        Vec3 end = snap != null ? snap
            : clampToReach(tie, player.getEyePosition(partial).add(player.getViewVector(partial).scale(LEAD_DISTANCE)));

        float dx = (float) (end.x - tie.x), dy = (float) (end.y - tie.y), dz = (float) (end.z - tie.z);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(tie.x - cam.x, tie.y - cam.y, tie.z - cam.z);
        int packedLight = LevelRenderer.getLightColor(level, BlockPos.containing(tie.x, tie.y, tie.z));
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RopeRenderer.drawRibbon(pose, buffers, packedLight, dx, dy, dz, RopeRenderer.slackSag(dx, dy, dz));
        buffers.endBatch(RenderType.leash());
        pose.popPose();
    }

    /** The post/gate tie point the player is aiming at, if it's a valid in-reach second anchor; null
     *  otherwise (then the loose end just trails the aim). */
    private static RopeAnchor aimedTargetAnchor(Minecraft mc, Level level, RopeAnchor from) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        if (pos.equals(from.pos())) {
            return null; // can't tie a post to itself
        }
        if (!(level.getBlockState(pos).getBlock() instanceof RopeFencePostBlock
                || level.getBlockState(pos).getBlock() instanceof RopeFenceGateBlock)) {
            return null;
        }
        RopeAnchor target = new RopeAnchor(pos, RopeTies.slotForHit(level, pos, hit.getLocation()));
        Vec3 to = RopeAnchor.worldTie(level, target);
        Vec3 fromTie = RopeAnchor.worldTie(level, from);
        if (to == null || fromTie == null) {
            return null;
        }
        double horiz = Math.sqrt((to.x - fromTie.x) * (to.x - fromTie.x) + (to.z - fromTie.z) * (to.z - fromTie.z));
        if (horiz > RopeTies.MAX_ROPE_HORIZONTAL || Math.abs(to.y - fromTie.y) > RopeTies.MAX_ROPE_VERTICAL) {
            return null; // out of reach → fall back to the trailing end so you SEE it won't reach
        }
        return target;
    }

    /** The post/gate currently shown a PREVIEW coil (client-only blockstate), so we can revert it. */
    private static RopeAnchor previewRopedTarget;

    /**
     * Each client tick, show the roped-coil model on the post you're aiming at while mid-tie (and clear
     * it the moment you look away / finish), so you preview not just the rope line but the coil too.
     * Purely a client blockstate flip; reverted to the post's real state via {@link RopeTies#refreshRoped}.
     */
    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        RopeAnchor desired = null;
        if (player != null && level != null) {
            RopeAnchor from = RopeTieState.get();
            if (from != null && holdingRope(player) && RopeTieState.isAt(from, level.dimension())) {
                desired = aimedTargetAnchor(mc, level, from);
            }
        }
        if (java.util.Objects.equals(desired, previewRopedTarget)) {
            return;
        }
        if (previewRopedTarget != null && level != null) {
            RopeTies.refreshRoped(level, previewRopedTarget); // restore the post's real (server) state
        }
        previewRopedTarget = desired;
        if (desired != null && level != null) {
            RopeTies.setRopedModel(level, desired, true); // preview the coil
        }
    }

    /** Reel {@code target} back toward {@code tie} so it never exceeds the rope's horizontal/vertical reach. */
    private static Vec3 clampToReach(Vec3 tie, Vec3 target) {
        double dx = target.x - tie.x, dy = target.y - tie.y, dz = target.z - tie.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz > RopeTies.MAX_ROPE_HORIZONTAL && horiz > 1.0e-6) {
            double s = RopeTies.MAX_ROPE_HORIZONTAL / horiz;
            dx *= s;
            dz *= s;
        }
        dy = Mth.clamp(dy, -RopeTies.MAX_ROPE_VERTICAL, RopeTies.MAX_ROPE_VERTICAL);
        return new Vec3(tie.x + dx, tie.y + dy, tie.z + dz);
    }

    private static boolean holdingRope(LocalPlayer player) {
        return player.getMainHandItem().is(BannerboundAntiquity.FIBER_ROPE.get())
            || player.getOffhandItem().is(BannerboundAntiquity.FIBER_ROPE.get());
    }
}
