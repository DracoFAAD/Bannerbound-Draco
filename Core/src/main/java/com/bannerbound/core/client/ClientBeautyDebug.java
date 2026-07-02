package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client state for the beauty-debug overlay (toggled with the {@code B} key by default). While
 * enabled, {@link BeautyDebugHudLayer} draws the looked-at block's name and appeal score next to
 * the crosshair.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientBeautyDebug {
    /** Toggle keybind — rebindable in Controls, defaults to {@code B}. */
    public static final KeyMapping TOGGLE_KEY = new KeyMapping(
        "key.bannerbound.beauty_debug", GLFW.GLFW_KEY_B, "key.categories.bannerbound");

    private static boolean enabled = false;

    /** Latest server reply for the diminishing-returns query (see {@code RequestBlockAppealPayload}). */
    private static BlockPos resultPos;
    private static int resultQueuePos;
    private static boolean resultTracked;
    /** True when the latest reply came from a home-scope query (block is inside one of the
     *  requesting player's home selections). The overlay then labels the value as "Home appeal"
     *  and reads the per-home queue slot rather than the chunk's. */
    private static boolean resultInHouse;
    /** Server-resolved appeal of the looked-at block — already culture-adjusted (owning
     *  settlement's styles) and diminished for its queue slot, so the overlay shows it verbatim
     *  and every client agrees on the value. */
    private static float resultAppeal;

    private ClientBeautyDebug() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Stores the server's diminishing-returns reply for one block position. */
    public static void setResult(BlockPos pos, int queuePosition, boolean tracked, boolean inHouse,
                                 float appeal) {
        resultPos = pos;
        resultQueuePos = queuePosition;
        resultTracked = tracked;
        resultInHouse = inHouse;
        resultAppeal = appeal;
    }

    /** True if the latest server reply is for {@code pos}. */
    public static boolean hasResultFor(BlockPos pos) {
        return pos.equals(resultPos);
    }

    /** The looked-at block's 1-based queue slot for the latest reply (0 = not counted). */
    public static int resultQueuePos() {
        return resultQueuePos;
    }

    /** Whether the chunk of the latest reply has a scanned beauty record. */
    public static boolean resultTracked() {
        return resultTracked;
    }

    /** Whether the latest reply was a home-scope answer (block inside a home selection). */
    public static boolean resultInHouse() {
        return resultInHouse;
    }

    /** The server-resolved, viewer-independent appeal of the looked-at block (latest reply). */
    public static float resultAppeal() {
        return resultAppeal;
    }

    public static void toggle() {
        enabled = !enabled;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(
                enabled ? "bannerbound.beauty_debug.on" : "bannerbound.beauty_debug.off"), true);
        }
    }
}
