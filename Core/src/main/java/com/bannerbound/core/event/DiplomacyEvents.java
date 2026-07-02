package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.DiplomacyManager;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundCore.MODID)
public final class DiplomacyEvents {
    private DiplomacyEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        DiplomacyManager.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DiplomacyManager.dropCarriedStandards(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DiplomacyManager.dropCarriedStandards(player);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DiplomacyManager.dropCarriedStandards(player);
        }
    }

    @SubscribeEvent
    public static void onItemEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity item) {
            DiplomacyManager.prepareStolenStandardItem(item);
        }
    }

    @SubscribeEvent
    public static void onItemExpire(ItemExpireEvent event) {
        if (DiplomacyManager.prepareStolenStandardItem(event.getEntity())) {
            event.addExtraLife(20 * 60);
        }
    }

    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            DiplomacyManager.onStolenStandardDropped(player, event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onItemPickupPre(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        ItemEntity item = event.getItemEntity();
        if (!DiplomacyManager.isStolenStandard(item.getItem())) return;
        DiplomacyManager.prepareStolenStandardItem(item);
        event.setCanPickup(DiplomacyManager.canPickupStolenStandard(player, item)
            ? TriState.TRUE : TriState.FALSE);
    }

    @SubscribeEvent
    public static void onItemPickupPost(ItemEntityPickupEvent.Post event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            DiplomacyManager.onStolenStandardPickedUp(player, event.getOriginalStack());
        }
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DiplomacyManager.removeStolenStandardsFromContainer(player, event.getContainer());
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!DiplomacyManager.isStolenStandard(player.getItemInHand(event.getHand()))) return;
        player.displayClientMessage(Component.translatable(
            "bannerbound.diplomacy.standard.no_place").withStyle(ChatFormatting.RED), true);
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!DiplomacyManager.isStolenStandard(player.getItemInHand(event.getHand()))) return;
        player.displayClientMessage(Component.translatable(
            "bannerbound.diplomacy.standard.no_place").withStyle(ChatFormatting.RED), true);
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (DiplomacyManager.tryScoreStandard(player, event.getPos())) {
            event.setCancellationResult(InteractionResult.CONSUME);
            event.setCanceled(true);
            return;
        }
        if (DiplomacyManager.isStolenStandard(player.getItemInHand(event.getHand()))) {
            player.displayClientMessage(Component.translatable(
                "bannerbound.diplomacy.standard.no_place").withStyle(ChatFormatting.RED), true);
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }
}
