package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

/**
 * Grog drunkenness audio (GROG_PLAN.md Phase 3.5): everything the player hears warps with intoxication.
 * <ul>
 *   <li><b>Pitch wobble</b> — a woozy sway on every sound (no EFX; {@link #pitchFactor()}).</li>
 *   <li><b>Muffle</b> — an OpenAL low-pass filter on each source (the direct path; works on any device
 *       with {@code ALC_EXT_EFX}).</li>
 *   <li><b>Reverb + slapback</b> — auxiliary effect slots (room reverb + an echo when really drunk).
 *       These need the OpenAL context to expose aux sends; if it doesn't, they no-op and the muffle
 *       still applies.</li>
 * </ul>
 * Applied from {@code DrunkSoundMixin} as each sound channel is configured. Fully defensive: any EFX
 * failure (or no EFX at all) just falls back to the pitch wobble. Stands down entirely if Sound Physics
 * Remastered is installed (it owns the OpenAL effect chain). NOTE: does <i>not</i> touch Simple Voice
 * Chat — SVC has its own audio pipeline; slurring voice needs a separate SVC-plugin integration.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class DrunkAudio {
    /** Level at which the audio warp is at full strength. */
    private static final float FULL_LEVEL = 6.0F;

    private static boolean triedInit;
    private static boolean efxReady;
    private static int lowpass;
    private static int reverbSlot, reverbEffect;
    private static int echoSlot, echoEffect;

    private DrunkAudio() {}

    // ─── Pitch wobble (no EFX) ───────────────────────────────────────────────────────────────────

    /** Pitch multiplier: 1.0 sober, else a slow sine wobble around a slightly lowered pitch, deeper
     *  with intoxication. Sampled per sound (MC sounds are mostly short, so they warble). */
    public static float pitchFactor() {
        int level = level();
        if (level <= 0) {
            return 1.0F;
        }
        float depth = Math.min(0.14F, 0.02F * level);
        float wob = (float) Math.sin((System.nanoTime() / 1.0E9) * 2.2);
        return 1.0F + wob * depth - depth * 0.25F;
    }

    // ─── EFX (muffle + reverb + slapback) ────────────────────────────────────────────────────────

    /** Attach or clear the drunk EFX on a freshly-played source, scaled by intoxication. Called from
     *  the channel mixin on the render thread (where the AL context is current). */
    public static void applyEfx(int source) {
        if (!ensure()) {
            return;
        }
        float drunk = Math.min(1.0F, level() / FULL_LEVEL);
        float muffle = Math.max(drunk, hangover()); // a hangover muffles the world even when sober
        if (muffle <= 0.0F) {
            clear(source);
            return;
        }
        try {
            // Muffle: drop the high frequencies (direct filter — always available with EFX).
            EXTEfx.alFilterf(lowpass, EXTEfx.AL_LOWPASS_GAIN, 1.0F);
            EXTEfx.alFilterf(lowpass, EXTEfx.AL_LOWPASS_GAINHF, 1.0F - 0.72F * muffle);
            AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, lowpass);
            AL10.alGetError();

            // Reverb + slapback are the DRUNK feel (a swimmy room), not the dull hangover.
            if (reverbSlot != 0 && drunk > 0.0F) {
                EXTEfx.alAuxiliaryEffectSlotf(reverbSlot, EXTEfx.AL_EFFECTSLOT_GAIN, Math.min(1.0F, 0.5F * drunk));
                AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, reverbSlot, 0, EXTEfx.AL_FILTER_NULL);
                AL10.alGetError();
            }
            if (echoSlot != 0 && drunk > 0.5F) {
                AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, echoSlot, 1, EXTEfx.AL_FILTER_NULL);
                AL10.alGetError();
            }
        } catch (Throwable ignored) {
            // Driver hiccup — leave the source as-is (the pitch wobble still carries the effect).
        }
    }

    private static void clear(int source) {
        try {
            AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, 0, EXTEfx.AL_FILTER_NULL);
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, 1, EXTEfx.AL_FILTER_NULL);
            AL10.alGetError();
        } catch (Throwable ignored) {
        }
    }

    /** Lazily create the shared EFX objects on first use (the AL context must exist + expose EFX).
     *  Returns false (and retries next call) until ready; false forever if EFX is absent / SP owns it. */
    private static boolean ensure() {
        if (triedInit) {
            return efxReady;
        }
        long context = ALC10.alcGetCurrentContext();
        if (context == 0L) {
            return false; // audio not up yet — try again on the next sound
        }
        triedInit = true;
        if (ModList.get().isLoaded("sound_physics_remastered")) {
            BannerboundAntiquity.LOGGER.info("Sound Physics present — drunk audio uses pitch wobble only.");
            return false;
        }
        try {
            long device = ALC10.alcGetContextsDevice(context);
            if (!ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
                BannerboundAntiquity.LOGGER.info("No ALC_EXT_EFX — drunk audio is pitch wobble only.");
                return false;
            }
            lowpass = EXTEfx.alGenFilters();
            EXTEfx.alFilteri(lowpass, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);

            reverbEffect = EXTEfx.alGenEffects();
            EXTEfx.alEffecti(reverbEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
            if (AL10.alGetError() == AL10.AL_NO_ERROR) {
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DECAY_TIME, 2.6F);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_GAIN, 0.32F);
                EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DIFFUSION, 1.0F);
                reverbSlot = EXTEfx.alGenAuxiliaryEffectSlots();
                EXTEfx.alAuxiliaryEffectSloti(reverbSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverbEffect);
            }

            echoEffect = EXTEfx.alGenEffects();
            EXTEfx.alEffecti(echoEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_ECHO);
            if (AL10.alGetError() == AL10.AL_NO_ERROR) {
                EXTEfx.alEffectf(echoEffect, EXTEfx.AL_ECHO_DELAY, 0.12F);
                EXTEfx.alEffectf(echoEffect, EXTEfx.AL_ECHO_FEEDBACK, 0.45F);
                echoSlot = EXTEfx.alGenAuxiliaryEffectSlots();
                EXTEfx.alAuxiliaryEffectSloti(echoSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, echoEffect);
            }
            AL10.alGetError(); // aux sends may be unavailable on this context — the muffle still works
            efxReady = true;
            BannerboundAntiquity.LOGGER.info("Drunk audio EFX ready (muffle{}{}).",
                reverbSlot != 0 ? " + reverb" : "", echoSlot != 0 ? " + echo" : "");
        } catch (Throwable t) {
            efxReady = false;
            BannerboundAntiquity.LOGGER.warn("Drunk audio EFX init failed; pitch wobble only.", t);
        }
        return efxReady;
    }

    private static int level() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? 0 : player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get());
    }

    /** A strong constant muffle while hungover (0 otherwise) — the morning-after dullness. */
    private static float hangover() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return 0.0F;
        }
        long until = mc.player.getData(BannerboundAntiquity.HANGOVER_UNTIL.get());
        return until > mc.level.getGameTime() ? 0.85F : 0.0F;
    }
}
