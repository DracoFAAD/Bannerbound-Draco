package com.bannerbound.antiquity.workshop;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.StoneCookingPotBlockEntity;
import com.bannerbound.core.api.settlement.Workshop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Structure rules for Antiquity kitchen workshops: a kitchen needs at least one stone cooking pot
 *  sitting on a lit campfire (not the town hall's) — the pot's own heat test, so the workshop status
 *  flips the moment the fire goes out. */
@ApiStatus.Internal
public final class CookingWorkshopRules {
    private CookingWorkshopRules() {
    }

    @Nullable
    public static Workshop.Status validateKitchen(ServerLevel sl, Workshop workshop, Set<BlockPos> marked,
                                                  List<BlockPos> reachableWork, List<BlockPos> reachableStorage) {
        for (BlockPos pos : marked) {
            if (sl.getBlockEntity(pos) instanceof StoneCookingPotBlockEntity pot && pot.isHeated()) {
                return null;
            }
        }
        return Workshop.Status.MISSING_HEAT_SOURCE;
    }

    /** Horizontal reach (blocks) the cook will walk to scoop water for a pot. */
    private static final int WATER_SCOOP_RADIUS = 10;

    /** The nearest open-water source within scoop reach of {@code pot}, or {@code null} — where the
     *  cook walks with the fired clay bucket (the tannery's water-fetch idiom). */
    @Nullable
    public static BlockPos findWaterSource(ServerLevel sl, BlockPos pot) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -WATER_SCOOP_RADIUS; dx <= WATER_SCOOP_RADIUS; dx++) {
                for (int dz = -WATER_SCOOP_RADIUS; dz <= WATER_SCOOP_RADIUS; dz++) {
                    p.set(pot.getX() + dx, pot.getY() + dy, pot.getZ() + dz);
                    net.minecraft.world.level.material.FluidState f = sl.getFluidState(p);
                    if (f.is(net.minecraft.tags.FluidTags.WATER) && f.isSource()) {
                        double d = pot.distSqr(p);
                        if (d < bestDistSq) {
                            bestDistSq = d;
                            best = p.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    /** The kitchen's open roasting fires: lit campfires in the marked set that are NOT under a
     *  cooking pot (those are the pots' heat) and not the settlement's town-hall campfire. The cook
     *  lays raw food on their vanilla roast slots; the finished items pop off onto the ground. */
    public static List<BlockPos> roastingFires(ServerLevel sl,
                                               com.bannerbound.core.api.settlement.Settlement settlement,
                                               Workshop workshop) {
        List<com.bannerbound.core.api.world.BlockSelection> boxes =
            com.bannerbound.core.api.world.BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id());
        List<BlockPos> fires = new java.util.ArrayList<>();
        if (boxes.isEmpty()) return fires;
        for (BlockPos pos : com.bannerbound.core.api.settlement.Homes.collectMarkedSolids(sl, boxes)) {
            var state = sl.getBlockState(pos);
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock)
                    || !state.getValue(net.minecraft.world.level.block.CampfireBlock.LIT)) continue;
            if (sl.getBlockState(pos.above()).getBlock()
                    instanceof com.bannerbound.antiquity.block.StoneCookingPotBlock) continue;
            if (pos.equals(settlement.townHallPos())) continue;
            fires.add(pos.immutable());
        }
        return fires;
    }

    /** How many of this kitchen's pots sit IDLE — empty (no water, no stew, nothing simmering).
     *  Sizes the cook's standing ingredient demand for the stocker. */
    public static int idlePots(ServerLevel sl, Workshop workshop) {
        int count = 0;
        for (BlockPos p : workshop.workBlocks()) {
            if (sl.getBlockEntity(p) instanceof StoneCookingPotBlockEntity pot
                    && !pot.hasStew() && !pot.isCooking() && !pot.hasWater()) {
                count++;
            }
        }
        return count;
    }
}
