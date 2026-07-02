package com.bannerbound.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.bannerbound.core.client.sky.ClientSkyState;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;

/**
 * Suppresses VANILLA's star pass so the faith sky can take its place (FAITH_PLAN — "the heavens
 * wheel once a year"): Bannerbound renders every star itself on the yearly-rotating sphere, so
 * vanilla's daily-rotating stars would shear apart from ours.
 * <p>
 * The {@code @Redirect} returns 0 for {@code getStarBrightness} so vanilla's own star draw block
 * inside {@code renderSky} is skipped entirely. The faith sky itself is NO LONGER drawn here — it
 * moved to {@link com.bannerbound.core.client.sky.FaithSkyRenderer}'s
 * {@code RenderLevelStageEvent.AFTER_WEATHER} handler. Drawing inside {@code renderSky} happened
 * BEFORE terrain, so the depth buffer was empty and Iris's deferred pipeline composited the
 * celestial geometry without testing it against terrain depth — stars showed through hills under a
 * shaderpack. Drawing post-terrain against the populated depth buffer (depth-test LEQUAL) lets
 * terrain occlude the stars in BOTH the vanilla and Iris paths.
 * <p>
 * Guarded: until the sky seed has synced (or on servers/clients without the data), vanilla stars
 * render untouched.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Redirect(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F"
        )
    )
    private float bannerbound$suppressVanillaStars(ClientLevel level, float partialTick) {
        if (ClientSkyState.field() != null) {
            return 0.0F;
        }
        return level.getStarBrightness(partialTick);
    }
}
