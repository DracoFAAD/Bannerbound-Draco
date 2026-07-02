package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.vanilla.VanillaContentState;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Wandering traders (and their trader llamas) no longer appear in the Antiquity expansion — vanilla
 * trading is replaced by the city-state diplomacy/trade system (see the CITY_STATES plan §1G).
 *
 * <p>Cancels them at {@link EntityJoinLevelEvent} rather than a spawn event, because the wandering
 * trader is placed by a {@code CustomSpawner} (not the normal mob-spawn path), and this also evicts
 * any already-saved trader on world reload — mirroring {@code HostileSpawnGate}'s join hook. Only
 * active when vanilla content is stripped ({@link VanillaContentState#isEnabled()} false).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class WanderingTraderSpawnGate {

    private WanderingTraderSpawnGate() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (VanillaContentState.isEnabled()) return;
        if (event.getLevel().isClientSide()) return;
        Entity e = event.getEntity();
        if (e instanceof WanderingTrader || e instanceof TraderLlama) {
            event.setCanceled(true);
        }
    }
}
