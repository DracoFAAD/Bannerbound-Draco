package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-only holder for the "editing drop-off location" mode. Entered when the server replies to
 * the Job tab's "Set drop location" button (see {@code OpenDropLocationEditPayload}); while active,
 * {@link DropLocationEditRenderer} draws a one-block wireframe at the looked-at block and an
 * action-bar prompt in the settlement color, and {@link com.bannerbound.core.event.DropLocationEditClick}
 * captures the right-click that marks the block.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class DropLocationEditState {
    private static int entityId = -1;
    private static Component name = Component.empty();
    private static Component jobTitle = Component.empty();
    private static int settlementRgb = 0xFFFFFF;
    private static boolean seed = false;

    private DropLocationEditState() {
    }

    public static boolean isActive() { return entityId != -1; }
    public static int entityId() { return entityId; }
    public static Component name() { return name; }
    public static Component jobTitle() { return jobTitle; }
    public static int settlementRgb() { return settlementRgb; }
    /** True when marking the farmer's seed source (vs the harvest drop-off). */
    public static boolean isSeed() { return seed; }

    public static void begin(int id, Component citizenName, Component title, int rgb, boolean isSeed) {
        entityId = id;
        name = citizenName;
        jobTitle = title;
        settlementRgb = rgb;
        seed = isSeed;
    }

    public static void clear() {
        entityId = -1;
    }
}
