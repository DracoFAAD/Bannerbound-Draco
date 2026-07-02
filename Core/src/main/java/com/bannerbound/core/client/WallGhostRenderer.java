package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Renders the wall blueprint in-world while one exists (WALLS_PLAN.md §E): translucent ghost
 * blocks where the blueprint expects a block and the world has air/replaceables, and a red
 * overlay where the world has the WRONG block (obstruction or wrong placement). Hand-building
 * needs no special placement API — placing the right block satisfies the diff and the ghost
 * simply stops drawing.
 *
 * <p>Technique: the ghost recipe preview's alpha-wrapping vertex consumer pattern, generalized
 * to block models — {@code renderSingleBlock} through a buffer source that reroutes every
 * render type to the translucent block sheet and scales vertex alpha. Per-frame iteration over
 * the blueprint map with a radius cull; entries are a few thousand longs at most, and drawn
 * ghosts are capped. Section-level geometry caching is the planned optimization if profiling
 * ever demands it.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class WallGhostRenderer {

    /** Ghosts draw within this distance of the camera (squared blocks below). */
    private static final double RADIUS_SQ = 48.0 * 48.0;
    /** Hard cap on ghost blocks drawn per frame — keeps megabase walls cheap. */
    private static final int MAX_GHOSTS_PER_FRAME = 600;
    /** Ghost opacity (~39%, matching the workstation ghost recipe silhouettes). */
    private static final int GHOST_ALPHA = 100;

    private WallGhostRenderer() {
    }

    // NOTE: no client-side connection simulation anymore — the server BAKES the design's
    // connections into the blueprint (WallConnectivity.bake) before syncing, so the synced
    // states ARE the exact final wall states (playtest 2026-06-12).

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (ClientWallBlueprint.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        GhostBufferSource ghostBuffer = new GhostBufferSource(buffer);

        // Collect in-radius unsatisfied positions, then draw NEAREST-FIRST — drawing in map
        // order spent the frame budget on far entries and made ghosts vanish right next to
        // the player ("proximity rendering flipped", playtest 2026-06-12).
        java.util.List<long[]> candidates = new java.util.ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Long2ObjectMap.Entry<BlockState> entry : ClientWallBlueprint.view().long2ObjectEntrySet()) {
            long packed = entry.getLongKey();
            double dx = BlockPos.getX(packed) + 0.5 - cam.x;
            double dy = BlockPos.getY(packed) + 0.5 - cam.y;
            double dz = BlockPos.getZ(packed) + 0.5 - cam.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > RADIUS_SQ) continue;
            candidates.add(new long[]{packed, (long) (distSq * 16.0)});
        }
        candidates.sort(java.util.Comparator.comparingLong(c -> c[1]));

        int drawn = 0;
        for (long[] candidate : candidates) {
            if (drawn >= MAX_GHOSTS_PER_FRAME) break;
            long packed = candidate[0];
            cursor.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            BlockState expected = ClientWallBlueprint.view().get(packed);
            BlockState actual = mc.level.getBlockState(cursor);
            if (expected == null || actual.is(expected.getBlock())) continue; // satisfied

            if (actual.isAir() || actual.canBeReplaced()) {
                drawn++;
                pose.pushPose();
                pose.translate(cursor.getX() - cam.x, cursor.getY() - cam.y, cursor.getZ() - cam.z);
                mc.getBlockRenderer().renderSingleBlock(expected, pose, ghostBuffer,
                    LightTexture.FULL_BRIGHT, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                pose.popPose();
            } else {
                // Wrong block in the wall's footprint — obstruction or misplacement. Red box.
                drawn++;
                DebugRenderer.renderFilledBox(pose, buffer, cursor, cursor, 1.0f, 0.15f, 0.15f, 0.35f);
            }
        }

        buffer.endBatch(Sheets.translucentCullBlockSheet());
        buffer.endBatch(RenderType.debugFilledBox());
    }

    /** Reroutes every requested render type to the translucent block sheet with scaled alpha. */
    private record GhostBufferSource(MultiBufferSource delegate) implements MultiBufferSource {
        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return new GhostVertexConsumer(delegate.getBuffer(Sheets.translucentCullBlockSheet()));
        }
    }

    /** Scales every vertex's alpha to {@link #GHOST_ALPHA}/255 — the ghost-silhouette look. */
    private record GhostVertexConsumer(VertexConsumer delegate) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha * GHOST_ALPHA / 255);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
