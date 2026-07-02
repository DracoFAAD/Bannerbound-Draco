package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side hold for the settlement's current unrest warnings (see
 * {@link com.bannerbound.core.network.SettlementWarningsPayload}). Pushed by the server alongside
 * the town-hall open; read by {@code TownHallScreen}'s Main tab. An empty list means "all clear".
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientSettlementWarningsState {
    private static volatile List<Component> warnings = List.of();

    private ClientSettlementWarningsState() {}

    public static void set(List<Component> newWarnings) {
        warnings = newWarnings == null ? List.of() : List.copyOf(newWarnings);
    }

    public static List<Component> get() { return warnings; }

    /** Wipe on disconnect so stale warnings don't carry into the next world. */
    public static void clear() { warnings = List.of(); }
}
