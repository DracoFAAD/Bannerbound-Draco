package com.bannerbound.core.civpm.managers;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.civpm.data.CPMRegion;
import com.bannerbound.core.civpm.utils.CPMMathUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CPMRegionsManager {
    Long2ObjectMap<CPMRegion> cached_regions = new Long2ObjectOpenHashMap<>();
    LongSet changed_regions = new LongOpenHashSet();

    public void saveAllChangedRegions(LevelEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();

        Path worldDataDir;
        Path regionsFolder;

        try {
            worldDataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
            regionsFolder = worldDataDir.resolve("cpm_regions");
            Files.createDirectories(regionsFolder);
        } catch (IOException e) {
            System.err.println("Failed to create region save directories: " + e.getMessage());
            return;
        }

        List<ServerPlayer> playerList = serverLevel.players();

        for (long changedRegionPos : changed_regions) {
            CPMRegion region = cached_regions.get(changedRegionPos);
            if (region == null) continue;

            CPMRegion.Serialization.saveToFile(region, regionsFolder.resolve("region_" + region.getX() + "-" + region.getY() + ".dat"));
        }

        changed_regions.clear();

        // clear cache
        for (long cachedRegionPos : cached_regions.keySet()) {
            CPMRegion region = cached_regions.get(cachedRegionPos);

            boolean loaded = isRegionLoaded(region, playerList);

            if (!loaded) {
                cached_regions.remove(cachedRegionPos);
            }
        }
    }

    private static boolean isRegionLoaded(CPMRegion region, List<ServerPlayer> playerList) {
        int regionX = region.getX();
        int regionY = region.getY();

        boolean loaded = false;

        for (ServerPlayer player : playerList) {
            int playerRegionX = Mth.floor(player.getBlockX() / 48.0);
            int playerRegionZ = Mth.floor(player.getBlockZ() / 48.0);

            int dx = regionX - playerRegionX;
            int dz = regionY - playerRegionZ;

            int distSqrRegions = dx * dx + dz * dz;

            if (distSqrRegions < 9) {
                loaded = true;
            }
        }

        return loaded;
    }

    public void regionChanged(CPMRegion region) {
        changed_regions.add(region.getPos());
    }

    @Nullable
    public CPMRegion loadRegion(long pos) {
        return loadRegion(pos, true);
    }

    @Nullable
    public CPMRegion loadRegion(long pos, boolean cache) {
        CPMRegion cached_region = cached_regions.get(pos);

        if (cached_region != null) {
            return cached_region;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;

        Path worldDataDir;
        Path regionsFolder;

        try {
            worldDataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
            regionsFolder = worldDataDir.resolve("cpm_regions");
            Files.createDirectories(regionsFolder);
        } catch (IOException e) {
            System.err.println("Failed to create region save directories: " + e.getMessage());
            return null;
        }

        Path regionFile = regionsFolder.resolve("region_" + CPMMathUtils.CPM2DUtils.unpackX(pos) + "-" + CPMMathUtils.CPM2DUtils.unpackY(pos) + ".dat");
        if (!Files.exists(regionFile)) return null;

        BannerboundCore.LOGGER.info("Loaded region file: {}", regionFile);

        if (cache) {
            CPMRegion region = CPMRegion.Serialization.loadFromFile(regionFile);
            cacheRegion(region);
            return region;
        } else {
            return CPMRegion.Serialization.loadFromFile(regionFile);
        }
    }

    public CPMRegion getRegion(long pos) {
        if (cached_regions.containsKey(pos)) {
            return cached_regions.get(pos);
        }

        CPMRegion loaded_region = loadRegion(pos, true);
        if (loaded_region != null) {
            return loaded_region;
        }

        CPMRegion new_region = new CPMRegion(pos);
        cacheRegion(new_region);
        return new_region;
    }

    public void cacheRegion(CPMRegion region) {
        cached_regions.put(region.getPos(), region);
    }
}
