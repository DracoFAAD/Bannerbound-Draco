package com.bannerbound.core.world;

import java.util.ArrayDeque;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Staggered background re-validation of workshops so status drift becomes visible without anyone
 * opening a menu — the war case: an enemy smashes your fletchery's chest and the floating label
 * flips to "Invalid — needs repair" within the cycle, not whenever you next click it.
 *
 * <p>Lag-safe by design: at most ONE workshop validates every {@link #TICKS_PER_VALIDATION} ticks
 * (round-robin over all settlements), workshops in unloaded chunks are skipped for free, and the
 * (summaries-only) broadcast goes out ONLY when a validation actually changed something. With N
 * loaded workshops a full sweep takes N×{@value #TICKS_PER_VALIDATION} ticks — 100 workshops ≈
 * 10 s, matching the houses' own 10 s validation cadence.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class WorkshopRevalidator {
    private static final int TICKS_PER_VALIDATION = 2;

    /** Pending (settlementId, workshopId) pairs for the current sweep; rebuilt when drained. */
    private static final ArrayDeque<UUID[]> QUEUE = new ArrayDeque<>();
    private static int tickCounter;

    private WorkshopRevalidator() {
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % TICKS_PER_VALIDATION != 0) return;
        MinecraftServer server = event.getServer();
        ServerLevel sl = server.overworld();
        if (QUEUE.isEmpty()) {
            for (Settlement s : SettlementData.get(sl).all()) {
                for (Workshop w : s.workshops().values()) {
                    QUEUE.add(new UUID[]{ s.id(), w.id() });
                }
            }
            if (QUEUE.isEmpty()) return;
        }
        UUID[] next = QUEUE.poll();
        Settlement settlement = SettlementData.get(sl).getById(next[0]);
        if (settlement == null) return;
        Workshop workshop = settlement.getWorkshop(next[1]);
        if (workshop == null) return;
        if (!anyChunkLoaded(sl, workshop)) return; // unloaded → free skip, swept next cycle

        Workshop.Status oldStatus = workshop.status();
        int oldCapacity = workshop.capacity();
        var oldAppeal = workshop.cachedAppealBeauty();
        Workshops.validate(sl, workshop);
        // Appeal tier counts as a change too — decorating (or trashing) a workshop must reach the
        // floating labels within the sweep, not only when a selection box happens to change.
        if (workshop.status() != oldStatus || workshop.capacity() != oldCapacity
                || workshop.cachedAppealBeauty() != oldAppeal) {
            SettlementData.get(sl).setDirty();
            SelectionBroadcaster.broadcastSummaries(server);
        }
    }

    /** True when any of the workshop's selection boxes sits in a loaded chunk (cheap guard so
     *  background validation never forces chunk loads). */
    private static boolean anyChunkLoaded(ServerLevel sl, Workshop workshop) {
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id())) {
            ChunkPos cp = new ChunkPos(sel.a());
            if (sl.hasChunk(cp.x, cp.z)) return true;
        }
        return false;
    }
}
