package com.bannerbound.antiquity.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/**
 * Wraps a spear item's baked model so that, ONLY while the entity holding it is mid-use with the SPEAR
 * wind-up (raise-over-shoulder throw), the held model is spun 180° about Y. The authored model is
 * oriented for the normal hold; the SPEAR raise would otherwise show it backwards. A static model
 * can't tell the two apart (both use {@code thirdperson_righthand}), so we do it here at render time.
 *
 * <p>{@code applyTransform} is the single choke point both the first- and third-person hand renders
 * pass through, and it's never hit for GUI / ground / fixed / NONE — so the inventory icon, dropped
 * item, item frame, the in-hand normal grip, and the flying/stuck projectile (rendered via NONE) are
 * all untouched.
 *
 * <p>{@code applyTransform} has no entity, so the use-check keys on {@link HeldItemRenderContext} —
 * the entity whose model is being drawn right now. Keying on a global ({@code Minecraft#player}) was
 * the "rotating one spear rotates all spears" bug: it flipped EVERY on-screen spear whenever the
 * local player raised one. Per-entity context fixes that — each spear flips only while its own holder
 * is winding up.
 */
@OnlyIn(Dist.CLIENT)
public class SpearHandFlipModel extends BakedModelWrapper<BakedModel> {
    // Geometric centre of the spear model in the frame ItemRenderer renders in (model bounds /16,
    // minus the renderer's internal 0.5 offset). Pivoting the 180° flip here keeps it seated in the
    // hand. Visual tunables — nudge if the raised spear still sits slightly off.
    private static final float PIVOT_X = 0.52F;
    private static final float PIVOT_Y = 0.375F;
    private static final float PIVOT_Z = 0.03F;

    // The flip applied to the raised (throwing) spear, as degrees about each axis (applied X→Y→Z
    // about the pivot above). Pure visual tunables. YP(180) alone left it seated but upside-down
    // (blade pointing down), so the vertical flip means the net rotation is about Z. If it still
    // points the wrong way, nudge these (e.g. swap which axis is 180, or try 90).
    private static final float FLIP_X_DEG = 0.0F;
    private static final float FLIP_Y_DEG = 0.0F;
    private static final float FLIP_Z_DEG = 180.0F;

    public SpearHandFlipModel(BakedModel original) {
        super(original);
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext ctx, PoseStack pose, boolean applyLeftHandTransform) {
        BakedModel result = super.applyTransform(ctx, pose, applyLeftHandTransform);
        if (isThirdPersonHand(ctx) && isHolderRaisingSpear()) {
            // Spin 180° about the model's GEOMETRIC centre so it stays seated in the fist. The model
            // spans ~(0..1.875, 0..1.75, 0.5..0.56) blocks; ItemRenderer.render then applies a -0.5
            // offset, so in this frame the centre sits at (~0.52, ~0.375, ~0.03) — pivoting there
            // (not at 0.5,0.5,0.5) keeps the flip from throwing the spear to the side / downward.
            pose.translate(PIVOT_X, PIVOT_Y, PIVOT_Z);
            if (FLIP_X_DEG != 0.0F) pose.mulPose(Axis.XP.rotationDegrees(FLIP_X_DEG));
            if (FLIP_Y_DEG != 0.0F) pose.mulPose(Axis.YP.rotationDegrees(FLIP_Y_DEG));
            if (FLIP_Z_DEG != 0.0F) pose.mulPose(Axis.ZP.rotationDegrees(FLIP_Z_DEG));
            pose.translate(-PIVOT_X, -PIVOT_Y, -PIVOT_Z);
        }
        return result;
    }

    /** Only THIRD person needs the flip — first person already looked right, so flipping it there
     *  would reverse it. (Both share the model's display transform; only the use-pose differs.) */
    private static boolean isThirdPersonHand(ItemDisplayContext ctx) {
        return ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
            || ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
    }

    /** True only while the entity whose model is currently being rendered (see
     *  {@link HeldItemRenderContext}) is actively using an item whose use-anim is SPEAR. Per-entity,
     *  so one holder's wind-up never flips another holder's spear. */
    private static boolean isHolderRaisingSpear() {
        LivingEntity holder = HeldItemRenderContext.current();
        if (holder == null || !holder.isUsingItem()) {
            return false;
        }
        ItemStack use = holder.getUseItem();
        return !use.isEmpty() && use.getUseAnimation() == UseAnim.SPEAR;
    }
}
