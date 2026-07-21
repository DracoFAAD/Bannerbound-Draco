package com.bannerbound.core.civpm;

import com.bannerbound.core.civpm.data.CPMRegion;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.ServerTickEvent;


public class CivPM {
    private static final CivPM instance = new CivPM();
    public static CivPM getInstance() {return instance;}

    Long2ObjectMap<CPMRegion> cached_regions = new Long2ObjectOpenHashMap<>();
    LongSet changed_regions = new LongOpenHashSet();

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

            // broadcast fr
            long position = CPMRegion.pack(Mth.floor(pos.getX() / 48.0), Mth.floor(pos.getZ() / 48.0));
            CPMRegion region = cached_regions.get(position);

            if (region == null) {
                CPMRegion new_region = new CPMRegion(position);
                cached_regions.put(position, new_region);
                region = new_region;
            } else {
                region.addWanderer(String.valueOf(region.getWanderers().size()));
            }

            player.sendSystemMessage(Component.literal(region.toString()).append("- " + region.serializeWanderers()));
        }
    }

    public void regionChanged(CPMRegion region) {
        changed_regions.add(region.getPos());
    }
}