package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

@EventBusSubscriber(modid = BannerboundCore.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
@ApiStatus.Internal
public final class ClientResearchEvents {
    private ClientResearchEvents() {
    }

    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        event.register(UnknownItemHelper.QUESTION_MARK_MODEL);
    }

    @SubscribeEvent
    public static void onBakingCompleted(ModelEvent.BakingCompleted event) {
        UnknownItemHelper.invalidateCache();
    }
}
