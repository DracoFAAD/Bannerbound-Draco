package com.bannerbound.antiquity.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Renders the Carpenter's Table's dynamic <b>items</b> (the low deposited log pile, the spinning picker
 * result + browse arrows, and the saw-minigame animation). Everything is drawn at the master block's
 * centre (x/z = 0.5) — the blockstate rotation pivot, which stays on the tabletop for any facing and
 * matches the ghost click hitbox. All <b>text</b> (counts, names, the queue) is drawn separately by
 * {@link CarpentryReadoutRenderer} via a level-stage pass, because glyphs added inside a block-entity
 * renderer don't get flushed reliably.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class WoodworkingTableRenderer implements BlockEntityRenderer<WoodworkingTableBlockEntity> {
    /** The model's tabletop top face sits at roughly 15px = 0.94. */
    static final double TOP_Y = 0.96;
    static final double CELL = 0.16;
    static final int MAX_PILE_LAYERS = 2;
    private static final float BLOCK_SCALE = 0.16F;
    private static final float ITEM_SCALE = 0.26F;

    private final ItemRenderer itemRenderer;

    public WoodworkingTableRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(WoodworkingTableBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        if (CarpentrySawState.activeFor(be.getBlockPos())) {
            renderSaw(be, pose, buffers, light);
            return; // the saw animation takes over the tabletop while it runs
        }
        renderPile(be, partialTick, pose, buffers, light);
        renderGhost(be, partialTick, pose, buffers);
    }

    /** Low deposited logs. Counts live on the tabletop readout, so this stays decorative and compact. */
    private void renderPile(WoodworkingTableBlockEntity be, float partialTick, PoseStack pose,
                            MultiBufferSource buffers, int light) {
        List<ItemStack> contents = be.getLogs();
        net.minecraft.core.Direction dir = be.getInsertDir();
        int slideCell = be.getLastSlideCell();
        float slide = 0.0F;
        if (be.getInsertAnimTicks() > 0) {
            float f = Math.max(0.0F,
                (be.getInsertAnimTicks() - partialTick) / WoodworkingTableBlockEntity.SLIDE_TICKS);
            slide = f * f * 0.45F;
        }
        for (int cell = 0; cell < contents.size() && cell < 9; cell++) {
            ItemStack s = contents.get(cell);
            if (s.isEmpty()) continue;
            double ox = ((cell % 3) - 1) * CELL - 0.1;
            double oz = ((cell / 3) - 1) * CELL + 0.06;
            boolean isBlock = s.getItem() instanceof BlockItem;
            float scale = isBlock ? BLOCK_SCALE : ITEM_SCALE;
            double layerH = isBlock ? scale + 0.005 : 0.022;
            int shown = Math.min(s.getCount(), MAX_PILE_LAYERS);
            for (int layer = 0; layer < shown; layer++) {
                boolean slides = cell == slideCell && layer == shown - 1;
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
    }

    /** The picker: the selected output floats + spins above the master block (the ghost click hitbox
     *  spot), with browse arrows when there are multiple offers. */
    private void renderGhost(WoodworkingTableBlockEntity be, float partialTick, PoseStack pose,
                             MultiBufferSource buffers) {
        ItemStack ghost = be.getGhostResult();
        if (ghost.isEmpty()) return;
        long time = be.getLevel() != null ? be.getLevel().getGameTime() : 0L;
        float t = time + partialTick;
        double y = be.ghostPreviewY();
        pose.pushPose();
        pose.translate(0.5, y + Math.sin(t * 0.1F) * 0.04F, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(t * 3.0F));
        pose.scale(0.34F, 0.34F, 0.34F);
        // Translucent, like the other workstations' recipe ghosts (and now matching the readout chips).
        itemRenderer.renderStatic(ghost, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY, pose, GhostItemRenderer.wrap(buffers, 155), be.getLevel(), 0);
        pose.popPose();
        GhostArrowRenderer.render(be, pose, buffers);
    }

    /** The in-world saw animation. The scene faces the player, with the log fed left-to-right and the
     *  saw stroking across the tabletop depth. */
    private void renderSaw(WoodworkingTableBlockEntity be, PoseStack pose, MultiBufferSource buffers, int light) {
        float progress = CarpentrySawState.progress;
        float pulse = CarpentrySawState.pulseAmount();
        ItemStack log = be.representativeBudgetLog();
        if (log.isEmpty()) log = new ItemStack(net.minecraft.world.level.block.Blocks.OAK_LOG);
        ItemStack saw = new ItemStack(com.bannerbound.antiquity.BannerboundAntiquity.BONE_SAW.get());

        pose.pushPose();
        pose.translate(0.5, TOP_Y + 0.16, 0.5);
        // Turn the scene's front (+Z) to point straight at the camera. Computed geometrically from the
        // camera→block vector (NOT a captured yaw, whose sign conventions kept landing 180° off): the
        // BER pose is world-axis-aligned, so a world-space Y rotation of atan2(dx, dz) aims +Z at the
        // viewer. The minigame freezes the view, so this is effectively fixed yet always faces the player.
        pose.mulPose(Axis.YP.rotationDegrees(CarpentrySawState.sceneYaw));

        // The log: a normal block scaled down, fed left→right through the saw as it's cut.
        double slideX = -0.36 + progress * 0.62;
        pose.pushPose();
        pose.translate(slideX, 0.0, 0.0);
        pose.scale(0.68F, 0.68F, 0.68F);
        pose.mulPose(Axis.ZP.rotationDegrees(90.0F)); // lay it on its side (end-grain to the ends)
        itemRenderer.renderStatic(log, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
            pose, buffers, be.getLevel(), 0);
        pose.popPose();
        renderSawdust(be, pose, buffers, progress, pulse, light);

// The saw rotation
        pose.pushPose();
        pose.translate(0.0, 0.18 - pulse * 0.035, 0.24 - CarpentrySawState.sawY * 0.36);
        pose.scale(0.8F + pulse * 0.05F, 0.8F + pulse * 0.05F, 0.8F + pulse * 0.05F);
        pose.mulPose(Axis.XP.rotationDegrees(-45.0F));
        pose.mulPose(Axis.YP.rotationDegrees(-85.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(-0.0F + pulse * 5.0F));
        itemRenderer.renderStatic(saw, ItemDisplayContext.NONE, light,
            OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
        pose.popPose();

        pose.popPose();
    }

    /** Small physical shaving chips around the cut point so progress reads on the table itself. */
    private void renderSawdust(WoodworkingTableBlockEntity be, PoseStack pose, MultiBufferSource buffers,
                               float progress, float pulse, int light) {
        ItemStack shaving = new ItemStack(net.minecraft.world.level.block.Blocks.OAK_PLANKS);
        int chips = 3 + Math.min(8, (int) (progress * 10.0F)) + (pulse > 0.0F ? 2 : 0);
        for (int i = 0; i < chips; i++) {
            float scatter = ((i * 37) % 100) / 100.0F;
            double side = (i % 2 == 0 ? -1.0 : 1.0);
            pose.pushPose();
            pose.translate(side * (0.08 + scatter * 0.12), -0.22 + scatter * 0.03,
                0.16 + ((i * 17) % 7) * 0.025);
            pose.scale(0.045F, 0.018F, 0.045F);
            pose.mulPose(Axis.YP.rotationDegrees(i * 47.0F));
            itemRenderer.renderStatic(shaving, ItemDisplayContext.NONE, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
            pose.popPose();
        }
    }

    /** The picker, build-list chips, and saw scene all float well above the block, and the model itself
     *  extends a second block toward its facing. The default per-block cull box is just the 1×1 master
     *  cell, so from steep/oblique angles (looking up, or the base block off-screen) the floating content
     *  popped out of view. Grow the box up + outward so it stays drawn while any of it is on-screen. */
    @Override
    public AABB getRenderBoundingBox(WoodworkingTableBlockEntity be) {
        BlockPos pos = be.getBlockPos();
        return new AABB(pos).inflate(1.5, 0.0, 1.5).expandTowards(0.0, 4.0, 0.0);
    }

    static float snappedYawTowardCamera(BlockPos pos, Vec3 camera) {
        double dx = camera.x - (pos.getX() + 0.5);
        double dz = camera.z - (pos.getZ() + 0.5);
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx >= 0.0 ? 90.0F : -90.0F;
        }
        return dz >= 0.0 ? 0.0F : 180.0F;
    }

    static double snappedOffsetX(float yaw, double distance) {
        if (yaw == 90.0F) return distance;
        if (yaw == -90.0F) return -distance;
        return 0.0;
    }

    static double snappedOffsetZ(float yaw, double distance) {
        if (yaw == 0.0F) return distance;
        if (yaw == 180.0F) return -distance;
        return 0.0;
    }
}
