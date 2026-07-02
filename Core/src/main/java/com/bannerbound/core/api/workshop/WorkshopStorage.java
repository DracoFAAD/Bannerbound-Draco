package com.bannerbound.core.api.workshop;

import java.util.ArrayList;
import java.util.List;

import com.bannerbound.core.api.settlement.Workshop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Item access across a workshop's storage blocks (the cached {@code #bannerbound:workshop_storage}
 * positions). All inputs a crafter uses come FROM here and all outputs go TO here — no external
 * drop-off; hauling between stockpiles and workshops is the future Stocker's job.
 */
public final class WorkshopStorage {
    private WorkshopStorage() {
    }

    private static List<IItemHandler> handlers(ServerLevel sl, Workshop workshop) {
        List<IItemHandler> out = new ArrayList<>();
        for (BlockPos p : workshop.storageBlocks()) {
            IItemHandler h = sl.getCapability(Capabilities.ItemHandler.BLOCK, p, null);
            if (h != null) out.add(h);
        }
        return out;
    }

    /** Total count of {@code item} across the workshop's storage. */
    public static int count(ServerLevel sl, Workshop workshop, Item item) {
        int total = 0;
        for (IItemHandler h : handlers(sl, workshop)) {
            for (int slot = 0; slot < h.getSlots(); slot++) {
                ItemStack s = h.getStackInSlot(slot);
                if (s.is(item)) total += s.getCount();
            }
        }
        return total;
    }

    /**
     * Withdraws exactly {@code count} of {@code item}, or NOTHING. Returns the extracted stack
     * (count = requested) or {@link ItemStack#EMPTY} if the storage didn't hold enough — checked
     * first so a failed withdrawal never leaves the storage partially drained.
     */
    public static ItemStack extract(ServerLevel sl, Workshop workshop, Item item, int count) {
        if (count(sl, workshop, item) < count) return ItemStack.EMPTY;
        int remaining = count;
        for (IItemHandler h : handlers(sl, workshop)) {
            for (int slot = 0; slot < h.getSlots() && remaining > 0; slot++) {
                if (!h.getStackInSlot(slot).is(item)) continue;
                ItemStack taken = h.extractItem(slot, remaining, false);
                remaining -= taken.getCount();
            }
            if (remaining <= 0) break;
        }
        return new ItemStack(item, count - Math.max(0, remaining));
    }

    /** A snapshot of every non-empty stack across the workshop's storage (copies — safe to read,
     *  mutating them changes nothing). Drives the Stocker's surplus scan. */
    public static List<ItemStack> contents(ServerLevel sl, Workshop workshop) {
        List<ItemStack> out = new ArrayList<>();
        for (IItemHandler h : handlers(sl, workshop)) {
            for (int slot = 0; slot < h.getSlots(); slot++) {
                ItemStack s = h.getStackInSlot(slot);
                if (!s.isEmpty()) out.add(s.copy());
            }
        }
        return out;
    }

    /** Inserts {@code stack} into the first space available; returns the un-fitting leftover. */
    public static ItemStack insert(ServerLevel sl, Workshop workshop, ItemStack stack) {
        ItemStack remaining = stack;
        for (IItemHandler h : handlers(sl, workshop)) {
            for (int slot = 0; slot < h.getSlots() && !remaining.isEmpty(); slot++) {
                remaining = h.insertItem(slot, remaining, false);
            }
            if (remaining.isEmpty()) break;
        }
        return remaining;
    }
}
