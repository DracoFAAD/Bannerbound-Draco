package com.bannerbound.core.client;

import com.bannerbound.core.network.FaithStatePayload;

import org.jetbrains.annotations.ApiStatus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the player's settlement faith state ({@link FaithStatePayload},
 * pushed once per second + on changes). Read by the town hall stats panel (devotion
 * line) and the Faith button/screens.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientFaithState {
    private static boolean hasFaith;
    private static String faithName = "";
    private static int pathOrdinal;
    private static int memberSettlements;
    private static double devotionStored;
    private static double devotionPerSecond;
    private static boolean choiceWindowOpen;

    private ClientFaithState() {
    }

    public static void replace(FaithStatePayload payload) {
        hasFaith = payload.hasFaith();
        faithName = payload.faithName();
        pathOrdinal = payload.pathOrdinal();
        memberSettlements = payload.memberSettlements();
        devotionStored = payload.devotionStored();
        devotionPerSecond = payload.devotionPerSecond();
        choiceWindowOpen = payload.choiceWindowOpen();
    }

    public static void clear() {
        hasFaith = false;
        faithName = "";
        pathOrdinal = 0;
        memberSettlements = 0;
        devotionStored = 0;
        devotionPerSecond = 0;
        choiceWindowOpen = false;
    }

    public static boolean hasFaith() {
        return hasFaith;
    }

    public static String faithName() {
        return faithName;
    }

    public static int pathOrdinal() {
        return pathOrdinal;
    }

    public static int memberSettlements() {
        return memberSettlements;
    }

    public static double devotionStored() {
        return devotionStored;
    }

    public static double devotionPerSecond() {
        return devotionPerSecond;
    }

    public static boolean choiceWindowOpen() {
        return choiceWindowOpen;
    }
}
