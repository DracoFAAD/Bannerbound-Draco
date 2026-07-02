package com.bannerbound.core.faction;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Force-loads every chunk that belongs to a settlement, the same way an FTB Chunks claim keeps
 * the area ticking and resident. Chunks are flagged via {@link ServerLevel#setChunkForced} which
 * NeoForge persists across restarts — but we re-apply on server start just to be defensive in
 * case the world was edited externally.
 * <p>
 * Forces live on the overworld for now (claims are only stored as packed {@link ChunkPos}).
 * When we add per-dimension claims, this needs a per-level map.
 */
@ApiStatus.Internal
public final class ChunkForceLoader {
    private ChunkForceLoader() {
    }

    public static void force(ServerLevel level, long packed) {
        ChunkPos pos = new ChunkPos(packed);
        level.setChunkForced(pos.x, pos.z, true);
    }

    public static void unforce(ServerLevel level, long packed) {
        ChunkPos pos = new ChunkPos(packed);
        level.setChunkForced(pos.x, pos.z, false);
    }

    public static void forceAll(ServerLevel level, Settlement settlement) {
        for (long packed : settlement.claimedChunks()) {
            force(level, packed);
        }
    }

    public static void unforceAll(ServerLevel level, Settlement settlement) {
        for (long packed : settlement.claimedChunks()) {
            unforce(level, packed);
        }
    }

    /**
     * Walks every known settlement and re-applies force-loading to all their claimed chunks.
     * Called on server start so the chunks come up loaded before any settlement tick runs.
     */
    public static void reapplyAll(MinecraftServer server) {
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        SettlementData data = SettlementData.get(overworld);
        for (Settlement s : data.all()) {
            forceAll(overworld, s);
        }
    }
}
