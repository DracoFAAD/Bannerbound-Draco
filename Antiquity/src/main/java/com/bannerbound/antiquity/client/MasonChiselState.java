package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Shared client-side state for the mason's-bench chisel-strike minigame, written by
 * {@link MasonChiselScreen} (the input layer) and read by {@link MasonsBenchRenderer} (the in-world
 * chisel + stone animation). Kept in a plain holder — deliberately NOT on the Screen — so the
 * block-entity renderer never references a {@code Screen} subclass. Mirrors {@code CarpentrySawState},
 * but models discrete timed STRIKES rather than continuous sawing travel.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class MasonChiselState {
    private MasonChiselState() {
    }

    /** True while a chisel minigame is open. */
    public static volatile boolean active = false;
    /** The bench the active session belongs to (the renderer matches on this). */
    public static volatile BlockPos activePos = null;
    /** Overall progress, 0 – 1 (struck strikes / needed). */
    public static volatile float progress = 0.0F;
    /** Number of strikes the batch asks for. */
    public static volatile int strikesNeeded = 1;
    /** Landed strikes, capped to {@link #strikesNeeded}. */
    public static volatile int strikesDone = 0;
    /** Chisel vertical pose, 0 (raised) – 1 (driven down), animated per strike. */
    public static volatile float toolY = 0.0F;
    /** Cardinal scene yaw captured when the minigame opens. */
    public static volatile float sceneYaw = 0.0F;
    /** A short feedback pulse when a strike lands. */
    private static volatile long pulseUntilMs = 0L;
    /** When the current strike's down-stroke animation ends (drives {@link #toolY}). */
    private static volatile long strikeAnimUntilMs = 0L;

    /** Begins a session on {@code pos}, resetting the animation state. */
    public static void begin(BlockPos pos, int strikes, float yaw) {
        activePos = pos;
        progress = 0.0F;
        strikesNeeded = Math.max(1, strikes);
        strikesDone = 0;
        toolY = 0.0F;
        sceneYaw = yaw;
        pulseUntilMs = 0L;
        strikeAnimUntilMs = 0L;
        active = true;
    }

    /** Records a landed strike and starts its down-stroke animation + feedback pulse. */
    public static void strike() {
        strikesDone = Math.min(strikesNeeded, strikesDone + 1);
        progress = (float) strikesDone / Math.max(1, strikesNeeded);
        pulseUntilMs = System.currentTimeMillis() + 180L;
        strikeAnimUntilMs = System.currentTimeMillis() + 160L;
    }

    /** Advances {@link #toolY} from the most recent strike's clock — call each render frame. */
    public static void animate() {
        long left = strikeAnimUntilMs - System.currentTimeMillis();
        if (left <= 0L) {
            toolY = 0.0F;
            return;
        }
        // Quick drive-down then ease back up over 160ms.
        float f = 1.0F - Mth.clamp(left / 160.0F, 0.0F, 1.0F); // 0 at start → 1 at end
        toolY = (float) Math.sin(f * Math.PI); // 0 → 1 (down) → 0 (up)
    }

    /** 0-1 pulse intensity for the renderer. */
    public static float pulseAmount() {
        long left = pulseUntilMs - System.currentTimeMillis();
        return left <= 0L ? 0.0F : Mth.clamp(left / 180.0F, 0.0F, 1.0F);
    }

    /** Clears the session (minigame closed). */
    public static void clear() {
        active = false;
        activePos = null;
        progress = 0.0F;
        strikesNeeded = 1;
        strikesDone = 0;
        toolY = 0.0F;
        sceneYaw = 0.0F;
        pulseUntilMs = 0L;
        strikeAnimUntilMs = 0L;
    }

    /** True if a session is active for {@code pos}. */
    public static boolean activeFor(BlockPos pos) {
        return active && pos.equals(activePos);
    }
}
