package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Stops players from using, placing, equipping, attacking-with, or otherwise interacting with items
 * their bannerbound doesn't know yet. Effective known set = global starting items + this
 * settlement's research unlocks. No settlement = starting items only.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class UnknownItemBlocker {
    private UnknownItemBlocker() {
    }

    public static boolean isUnknownForPlayer(ServerPlayer player, Item item) {
        // Creative players understand everything — item gating is a survival-only constraint.
        // This funnels every server-side block (interactions, attacks, equip, crafting gate),
        // so the single check here exempts all of them at once.
        if (player.isCreative()) {
            return false;
        }
        // Single source of truth: the player's settlement knows starting items + its research
        // unlocks; no settlement → starting items only. Mirrors the server-side drop filter.
        Settlement settlement =
            com.bannerbound.core.api.research.SettlementDropFilter.settlementOf(player);
        return !com.bannerbound.core.api.research.ItemKnowledge.isKnown(settlement, item);
    }

    private static boolean isUnknown(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty() || player.isCreative()) {
            return false;
        }
        // Stack-aware: catches component-gated items (e.g. a modular arrow whose material the civ
        // hasn't researched) that the bare item-id check would wrongly pass.
        Settlement settlement =
            com.bannerbound.core.api.research.SettlementDropFilter.settlementOf(player);
        return !com.bannerbound.core.api.research.ItemKnowledge.isKnown(settlement, stack);
    }

    /**
     * Sweeps the player's armor + offhand slots: any item that's currently unknown to them
     * (e.g. iron armor after they disbanded their iron-researching settlement) is removed,
     * pushed into the main inventory, or dropped if the inventory is full.
     * Call this after any change that shrinks the player's effective known set
     * (disband, leave, age regression, etc.).
     */
    public static void unequipUnknownGear(ServerPlayer player) {
        boolean anyRemoved = false;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            if (!isUnknownForPlayer(player, stack.getItem())) continue;
            ItemStack copy = stack.copy();
            player.setItemSlot(slot, ItemStack.EMPTY);
            if (!player.getInventory().add(copy)) {
                player.drop(copy, false);
            }
            anyRemoved = true;
        }
        if (anyRemoved) {
            deny(player);
        }
    }

    private static void deny(ServerPlayer player) {
        player.sendSystemMessage(Component.translatable("bannerbound.unknown_item.action")
            .withStyle(ChatFormatting.RED));
    }

    private static boolean checkAndDeny(Player p, ItemStack stack, ICancellableEvent event) {
        if (!(p instanceof ServerPlayer sp)) {
            return false;
        }
        if (isUnknown(sp, stack)) {
            event.setCanceled(true);
            deny(sp);
            return true;
        }
        return false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        checkAndDeny(event.getEntity(), event.getItemStack(), event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        checkAndDeny(event.getEntity(), event.getItemStack(), event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        ItemStack held = event.getItemStack();
        if (held.isEmpty()) {
            return;
        }
        checkAndDeny(event.getEntity(), held, event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        checkAndDeny(event.getEntity(), event.getItemStack(), event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        checkAndDeny(event.getEntity(), event.getItemStack(), event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        ItemStack weapon = sp.getItemInHand(InteractionHand.MAIN_HAND);
        if (isUnknown(sp, weapon)) {
            event.setCanceled(true);
            deny(sp);
        }
    }

    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        ItemStack to = event.getTo();
        if (to.isEmpty()) {
            return;
        }
        if (!isUnknownForPlayer(sp, to.getItem())) {
            return;
        }
        ItemStack copy = to.copy();
        sp.setItemSlot(event.getSlot(), ItemStack.EMPTY);
        if (!sp.getInventory().add(copy)) {
            sp.drop(copy, false);
        }
        deny(sp);
    }
}
