package com.bannerbound.core.territory;

import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.Map;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

/**
 * Resolves a single "majority biome" for a settlement — the biome that most of its claimed
 * chunks fall under, used to pick which biome-specific cost ladder to apply when expanding
 * territory. Sampling is cheap: one biome read per claimed chunk at a fixed mid-Y position.
 */
@ApiStatus.Internal
public final class TerritoryBiomeResolver {
    /** Y level sampled for biome at each chunk center. Picks a sea-level-ish slice so most
     *  Overworld biomes resolve consistently regardless of terrain height. */
    private static final int SAMPLE_Y = 64;

    private TerritoryBiomeResolver() {}

    /** Returns the resource location of the biome covering the largest share of the settlement's
     *  claimed chunks. Null if the settlement has no claims. */
    public static ResourceLocation majorityBiome(ServerLevel level, Settlement settlement) {
        if (settlement == null || settlement.claimedChunks().isEmpty()) return null;
        Map<ResourceLocation, Integer> counts = new HashMap<>();
        for (long packed : settlement.claimedChunks()) {
            ChunkPos cp = new ChunkPos(packed);
            BlockPos sample = new BlockPos(cp.getMinBlockX() + 8, SAMPLE_Y, cp.getMinBlockZ() + 8);
            Holder<Biome> biome = level.getBiome(sample);
            ResourceLocation rl = biome.unwrapKey()
                .map(k -> k.location())
                .orElseGet(() -> level.registryAccess()
                    .registryOrThrow(Registries.BIOME)
                    .getKey(biome.value()));
            if (rl == null) continue;
            counts.merge(rl, 1, Integer::sum);
        }

        ResourceLocation best = null;
        int bestCount = -1;
        for (Map.Entry<ResourceLocation, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }
}
