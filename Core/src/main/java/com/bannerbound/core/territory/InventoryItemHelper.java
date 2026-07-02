package com.bannerbound.core.territory;

import com.bannerbound.core.api.territory.ChunkClaimCost;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Small helpers for "does the player have N of item X across their inventory?" + bulk consume.
 * Both are atomic-as-needed: {@link #consume} only writes if {@link #countAll(ServerPlayer, java.util.List)}
 * confirms every line item has enough. Used by the chunk-claim expansion handler to charge a tier.
 */
@ApiStatus.Internal
public final class InventoryItemHelper {
    private InventoryItemHelper() {}

    /** Count how many of {@code item} the player has across main / armor / offhand inventories. */
    public static int countItem(ServerPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int total = 0;
        total += sumOf(inv.items, item);
        total += sumOf(inv.armor, item);
        total += sumOf(inv.offhand, item);
        return total;
    }

    /** True if the player has at least {@code cost.count()} of each item listed in {@code costs}. */
    public static boolean hasAll(ServerPlayer player, List<ChunkClaimCost.ItemCost> costs) {
        for (ChunkClaimCost.ItemCost c : costs) {
            if (countItem(player, c.item()) < c.count()) return false;
        }
        return true;
    }

    /** Consume the listed costs. Caller must have called {@link #hasAll} first — this method
     *  assumes feasibility and removes greedily across inventory slots. Returns false if anything
     *  went wrong mid-consume (partial state possible; caller should treat as a fatal bug). */
    public static boolean consume(ServerPlayer player, List<ChunkClaimCost.ItemCost> costs) {
        Inventory inv = player.getInventory();
        for (ChunkClaimCost.ItemCost c : costs) {
            int remaining = c.count();
            remaining = removeFrom(inv.items, c.item(), remaining);
            if (remaining > 0) remaining = removeFrom(inv.armor, c.item(), remaining);
            if (remaining > 0) remaining = removeFrom(inv.offhand, c.item(), remaining);
            if (remaining > 0) return false;
        }
        return true;
    }

    /** Counts every stack matching {@code item} in {@code stacks}. */
    private static int sumOf(List<ItemStack> stacks, Item item) {
        int n = 0;
        for (ItemStack s : stacks) {
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** Removes up to {@code wanted} items of {@code item} from {@code stacks}. Returns the
     *  amount still owed (0 = fully satisfied). */
    private static int removeFrom(List<ItemStack> stacks, Item item, int wanted) {
        for (int i = 0; i < stacks.size() && wanted > 0; i++) {
            ItemStack s = stacks.get(i);
            if (!s.is(item)) continue;
            int take = Math.min(s.getCount(), wanted);
            s.shrink(take);
            wanted -= take;
        }
        return wanted;
    }
}
