package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side global "muffle" amount for world sound. The {@code SoundEngineMixin} multiplies every
 * non-Bannerbound sound's volume by {@link #factor()} each tick, so setting the factor below 1.0
 * makes the world recede (the muffled-hearing effect). Core owns this because mixins live in Core;
 * an expansion (Antiquity's poison) drives it via {@link #set} from its client tick — Core stays
 * ignorant of why the world is muffled.
 *
 * <p>This is the volume-duck tier of muffle (reliable everywhere). A true spectral low-pass would
 * attach an OpenAL EFX filter at the same hook — a future upgrade once it can be audio-tested.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class SoundMuffle {
    private static volatile float factor = 1.0F;

    private SoundMuffle() {}

    /** Set the muffle factor: 1.0 = normal, 0.0 = silent. Clamped. */
    public static void set(float f) {
        factor = Math.max(0.0F, Math.min(1.0F, f));
    }

    /** Current world-sound volume multiplier (1.0 = no muffle). */
    public static float factor() {
        return factor;
    }
}
