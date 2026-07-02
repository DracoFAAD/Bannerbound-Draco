package com.bannerbound.core.api.workshop;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/**
 * Settlement-wide item counting for the min-stock governor: EVERY container inside the
 * settlement's claimed chunks — stockpiles, workshop storages, and any loose chest/barrel/basket
 * a player stashed things in ("10 arrows somewhere" counts, per the design). Matched by item
 * identity only — quality tiers are ignored, so 8 crude arrows satisfy "min 8 arrows". Counted by
 * walking each loaded claimed chunk's block entities (already in memory — no chunk loads, no
 * block scans); unloaded chunks degrade gracefully to "not counted". Each container BE counts its
 * OWN slots, so double chests aren't double-counted. Cached briefly ({@link #CACHE_TICKS}); the
 * future Stocker/logistics tier replaces this with a real inventory index.
 */
public final class SettlementItemCensus {
    /** How long a count stays fresh. ~5 s: cheap, and a crafter mid-craft re-counts on finish. */
    private static final long CACHE_TICKS = 100L;

    private record Key(UUID settlementId, Item item) {
    }

    private record Cached(long tick, int count) {
    }

    private static final Map<Key, Cached> CACHE = new ConcurrentHashMap<>();

    private SettlementItemCensus() {
    }

    /** Settlement-wide count of {@code item} across all containers in claimed chunks, cached. */
    public static int count(ServerLevel sl, Settlement settlement, Item item) {
        Key key = new Key(settlement.id(), item);
        long now = sl.getGameTime();
        Cached c = CACHE.get(key);
        if (c != null && now - c.tick() < CACHE_TICKS) {
            return c.count();
        }
        int total = 0;
        for (long packed : settlement.claimedChunks()) {
            net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(packed);
            if (!sl.hasChunk(cp.x, cp.z)) continue; // unloaded → degrade gracefully
            net.minecraft.world.level.chunk.LevelChunk chunk = sl.getChunk(cp.x, cp.z);
            for (Map.Entry<net.minecraft.core.BlockPos, net.minecraft.world.level.block.entity.BlockEntity> e
                    : chunk.getBlockEntities().entrySet()) {
                if (!(e.getValue() instanceof net.minecraft.world.Container container)) continue;
                // Wild storage (unopened loot chests, mineshaft chests deep under the claim) is
                // not the settlement's pantry — it must neither satisfy min-stock nor be looted.
                if (com.bannerbound.core.entity.DropOffContainers.isWildStorage(sl, e.getKey())) continue;
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    net.minecraft.world.item.ItemStack s = container.getItem(slot);
                    if (s.is(item)) total += s.getCount();
                }
            }
        }
        CACHE.put(key, new Cached(now, total));
        return total;
    }

    /** Convenience for the menu rows: count by item registry id string ("" / unknown → 0). */
    public static int countById(ServerLevel sl, Settlement settlement, String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return 0;
        Item item = BuiltInRegistries.ITEM.get(rl);
        return item == net.minecraft.world.item.Items.AIR ? 0 : count(sl, settlement, item);
    }
}
