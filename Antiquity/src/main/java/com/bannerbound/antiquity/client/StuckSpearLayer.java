package com.bannerbound.antiquity.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.entity.StuckSpear;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws every spear embedded in a mob, reading the mob's synced {@code List<StuckSpear>} data
 * attachment. It runs inside the mob renderer's already-posed PoseStack (model space), so each
 * spear tracks the body for free — the vanilla arrows-in-a-mob approach, no follow-entity, no lag,
 * no relog. Extends {@link RenderLayer} directly (not vanilla {@code StuckInBodyLayer}, which is
 * bound to {@code PlayerModel}) so it works for every living entity + players.
 *
 * <p>The orientation constants mirror {@code SpearProjectileRenderer} so a stuck spear reads the
 * same as it did in flight. They — and the two rotation offsets — are visual tunables: the layer
 * runs in model space (Y-down, X-flipped, origin lifted), so the in-flight values may need a small
 * in-game adjustment.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class StuckSpearLayer<T extends LivingEntity, M extends EntityModel<T>>
        extends RenderLayer<T, M> {
    /** Brings the model's authored 45°-up diagonal onto the stored pitch axis. */
    private static final float MODEL_PITCH_CORRECTION = -45.0F;
    /** Heading correction, matching the in-flight renderer's yaw-90. Tunable if the stuck spear
     *  points off to one side. */
    private static final float YAW_CORRECTION = -90.0F;
    /** Full size (matches the in-flight/stuck-in-block renderer). MUST stay 1.0: the ANCHOR translate
     *  below runs after this scale, so any value <1 shrinks the anchor offset and the spear floats
     *  off its hit point (and swings as the mob turns). */
    private static final float MODEL_SCALE = 1.0F;
    // Anchor the spear's TIP exactly at the captured hit point (the head burying happens at CAPTURE
    // time — SpearProjectile pushes the point inward along the flight dir — so no fudge here).
    private static final float TIP_X = 30.0F / 16.0F;
    private static final float TIP_Y = 28.0F / 16.0F;
    private static final float TIP_Z = 8.5F / 16.0F;
    // +0.5 cancels ItemRenderer.render's internal translate(-0.5) so the TIP lands on the anchor.
    private static final float ANCHOR_X = 0.5F - TIP_X;
    private static final float ANCHOR_Y = 0.5F - TIP_Y;
    private static final float ANCHOR_Z = 0.5F - TIP_Z;

    private final ItemRenderer itemRenderer;

    public StuckSpearLayer(RenderLayerParent<T, M> parent, ItemRenderer itemRenderer) {
        super(parent);
        this.itemRenderer = itemRenderer;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffer, int packedLight, T entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        List<StuckSpear> stuck = entity.getExistingDataOrNull(BannerboundAntiquity.STUCK_SPEARS.get());
        if (stuck == null || stuck.isEmpty()) {
            return;
        }
        for (StuckSpear spear : stuck) {
            ItemStack stack = spear.stack();
            if (stack.isEmpty()) {
                continue;
            }
            pose.pushPose();
            // Place the model ORIGIN on the body-local hit point. This already tracks the mob (the
            // renderer's body rotation is in the pose).
            pose.translate(spear.localX(), spear.localY(), spear.localZ());

            // Undo ONLY the renderer's X/Y mirror so the model isn't inside-out. Do NOT re-inject
            // the body yaw: the renderer's pose already carries it, so a CONSTANT orientation here
            // rotates rigidly WITH the body (like a leg). The previous "cancel + replay world yaw"
            // made the heading counter-rotate, so the spear looked world-fixed. With the double
            // mirror cancelled, the same yaw-90 / pitch-45 the in-flight renderer uses applies here.
            pose.scale(-1.0F, -1.0F, 1.0F);
            pose.mulPose(Axis.YP.rotationDegrees(spear.yaw() + YAW_CORRECTION));
            pose.mulPose(Axis.ZP.rotationDegrees(spear.pitch() + MODEL_PITCH_CORRECTION));

            pose.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
            pose.translate(ANCHOR_X, ANCHOR_Y, ANCHOR_Z);
            this.itemRenderer.renderStatic(stack, ItemDisplayContext.NONE, packedLight,
                OverlayTexture.NO_OVERLAY, pose, buffer, entity.level(), entity.getId());
            pose.popPose();
        }
    }
}
