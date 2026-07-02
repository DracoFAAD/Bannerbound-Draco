package com.bannerbound.antiquity.client;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.network.GhostActionPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side right-click routing for the ghost-preview targets. The browse arrows and the ghost
 * result are billboards in the air — no block, no entity — so vanilla's pick can't hit them; this
 * intercepts the use-key press, ray-tests the targets of nearby workstations, and forwards the hit
 * as a {@link GhostActionPayload} (cancelling the vanilla use so nothing behind reacts). Aiming at
 * the workstation block itself always keeps the normal insert/remove/craft interactions.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class GhostClickEvents {
    private GhostClickEvents() {}

    @SubscribeEvent
    static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) return;
        // Shared with the green-crosshair affordance so what you see is exactly what you can click.
        GhostClickTargets.Hover hover = GhostClickTargets.findHovered(Minecraft.getInstance());
        if (hover == null) return;
        event.setCanceled(true);
        event.setSwingHand(true);
        PacketDistributor.sendToServer(new GhostActionPayload(hover.pos(), hover.picked().target().action()));
    }
}
