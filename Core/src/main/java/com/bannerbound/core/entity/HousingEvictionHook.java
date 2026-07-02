package com.bannerbound.core.entity;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.social.ThoughtKind;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Small helper that runs whenever housing logic needs to evict residents — bed loss, House
 * Block break, validation flip to invalid. Centralised here so callers (the BE, the
 * lifecycle event) don't each have to know about every per-citizen side effect of eviction.
 *
 * <p>Effects per evicted citizen:
 * <ul>
 *   <li>Clear all {@code *_HOME} thoughts (NICE / LOVE / LIKE / UNCOMFORTABLE / HATE) so the
 *       screen doesn't show stale rows. {@code NO_HOME} is re-applied by the citizen's own
 *       20-tick poll on the next cycle — no need to add it here.</li>
 *   <li>Recompute happiness so the synched-data slot reflects the cleared thoughts immediately.</li>
 * </ul>
 *
 * <p>No-op for citizens that aren't currently loaded — they'll re-evaluate from chunk thoughts
 * the next time they're ticked anyway.
 */
@ApiStatus.Internal
public final class HousingEvictionHook {
    private HousingEvictionHook() {}

    private static final ThoughtKind[] HOME_THOUGHTS = {
        ThoughtKind.NICE_HOME,
        ThoughtKind.LOVE_HOME,
        ThoughtKind.LIKE_HOME,
        ThoughtKind.UNCOMFORTABLE_HOME,
        ThoughtKind.HATE_HOME
    };

    public static void onEvict(ServerLevel sl, List<UUID> citizenIds) {
        for (UUID id : citizenIds) {
            Entity e = sl.getEntity(id);
            if (!(e instanceof CitizenEntity c)) continue;
            boolean changed = false;
            for (ThoughtKind k : HOME_THOUGHTS) {
                if (c.getThoughts().remove(k, null)) changed = true;
            }
            if (changed) c.recomputeHappiness();
            // An evicted citizen shouldn't keep sleeping in a bed that's no longer part of their
            // valid home — when a house block goes invalid (or loses the bed), wake everyone
            // sleeping in it so they get up and leave, and free the bed's OCCUPIED state.
            wakeIfSleeping(sl, c);
        }
    }

    /** Wake {@code c} if it's asleep and clear the OCCUPIED flag on the bed HEAD it was using.
     *  No-op when the citizen isn't sleeping. */
    private static void wakeIfSleeping(ServerLevel sl, CitizenEntity c) {
        if (!c.isSleeping()) return;
        net.minecraft.core.BlockPos bedPos = c.getSleepingPos().orElse(null);
        c.stopSleeping();
        if (bedPos == null) return;
        net.minecraft.world.level.block.state.BlockState bs = sl.getBlockState(bedPos);
        if (bs.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                && bs.getValue(net.minecraft.world.level.block.BedBlock.PART)
                    == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
            sl.setBlock(bedPos,
                bs.setValue(net.minecraft.world.level.block.BedBlock.OCCUPIED, false),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }
}
