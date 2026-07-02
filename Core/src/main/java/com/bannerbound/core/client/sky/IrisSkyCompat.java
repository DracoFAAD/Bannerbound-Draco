package com.bannerbound.core.client.sky;

import java.lang.reflect.Method;

/**
 * RETIRED — no longer called by {@link FaithSkyRenderer}. Kept as documentation of the approach
 * that the post-terrain ({@code RenderLevelStageEvent.AFTER_WEATHER}) render-flow superseded.
 * <p>
 * History: when the faith sky drew inside {@code renderSky} (before terrain), Iris overrode our
 * {@code POSITION_COLOR} shader with its world program ({@code gbuffers_basic}), which transforms
 * vertices by the captured camera modelview ({@code gbufferModelView}) — but our vertices already
 * baked the frustum/camera matrix, so the camera rotation applied twice and the whole sky swam with
 * the camera. Vanilla's own stars escape this because they draw in the {@code STARS} phase, where
 * Iris binds its SKY program (per-draw modelview). This class force-set the {@code STARS} phase
 * around {@code endBatch} so Iris treated our geometry like vanilla stars.
 * <p>
 * Why it is no longer used: the celestial layer moved POST-terrain. There we set the global modelview
 * ourselves and draw against the real depth buffer, so the double-transform cannot occur AND we
 * deliberately want Iris's depth-testing world program (not its sky pass, which would skip the
 * terrain depth test and resurrect the stars-through-terrain bug). All of this is still reflective —
 * Iris remains a soft dependency — should a future iteration need it again.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris (1.21.1 branch)</a>
 */
@SuppressWarnings("unused") // retired; see class javadoc
final class IrisSkyCompat {
    private static boolean initialized;
    private static boolean usable;
    private static Object irisApi;          // net.irisshaders.iris.api.v0.IrisApi instance
    private static Method isShaderPackInUse; // IrisApi#isShaderPackInUse()
    private static Method setOverridePhase;  // GbufferPrograms#setOverridePhase(WorldRenderingPhase)
    private static Object phaseStars;        // WorldRenderingPhase.STARS

    private IrisSkyCompat() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void init() {
        initialized = true;
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisApi = apiClass.getMethod("getInstance").invoke(null);
            isShaderPackInUse = apiClass.getMethod("isShaderPackInUse");

            Class<?> gbufferPrograms = Class.forName("net.irisshaders.iris.layer.GbufferPrograms");
            Class<?> phaseEnum = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPhase");
            setOverridePhase = gbufferPrograms.getMethod("setOverridePhase", phaseEnum);
            phaseStars = Enum.valueOf((Class<? extends Enum>) phaseEnum, "STARS");

            usable = true;
        } catch (Throwable absentOrChanged) {
            // Iris not installed, or its API/internals shifted — plain vanilla path is fine.
            usable = false;
        }
    }

    /**
     * If an Iris shaderpack is active, switch Iris to its sky shader for the upcoming draw.
     *
     * @return {@code true} when the override was applied (caller must pair it with {@link #end()})
     */
    static boolean begin() {
        if (!initialized) {
            init();
        }
        if (!usable) {
            return false;
        }
        try {
            if (!Boolean.TRUE.equals(isShaderPackInUse.invoke(irisApi))) {
                return false;
            }
            setOverridePhase.invoke(null, phaseStars);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Clears the phase override set by {@link #begin()}. */
    static void end() {
        if (!usable) {
            return;
        }
        try {
            setOverridePhase.invoke(null, new Object[]{null});
        } catch (Throwable ignored) {
            // Best effort — a failed reset only affects this frame's leftover override.
        }
    }
}
