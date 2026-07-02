package com.bannerbound.antiquity.client;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.network.CarveCommitPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Routes the use-key to the carve commit while a ghost preview is showing. The previewed block is
 * hidden (air) on the client, so vanilla's pick can't hit it; this intercepts the press and forwards
 * the anchor to the server (which replays the carve there), cancelling the vanilla use so nothing
 * behind the hidden block reacts. Mirrors {@link GhostClickEvents}.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class CarveClickEvents {
    private CarveClickEvents() {}

    @SubscribeEvent
    static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        BlockPos anchor = CarvePreviewController.activeAnchor();
        if (anchor == null) {
            return; // no preview → leave the normal interaction (and the normal carve handlers) alone
        }
        event.setCanceled(true);
        event.setSwingHand(true);
        PacketDistributor.sendToServer(new CarveCommitPayload(anchor));
        CarvePreviewController.clearForCommit();
    }
}
