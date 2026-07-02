package com.bannerbound.antiquity.mixin;

import com.bannerbound.antiquity.client.HammerArmState;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Raises the third-person hammer arm overhead while a player is cold-hammering at the Stone Anvil — so
 * your own avatar (in F5) and other players nearby see the swing, not just the first-person hand.
 *
 * <p>Injected at the TAIL of {@link PlayerModel#setupAnim} so it overrides the finalised arm pose for
 * the frame. It skips while the player is actively {@code swinging}, letting the vanilla per-strike
 * swing play the down-smack; between strikes the eased raise from {@link HammerArmState} holds the
 * hammer up. Non-hammering players are untouched (cheap UUID lookup, returns early).
 */
@Mixin(PlayerModel.class)
public abstract class PlayerArmRaiseMixin {
    /** Radians the arm rotates back/overhead at full raise (≈126°). */
    private static final float MAX_RAISE = -2.2F;

    @Inject(method = "setupAnim", at = @At("TAIL"))
    private void bb$raiseHammerArm(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                   float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (!(entity instanceof Player player) || player.swinging) return;
        float raise = HammerArmState.raise(player.getUUID());
        if (raise <= 0.001F) return;
        PlayerModel<?> model = (PlayerModel<?>) (Object) this;
        ModelPart arm = player.getMainArm() == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        arm.xRot = MAX_RAISE * raise;
        arm.yRot = 0F;
        arm.zRot = 0F;
    }
}
