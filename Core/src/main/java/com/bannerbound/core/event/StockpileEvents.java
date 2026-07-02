package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.block.StockpileBlock;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Server-side place hook for the Stockpile block: when one is placed, register a fresh
 * {@link com.bannerbound.core.api.settlement.Stockpile} on the settlement owning the chunk and stash
 * its id on the BE (mirrors {@code HousingEvents.onHousePlace}). Enclosure validity is then kept up
 * to date by the BE's periodic scan, so no per-edit hook is needed for v1.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class StockpileEvents {
    private StockpileEvents() {}

    @SubscribeEvent
    public static void onStockpilePlace(BlockEvent.EntityPlaceEvent event) {
        if (!event.getPlacedBlock().is(BannerboundCore.STOCKPILE.get())) return;
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        StockpileBlock.registerOnPlace(sl, event.getPos());
    }

    /**
     * If the settlement's marked preferred-storage depot block is broken, clear the marking so the
     * Labor tab no longer points at a depot that's gone. Runs at LOWEST priority so a break cancelled
     * by chunk protection doesn't wrongly clear it.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPreferredStorageBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        MinecraftServer server = sl.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement owner = data.getByChunk(new ChunkPos(event.getPos()).toLong());
        if (owner != null && event.getPos().equals(owner.preferredStoragePos())) {
            owner.setPreferredStoragePos(null);
            data.setDirty();
            SettlementManager.broadcastLaborState(server, owner);
        }
    }
}
