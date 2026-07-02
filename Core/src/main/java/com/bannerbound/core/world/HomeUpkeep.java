package com.bannerbound.core.world;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Home;
import com.bannerbound.core.api.settlement.HomeDemand;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Once-per-in-game-day home upkeep: each valid, occupied home eats one day's worth of its active
 * luxuries ({@link HomeDemand#consumeDaily}) out of its own pantry. Fires at the day boundary
 * (dawn), so luxuries are an ongoing economic sink — a stocked pantry drains over days and the
 * demand lapses until the stocker (later, a market) refills it.
 *
 * <p>Lag-safe: the per-tick work is a single day-index compare; the once-a-day pass skips homes in
 * unloaded chunks (no force-load).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class HomeUpkeep {
    private static long lastDay = Long.MIN_VALUE;

    private HomeUpkeep() {
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel sl = event.getServer().overworld();
        long day = sl.getDayTime() / 24_000L;
        if (day == lastDay) return;
        if (lastDay == Long.MIN_VALUE) { // first observed tick (world load) — set baseline, don't fire
            lastDay = day;
            return;
        }
        lastDay = day;
        for (Settlement s : SettlementData.get(sl).all()) {
            if (s.isDormant()) continue; // frozen "in amber" — no luxury drain while all offline
            for (Home h : s.homes().values()) {
                if (h.status() != Home.Status.VALID || h.residents().isEmpty()) continue;
                ChunkPos cp = new ChunkPos(h.pos());
                if (!sl.hasChunk(cp.x, cp.z)) continue; // unloaded → skip, no force-load
                HomeDemand.consumeDaily(sl, s, h);
            }
        }
    }
}
