package com.bannerbound.core.command;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.network.OpenSettleScreenPayload;
import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class SettleCommand {
    private SettleCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("settle")
            .requires(s -> s.hasPermission(2))
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                SettlementData data = SettlementData.get(player.getServer().overworld());
                Settlement existing = data.getByPlayer(player.getUUID());
                if (existing != null) {
                    player.sendSystemMessage(Component.translatable("bannerbound.settle.error.already")
                        .withStyle(ChatFormatting.RED));
                    return 0;
                }
                if (com.bannerbound.core.api.settlement.SettlementManager.isAtMaxFactions(data)) {
                    player.sendSystemMessage(Component.translatable("bannerbound.settle.error.max_factions")
                        .withStyle(ChatFormatting.RED));
                    return 0;
                }
                int siteWarnings = com.bannerbound.core.territory.SettlementSiteAssessor.assessMask(
                    player.serverLevel(), player.blockPosition());
                PacketDistributor.sendToPlayer(player, new OpenSettleScreenPayload(siteWarnings));
                return 1;
            })
        );
    }
}
