package com.bannerbound.antiquity.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.MasonsBenchBlockEntity;
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
 * Renders the Mason's Bench's dynamic <b>items</b> (the deposited stone pile, the spinning picker
 * result + browse arrows, and the chisel-strike animation) — the stone analogue of
 * {@code WoodworkingTableRenderer}. Everything is drawn at the master block's centre (x/z = 0.5).
 * All <b>text</b> (counts, names, the queue) is drawn separately by {@link MasonryReadoutRenderer}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class MasonsBenchRenderer implements BlockEntityRenderer<MasonsBenchBlockEntity> {
    /** The model's bench top face sits at roughly 14px = 0.875; round up a touch for the pile. */
    static final double TOP_Y = 0.90;
    static final double CELL = 0.16;
    static final int MAX_PILE_LAYERS = 2;
    private static final float BLOCK_SCALE = 0.16F;

    private final ItemRenderer itemRenderer;

    public MasonsBenchRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(MasonsBenchBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        if (MasonChiselState.activeFor(be.getBlockPos())) {
            MasonChiselState.animate();
            renderChisel(be, pose, buffers, light);
            return; // the chisel animation takes over the bench top while it runs
        }
        renderPile(be, partialTick, pose, buffers, light);
        renderGhost(be, partialTick, pose, buffers);
    }

    /** Low deposited stones. Counts live on the bench readout, so this stays decorative and compact. */
    private void renderPile(MasonsBenchBlockEntity be, float partialTick, PoseStack pose,
                            MultiBufferSource buffers, int light) {
        List<ItemStack> contents = be.getStones();
        net.minecraft.core.Direction dir = be.getInsertDir();
        int slideCell = be.getLastSlideCell();
        float slide = 0.0F;
        if (be.getInsertAnimTicks() > 0) {
            float f = Math.max(0.0F,
                (be.getInsertAnimTicks() - partialTick) / MasonsBenchBlockEntity.SLIDE_TICKS);
            slide = f * f * 0.45F;
        }
        for (int cell = 0; cell < contents.size() && cell < 9; cell++) {
            ItemStack s = contents.get(cell);
            if (s.isEmpty()) continue;
            double ox = ((cell % 3) - 1) * CELL - 0.1;
            double oz = ((cell / 3) - 1) * CELL + 0.06;
            boolean isBlock = s.getItem() instanceof BlockItem;
            float scale = isBlock ? BLOCK_SCALE : 0.26F;
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

    /** The picker: the selected output floats + spins above the master block, with browse arrows when
     *  there are multiple offers. */
    private void renderGhost(MasonsBenchBlockEntity be, float partialTick, PoseStack pose,
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
        itemRenderer.renderStatic(ghost, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY, pose, GhostItemRenderer.wrap(buffers, 155), be.getLevel(), 0);
        pose.popPose();
        GhostArrowRenderer.render(be, pose, buffers);
    }

    /** The in-world chisel animation: a block of stone faces the player while the chisel drives down
     *  on each landed strike, throwing stone chips. */
    private void renderChisel(MasonsBenchBlockEntity be, PoseStack pose, MultiBufferSource buffers, int light) {
        float pulse = MasonChiselState.pulseAmount();
        float toolY = MasonChiselState.toolY;
        ItemStack stone = be.representativeBudgetStone();
        if (stone.isEmpty()) stone = new ItemStack(net.minecraft.world.level.block.Blocks.COBBLESTONE);
        ItemStack chisel = new ItemStack(com.bannerbound.antiquity.BannerboundAntiquity.STONE_CHISEL.get());

        pose.pushPose();
        pose.translate(0.5, TOP_Y + 0.16, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(MasonChiselState.sceneYaw));

        // The worked stone block, sitting on the bench.
        pose.pushPose();
        pose.translate(-0.05, 0.0, 0.0);
        pose.scale(0.62F, 0.62F, 0.62F);
        itemRenderer.renderStatic(stone, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
            pose, buffers, be.getLevel(), 0);
        pose.popPose();
        renderChips(be, pose, buffers, MasonChiselState.progress, pulse, light);

        // The chisel: held to the right of the worked stone, tip pointing left toward it. Each strike
        // JABS it horizontally into the stone and springs back — a back-and-forth chiselling motion,
        // not a vertical press (toolY 0 = drawn back, 1 = driven into the stone).
        pose.pushPose();
        pose.translate(0.44 - toolY * 0.13, 0.14, 0.06);
        pose.scale(0.7F, 0.7F, 0.7F);
        pose.mulPose(Axis.YP.rotationDegrees(-28.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(130.0F)); // flip the tip to point toward the stone
        itemRenderer.renderStatic(chisel, ItemDisplayContext.NONE, light,
            OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
        pose.popPose();

        pose.popPose();
    }

    /** Small physical stone chips around the cut point so progress reads on the bench itself. */
    private void renderChips(MasonsBenchBlockEntity be, PoseStack pose, MultiBufferSource buffers,
                             float progress, float pulse, int light) {
        ItemStack chip = new ItemStack(net.minecraft.world.level.block.Blocks.COBBLESTONE);
        int chips = 3 + Math.min(8, (int) (progress * 10.0F)) + (pulse > 0.0F ? 2 : 0);
        for (int i = 0; i < chips; i++) {
            float scatter = ((i * 37) % 100) / 100.0F;
            double side = (i % 2 == 0 ? -1.0 : 1.0);
            pose.pushPose();
            pose.translate(side * (0.08 + scatter * 0.12), -0.20 + scatter * 0.03,
                0.16 + ((i * 17) % 7) * 0.025);
            pose.scale(0.04F, 0.04F, 0.04F);
            pose.mulPose(Axis.YP.rotationDegrees(i * 47.0F));
            itemRenderer.renderStatic(chip, ItemDisplayContext.NONE, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, be.getLevel(), 0);
            pose.popPose();
        }
    }

    @Override
    public AABB getRenderBoundingBox(MasonsBenchBlockEntity be) {
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
