package com.bannerbound.antiquity.client;

import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws the carve preview: the result blockstate(s) chosen by {@link CarvePreviewController}, as
 * low-alpha silhouettes at the (client-hidden) source positions. Same alpha-wrapping vertex-consumer
 * trick as Core's {@code WallGhostRenderer} — {@code renderSingleBlock} through a buffer source that
 * reroutes every render type to the translucent block sheet and scales vertex alpha.
 *
 * <p>Block-entity-rendered blocks (e.g. the bloomery) show their base model only here, because
 * {@code renderSingleBlock} doesn't invoke their {@code BlockEntityRenderer}.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class CarveGhostRenderer {

    /** Ghost opacity (~39%, matching the workstation ghost recipe silhouettes). */
    private static final int GHOST_ALPHA = 100;

    private CarveGhostRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Map<BlockPos, BlockState> ghosts = CarvePreviewController.ghosts();
        if (ghosts.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        GhostBufferSource ghostBuffer = new GhostBufferSource(buffer);
        VertexConsumer lines = buffer.getBuffer(RenderType.lines());

        // Gentle pulse on the outline reads as "this gesture is ready to commit" — the same
        // green-affordance language the workstation ghost clicks already use.
        float t = (float) mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float outlineAlpha = 0.55F + 0.30F * Mth.sin(t * 0.2F);

        for (Map.Entry<BlockPos, BlockState> e : ghosts.entrySet()) {
            BlockPos p = e.getKey();
            BlockState state = e.getValue();
            pose.pushPose();
            pose.translate(p.getX() - cam.x, p.getY() - cam.y, p.getZ() - cam.z);
            mc.getBlockRenderer().renderSingleBlock(state, pose, ghostBuffer,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();

            // Trace the RESULT's true voxel shape as a green wireframe: sharpens the silhouette and
            // signals the carve is valid. Cam-relative coords against the base (un-translated) pose,
            // matching SelectionRenderer / renderLineBox usage elsewhere.
            double dx = p.getX() - cam.x;
            double dy = p.getY() - cam.y;
            double dz = p.getZ() - cam.z;
            outlineShape(mc.level, p, state).forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
                LevelRenderer.renderLineBox(pose, lines,
                    dx + minX, dy + minY, dz + minZ, dx + maxX, dy + maxY, dz + maxZ,
                    0.32F, 0.96F, 0.42F, outlineAlpha));
        }
        buffer.endBatch(Sheets.translucentCullBlockSheet());
        buffer.endBatch(RenderType.lines());
    }

    /** The result's outline shape, falling back to a full cube if it's empty or its shape would need
     *  a block-entity we don't have (the previewed cell is air). */
    private static VoxelShape outlineShape(Level level, BlockPos pos, BlockState state) {
        try {
            VoxelShape shape = state.getShape(level, pos);
            return shape.isEmpty() ? Shapes.block() : shape;
        } catch (RuntimeException ignored) {
            return Shapes.block();
        }
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
