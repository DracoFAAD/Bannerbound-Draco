package com.bannerbound.antiquity.network;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.SpearFishing;

import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client→server bridge for the <b>empty-hand</b> reel-in. Vanilla fires {@code RightClickEmpty} only
 * on the client when you right-click air with an empty hand, so the server never hears it; when the
 * player is sneaking and actually has a tethered spear/catch out, we forward a {@link ReelTetherPayload}
 * so the server can reel it in (the held-rope path is handled directly by {@code FiberRopeItem.use}).
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class SpearFishingClientEvents {
    private SpearFishingClientEvents() {}

    @SubscribeEvent
    static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        Player player = event.getEntity();
        if (player.isShiftKeyDown() && SpearFishing.hasTether(player)) {
            PacketDistributor.sendToServer(ReelTetherPayload.INSTANCE);
        }
    }
}
