package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Server-authoritative handling of drop-off marking. While a player is in drop-edit mode (tracked by
 * {@link DropLocationEditServer}) every block right-click is cancelled here so the container never
 * opens, and the clicked block is marked as the citizen's drop-off — all on the server thread, so it
 * works identically in single-player and on a dedicated server with no client/server race. The
 * client only renders the wireframe and suppresses held-item use (see {@code DropLocationEditClick}).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class DropLocationServerGuard {
    private DropLocationServerGuard() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        Integer citizenId = DropLocationEditServer.getCitizenId(sp.getUUID());
        if (citizenId == null) return;
        // Never open the container or use the held item while marking.
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        event.setCanceled(true);
        if (event.getHand() != InteractionHand.MAIN_HAND) return; // act once
        boolean done;
        if (citizenId == com.bannerbound.core.network.OpenDropLocationEditPayload.PREFERRED_STORAGE_TARGET) {
            done = com.bannerbound.core.network.ServerPayloadHandler.markPreferredStorage(sp, event.getPos());
        } else {
            done = com.bannerbound.core.network.ServerPayloadHandler.markStorage(
                sp, citizenId, event.getPos(), DropLocationEditServer.isSeed(sp.getUUID()));
        }
        if (done) {
            // Marked (or the citizen is gone) — leave edit mode and tell the client to stop drawing.
            Integer returnCitizenId = DropLocationEditServer.getReturnCitizenId(sp.getUUID());
            DropLocationEditServer.clear(sp.getUUID());
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                new com.bannerbound.core.network.EndDropLocationEditPayload());
            if (returnCitizenId != null) {
                com.bannerbound.core.network.ServerPayloadHandler.reopenCitizenScreen(sp, returnCitizenId);
            }
        }
        // On a rejected block (false) the player stays in edit mode and can try another block.
    }
}
