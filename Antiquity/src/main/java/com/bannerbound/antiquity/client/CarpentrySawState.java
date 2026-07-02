package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Shared client-side state for the carpenter's-table saw minigame, written by
 * {@link WoodworkingTableSawScreen} (the input layer) and read by {@code WoodworkingTableRenderer} (the
 * in-world saw + log animation). Kept in a plain holder — deliberately NOT on the Screen — so the
 * block-entity renderer never has to reference a {@code Screen} subclass (a renderer pulling in GUI
 * classes during level rendering is both an anti-pattern and a class-load hazard). Mirrors how
 * {@code FletchingScreen} exposes its FOV statics, but split out because here a BER is the reader.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class CarpentrySawState {
    private CarpentrySawState() {
    }

    /** True while a saw minigame is open. */
    public static volatile boolean active = false;
    /** The table the active session belongs to (the renderer matches on this). */
    public static volatile BlockPos activePos = null;
    /** Saw blade vertical position, 0 (low) – 1 (high), tracking the mouse. */
    public static volatile float sawY = 0.5F;
    /** Overall saw progress, 0 – 1 — the log scrolls right by this much. */
    public static volatile float progress = 0.0F;
    /** Number of full saw strokes the batch asks for; used by the in-world progress readout. */
    public static volatile int strokesNeeded = 1;
    /** Completed whole strokes, capped to {@link #strokesNeeded}. */
    public static volatile int strokesDone = 0;
    /** Fraction through the current stroke, 0-1, used to add rhythm to the saw pose. */
    public static volatile float strokeProgress = 0.0F;
    /** True while the player holds the left button (drives the saw "biting" pose). */
    public static volatile boolean holding = false;
    /** Cardinal scene yaw captured when the minigame opens. */
    public static volatile float sceneYaw = 0.0F;
    /** A short feedback pulse when a stroke completes. */
    private static volatile long pulseUntilMs = 0L;
    /** Begins a session on {@code pos}, resetting the animation state. */
    public static void begin(BlockPos pos, int strokes, float yaw) {
        activePos = pos;
        sawY = 0.5F;
        progress = 0.0F;
        strokesNeeded = Math.max(1, strokes);
        strokesDone = 0;
        strokeProgress = 0.0F;
        holding = false;
        sceneYaw = yaw;
        pulseUntilMs = 0L;
        active = true;
    }

    /** Updates the progress values that the in-world renderer reads. */
    public static void updateProgress(double travelDone, double travelPerStroke) {
        double needed = Math.max(1.0, strokesNeeded * travelPerStroke);
        progress = (float) Mth.clamp(travelDone / needed, 0.0, 1.0);
        strokesDone = Math.min(strokesNeeded, (int) Math.floor(travelDone / travelPerStroke));
        strokeProgress = (float) ((travelDone % travelPerStroke) / travelPerStroke);
        sawY = 0.5F + 0.45F * (float) Math.sin(strokeProgress * Math.PI * 2.0);
    }

    /** Starts a small visual pulse in the saw renderer. */
    public static void pulse() {
        pulseUntilMs = System.currentTimeMillis() + 180L;
    }

    /** 0-1 pulse intensity for the saw renderer. */
    public static float pulseAmount() {
        long left = pulseUntilMs - System.currentTimeMillis();
        return left <= 0L ? 0.0F : Mth.clamp(left / 180.0F, 0.0F, 1.0F);
    }

    /** Clears the session (minigame closed). */
    public static void clear() {
        active = false;
        activePos = null;
        holding = false;
        progress = 0.0F;
        strokesNeeded = 1;
        strokesDone = 0;
        strokeProgress = 0.0F;
        sceneYaw = 0.0F;
        pulseUntilMs = 0L;
    }

    /** True if a session is active for {@code pos}. */
    public static boolean activeFor(BlockPos pos) {
        return active && pos.equals(activePos);
    }
}
