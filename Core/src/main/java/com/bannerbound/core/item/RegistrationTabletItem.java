package com.bannerbound.core.item;

import java.util.List;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.SettlementManager;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public class RegistrationTabletItem extends Item {
    public RegistrationTabletItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.success(stack);
        }
        String settlementName = stack.get(BannerboundCore.SETTLEMENT_REF.get());
        if (settlementName == null) {
            serverPlayer.sendSystemMessage(Component.translatable("bannerbound.tablet.error.blank")
                .withStyle(ChatFormatting.RED));
            return InteractionResultHolder.fail(stack);
        }
        SettlementManager.JoinResult result = SettlementManager.tryJoin(serverPlayer, settlementName);
        switch (result) {
            case OK -> {
                Integer charges = stack.get(BannerboundCore.TABLET_CHARGES.get());
                int remaining = (charges == null ? 1 : charges) - 1;
                if (remaining <= 0) {
                    stack.shrink(1);
                } else {
                    stack.set(BannerboundCore.TABLET_CHARGES.get(), remaining);
                }
            }
            case ALREADY_IN_SETTLEMENT -> serverPlayer.sendSystemMessage(
                Component.translatable("bannerbound.join.error.already_in_settlement")
                    .withStyle(ChatFormatting.RED));
            case NOT_FOUND -> serverPlayer.sendSystemMessage(
                Component.translatable("bannerbound.tablet.error.settlement_gone", settlementName)
                    .withStyle(ChatFormatting.RED));
        }
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String settlementName = stack.get(BannerboundCore.SETTLEMENT_REF.get());
        if (settlementName != null) {
            tooltip.add(Component.translatable("bannerbound.tablet.tooltip", settlementName)
                .withStyle(ChatFormatting.GRAY));
            Integer charges = stack.get(BannerboundCore.TABLET_CHARGES.get());
            Integer maxCharges = stack.get(BannerboundCore.TABLET_MAX_CHARGES.get());
            if (charges != null) {
                int max = maxCharges != null ? maxCharges : charges;
                tooltip.add(Component.translatable("bannerbound.tablet.tooltip.charges", charges, max)
                    .withStyle(ChatFormatting.DARK_GRAY));
            }
        } else {
            tooltip.add(Component.translatable("bannerbound.tablet.tooltip.blank")
                .withStyle(ChatFormatting.DARK_GRAY));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
