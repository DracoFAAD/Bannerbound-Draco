package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * One looping poison ambience drone (a single stage layer, for any poison). It plays "in the player's
 * head" (no attenuation, relative) and ramps its volume toward a target each tick, so
 * {@code PoisonClientEffects} can crossfade between stage layers and fade the whole thing in on a hit
 * / out on a heal. It self-stops once it has faded fully to silence.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class PoisonAmbienceSound extends AbstractTickableSoundInstance {
    /** Per-tick volume ramp — ~0.04/tick ≈ a 0.5s fade, smooth crossfades without being sluggish. */
    private static final float RAMP = 0.04F;

    private float target = 1.0F;

    public PoisonAmbienceSound(SoundEvent sound) {
        super(sound, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
        this.looping = true;
        this.delay = 0;
        // MUST start > 0: SoundEngine.play() culls a sound that is inaudible at start and never
        // begins it, so a 0-volume fade-in would simply never play. Start quiet, then ramp up.
        this.volume = 0.2F;
        this.relative = true;        // centred on the listener
        this.attenuation = Attenuation.NONE;
    }

    /** Set the volume this drone ramps toward (1 = active stage, 0 = fading out). */
    public void setTarget(float t) {
        this.target = Math.max(0.0F, Math.min(1.0F, t));
    }

    @Override
    public void tick() {
        if (this.volume < target) {
            this.volume = Math.min(target, this.volume + RAMP);
        } else if (this.volume > target) {
            this.volume = Math.max(target, this.volume - RAMP);
        }
        if (target <= 0.0F && this.volume <= 0.001F) {
            this.stop(); // fully faded out → let the SoundManager drop it
        }
    }
}
