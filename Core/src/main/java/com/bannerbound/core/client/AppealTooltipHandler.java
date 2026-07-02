package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Appends a purple "Appeal: X" line to every block item's tooltip. The value is the appeal
 * resolved for the local player's settlement (base + culture-style overrides), synced via
 * {@link ClientBlockAppealState}, so it changes with the settlement's chosen style.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class AppealTooltipHandler {
    private AppealTooltipHandler() {
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof BlockItem blockItem)) return;
        // Disguised/unknown items get their tooltip rewritten by EraTooltipHandler — leave them.
        if (UnknownItemHelper.isUnknownForLocalPlayer(stack)) return;

        float appeal = ClientBlockAppealState.appealOf(blockItem.getBlock());
        event.getToolTip().add(Component.translatable("bannerbound.tooltip.appeal",
            String.format("%.2f", appeal)).withStyle(ChatFormatting.LIGHT_PURPLE));
    }
}
