package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.vanilla.VanillaContentState;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Vanilla villager trading is permanently disabled in the Antiquity expansion — villages are now AI
 * city-states, traded with via the Town Hall diplomacy tab (after the Bartering research), not by
 * clicking individual villagers (see the CITY_STATES plan §1G). Right-clicking a villager cancels the
 * vanilla trade GUI and gives the villager's "can't trade" reaction (the unhappy head-shake + "no"
 * sound), like clicking a nitwit.
 *
 * <p>Only active when vanilla content is stripped ({@link VanillaContentState#isEnabled()} false —
 * always so under Antiquity). We never touch the villager itself (mod compatibility); we only block
 * the interaction. Name tags / other item interactions are left alone.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class VillagerTradeGate {

    private VillagerTradeGate() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (VanillaContentState.isEnabled()) return;
        if (!(event.getTarget() instanceof Villager villager)) return;
        // Leave name-tag naming alone; only the bare trade interaction is blocked.
        if (event.getItemStack().is(Items.NAME_TAG)) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
        if (!event.getLevel().isClientSide()) {
            Player player = event.getEntity();
            villager.setUnhappyCounter(40); // the vanilla "can't trade" head-shake / angry puff
            player.level().playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
    }
}
