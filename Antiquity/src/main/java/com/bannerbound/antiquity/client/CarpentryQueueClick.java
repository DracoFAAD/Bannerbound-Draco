package com.bannerbound.antiquity.client;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.network.CarpentryActionPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Right-click routing for the in-world QUEUE chips: aiming at a queued item and pressing use removes
 * that entry. The picker's add/cycle reuses the shared ghost-preview path ({@code GhostClickEvents});
 * queue chips sit at different positions, so the two never collide (and this bails if the ghost handler
 * already consumed the click).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class CarpentryQueueClick {
    private CarpentryQueueClick() {}

    @SubscribeEvent
    static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isCanceled()) return;
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) return;
        CarpentryReadoutRenderer.QueueHit hit = CarpentryReadoutRenderer.findHoveredQueue(Minecraft.getInstance());
        if (hit == null) return;
        event.setCanceled(true);
        event.setSwingHand(true);
        PacketDistributor.sendToServer(
            new CarpentryActionPayload(hit.pos(), CarpentryActionPayload.REMOVE_QUEUE, hit.index()));
    }
}
