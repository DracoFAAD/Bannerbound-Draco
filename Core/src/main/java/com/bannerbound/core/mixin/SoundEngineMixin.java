package com.bannerbound.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.bannerbound.core.client.SoundMuffle;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;

/**
 * Ducks world-sound volume by {@link SoundMuffle#factor()} so an expansion (Antiquity's poison) can
 * make the world recede — the muffled-hearing effect. {@code calculateVolume(SoundInstance)} is
 * recomputed every tick per playing sound, so the duck is continuous and tracks the live factor.
 *
 * <p>Bannerbound's own sounds (the poison ambience / cues) are exempt so they stay clear over the
 * muffle. {@code require = 0}: if the obfuscated signature ever drifts, the mixin degrades to a
 * no-op (muffle just stops working) rather than crashing the game.
 */
@Mixin(SoundEngine.class)
public class SoundEngineMixin {

    @Inject(
        method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
        at = @At("RETURN"),
        cancellable = true,
        require = 0
    )
    private void bannerbound$muffleWorldSound(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        float f = SoundMuffle.factor();
        if (f >= 1.0F || sound == null || sound.getLocation() == null) {
            return;
        }
        String ns = sound.getLocation().getNamespace();
        if (ns.equals("bannerbound") || ns.equals("bannerboundantiquity")) {
            return; // keep our own poison ambience / cues clear over the muffle
        }
        cir.setReturnValue(cir.getReturnValueF() * f);
    }
}
