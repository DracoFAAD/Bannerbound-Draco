package com.bannerbound.antiquity.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Drunk black-out collapse (GROG_PLAN.md Phase 3.5): while a player is blacked-out from grog
 * ({@code PASS_OUT_UNTIL}), topple their rendered body over onto the ground — curare-style, but a
 * slower slump: they tip over the first ~1.5s, lie there out cold, then get back up as they come to.
 * {@code require = 0} so a mappings shift just disables the collapse rather than crashing.
 */
@Mixin(LivingEntityRenderer.class)
public class DrunkProneMixin {
    @Inject(method = "setupRotations", at = @At("TAIL"), require = 0)
    private void bannerbound$collapse(LivingEntity entity, PoseStack poseStack, float bob, float yBodyRot,
                                      float partialTick, float scale, CallbackInfo ci) {
        if (!(entity instanceof Player)) {
            return;
        }
        long passOut = entity.getData(BannerboundAntiquity.PASS_OUT_UNTIL.get());
        if (passOut <= 0L) {
            return;
        }
        float remaining = (passOut - entity.level().getGameTime()) - partialTick;
        if (remaining <= 0.0F) {
            return;
        }
        float elapsed = com.bannerbound.antiquity.item.Intoxication.PASS_OUT_TICKS - remaining;
        float down = Math.min(1.0F, elapsed / 30.0F);   // ~1.5s to tip over
        float up = Math.min(1.0F, remaining / 24.0F);    // ~1.2s to get back up
        float t = Math.max(0.0F, Math.min(down, up));
        if (t > 0.0F) {
            // Topple sideways about the feet (that lays the body flat at ground level), lifting only by
            // ~half the body WIDTH so the lying body rests ON the ground instead of half-sinking — NOT
            // half the height, which floated it.
            poseStack.translate(0.0F, entity.getBbWidth() * 0.5F * t, 0.0F);
            poseStack.mulPose(Axis.ZP.rotationDegrees(90.0F * t));
        }
    }
}
