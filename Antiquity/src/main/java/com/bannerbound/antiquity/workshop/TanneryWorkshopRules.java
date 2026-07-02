package com.bannerbound.antiquity.workshop;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.ClayTankBlock;
import com.bannerbound.antiquity.block.entity.ClayTankBlockEntity;
import com.bannerbound.antiquity.block.entity.ClayTankBlockEntity.LiquidType;
import com.bannerbound.core.api.settlement.Homes;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Structure rules for Antiquity tannery workshops: a tannery needs a clay tank inside to cure. */
@ApiStatus.Internal
public final class TanneryWorkshopRules {
    private TanneryWorkshopRules() {
    }

    @Nullable
    public static Workshop.Status validateTannery(ServerLevel sl, Workshop workshop, Set<BlockPos> marked,
                                                  List<BlockPos> reachableWork, List<BlockPos> reachableStorage) {
        return findTankController(sl, marked) == null ? Workshop.Status.MISSING_CURING_LIQUID : null;
    }

    /** A clay tank controller (the bottom, PART 0) inside the marked set, or {@code null}. */
    @Nullable
    public static BlockPos findTankController(ServerLevel sl, Set<BlockPos> marked) {
        for (BlockPos pos : marked) {
            var state = sl.getBlockState(pos);
            if (state.getBlock() instanceof ClayTankBlock && state.getValue(ClayTankBlock.PART) == 0) {
                return pos.immutable();
            }
        }
        return null;
    }

    /** How many clay tank bases (controllers, PART 0) sit inside the workshop. Each one needs its own
     *  fired clay bucket kept in storage — the tanner uses it to scoop water in to charge the tank
     *  (the bucket is never consumed, just required to be present). One tank → one base, normally. */
    public static int countTankBases(ServerLevel sl, Workshop workshop) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id());
        if (boxes.isEmpty()) return 0;
        int count = 0;
        for (BlockPos pos : Homes.collectMarkedSolids(sl, boxes)) {
            var state = sl.getBlockState(pos);
            if (state.getBlock() instanceof ClayTankBlock && state.getValue(ClayTankBlock.PART) == 0) {
                count++;
            }
        }
        return count;
    }

    /** The clay tank controller for a workshop, resolved from its marked boxes, or {@code null}. */
    @Nullable
    public static ClayTankBlockEntity findTank(ServerLevel sl, Workshop workshop) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByWorkshop(workshop.id());
        if (boxes.isEmpty()) return null;
        BlockPos controller = findTankController(sl, Homes.collectMarkedSolids(sl, boxes));
        return controller != null && sl.getBlockEntity(controller) instanceof ClayTankBlockEntity be ? be : null;
    }

    /** How many leather units are already in flight on the workshop's racks — a hide DRYING, or a
     *  finished-but-uncollected leather (DRY). These are committed units the demand check must
     *  subtract (see {@code Workshops.wantsAnother}) so a single order doesn't lay a second hide to
     *  dry while one is already drying. */
    public static int leatherInProgress(ServerLevel sl, Workshop workshop) {
        int count = 0;
        for (BlockPos p : workshop.workBlocks()) {
            if (sl.getBlockEntity(p) instanceof com.bannerbound.antiquity.block.entity.TanningRackBlockEntity rack) {
                var phase = rack.getPhase();
                if (phase == com.bannerbound.antiquity.block.entity.TanningRackBlockEntity.Phase.DRYING
                    || phase == com.bannerbound.antiquity.block.entity.TanningRackBlockEntity.Phase.DRY) {
                    count++;
                }
            }
        }
        return count;
    }

    /** True when the workshop has a clay tank holding curing liquid (a CURE step can run). */
    public static boolean hasCuring(ServerLevel sl, Workshop workshop) {
        ClayTankBlockEntity tank = findTank(sl, workshop);
        return tank != null && tank.getLiquid() == LiquidType.CURING && tank.getBuckets() > 0;
    }

    /** Horizontal reach (blocks) the tanner will walk to fetch water from to charge a tank. */
    private static final int WATER_SCOOP_RADIUS = 10;

    /** The nearest open-water source block within reach of the workshop's tank, or {@code null} when
     *  none is close enough. The tanner walks here with the fired clay bucket, fills it, and returns
     *  to charge the tank — so water no longer has to sit glued to the tank, only somewhere nearby.
     *  Scanned in a small box around the tank pillar (cheap, and only when a charge is actually due). */
    @Nullable
    public static BlockPos findWaterSource(ServerLevel sl, ClayTankBlockEntity tank) {
        BlockPos base = tank.getBlockPos();
        int top = tank.pillarHeight();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dy = -1; dy <= top; dy++) {
            for (int dx = -WATER_SCOOP_RADIUS; dx <= WATER_SCOOP_RADIUS; dx++) {
                for (int dz = -WATER_SCOOP_RADIUS; dz <= WATER_SCOOP_RADIUS; dz++) {
                    p.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    net.minecraft.world.level.material.FluidState f = sl.getFluidState(p);
                    if (f.is(net.minecraft.tags.FluidTags.WATER) && f.isSource()) {
                        double d = base.distSqr(p);
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
}
