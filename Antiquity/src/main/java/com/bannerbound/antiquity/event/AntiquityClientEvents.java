package com.bannerbound.antiquity.event;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.core.client.ClientFoodWarningState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.ApiStatus;

@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public class AntiquityClientEvents {
    @SubscribeEvent
    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientFoodWarningState.clear();
    }
}
