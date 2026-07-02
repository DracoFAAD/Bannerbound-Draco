package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Belt-and-suspenders enforcement for the birdseye camera. Runs every client tick and:
 * <ul>
 *   <li>If {@link ClientBirdseyeState} is active and something stole the camera entity away
 *       from our ghost, slams it back. Keeps the camera locked even if another mod or vanilla
 *       code path resets it.</li>
 *   <li>If the state has gone inactive (screen closed / removed fired) but the camera entity
 *       is still our last-known ghost (i.e. {@code removed()} didn't propagate cleanly), forces
 *       it back to the local player. This is what saves us from "ESC closed the GUI but I'm
 *       still in birdseye view" — a problem the screen alone can't solve if its lifecycle
 *       hooks get bypassed.</li>
 * </ul>
 * We track the last ghost outside of {@link ClientBirdseyeState} because state.exit() wipes
 * its reference; this guard needs to remember "this is the entity I should NOT let stay
 * bound" until the next frame after exit.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class BirdseyeCameraGuard {
    /** Last ghost we saw bound while state was active. Cleared once we've restored the camera
     *  away from it; survives state.exit() so we can detect a stale binding. */
    private static Entity lastKnownGhost;

    private BirdseyeCameraGuard() {}

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (ClientBirdseyeState.isActive()) {
            Entity ghost = ClientBirdseyeState.ghostCamera();
            if (ghost == null) return;
            lastKnownGhost = ghost;
            if (mc.getCameraEntity() != ghost) {
                mc.setCameraEntity(ghost);
            }
        } else if (lastKnownGhost != null) {
            // State is inactive but camera might still point at our stale ghost — restore it.
            if (mc.getCameraEntity() == lastKnownGhost) {
                mc.setCameraEntity(mc.player);
            }
            lastKnownGhost = null;
        }
    }
}
