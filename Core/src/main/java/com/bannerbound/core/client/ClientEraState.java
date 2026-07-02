package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Era;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Tracks the local player's settlement era and the world era. Used only for display now â€”
 * item knowledge is governed by {@link ClientStartingItems} + {@link ClientResearchState}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientEraState {
    private static volatile Era playerEra = Era.ANCIENT;
    private static volatile Era worldEra = Era.ANCIENT;
    /** World year computed server-side from the leading civ's era + per-era research progress.
     *  Negative = BC, positive = AD. Display-only; gameplay never reads this. */
    private static volatile int worldYear = -100000;

    private ClientEraState() {
    }

    public static Era getPlayerEra() {
        return playerEra;
    }

    public static Era getWorldEra() {
        return worldEra;
    }

    public static int getWorldYear() {
        return worldYear;
    }

    public static void setEras(int playerOrd, int worldOrd, int year) {
        playerEra = Era.fromOrdinalOrDefault(playerOrd);
        worldEra = Era.fromOrdinalOrDefault(worldOrd);
        worldYear = year;
    }
}
