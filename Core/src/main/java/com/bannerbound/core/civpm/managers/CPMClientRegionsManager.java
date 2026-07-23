package com.bannerbound.core.civpm.managers;

import com.bannerbound.core.civpm.data.CPMRegion;
import com.bannerbound.core.civpm.packets.clienttoserver.CPMRegionRequestPacket;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public class CPMClientRegionsManager {
    public static long current_region = 0L;
    private Long2ObjectOpenHashMap<CPMRegion> regions = new Long2ObjectOpenHashMap<>();

    public void playerChangedRegion() {
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (localPlayer == null) return;

        requestRegionDataFromServer(current_region);
    }

    public void requestRegionDataFromServer(long position) {
        PacketDistributor.sendToServer(new CPMRegionRequestPacket(position));
    }
}
