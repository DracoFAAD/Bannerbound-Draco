package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Step 15 — recompute regent state on player presence changes.
 *
 * <p>The Chiefdom regency model says "the regent is the least-resented online member while
 * the chief is offline." Those two states change on every login + logout, so we drive
 * {@link SettlementManager#recomputeRegent} from both events for every Chiefdom in the world
 * (cheap — there's typically one Chiefdom and the recompute is O(citizens + members)).
 *
 * <p>{@link com.bannerbound.core.api.settlement.ImmigrationManager#tickAll} also calls
 * {@code recomputeRegent} periodically as a fallback heartbeat — covers the edge case where
 * the events miss (rare, but happens during server crash recovery).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class RegencyEvents {
    private RegencyEvents() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        recomputeForAllChiefdoms(event.getEntity() instanceof ServerPlayer sp ? sp : null);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        recomputeForAllChiefdoms(event.getEntity() instanceof ServerPlayer sp ? sp : null);
    }

    /** Iterate every Chiefdom and recompute its regent. We don't filter to "settlements the
     *  logging player is a member of" because the regent depends on every online member's
     *  citizen-resentment totals — even a non-member logging in could shift those totals
     *  through prior interactions. Cheap: typical world has a small number of settlements. */
    private static void recomputeForAllChiefdoms(ServerPlayer source) {
        if (source == null) return;
        MinecraftServer server = source.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        SettlementData data = SettlementData.get(overworld);
        for (Settlement s : data.all()) {
            if (s.governmentType() != Settlement.Government.CHIEFDOM) continue;
            SettlementManager.recomputeRegent(server, s);
        }
    }
}
