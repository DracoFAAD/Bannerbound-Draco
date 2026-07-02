package com.bannerbound.core.event;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.InsightManager;
import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Vanilla player insight hooks for the event-driven trigger types. {@code obtain_item} is NOT here —
 * it is a holdings poll ({@link InsightManager#tickLevels}) that reads what the settlement actually has
 * (storage + members' inventories), so it needs no per-action hook and never stamps items.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
public final class InsightEvents {
    private InsightEvents() {}

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        Settlement settlement = SettlementDropFilter.settlementOf(event.getEntity());
        InsightManager.recordEvent(level.getServer(), settlement, "place_block",
            InsightManager.matcherFor(event.getPlacedBlock().getBlock()), 1);
    }

    @SubscribeEvent
    public static void onAnimalBred(BabyEntitySpawnEvent event) {
        if (!InsightManager.isTracked("breed_animal")) return;
        if (!(event.getCausedByPlayer() instanceof ServerPlayer player) || event.getChild() == null) return;
        Settlement settlement = SettlementDropFilter.settlementOf(player);
        InsightManager.recordEvent(player.getServer(), settlement, "breed_animal",
            InsightManager.matcherFor(event.getChild().getType()), 1);
    }
}
