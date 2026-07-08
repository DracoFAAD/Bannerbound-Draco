package com.bannerbound.antiquity.workshop;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.DryingRackBlockEntity;
import com.bannerbound.antiquity.recipe.DryingRackRecipe;
import com.bannerbound.antiquity.recipe.DryingRackRecipeManager;
import com.bannerbound.core.api.settlement.Homes;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import com.bannerbound.antiquity.craft.Tannery;

/**
 * Shared NPC drying-rack tending, split by {@link DryingRackRecipe#category()}: the Cook tends
 * {@code food} recipes (jerky, dried fish) on its kitchen's racks, General Crafts tends
 * {@code craft} recipes (plant fiber -> thatch) on its own, and {@code none} recipes (cured hide,
 * the Tannery's leather line) are touched by neither. Racks are workshop AUXILIARIES (found in
 * the marked set, never registered work blocks), the same pattern as the tannery's clay tank.
 * inFlight counts a recipe's units that hang on the racks whether still drying or dry-but-
 * uncollected: both are committed, so the Workshops.wantsAnother demand check must subtract them
 * or executors keep hanging extra inputs. Executors subtract inFlightForResult, not per-recipe
 * inFlight: two recipes can dry into the SAME item (plant fiber and dry fiber both -> thatch
 * bundle), and counting only one recipe's hangs lets its sibling double-fill the demand.
 * takeDry returns EMPTY if the slot was raced away.
 */
@ApiStatus.Internal
final class RackTending {
    private RackTending() {
    }

    static List<BlockPos> racks(ServerLevel sl, Workshop workshop) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id());
        List<BlockPos> out = new ArrayList<>();
        if (boxes.isEmpty()) return out;
        for (BlockPos pos : Homes.collectMarkedSolids(sl, boxes)) {
            if (sl.getBlockEntity(pos) instanceof DryingRackBlockEntity) out.add(pos.immutable());
        }
        return out;
    }

    @Nullable
    static BlockPos rackWithDry(ServerLevel sl, List<BlockPos> racks, String category) {
        for (BlockPos pos : racks) {
            if (sl.getBlockEntity(pos) instanceof DryingRackBlockEntity rack
                    && rack.firstDrySlot(r -> r.category().equals(category)) >= 0) {
                return pos;
            }
        }
        return null;
    }

    static net.minecraft.world.item.ItemStack dryResultAt(ServerLevel sl, BlockPos rackPos, String category) {
        if (!(sl.getBlockEntity(rackPos) instanceof DryingRackBlockEntity rack)) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        int slot = rack.firstDrySlot(r -> r.category().equals(category));
        return slot < 0 ? net.minecraft.world.item.ItemStack.EMPTY : rack.result(slot).copy();
    }

    static net.minecraft.world.item.ItemStack takeDry(ServerLevel sl, BlockPos rackPos, String category) {
        if (!(sl.getBlockEntity(rackPos) instanceof DryingRackBlockEntity rack)) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        int slot = rack.firstDrySlot(r -> r.category().equals(category));
        return slot < 0 ? net.minecraft.world.item.ItemStack.EMPTY : rack.takeSlot(slot);
    }

    @Nullable
    static BlockPos rackWithRoom(ServerLevel sl, List<BlockPos> racks) {
        for (BlockPos pos : racks) {
            if (sl.getBlockEntity(pos) instanceof DryingRackBlockEntity rack && !rack.isFull()) {
                return pos;
            }
        }
        return null;
    }

    static int inFlight(ServerLevel sl, List<BlockPos> racks, DryingRackRecipe recipe) {
        int count = 0;
        for (BlockPos pos : racks) {
            if (!(sl.getBlockEntity(pos) instanceof DryingRackBlockEntity rack)) continue;
            for (int i = 0; i < DryingRackBlockEntity.SLOTS; i++) {
                if (rack.input(i).is(recipe.input())) count += Math.max(1, recipe.result().getCount());
            }
        }
        return count;
    }

    static int inFlightForResult(ServerLevel sl, List<BlockPos> racks, String category,
                                 net.minecraft.world.item.Item result) {
        int count = 0;
        for (DryingRackRecipe recipe : recipes(category)) {
            if (recipe.result().getItem() == result) count += inFlight(sl, racks, recipe);
        }
        return count;
    }

    static List<DryingRackRecipe> recipes(String category) {
        List<DryingRackRecipe> out = new ArrayList<>();
        for (DryingRackRecipe recipe : DryingRackRecipeManager.all()) {
            if (recipe.category().equals(category)) out.add(recipe);
        }
        return out;
    }
}
