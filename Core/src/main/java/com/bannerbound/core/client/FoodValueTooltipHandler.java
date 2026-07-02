package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Appends a green "Food value: X" line to any item the {@link ClientFoodValueState} table
 * recognises as food. Silent for items with no food value (no line shown). Sits parallel to
 * {@link AppealTooltipHandler}; same {@code Dist.CLIENT} mod-bus subscriber pattern.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class FoodValueTooltipHandler {
    private FoodValueTooltipHandler() {
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        float value = ClientFoodValueState.effectiveValue(stack);
        if (value <= 0f) return;
        event.getToolTip().add(Component.translatable("bannerbound.tooltip.food_value",
            String.format("%.2f", value)).withStyle(ChatFormatting.GREEN));
    }
}
