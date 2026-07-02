package com.bannerbound.antiquity.workshop;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.BloomeryBlock;
import com.bannerbound.antiquity.block.BellowsBlock;
import com.bannerbound.antiquity.block.entity.BloomeryBlockEntity;
import com.bannerbound.antiquity.item.HammerItem;
import com.bannerbound.core.api.settlement.Homes;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Structure rules for Antiquity smithy workshops: a smithy needs a complete bloomery with a bellows
 * beside it (the smith's heat source — {@code MISSING_HEAT_SOURCE}) and a hammer kept in reachable
 * storage (the carpentry bone-saw pattern — {@code MISSING_TOOL}). The hammer's material rank also
 * caps NPC craft quality, exactly like the player minigame's hammer-rank gate.
 */
@ApiStatus.Internal
public final class SmithyWorkshopRules {
    private SmithyWorkshopRules() {
    }

    @Nullable
    public static Workshop.Status validateSmithy(ServerLevel sl, Workshop workshop, Set<BlockPos> marked,
                                                 List<BlockPos> reachableWork, List<BlockPos> reachableStorage) {
        BlockPos bloomery = findBloomeryLowerIn(sl, marked);
        if (bloomery == null || findBellows(sl, bloomery) == null) {
            return Workshop.Status.MISSING_HEAT_SOURCE;
        }
        return bestHammerRank(sl, reachableStorage) >= 0 ? null : Workshop.Status.MISSING_TOOL;
    }

    @Nullable
    private static BlockPos findBloomeryLowerIn(ServerLevel sl, Set<BlockPos> marked) {
        for (BlockPos pos : marked) {
            var state = sl.getBlockState(pos);
            if (state.getBlock() instanceof BloomeryBlock
                    && state.getValue(BloomeryBlock.HALF) == DoubleBlockHalf.LOWER) {
                return pos.immutable();
            }
        }
        return null;
    }

    /** The workshop's bloomery (the lower half's block entity), resolved from its marked boxes. */
    @Nullable
    public static BloomeryBlockEntity findBloomery(ServerLevel sl, Workshop workshop) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id());
        if (boxes.isEmpty()) return null;
        BlockPos lower = findBloomeryLowerIn(sl, Homes.collectMarkedSolids(sl, boxes));
        return lower != null && sl.getBlockEntity(lower) instanceof BloomeryBlockEntity be ? be : null;
    }

    /** A bellows block horizontally beside the bloomery's lower cell (where the smith stands to
     *  pump), or {@code null}. */
    @Nullable
    public static BlockPos findBellows(ServerLevel sl, BlockPos bloomeryLower) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = bloomeryLower.relative(d);
            if (sl.getBlockState(p).getBlock() instanceof BellowsBlock) return p.immutable();
        }
        return null;
    }

    /** The best hammer rank in the given storage blocks ({@code -1} = no hammer at all). Scans the
     *  handlers directly — the workshop's cached storage list isn't guaranteed mid-validation. */
    public static int bestHammerRank(ServerLevel sl, List<BlockPos> storage) {
        int best = -1;
        for (BlockPos p : storage) {
            IItemHandler h = sl.getCapability(Capabilities.ItemHandler.BLOCK, p, null);
            if (h == null) continue;
            for (int slot = 0; slot < h.getSlots(); slot++) {
                ItemStack s = h.getStackInSlot(slot);
                if (s.getItem() instanceof HammerItem hammer) {
                    best = Math.max(best, hammer.rank());
                }
            }
        }
        return best;
    }

    /** The best hammer rank kept anywhere in this workshop's storage ({@code -1} = none). */
    public static int bestHammerRank(ServerLevel sl, Workshop workshop) {
        return bestHammerRank(sl, workshop.storageBlocks());
    }
}
