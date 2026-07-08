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

/**
 * Bridges vanilla player actions (item pickup/craft, block use/place, advancement earn) into
 * {@link CodexManager}, which fires the matching Chronicle codex triggers. Server-side only.
 * Block use/place run at LOWEST priority and skip cancelled events so a protection cancel
 * (e.g. ClaimProtectionEvents) settles first and a denied action does not count as done.
 * The pickup/craft events miss creative-menu grabs, /give, and container looting, so a
 * once-per-second sweep also fires item_obtained for any trigger-watched item found in an
 * online player's inventory - "obtained" means "first time you have it", matching the vanilla
 * inventory_changed advancement semantic. The sweep only scans items that some entry unlock or
 * tutorial popup trigger actually names, and each (player, item) pair fires once per session.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class CodexEvents {
    private static final java.util.Map<java.util.UUID, java.util.Set<String>> SWEPT_ITEMS =
        new java.util.HashMap<>();
    private static int sweepTick;

    private CodexEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (++sweepTick % 20 != 0) return;
        java.util.Set<String> watched = watchedItems();
        if (watched.isEmpty()) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            java.util.Set<String> done =
                SWEPT_ITEMS.computeIfAbsent(player.getUUID(), id -> new java.util.HashSet<>());
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                var stack = player.getInventory().getItem(slot);
                if (stack.isEmpty()) continue;
                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (watched.contains(itemId) && done.add(itemId)) {
                    CodexManager.onItemObtained(player, itemId);
                }
            }
        }
    }

    private static java.util.Set<String> watchedItems() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (com.bannerbound.core.codex.CodexEntry entry
                : com.bannerbound.core.codex.CodexEntryLoader.getAll().values()) {
            collectItems(entry.unlock(), out);
        }
        for (com.bannerbound.core.codex.TutorialPopup popup
                : com.bannerbound.core.codex.TutorialPopupLoader.getAll().values()) {
            collectItems(popup.trigger(), out);
        }
        return out;
    }

    private static void collectItems(com.bannerbound.core.codex.CodexUnlockRule rule,
                                     java.util.Set<String> out) {
        for (com.bannerbound.core.codex.CodexCondition condition : rule.conditions()) {
            if (!"item_obtained".equalsIgnoreCase(condition.type())) continue;
            String item = condition.item().isEmpty() ? condition.id() : condition.item();
            if (!item.isEmpty()) out.add(item);
        }
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

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        CodexManager.onCustom(player, "dimension_entered", event.getTo().location().toString());
    }
}
