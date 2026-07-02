package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.entity.GroundDecalEntity;
import com.bannerbound.core.client.ClientResearchState;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side "which tracks belong to the same animal" highlight for the hunting tracker. Examining
 * (right-clicking) a footprint remembers the animal that left it; {@link GroundDecalRenderer} then
 * tints every still-active track from that same animal cyan so the trail reads as one continuous
 * path. Re-examining any track re-arms the tint (and can switch to a different animal).
 *
 * <p>The whole effect is gated behind the {@code hunting_instincts} research — without it,
 * {@link #examine} is a no-op and tracks render their normal colour. Holds a single "current
 * highlight" (one animal at a time), faded out over {@link #FADE_TICKS} since the last examine.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class FootprintHighlight {
    /** Research flag that lets a hunter read which tracks share an animal. Kept as a constant so the
     *  Research Tree Editor auto-discovers it (matches the {@code *FLAG*} scan convention). */
    public static final String FLAG_HUNTING_INSTINCTS = "bannerbound.hunting_instincts";
    /** Cyan tint holds then fades over this many ticks (~10s) — long enough to walk a trail. */
    private static final float FADE_TICKS = 200.0F;

    private static int groupId = -1;              // animal currently highlighted, -1 = none
    private static long startGameTime = Long.MIN_VALUE; // client game-time the highlight was (re)armed

    private FootprintHighlight() {}

    /**
     * Examining a track: if {@code hunting_instincts} is researched, light up every active track left
     * by the same animal in cyan. No research (or an ungrouped decal) → no-op.
     */
    public static void examine(GroundDecalEntity decal) {
        int group = decal.getGroupId();
        if (group < 0 || !ClientResearchState.hasFlag(FLAG_HUNTING_INSTINCTS)) {
            return;
        }
        groupId = group;
        startGameTime = decal.level().getGameTime();
    }

    /**
     * Cyan-tint strength in [0,1] for a track belonging to {@code group}: 0 when it isn't the
     * highlighted animal or the highlight has faded, fading linearly to 0 over {@link #FADE_TICKS}.
     * The renderer lerps the track colour white→cyan by this amount.
     */
    public static float strength(int group, long gameTime, float partialTick) {
        if (group < 0 || group != groupId) {
            return 0.0F;
        }
        float age = (gameTime - startGameTime) + partialTick;
        if (age < 0.0F || age >= FADE_TICKS) {
            return 0.0F;
        }
        return 1.0F - age / FADE_TICKS;
    }
}
