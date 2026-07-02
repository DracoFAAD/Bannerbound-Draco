package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.stockpile.StockpileService;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Shared sourcing helpers for the forester roles — sapling species resolution and consume-one
 * pulls from the citizen's job depot (then the settlement stockpiles). Factored out of
 * {@link ForesterWorkGoal#tryReplant} so both the gatherer's replant and the
 * {@link ForesterPlantationGoal} draw from the same supply chain (the Stocker drains the drop-off,
 * so saplings/bone meal usually end up in storage).
 */
@ApiStatus.Internal
public final class ForesterSupplies {
    private ForesterSupplies() {}

    /** The sapling item that matches a {@code *_log} block via the vanilla {@code _log → _sapling}
     *  name substitution, or {@link Items#AIR} when there's no such sapling (stems, modded oddballs)
     *  or the species isn't single-sapling growable (see {@link #isSupportedSapling}). */
    public static Item saplingForLog(@Nullable Block logBlock) {
        if (logBlock == null) return Items.AIR;
        ResourceLocation logId = BuiltInRegistries.BLOCK.getKey(logBlock);
        if (logId == null) return Items.AIR;
        String saplingPath = logId.getPath().replace("_log", "_sapling");
        if (saplingPath.equals(logId.getPath())) return Items.AIR;   // not a *_log block
        ResourceLocation saplingId = ResourceLocation.fromNamespaceAndPath(logId.getNamespace(), saplingPath);
        if (!BuiltInRegistries.BLOCK.containsKey(saplingId)) return Items.AIR;
        Item item = BuiltInRegistries.BLOCK.get(saplingId).asItem();
        return isSupportedSapling(item) ? item : Items.AIR;
    }

    /** The block placed when planting a sapling item ({@link Blocks#AIR}'s block for non-saplings). */
    public static Block saplingBlock(Item saplingItem) {
        return Block.byItem(saplingItem);
    }

    /** Single-sapling-growable filter for v1 plantations: any {@code *_sapling} item EXCEPT dark oak
     *  (needs a 2×2 grid). Mangrove propagules and nether fungi don't end in {@code _sapling} and are
     *  excluded for free. Modded saplings are assumed single-growable (best effort). */
    public static boolean isSupportedSapling(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) return false;
        String path = id.getPath();
        if (!path.endsWith("_sapling")) return false;
        return !path.equals("dark_oak_sapling");
    }

    /** True if at least one {@code item} can be sourced from the depot or the settlement stockpiles. */
    public static boolean hasOne(ServerLevel level, @Nullable Settlement settlement,
                                 @Nullable Container depot, Item item) {
        if (item == Items.AIR) return false;
        if (depot != null) {
            for (int i = 0; i < depot.getContainerSize(); i++) {
                ItemStack s = depot.getItem(i);
                if (!s.isEmpty() && s.is(item)) return true;
            }
        }
        return settlement != null && StockpileService.count(level, settlement, item) > 0;
    }

    /** Consumes one {@code item} from the depot (first) then a stockpile. Returns false if none
     *  was available. */
    public static boolean takeOne(ServerLevel level, @Nullable Settlement settlement,
                                  @Nullable Container depot, Item item) {
        if (item == Items.AIR) return false;
        if (depot != null) {
            for (int i = 0; i < depot.getContainerSize(); i++) {
                ItemStack s = depot.getItem(i);
                if (!s.isEmpty() && s.is(item)) {
                    s.shrink(1);
                    depot.setChanged();
                    return true;
                }
            }
        }
        if (settlement != null && StockpileService.count(level, settlement, item) > 0) {
            StockpileService.withdraw(level, settlement, item, 1);
            return true;
        }
        return false;
    }

    /**
     * Picks a sapling species to plant: the forester's preferred-log species if a matching sapling is
     * in stock, else the first supported sapling found in the depot. Returns {@link Items#AIR} when
     * nothing plantable is on hand.
     */
    public static Item pickSpecies(CitizenEntity citizen, ServerLevel level,
                                   @Nullable Settlement settlement, @Nullable Container depot) {
        Item preferred = saplingForLog(citizen.getPreferredLog());
        if (preferred != Items.AIR && hasOne(level, settlement, depot, preferred)) return preferred;
        if (depot != null) {
            for (int i = 0; i < depot.getContainerSize(); i++) {
                ItemStack s = depot.getItem(i);
                if (!s.isEmpty() && isSupportedSapling(s.getItem())) return s.getItem();
            }
        }
        return Items.AIR;
    }
}
