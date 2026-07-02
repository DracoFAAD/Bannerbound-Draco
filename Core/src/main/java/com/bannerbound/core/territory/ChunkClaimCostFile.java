package com.bannerbound.core.territory;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.territory.ChunkClaimCost;
import com.bannerbound.core.api.territory.data.ChunkClaimCostLoader;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;

/**
 * One parsed cost file — keyed in {@link ChunkClaimCostLoader} by era id ("antiquity", "medieval",
 * etc.) — containing the per-era expansion cap and the default + per-biome cost tier ladders.
 * <p>
 * Biome lookup falls back to {@link #defaultTiers} when a settlement's majority biome isn't
 * present in {@link #biomeTiers}. Each entry's tier list is indexed by the within-era expansion
 * number (0 = this era's first expansion); {@code TerritoryService} resolves a settlement's
 * global expansion count onto the right era's ladder.
 *
 * @param era            era id this file applies to
 * @param maxExpansions  hard cap on number of expansions a settlement can perform during this era
 * @param defaultTiers   fallback cost ladder used when no biome-specific override exists
 * @param biomeTiers     biome resource location → ladder; takes priority over {@link #defaultTiers}
 */
@ApiStatus.Internal
public record ChunkClaimCostFile(
        String era,
        int maxExpansions,
        List<ChunkClaimCost> defaultTiers,
        Map<ResourceLocation, List<ChunkClaimCost>> biomeTiers) {

    /**
     * Resolves the tier list to use for a settlement whose majority biome is {@code biome}.
     * Returns the biome override if present, else the default tiers. Never null (loader
     * guarantees defaults exist).
     */
    public List<ChunkClaimCost> tiersFor(ResourceLocation biome) {
        if (biome != null) {
            List<ChunkClaimCost> override = biomeTiers.get(biome);
            if (override != null) return override;
        }
        return defaultTiers;
    }
}
