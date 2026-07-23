package com.bannerbound.core.civpm.managers.packets;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.civpm.CivPM;
import com.bannerbound.core.civpm.data.CPMRegion;
import com.bannerbound.core.civpm.packets.clienttoserver.CPMRegionRequestPacket;
import com.bannerbound.core.civpm.packets.servertoclient.CPMRegionResponsePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class CPMServerPacketsManager {
    public static void handleRegionRequestPacket(final CPMRegionRequestPacket payload, final IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) return;

        CPMRegion region = CivPM.getRegionManager().getRegion(payload.pos());

        PacketDistributor.sendToPlayer(sender, new CPMRegionResponsePacket(region.getPos(), region.serializeWanderers()));
    }
}
