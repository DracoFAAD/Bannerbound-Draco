package com.bannerbound.core.event;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.crisis.CrisisManager;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Generic vanilla-action triggers for data-authored crises. */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class CrisisEvents {
    private CrisisEvents() {
    }

    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || event.getOriginalStack().isEmpty()) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        if (settlement == null) return;
        String itemId = BuiltInRegistries.ITEM.getKey(event.getOriginalStack().getItem()).toString();
        CrisisManager.onItemObtained(server, settlement, itemId, event.getOriginalStack().getCount());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        if (settlement == null) return;
        String blockId = BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock()).toString();
        CrisisManager.onBlockPlaced(server, settlement, blockId);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        if (settlement == null) return;
        String blockId = BuiltInRegistries.BLOCK.getKey(event.getState().getBlock()).toString();
        CrisisManager.onBlockBroken(server, settlement, blockId);
    }

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getCrafting().isEmpty()) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        if (settlement == null) return;
        String itemId = BuiltInRegistries.ITEM.getKey(event.getCrafting().getItem()).toString();
        CrisisManager.onItemObtained(server, settlement, itemId, event.getCrafting().getCount());
        CrisisManager.onItemCrafted(server, settlement, itemId, event.getCrafting().getCount());
    }
}
