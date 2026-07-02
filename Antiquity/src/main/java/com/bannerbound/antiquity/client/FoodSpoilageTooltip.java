package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.FoodSpoilage;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Shows a perishable food's freshness on its tooltip as a coloured word — {@code Fresh} (green) or
 * {@code Bland} (yellow, noting the halved food value) — plus a "Salted" line once preserved. Reads
 * only the synced {@link FoodSpoilage} component, so it needs nothing server-side. Items not yet
 * stamped (e.g. fresh from the creative menu) carry no component and show nothing until they enter a
 * real inventory. Fully spoiled food is a separate {@code spoiled_food} item, not a freshness level.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class FoodSpoilageTooltip {
    private FoodSpoilageTooltip() {}

    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        FoodSpoilage fs = event.getItemStack().get(BannerboundAntiquity.FOOD_SPOILAGE.get());
        if (fs == null) return;

        if (fs.isBland()) {
            event.getToolTip().add(Component.translatable("bannerboundantiquity.spoilage.bland")
                .withStyle(ChatFormatting.YELLOW));
        } else {
            event.getToolTip().add(Component.translatable("bannerboundantiquity.spoilage.fresh")
                .withStyle(ChatFormatting.GREEN));
        }
        if (fs.salted()) {
            event.getToolTip().add(Component.translatable("bannerboundantiquity.spoilage.salted")
                .withStyle(ChatFormatting.AQUA));
        }
    }
}
