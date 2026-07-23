package com.bannerbound.core.civpm;

import com.bannerbound.core.civpm.data.CPMRegion;
import com.bannerbound.core.civpm.managers.CPMRegionsManager;
import com.bannerbound.core.civpm.utils.CPMMathUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;


public class CivPM {
    private static final CivPM instance = new CivPM();
    private static final CPMRegionsManager regions_manager = new CPMRegionsManager();

    public static CivPM getInstance() {return instance;}
    public static CPMRegionsManager getRegionManager() {return regions_manager;}

    private int broadcastmsgticks = 0;

    public void tick(ServerTickEvent.Post event) {
        ServerLevel overworld = event.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        if (overworld.players().isEmpty()) return;
        ServerPlayer player = overworld.players().getFirst();
        BlockPos pos = player.blockPosition();

        broadcastmsgticks++;

        if (broadcastmsgticks == 40) {
            broadcastmsgticks = 0;

            long position = CPMMathUtils.CPM2DUtils.packBlockToRegion(pos.getX(), pos.getZ());
            CPMRegion region = regions_manager.getRegion(position);

            if (region.getWanderers().size() < 10)
                region.addWanderer(UUID.randomUUID());

            //player.sendSystemMessage(Component.literal(region + " - (" + region.getWanderers().size() + " / 10)"));
        }
    }
}