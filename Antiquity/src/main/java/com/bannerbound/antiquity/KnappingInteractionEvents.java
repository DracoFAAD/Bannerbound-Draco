package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The two-rocks knapping gesture: right-clicking while holding a knappable rock (see
 * {@link Knapping#KNAPPING_ROCKS}) in BOTH hands opens the knapping screen instead of placing a
 * rock. Both the block-aimed and air right-clicks are intercepted (and both hands' events canceled,
 * so neither rock is placed); the screen is opened once, server-side, on the main-hand event.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class KnappingInteractionEvents {
    private KnappingInteractionEvents() {
    }

    @SubscribeEvent
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        handle(event);
    }

    @SubscribeEvent
    static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        handle(event);
    }

    private static void handle(PlayerInteractEvent event) {
        Player player = event.getEntity();
        if (!player.getMainHandItem().is(Knapping.KNAPPING_ROCKS)
                || !player.getOffhandItem().is(Knapping.KNAPPING_ROCKS)) {
            return;
        }
        // This gesture means "knap" — never place either rock (both subclasses are cancelable).
        ((net.neoforged.bus.api.ICancellableEvent) event).setCanceled(true);
        if (event.getHand() == InteractionHand.MAIN_HAND && player instanceof ServerPlayer serverPlayer) {
            Knapping.tryOpen(serverPlayer);
        }
    }
}
