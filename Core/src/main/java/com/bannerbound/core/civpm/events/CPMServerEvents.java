package com.bannerbound.core.civpm.events;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.civpm.CivPM;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber (modid = BannerboundCore.MODID)
public class CPMServerEvents {
    @SubscribeEvent
    public static void tickEvent(ServerTickEvent.Post event) {
        CivPM.getInstance().tick(event);
    }
}
