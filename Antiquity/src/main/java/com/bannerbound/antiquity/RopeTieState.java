package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Client-side preview state: which tie point (post centre or gate upright) the local player is tying a
 * rope from. Drives the rope-to-hand preview line in {@code RopeTiePreviewRenderer}. (The anchor's
 * roped <em>model</em> is shown server-authoritatively via blockstate, not here, to avoid flicker.)
 * A single static is fine — only the one local player; never written server-side.
 */
@ApiStatus.Internal
public final class RopeTieState {
    private static RopeAnchor anchor;
    private static ResourceKey<Level> dimension;

    // The rope just tied by the local player, for the "zip taut" settle animation (order-normalized).
    private static RopeAnchor zipA;
    private static RopeAnchor zipB;
    private static long zipStartTick;

    private RopeTieState() {}

    /** Note that the rope between {@code a} and {@code b} was just tied, so the renderer can animate it
     *  snapping taut and settling. {@code gameTime} = the client level's current game time. */
    public static void recordZip(RopeAnchor a, RopeAnchor b, long gameTime) {
        if (a == null || b == null) {
            return;
        }
        boolean aFirst = a.compareTo(b) <= 0;
        zipA = (aFirst ? a : b).immutable();
        zipB = (aFirst ? b : a).immutable();
        zipStartTick = gameTime;
    }

    /** Zip-settle progress in [0,1] for the rope between {@code a} and {@code b}, or -1 if it isn't the
     *  just-tied rope (or the animation is over). */
    public static float zipProgress(RopeAnchor a, RopeAnchor b, long gameTime, float partial, float durationTicks) {
        if (zipA == null) {
            return -1.0F;
        }
        boolean match = (zipA.equals(a) && zipB.equals(b)) || (zipA.equals(b) && zipB.equals(a));
        if (!match) {
            return -1.0F;
        }
        float p = (gameTime - zipStartTick + partial) / durationTicks;
        return p >= 1.0F ? -1.0F : Math.max(0.0F, p);
    }

    public static void set(RopeAnchor a, ResourceKey<Level> dim) {
        anchor = a;
        dimension = dim;
    }

    public static void clear() {
        anchor = null;
        dimension = null;
    }

    public static RopeAnchor get() {
        return anchor;
    }

    public static boolean isAt(RopeAnchor a, ResourceKey<Level> dim) {
        return anchor != null && anchor.equals(a) && dim.equals(dimension);
    }
}
