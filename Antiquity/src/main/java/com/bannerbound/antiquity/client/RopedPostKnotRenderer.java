package com.bannerbound.antiquity.client;

import com.bannerbound.antiquity.block.RopeFencePostBlock;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LeashKnotRenderer;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Vanilla leash-knot renderer, except it hides the knot when it sits on a rope-fence post that's
 * showing its "with rope" model. A fiber rope tied to a raft drives that post's {@code ROPED}
 * blockstate (see RaftEntity#reconcileRopedPost), and the post's coil stands in for the knot — so a
 * second little leash knot on top would be visual clutter. Lead ties don't set {@code ROPED}, so
 * their knot still shows; ordinary fences (no such blockstate) always render the knot normally.
 */
@OnlyIn(Dist.CLIENT)
public class RopedPostKnotRenderer extends LeashKnotRenderer {
    public RopedPostKnotRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(LeashFenceKnotEntity knot, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        BlockState state = knot.level().getBlockState(knot.getPos());
        if (state.getBlock() instanceof RopeFencePostBlock && state.getValue(RopeFencePostBlock.ROPED)) {
            return; // the post's with-rope model already shows the tie
        }
        super.render(knot, yaw, partialTick, poseStack, buffer, packedLight);
    }
}
