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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Blocks the vanilla shovel-flatten-to-path interaction until the player's settlement has
 * researched Paving (flag {@code bannerbound.allow_paving}). Pre-research, right-clicking grass
 * with a shovel does nothing — even if shovels are otherwise unlocked.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class PavingGate {
    private PavingGate() {
    }

    @SubscribeEvent
    public static void onToolModify(BlockEvent.BlockToolModificationEvent event) {
        if (event.getItemAbility() != ItemAbilities.SHOVEL_FLATTEN) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (player.isCreative()) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement != null && ResearchManager.hasFlag(settlement, "bannerbound.allow_paving")) {
            return;
        }
        // No settlement or no paving flag — cancel the modification.
        event.setCanceled(true);
        player.displayClientMessage(
            Component.translatable("bannerbound.paving.locked").withStyle(ChatFormatting.RED),
            true);
    }
}
