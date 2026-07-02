package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.codex.CodexManager;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Vanilla-action Chronicle triggers. */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class CodexEvents {
    private CodexEvents() {
    }

    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (event.getOriginalStack().isEmpty()) return;
        String itemId = BuiltInRegistries.ITEM.getKey(event.getOriginalStack().getItem()).toString();
        CodexManager.onItemObtained(player, itemId);
    }

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getCrafting().isEmpty()) return;
        String itemId = BuiltInRegistries.ITEM.getKey(event.getCrafting().getItem()).toString();
        CodexManager.onItemObtained(player, itemId);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockUsed(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(
            event.getLevel().getBlockState(event.getPos()).getBlock()).toString();
        CodexManager.onBlockUsed(player, blockId);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) return;
        String blockId = BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock()).toString();
        CodexManager.onBlockPlaced(player, blockId);
    }

    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        CodexManager.onAdvancement(player, event.getAdvancement().id().toString());
    }
}
