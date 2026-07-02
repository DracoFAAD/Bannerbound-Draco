package com.bannerbound.antiquity.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person hand support for {@link UseAnim#TOOT_HORN} items (e.g. the blowgun, which draws like a
 * goat horn raised to the mouth). Vanilla's first-person {@code renderArmWithItem} switch has cases for
 * BOW/SPEAR/CROSSBOW/BRUSH/etc. but <b>none for TOOT_HORN</b>, so a horn-anim item being used gets no
 * arm transform and renders stuck at the camera origin. We supply the standard item arm transform just
 * before the held item is drawn (the same transform NONE/BLOCK use); the model's firstperson display
 * transforms then position it at the mouth. Third person is already handled natively by vanilla's
 * {@code TOOT_HORN} arm pose.
 *
 * <p>Injected before the <em>second</em> {@code renderItem} call (ordinal 1 — the one inside the
 * generic using/swinging branch), and guarded to the exact "is using this item in this hand" condition
 * vanilla's switch checks, so non-using items (which already had their transform applied) are untouched.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class FirstPersonTootHornMixin {
    @Shadow
    protected abstract void applyItemArmTransform(PoseStack poseStack, HumanoidArm arm, float equippedProgress);

    @Inject(
        method = "renderArmWithItem",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            ordinal = 1
        )
    )
    private void bb$tootHornFirstPerson(AbstractClientPlayer player, float partialTick, float pitch,
                                        InteractionHand hand, float swingProgress, ItemStack stack,
                                        float equippedProgress, PoseStack poseStack,
                                        MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (!player.isUsingItem() || player.getUseItemRemainingTicks() <= 0
            || player.getUsedItemHand() != hand
            || stack.getUseAnimation() != UseAnim.TOOT_HORN) {
            return;
        }
        boolean mainHand = hand == InteractionHand.MAIN_HAND;
        HumanoidArm arm = mainHand ? player.getMainArm() : player.getMainArm().getOpposite();
        this.applyItemArmTransform(poseStack, arm, equippedProgress);
    }
}
