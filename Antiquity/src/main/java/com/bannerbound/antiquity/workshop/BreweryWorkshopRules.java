package com.bannerbound.antiquity.workshop;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.FermentationTroughBlock;
import com.bannerbound.antiquity.block.MortarAndPestleBlock;
import com.bannerbound.core.api.settlement.Homes;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Structure rules for Antiquity brewery workshops: a brewery needs a Mortar & Pestle inside —
 *  the brewer pestles raw fermentables (berries) there before charging the troughs. */
@ApiStatus.Internal
public final class BreweryWorkshopRules {
    private BreweryWorkshopRules() {
    }

    @Nullable
    public static Workshop.Status validateBrewery(ServerLevel sl, Workshop workshop, Set<BlockPos> marked,
                                                  List<BlockPos> reachableWork, List<BlockPos> reachableStorage) {
        return findMortarIn(sl, marked) == null ? Workshop.Status.MISSING_CRAFTING_SURFACE : null;
    }

    @Nullable
    private static BlockPos findMortarIn(ServerLevel sl, Set<BlockPos> marked) {
        for (BlockPos pos : marked) {
            if (sl.getBlockState(pos).getBlock() instanceof MortarAndPestleBlock) {
                return pos.immutable();
            }
        }
        return null;
    }

    /** The workshop's Mortar & Pestle, resolved from its marked boxes, or {@code null}. The brewer
     *  stands here for PESTLE crafts (the validator guarantees one exists in a valid brewery). */
    @Nullable
    public static BlockPos findMortar(ServerLevel sl, Workshop workshop) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id());
        if (boxes.isEmpty()) return null;
        return findMortarIn(sl, Homes.collectMarkedSolids(sl, boxes));
    }

    /** How many of this workshop's trough pools are UNCHARGED (plain water / empty — not fermenting,
     *  not holding finished grog). Work blocks are pool anchors (one per connected run), so this is a
     *  straight count; it sizes the brewer's standing pestled-item demand: one charge item per pool. */
    public static int unchargedPools(ServerLevel sl, Workshop workshop) {
        int count = 0;
        for (BlockPos p : workshop.workBlocks()) {
            if (sl.getBlockState(p).getBlock() instanceof FermentationTroughBlock
                    && !FermentationTroughBlock.isPoolCharged(sl, p)) {
                count++;
            }
        }
        return count;
    }
}
