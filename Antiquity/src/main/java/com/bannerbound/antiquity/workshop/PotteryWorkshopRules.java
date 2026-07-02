package com.bannerbound.antiquity.workshop;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.KilnBlock;
import com.bannerbound.core.api.settlement.Homes;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Structure rules for Antiquity pottery workshops. */
@ApiStatus.Internal
public final class PotteryWorkshopRules {
    private PotteryWorkshopRules() {
    }

    @Nullable
    public static Workshop.Status validatePottery(ServerLevel sl, Workshop workshop,
                                                  Set<BlockPos> marked,
                                                  List<BlockPos> reachableWork,
                                                  List<BlockPos> reachableStorage) {
        return findCompleteKilnController(sl, marked) == null
            ? Workshop.Status.MISSING_HEAT_SOURCE
            : null;
    }

    @Nullable
    public static BlockPos findCompleteKilnController(ServerLevel sl, Set<BlockPos> marked) {
        for (BlockPos pos : marked) {
            BlockState state = sl.getBlockState(pos);
            if (!state.is(BannerboundAntiquity.KILN.get())
                    || state.getValue(KilnBlock.PART) != 0) {
                continue;
            }
            if (isCompleteKilnAt(sl, marked, pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    @Nullable
    public static BlockPos findCompleteKilnController(ServerLevel sl, Workshop workshop) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id());
        if (boxes.isEmpty()) return null;
        return findCompleteKilnController(sl, Homes.collectMarkedSolids(sl, boxes));
    }

    private static boolean isCompleteKilnAt(ServerLevel sl, Set<BlockPos> marked, BlockPos base) {
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dz = 0; dz < 2; dz++) {
                    BlockPos cell = base.offset(dx, dy, dz);
                    if (!marked.contains(cell)) {
                        return false;
                    }
                    BlockState cellState = sl.getBlockState(cell);
                    int expectedPart = dx * 4 + dy * 2 + dz;
                    if (!cellState.is(BannerboundAntiquity.KILN.get())
                            || cellState.getValue(KilnBlock.PART) != expectedPart) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
