package com.bannerbound.core.api.territory;

import com.bannerbound.core.api.settlement.Settlement;

import java.util.List;

import net.minecraft.world.item.Item;

/**
 * Single tier of the chunk-claim expansion cost ladder: a population requirement plus a bundle
 * of item stacks that must be present in the player's inventory and will be consumed on claim.
 * <p>
 * Loaded from {@code data/bannerbound/chunk_claim_costs/&lt;era&gt;.json}; one tier per
 * expansion the settlement has made in that era.
 *
 * @param populationRequired minimum {@link com.bannerbound.core.api.settlement.Settlement#population()}
 *                           the settlement must have to afford this tier
 * @param items              item id + count pairs; all must be present, all are consumed atomically
 */
public record ChunkClaimCost(int populationRequired, List<ItemCost> items) {

    /** One item × count entry inside a {@link ChunkClaimCost}. */
    public record ItemCost(Item item, int count) {}
}
