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

/**
 * Shared NPC drying-rack tending, split by {@link DryingRackRecipe#category()}: the Cook tends
 * {@code food} recipes (jerky, dried fish) on its kitchen's racks, General Crafts tends
 * {@code craft} recipes (plant fiber → thatch) on its own — and {@code none} recipes (cured hide,
 * the Tannery's leather line) are touched by neither. Racks are workshop AUXILIARIES (found in the
 * marked set, never registered work blocks), the same pattern as the tannery's clay tank.
 */
@ApiStatus.Internal
final class RackTending {
    private RackTending() {
    }

    /** Every drying rack inside the workshop's marked boxes. */
    static List<BlockPos> racks(ServerLevel sl, Workshop workshop) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id());
        List<BlockPos> out = new ArrayList<>();
        if (boxes.isEmpty()) return out;
        for (BlockPos pos : Homes.collectMarkedSolids(sl, boxes)) {
            if (sl.getBlockEntity(pos) instanceof DryingRackBlockEntity) out.add(pos.immutable());
        }
        return out;
    }

    /** A rack position holding a finished slot of the given category, or {@code null}. */
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

    /** The dried result waiting on {@code rackPos} for this category, or EMPTY. */
    static net.minecraft.world.item.ItemStack dryResultAt(ServerLevel sl, BlockPos rackPos, String category) {
        if (!(sl.getBlockEntity(rackPos) instanceof DryingRackBlockEntity rack)) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        int slot = rack.firstDrySlot(r -> r.category().equals(category));
        return slot < 0 ? net.minecraft.world.item.ItemStack.EMPTY : rack.result(slot).copy();
    }

    /** Take the first finished slot of this category off the rack (EMPTY if raced away). */
    static net.minecraft.world.item.ItemStack takeDry(ServerLevel sl, BlockPos rackPos, String category) {
        if (!(sl.getBlockEntity(rackPos) instanceof DryingRackBlockEntity rack)) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        int slot = rack.firstDrySlot(r -> r.category().equals(category));
        return slot < 0 ? net.minecraft.world.item.ItemStack.EMPTY : rack.takeSlot(slot);
    }

    /** A rack with a free spot, or {@code null}. */
    @Nullable
    static BlockPos rackWithRoom(ServerLevel sl, List<BlockPos> racks) {
        for (BlockPos pos : racks) {
            if (sl.getBlockEntity(pos) instanceof DryingRackBlockEntity rack && !rack.isFull()) {
                return pos;
            }
        }
        return null;
    }

    /** How many of {@code recipe}'s units hang on the workshop's racks — drying OR dry-uncollected;
     *  both are committed units the {@code Workshops.wantsAnother} demand check must subtract. */
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

    /** Every loaded drying recipe of {@code category}. */
    static List<DryingRackRecipe> recipes(String category) {
        List<DryingRackRecipe> out = new ArrayList<>();
        for (DryingRackRecipe recipe : DryingRackRecipeManager.all()) {
            if (recipe.category().equals(category)) out.add(recipe);
        }
        return out;
    }
}
