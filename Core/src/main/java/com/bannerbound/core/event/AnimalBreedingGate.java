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
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Gates animal breeding behind the {@code bannerbound.allow_animal_breeding} flag.
 * Even if the player has wheat (a starting item), they can't use it to push an adult animal
 * into love mode unless their settlement has researched a node that grants the flag.
 *
 * The check fires when the player right-clicks an adult, non-loving animal with its breeding food.
 * Other animal interactions (healing wolves, taming horses, etc.) aren't blocked.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class AnimalBreedingGate {
    public static final String FLAG = "bannerbound.allow_animal_breeding";

    private AnimalBreedingGate() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        if (sp.isCreative()) {
            return;
        }
        if (!(event.getTarget() instanceof Animal animal)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !animal.isFood(stack)) {
            return;
        }
        // Only intervene when the food would actually trigger breeding: adult animal, not
        // already in love. (Babies eat to grow faster; loving animals are already committed.)
        if (animal.getAge() != 0 || animal.isInLove()) {
            return;
        }

        if (hasBreedingFlag(sp)) {
            return;
        }

        event.setCanceled(true);
        sp.sendSystemMessage(Component.translatable("bannerbound.feature.cant_do_yet")
            .withStyle(ChatFormatting.RED));
    }

    private static boolean hasBreedingFlag(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        try {
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(player.getUUID());
            return ResearchManager.hasFlag(settlement, FLAG);
        } catch (Exception ex) {
            return false;
        }
    }
}
