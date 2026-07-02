package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.SettlementFoodWarningPayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side hold for the latest settlement food-warning level (see
 * {@link SettlementFoodWarningPayload}). Read by {@code SettlementFoodWarningHudLayer}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientFoodWarningState {
    private static volatile int level = SettlementFoodWarningPayload.LEVEL_OK;

    private ClientFoodWarningState() {}

    public static void set(int newLevel) { level = newLevel; }

    public static int level() { return level; }

    /** Wipe on disconnect so a stale banner doesn't carry into the next world. */
    public static void clear() { level = SettlementFoodWarningPayload.LEVEL_OK; }
}
