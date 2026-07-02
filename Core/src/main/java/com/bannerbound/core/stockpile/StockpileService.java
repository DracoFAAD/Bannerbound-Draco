package com.bannerbound.core.stockpile;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Stockpile;
import com.bannerbound.core.building.StockpileEnclosure;
import com.bannerbound.core.entity.DropOffContainers;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Read/withdraw access to a settlement's "town inventory" — the storage containers inside its
 * valid Stockpile Rack enclosures.
 * <p>
 * Workers that consume inputs (the farmer's seeds, the forester's saplings) pull from here when
 * their own workstation has run dry. The Stocker continually drains workstation inventories into
 * the stockpiles, so the stockpile — not the workstation — is the real reserve.
 */
@ApiStatus.Internal
public final class StockpileService {
    private StockpileService() {
    }

    /** Total count of {@code item} across the settlement's valid stockpiles. */
    public static int count(ServerLevel level, Settlement settlement, Item item) {
        int total = 0;
        for (IItemHandler handler : storageHandlers(level, settlement)) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.is(item)) total += stack.getCount();
            }
        }
        return total;
    }

    /** A summed view of the whole town inventory: item → total count across every valid stockpile.
     *  Backs the barter screen's storage panels and storage-aware barbarian demands. Insertion-ordered. */
    public static java.util.LinkedHashMap<Item, Integer> summarize(ServerLevel level, Settlement settlement) {
        java.util.LinkedHashMap<Item, Integer> out = new java.util.LinkedHashMap<>();
        for (IItemHandler handler : storageHandlers(level, settlement)) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty()) continue;
                out.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return out;
    }

    /** Withdraws up to {@code max} of {@code item} from the settlement's valid stockpiles.
     *  Returns the count actually withdrawn. */
    public static int withdraw(ServerLevel level, Settlement settlement, Item item, int max) {
        if (max <= 0) return 0;
        int taken = 0;
        for (IItemHandler handler : storageHandlers(level, settlement)) {
            for (int slot = 0; slot < handler.getSlots() && taken < max; slot++) {
                if (!handler.getStackInSlot(slot).is(item)) continue;
                taken += handler.extractItem(slot, max - taken, false).getCount();
            }
            if (taken >= max) break;
        }
        return taken;
    }

    /**
     * Re-scans the enclosure around {@code sp}'s block and writes the result back onto the record:
     * validity, status, and the (≤ {@link Stockpile#MAX_CONTAINERS}) enclosed container positions.
     * Called from the Stockpile Block's validation ticker. Maps the low-level
     * {@link StockpileEnclosure.FailReason} onto the player-facing {@link Stockpile.Status}.
     */
    public static void validate(ServerLevel level, Stockpile sp) {
        StockpileEnclosure.Result r = StockpileEnclosure.scan(level, sp.pos());
        if (!r.valid()) {
            sp.setContainers(java.util.List.of());
            sp.setValid(false);
            sp.setStatus(switch (r.reason()) {
                case NOT_ENCLOSED -> Stockpile.Status.NOT_ENCLOSED;
                case NO_GATE -> Stockpile.Status.NO_GATE;
                case NO_ROOF -> Stockpile.Status.NO_ROOF;
                case TOO_LARGE -> Stockpile.Status.TOO_LARGE;
                default -> Stockpile.Status.NOT_ENCLOSED;
            });
            return;
        }
        sp.setContainers(r.storage());
        if (r.storage().isEmpty()) {
            sp.setValid(false);
            sp.setStatus(Stockpile.Status.NO_CONTAINERS);
        } else {
            sp.setValid(true);
            sp.setStatus(Stockpile.Status.VALID);
        }
    }

    /**
     * Item handlers of the settlement's town-inventory storage: every container block enclosed by a
     * <b>valid</b> Stockpile (fence/wall + roof). Positions come from each {@link Stockpile}'s
     * validation scan; here they're resolved to live {@code IItemHandler}s, skipping any that are
     * unloaded or no longer containers (degrades gracefully — counts/withdrawals just see less).
     */
    private static List<IItemHandler> storageHandlers(ServerLevel level, Settlement settlement) {
        List<IItemHandler> out = new ArrayList<>();
        for (Stockpile sp : settlement.stockpiles().values()) {
            if (!sp.valid()) continue;
            for (BlockPos cpos : sp.containers()) {
                // The chest capability resolves each half of a double chest to the COMBINED
                // inventory — one handler per pair or every count/withdraw sees it twice.
                if (DropOffContainers.isSecondaryChestHalf(level, cpos)) continue;
                IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, cpos, null);
                // Antiquity baskets back a vanilla Container but register no capability — wrap them.
                if (h == null && level.getBlockEntity(cpos) instanceof net.minecraft.world.Container c) {
                    h = new net.neoforged.neoforge.items.wrapper.InvWrapper(c);
                }
                if (h != null) out.add(h);
            }
        }
        return out;
    }
}
