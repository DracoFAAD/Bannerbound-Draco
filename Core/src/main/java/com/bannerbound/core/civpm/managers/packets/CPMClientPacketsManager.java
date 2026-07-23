package com.bannerbound.core.civpm.managers.packets;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.civpm.data.CPMRegion;
import com.bannerbound.core.civpm.packets.servertoclient.CPMRegionResponsePacket;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class CPMClientPacketsManager {
    public static void handleRegionResponsePacket(final CPMRegionResponsePacket payload, final IPayloadContext context) {
        if (!(context.player() instanceof LocalPlayer sender)) return;

        CPMRegion region = new CPMRegion(payload.pos(), payload.wanderers());
        sender.sendSystemMessage(Component.literal("Recieved region response packet for " + region + " with " + region.getWanderers().size() + " wanderers"));
    }
}
