package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.PoisonedFoodData;
import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.core.client.ClientPopulationState;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Reveals a laced food's poison on its tooltip — but ONLY to players in the SAME settlement that
 * poisoned it (so your own people know which rations to avoid, while the enemy you're feeding it to
 * sees a perfectly normal apple). The poison data rides the stack everywhere; this is purely a
 * display gate, comparing the stored poisoner-settlement to the local player's synced settlement id.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class PoisonFoodTooltip {
    private PoisonFoodTooltip() {}

    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        PoisonedFoodData laced = event.getItemStack().get(BannerboundAntiquity.POISONED_FOOD.get());
        if (laced == null) {
            return;
        }
        LocalPlayer me = Minecraft.getInstance().player;
        if (me == null || laced.poisoner().isEmpty()) {
            return;
        }
        UUID poisoner;
        try {
            poisoner = UUID.fromString(laced.poisoner());
        } catch (IllegalArgumentException e) {
            return;
        }
        // Reveal only to the poisoner themselves, or to whoever currently shares their settlement.
        if (!me.getUUID().equals(poisoner) && !ClientPopulationState.isMember(poisoner)) {
            return; // looks like a clean apple to you
        }
        PoisonType type = PoisonType.fromId(laced.poisonId());
        Component name = Component.translatable("poison.bannerboundantiquity."
            + (type != null ? type.id() : laced.poisonId()));
        event.getToolTip().add(Component.translatable(
            "bannerboundantiquity.poisoned_food.tooltip", name, laced.dose())
            .withStyle(ChatFormatting.DARK_GREEN));
    }
}
