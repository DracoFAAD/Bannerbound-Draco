package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Oleander's heartbeat: as the cardiac clock runs down (fraction 0→1) the local player hears their own
 * heart thud at an accelerating cadence and rising pitch, and the screen pumps red in time (see
 * {@link PoisonPostProcessor}'s oleander path, which reads {@link #pulse}). A single {@code
 * oleander_heartbeat} sound is replayed faster and faster — no per-beat asset needed.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class PoisonHeartbeat {
    private static long lastBeat = Long.MIN_VALUE;
    private static long nextBeat = Long.MIN_VALUE;

    private PoisonHeartbeat() {}

    /** Drive the beats each client tick while oleander-poisoned. {@code fraction} = how far the cardiac
     *  clock has run (0 at infection, 1 at arrest). */
    public static void tick(float fraction) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long now = mc.level.getGameTime();
        if (nextBeat == Long.MIN_VALUE || now >= nextBeat) {
            lastBeat = now;
            nextBeat = now + beatInterval(fraction);
            playBeat(mc, fraction);
        }
    }

    /** Beat gap in ticks: ~26t (a calm ~1.3s) at the start, tightening to ~6t (a racing ~0.3s) at the end. */
    private static int beatInterval(float fraction) {
        return Math.max(5, Math.round(Mth.lerp(Mth.clamp(fraction, 0.0F, 1.0F), 26.0F, 6.0F)));
    }

    private static void playBeat(Minecraft mc, float fraction) {
        float volume = Mth.clamp(fraction, 0.0F, 1.0F); // builds from silence to full over the 3-min countdown
        float pitch = 0.9F + mc.level.getRandom().nextFloat() * 0.2F; // 0.9–1.1, varied so it isn't repetitive
        // forUI = non-positional ("in your head"). Silent until the heartbeat.ogg asset is added.
        mc.getSoundManager().play(
            SimpleSoundInstance.forUI(BannerboundAntiquity.OLEANDER_HEARTBEAT.get(), pitch, volume));
    }

    /** Visual pulse envelope (0..1) since the last beat — a sharp thud that decays over ~5 ticks, so the
     *  post-process can pump the red vignette in time with the beat. */
    public static float pulse(float nowTicks) {
        if (lastBeat == Long.MIN_VALUE) {
            return 0.0F;
        }
        float t = nowTicks - lastBeat;
        if (t < 0.0F || t > 5.0F) {
            return 0.0F;
        }
        return Math.max(0.0F, 1.0F - t / 5.0F);
    }

    public static void reset() {
        lastBeat = Long.MIN_VALUE;
        nextBeat = Long.MIN_VALUE;
    }
}
