package com.bannerbound.core.api.settlement;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/**
 * Implemented by worker workstation block entities (Forester's Log, Digger's Slab,
 * Farmer's Granary, Fisher's Creel, Forager's Basket) so the Stocker can drain their output
 * without each block entity needing its own extraction API. The Stocker reads {@link #items()}
 * directly, mutates the stacks it collects, then calls {@link #setStockChanged()} so vanilla
 * persists the change.
 *
 * <p>This interface doubles as the Stocker's "collect from me" tag: {@code StockerWorkGoal}
 * drains every workstation whose block entity implements it, so a new workstation only has to
 * implement {@code WorkstationInventory} to be serviced — there is no separate type list to
 * keep in sync.
 */
@ApiStatus.Internal
public interface WorkstationInventory {
    /** The live backing inventory list. Mutating it is how the Stocker collects items. */
    NonNullList<ItemStack> items();

    /** Marks the block entity dirty after the Stocker mutates {@link #items()}. */
    void setStockChanged();

    /** True if every slot is empty — nothing for the Stocker to collect. */
    default boolean isStockEmpty() {
        for (ItemStack s : items()) {
            if (!s.isEmpty()) return false;
        }
        return true;
    }
}
