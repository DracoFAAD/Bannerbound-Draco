package com.bannerbound.antiquity.mixin;

import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bannerbound.antiquity.client.DrunkAudio;
import com.mojang.blaze3d.audio.Channel;

/**
 * Grog drunkenness audio (GROG_PLAN.md Phase 3.5): two hooks on the sound channel —
 * <ul>
 *   <li>after {@code setPitch}, re-pitch the source by the woozy wobble ({@link DrunkAudio#pitchFactor()});</li>
 *   <li>when a channel {@code play}s, attach the drunk EFX chain (muffle + reverb + slapback) to it
 *       ({@link DrunkAudio#applyEfx(int)}).</li>
 * </ul>
 * Sober → both are no-ops. Same render thread the channel already drives OpenAL on.
 */
@Mixin(Channel.class)
public class DrunkSoundMixin {
    @Shadow
    private int source;

    @Inject(method = "setPitch", at = @At("TAIL"))
    private void bannerbound$drunkenPitch(float pitch, CallbackInfo ci) {
        float factor = DrunkAudio.pitchFactor();
        if (factor != 1.0F) {
            AL10.alSourcef(this.source, AL10.AL_PITCH, pitch * factor);
        }
    }

    @Inject(method = "play", at = @At("TAIL"))
    private void bannerbound$drunkenEfx(CallbackInfo ci) {
        DrunkAudio.applyEfx(this.source);
    }
}
