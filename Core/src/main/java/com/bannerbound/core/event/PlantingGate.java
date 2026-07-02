package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.research.ResearchManager;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Blocks three vanilla "use the land" interactions until the player's settlement has researched
 * Agricultural Revolution (flag {@code bannerbound.allow_planting}):
 * <ul>
 *   <li>Right-clicking a sapling onto soil to plant it.</li>
 *   <li>Right-clicking a hoe on grass/dirt to till it into farmland.</li>
 *   <li>Right-clicking a known seed/crop item onto farmland to sow it.</li>
 * </ul>
 * Items themselves stay unlocked (the recipe is craftable) — only the placement gesture is gated.
 * Mirrors {@link PavingGate}'s pattern: cancel the event, push a red action-bar message to the
 * player. Unsettled players (no settlement) are also gated — same rule as paving.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class PlantingGate {
    private static final String FLAG = "bannerbound.allow_planting";

    private PlantingGate() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isCreative()) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        boolean isSapling = stack.is(ItemTags.SAPLINGS);
        boolean isSeed = isSeedItem(stack.getItem())
            && event.getLevel().getBlockState(event.getPos()).is(Blocks.FARMLAND);
        if (!isSapling && !isSeed) return;
        if (settlementAllows(player)) return;
        event.setCanceled(true);
        sendLockedMessage(player);
    }

    @SubscribeEvent
    public static void onHoeTill(BlockEvent.BlockToolModificationEvent event) {
        if (event.getItemAbility() != ItemAbilities.HOE_TILL) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (player.isCreative()) return;
        if (settlementAllows(player)) return;
        event.setCanceled(true);
        sendLockedMessage(player);
    }

    /** Vanilla seed-style items. Small literal set is fine for v1 — modded crops can be added
     *  later if they're called out. Excluding wheat/bread/etc. that aren't placed on farmland. */
    private static boolean isSeedItem(Item item) {
        return item == Items.WHEAT_SEEDS
            || item == Items.BEETROOT_SEEDS
            || item == Items.MELON_SEEDS
            || item == Items.PUMPKIN_SEEDS
            || item == Items.CARROT
            || item == Items.POTATO
            || item == Items.TORCHFLOWER_SEEDS
            || item == Items.PITCHER_POD;
    }

    private static boolean settlementAllows(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return true; // fail-open if no server context
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        return s != null && ResearchManager.hasFlag(s, FLAG);
    }

    private static void sendLockedMessage(ServerPlayer player) {
        player.displayClientMessage(
            Component.translatable("bannerbound.planting.locked").withStyle(ChatFormatting.RED),
            true);
    }
}
