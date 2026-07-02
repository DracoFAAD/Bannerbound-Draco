package com.bannerbound.core.world;

import java.util.ArrayDeque;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Home;
import com.bannerbound.core.api.settlement.Homes;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Staggered background re-validation of homes — the residence twin of {@link WorkshopRevalidator}.
 * Homes have no anchor block entity to tick, so this sweep keeps their status / bed count / appeal
 * fresh without anyone opening the panel: break a wall and the home flips to "not enclosed" (and
 * evicts its residents) within the cycle, not whenever you next click it.
 *
 * <p>Lag-safe by design: at most ONE home validates every {@link #TICKS_PER_VALIDATION} ticks
 * (round-robin over all settlements), homes in unloaded chunks are skipped for free, and a
 * summaries broadcast goes out only when a validation actually changed something. With N loaded
 * homes a full sweep takes N×{@value #TICKS_PER_VALIDATION} ticks — matching the workshops' cadence.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class HomeRevalidator {
    private static final int TICKS_PER_VALIDATION = 2;

    /** Pending (settlementId, homeId) pairs for the current sweep; rebuilt when drained. */
    private static final ArrayDeque<UUID[]> QUEUE = new ArrayDeque<>();
    private static int tickCounter;

    private HomeRevalidator() {
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % TICKS_PER_VALIDATION != 0) return;
        MinecraftServer server = event.getServer();
        ServerLevel sl = server.overworld();
        if (QUEUE.isEmpty()) {
            for (Settlement s : SettlementData.get(sl).all()) {
                for (Home h : s.homes().values()) {
                    QUEUE.add(new UUID[]{ s.id(), h.id() });
                }
            }
            if (QUEUE.isEmpty()) return;
        }
        UUID[] next = QUEUE.poll();
        Settlement settlement = SettlementData.get(sl).getById(next[0]);
        if (settlement == null) return;
        Home home = settlement.getHomeById(next[1]);
        if (home == null) return;
        if (!anyChunkLoaded(sl, home)) return; // unloaded → free skip, swept next cycle

        Home.Status oldStatus = home.status();
        int oldBeds = home.bedCount();
        var oldBeauty = home.cachedBeauty();
        Homes.validate(sl, home);
        if (home.status() != oldStatus || home.bedCount() != oldBeds
                || home.cachedBeauty() != oldBeauty) {
            SettlementData.get(sl).setDirty();
            SelectionBroadcaster.broadcastSummaries(server);
        }
    }

    /** True when any of the home's selection boxes sits in a loaded chunk (cheap guard so
     *  background validation never forces chunk loads). */
    private static boolean anyChunkLoaded(ServerLevel sl, Home home) {
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).findByHome(home.id())) {
            ChunkPos cp = new ChunkPos(sel.a());
            if (sl.hasChunk(cp.x, cp.z)) return true;
        }
        return false;
    }
}
