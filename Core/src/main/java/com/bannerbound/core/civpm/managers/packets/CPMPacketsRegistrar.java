package com.bannerbound.core.civpm.managers.packets;

import com.bannerbound.core.civpm.packets.clienttoserver.CPMRegionRequestPacket;
import com.bannerbound.core.civpm.packets.servertoclient.CPMRegionResponsePacket;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class CPMPacketsRegistrar {
    private static void registerClientToServer(PayloadRegistrar registrar) {
        registrar.playToServer(
                CPMRegionRequestPacket.TYPE,
                CPMRegionRequestPacket.STREAM_CODEC,
                CPMServerPacketsManager::handleRegionRequestPacket
        );
    }

    private static void registerServerToClient(PayloadRegistrar registrar) {
        registrar.playToClient(
                CPMRegionResponsePacket.TYPE,
                CPMRegionResponsePacket.STREAM_CODEC,
                CPMClientPacketsManager::handleRegionResponsePacket
        );
    }

    public static void registerPackets(PayloadRegistrar registrar) {
        registerClientToServer(registrar);
        registerServerToClient(registrar);
    }
}
