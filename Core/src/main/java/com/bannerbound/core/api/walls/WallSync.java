package com.bannerbound.core.api.walls;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.network.WallBlueprintSyncPayload;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side blueprint push: expands the settlement's frozen {@link WallPlan} (against the
 * default design set — the library plugs in here in Phase 5) and ships it to settlement
 * members as {@code WallBlueprintSyncPayload}. Called on construct/cancel/adapt and on login;
 * a settlement without a plan syncs an empty payload, clearing the client's ghosts.
 */
public final class WallSync {

    private WallSync() {
    }

    public static void syncSettlement(ServerLevel level, Settlement settlement) {
        WallBlueprintSyncPayload payload = buildPayload(level, settlement);
        for (UUID member : settlement.members()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(member);
            if (player != null) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    public static void syncPlayer(ServerPlayer player, Settlement settlement) {
        PacketDistributor.sendToPlayer(player, buildPayload(player.serverLevel(), settlement));
    }

    /**
     * Ships an arbitrary (possibly UNCOMMITTED) plan's ghosts to one player — the preview
     * screen's "walk around and inspect before committing" flow. The next committed-state
     * sync (construct, login, plan change) replaces it.
     */
    public static void sendPlanPreview(ServerPlayer player, Settlement settlement, WallPlan plan) {
        PacketDistributor.sendToPlayer(player,
            payloadFor(player.serverLevel(), plan,
                WallService.resolver(player.serverLevel(), settlement)));
    }

    private static WallBlueprintSyncPayload buildPayload(ServerLevel level, Settlement settlement) {
        @Nullable WallPlan plan = WallData.get(level).plan(settlement.id());
        return payloadFor(level, plan, WallService.resolver(level, settlement));
    }

    private static WallBlueprintSyncPayload payloadFor(ServerLevel level, @Nullable WallPlan plan,
            java.util.function.Function<String, WallDesign> resolver) {
        if (plan == null) {
            return new WallBlueprintSyncPayload(new long[0], new int[0]);
        }
        // Baked connections: the ghosts show the EXACT states that will be built.
        Long2ObjectMap<BlockState> blueprint =
            WallConnectivity.bake(plan.buildBlueprint(resolver), level);
        long[] positions = new long[blueprint.size()];
        int[] stateIds = new int[blueprint.size()];
        int i = 0;
        for (Long2ObjectMap.Entry<BlockState> entry : blueprint.long2ObjectEntrySet()) {
            positions[i] = entry.getLongKey();
            stateIds[i] = Block.getId(entry.getValue());
            i++;
        }
        return new WallBlueprintSyncPayload(positions, stateIds);
    }
}
