package com.bannerbound.core.world;

import com.bannerbound.core.api.world.BlockSelectionRegistry;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;

import com.bannerbound.core.network.SelectionSyncPayload;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * One-stop helper for pushing the current {@link BlockSelectionRegistry} snapshot out as a
 * {@link SelectionSyncPayload}. Call after any registry mutation (register / unregister / mark
 * completed) so every client with a rod sees the change immediately. Single helper avoids
 * scattering the same enumerate-players boilerplate across call sites.
 */
@ApiStatus.Internal
public final class SelectionBroadcaster {
    private SelectionBroadcaster() {
    }

    /** Broadcast the registry snapshot to every player on {@code level}'s server. */
    public static void broadcast(ServerLevel level) {
        broadcast(level.getServer());
    }

    public static void broadcast(MinecraftServer server) {
        if (server == null) return;
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(server.overworld());
        SelectionSyncPayload payload = new SelectionSyncPayload(new ArrayList<>(registry.getAll()));
        // Workshop overview labels ride along with the wireframes (same triggers, same audience).
        var summaries = com.bannerbound.core.network.WorkshopSummarySyncPayload.build(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
            PacketDistributor.sendToPlayer(player, summaries);
        }
    }

    /** Summaries-only push (no selection snapshot) — used by the background workshop
     *  revalidator, which changes label data but never the box geometry. */
    public static void broadcastSummaries(MinecraftServer server) {
        if (server == null) return;
        var summaries = com.bannerbound.core.network.WorkshopSummarySyncPayload.build(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, summaries);
        }
    }

    /** Single-player push (e.g. on join). */
    public static void sendTo(ServerPlayer player) {
        if (player.getServer() == null) return;
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(player.serverLevel());
        PacketDistributor.sendToPlayer(player,
            new SelectionSyncPayload(new ArrayList<>(registry.getAll())));
        PacketDistributor.sendToPlayer(player,
            com.bannerbound.core.network.WorkshopSummarySyncPayload.build(player.getServer()));
    }
}
