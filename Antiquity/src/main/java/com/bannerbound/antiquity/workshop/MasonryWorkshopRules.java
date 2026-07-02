package com.bannerbound.antiquity.workshop;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.core.api.settlement.Workshop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Structure rules for Antiquity masonry workshops — the stone analogue of
 * {@link CarpentryWorkshopRules}. A masonry workshop needs a {@code stone_chisel} kept in its
 * storage to operate (the chisel moved off the worker onto the workshop, like the carpenter's saw);
 * the Crafter who staffs it stays tool-free.
 */
@ApiStatus.Internal
public final class MasonryWorkshopRules {
    private MasonryWorkshopRules() {
    }

    @Nullable
    public static Workshop.Status validateMasonry(ServerLevel sl, Workshop workshop,
                                                  Set<BlockPos> marked,
                                                  List<BlockPos> reachableWork,
                                                  List<BlockPos> reachableStorage) {
        return hasChiselInStorage(sl, reachableStorage) ? null : Workshop.Status.MISSING_TOOL;
    }

    /** True when any reachable storage block holds a stone chisel. */
    private static boolean hasChiselInStorage(ServerLevel sl, List<BlockPos> reachableStorage) {
        for (BlockPos p : reachableStorage) {
            IItemHandler h = sl.getCapability(Capabilities.ItemHandler.BLOCK, p, null);
            if (h == null) continue;
            for (int slot = 0; slot < h.getSlots(); slot++) {
                ItemStack s = h.getStackInSlot(slot);
                if (s.is(BannerboundAntiquity.STONE_CHISEL.get())) return true;
            }
        }
        return false;
    }
}
