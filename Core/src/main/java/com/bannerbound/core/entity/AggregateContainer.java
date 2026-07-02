package com.bannerbound.core.entity;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A read/write {@link Container} facade over several backing containers — the storage blocks enclosed
 * by a Stockpile. Lets the existing worker drop-off logic ({@link DropOffContainers#insert},
 * {@link DropOffContainers#roomFor}, {@link DropOffContainers#extractOne}, …) treat a whole stockpile
 * as one inventory: a flat slot index maps to a slot in one of the parts. So a worker assigned to the
 * stockpile block fans its yield across every chest / barrel / basket inside, with no Stocker needed.
 */
@ApiStatus.Internal
final class AggregateContainer implements Container {
    private final List<Container> parts;
    private final Container[] slotOwner;
    private final int[] slotLocal;
    private final int maxStack;

    AggregateContainer(List<Container> parts) {
        this.parts = parts;
        int size = 0;
        int ms = 64;
        for (Container c : parts) {
            size += c.getContainerSize();
            ms = Math.min(ms, c.getMaxStackSize());
        }
        this.maxStack = ms;
        this.slotOwner = new Container[size];
        this.slotLocal = new int[size];
        int idx = 0;
        for (Container c : parts) {
            int cs = c.getContainerSize();
            for (int i = 0; i < cs; i++) {
                slotOwner[idx] = c;
                slotLocal[idx] = i;
                idx++;
            }
        }
    }

    private boolean inRange(int slot) {
        return slot >= 0 && slot < slotOwner.length;
    }

    @Override
    public int getContainerSize() {
        return slotOwner.length;
    }

    @Override
    public boolean isEmpty() {
        for (Container c : parts) {
            if (!c.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return inRange(slot) ? slotOwner[slot].getItem(slotLocal[slot]) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return inRange(slot) ? slotOwner[slot].removeItem(slotLocal[slot], amount) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return inRange(slot) ? slotOwner[slot].removeItemNoUpdate(slotLocal[slot]) : ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (inRange(slot)) slotOwner[slot].setItem(slotLocal[slot], stack);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return inRange(slot) && slotOwner[slot].canPlaceItem(slotLocal[slot], stack);
    }

    @Override
    public int getMaxStackSize() {
        return maxStack;
    }

    @Override
    public void setChanged() {
        for (Container c : parts) {
            c.setChanged();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (Container c : parts) {
            c.clearContent();
        }
    }
}
