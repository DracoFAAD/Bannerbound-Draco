package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.DropOffContainers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Routes drops spawned during a forester's capture window straight into their assigned Forester's
 * Log block entity — no ground items, no pickup walking. The capture window is opened by
 * {@code ForesterWorkGoal.chopLog} when it hands the tree off to Pandas Falling Trees; PFT then
 * spawns ItemEntities along the falling-tree path over a couple of seconds, and we intercept
 * each one before it actually enters the world.
 * <p>
 * Anything the forester can't store (unknown item, workstation full, BE chunk unloaded,
 * workstation reassigned) is left to spawn as a normal ground ItemEntity — the capture window
 * can't prove an item came from the fell (PFT spawns drops with no originating block), so
 * deleting here also deleted player Q-drops and mob loot that happened to land in the window.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class ForesterDropCaptureEvents {
    /** Box half-extent for the broad-phase citizen lookup. Larger than the capture radius to cover
     *  citizens whose center is at the edge of the capture circle. */
    private static final double LOOKUP_INFLATE = 24.0;
    /** Squared capture radius around the felled trunk. Tighter than the old 16-block sphere (which
     *  siphoned unrelated player/mob drops); 10 still spans the falling-tree drop path of a normal
     *  tree — a giant's far-end drops just land on the ground instead of being captured. */
    private static final double CAPTURE_RADIUS_SQ = 10.0 * 10.0;

    private ForesterDropCaptureEvents() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        if (!(event.getEntity() instanceof ItemEntity itemEntity)) return;
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) return;

        BlockPos itemPos = itemEntity.blockPosition();
        AABB box = new AABB(itemPos).inflate(LOOKUP_INFLATE);
        List<CitizenEntity> candidates = level.getEntitiesOfClass(CitizenEntity.class, box,
            CitizenEntity::isCaptureWindowActive);
        if (candidates.isEmpty()) return;

        ItemStack remaining = stack;
        for (CitizenEntity citizen : candidates) {
            BlockPos center = citizen.getCaptureCenter();
            if (center == null) continue;
            if (center.distSqr(itemPos) > CAPTURE_RADIUS_SQ) continue;

            // Drops this civ doesn't recognize yet (e.g. saplings before they're researched) aren't
            // storable — skip capture and let them spawn. NEVER cancel here: the window can't tell a
            // fell drop from a player's dropped stack, and cancelling deleted the latter.
            if (!com.bannerbound.core.api.research.SettlementDropFilter.shouldDrop(
                    citizen.getSettlement(), null, remaining)) {
                continue;
            }

            // Resolve the citizen's marked drop-off container (chest / Antiquity basket). Removed
            // or replaced between the PFT call and the drop spawn is possible; resolveDropOff
            // returns null then and we skip — the next candidate (or the ground spawn) handles it.
            Container depot = DropOffContainers.resolveDropOff(level, citizen.getDropOff());
            if (depot == null) continue;

            remaining = DropOffContainers.insert(depot, remaining);
            if (remaining.isEmpty()) {
                // Whole stack soaked up by this drop-off → kill the entity before it spawns.
                event.setCanceled(true);
                return;
            }
        }

        // Anything still in `remaining` couldn't fit any nearby forester's BE. Leave it to spawn
        // as a ground item (shrunk to what wasn't banked) — never delete what we can't attribute.
        itemEntity.setItem(remaining);
    }
}
