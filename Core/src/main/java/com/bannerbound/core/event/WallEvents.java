package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.walls.WallData;
import com.bannerbound.core.api.walls.WallSync;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Walls-system glue events: blueprint push on login, and the placement-time wall tagging —
 * a block placed into a blueprint position that satisfies it is recorded in
 * {@code WallData.builtWall} the moment it lands (user decision 2026-06-11: wall membership
 * is tagged AT PLACEMENT, so even a cancelled dirt wall is remembered as wall, never read as
 * terrain). Breaking a tracked block untags it; non-event removals (explosions) are swept by
 * {@code WallData.reconcile} on construct/status.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class WallEvents {

    private WallEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        SettlementData data = SettlementData.get(player.getServer().overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) return;
        if (WallData.get(player.serverLevel()).plan(settlement.id()) == null) return;
        WallSync.syncPlayer(player, settlement);
    }

    @SubscribeEvent
    public static void onBlockPlaced(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        Settlement settlement = SettlementData.get(level.getServer().overworld())
            .getByChunk(new net.minecraft.world.level.ChunkPos(event.getPos()).toLong());
        if (settlement == null) return;
        WallData walls = WallData.get(level);
        it.unimi.dsi.fastutil.longs.Long2ObjectMap<net.minecraft.world.level.block.state.BlockState>
            blueprint = walls.blueprint(level, settlement.id(),
                com.bannerbound.core.api.walls.WallService.resolver(level, settlement));
        if (blueprint.isEmpty()) return;
        net.minecraft.world.level.block.state.BlockState expected =
            blueprint.get(event.getPos().asLong());
        if (expected != null && event.getPlacedBlock().is(expected.getBlock())) {
            walls.markBuilt(settlement.id(), event.getPos().asLong());
        }
        // The DESIGN is the authority on block states: snap the placed block (vanilla
        // placement picked its own connections) AND any neighboring wall blocks vanilla's
        // shape updates just mutated back to the blueprint's exact baked states ("keep
        // exactly the block-states/connections from the wall design", playtest 2026-06-12).
        snapToBlueprint(level, blueprint, event.getPos());
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            snapToBlueprint(level, blueprint, event.getPos().relative(direction));
        }
    }

    /** If {@code pos} is a blueprint position holding the RIGHT block in the WRONG state,
     *  rewrite it to the design's exact state — flag 2|16 (client update, NO neighbor shape
     *  updates) so the correction can't cascade into more vanilla mutations. */
    private static void snapToBlueprint(net.minecraft.server.level.ServerLevel level,
            it.unimi.dsi.fastutil.longs.Long2ObjectMap<net.minecraft.world.level.block.state.BlockState> blueprint,
            net.minecraft.core.BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState expected = blueprint.get(pos.asLong());
        if (expected == null) return;
        net.minecraft.world.level.block.state.BlockState actual = level.getBlockState(pos);
        if (actual != expected && actual.is(expected.getBlock())) {
            level.setBlock(pos, expected, 2 | 16);
        }
    }

    @SubscribeEvent
    public static void onBlockBroken(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        Settlement settlement = SettlementData.get(level.getServer().overworld())
            .getByChunk(new net.minecraft.world.level.ChunkPos(event.getPos()).toLong());
        if (settlement == null) return;
        WallData.get(level).clearBuilt(settlement.id(), event.getPos().asLong());
    }
}
