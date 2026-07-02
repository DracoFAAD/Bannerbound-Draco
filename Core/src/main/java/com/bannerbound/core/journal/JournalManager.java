package com.bannerbound.core.journal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.network.JournalSyncPayload;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-side journal helpers and sync. */
public final class JournalManager {
    private JournalManager() {
    }

    public static void putForPlayer(ServerPlayer player, JournalEntry entry) {
        if (player == null || entry == null || player.getServer() == null) return;
        JournalPlayerData data = JournalPlayerData.get(player.getServer().overworld());
        List<JournalEntry> entries = data.entriesFor(player.getUUID());
        entries.removeIf(existing -> existing.instanceId().equals(entry.instanceId()));
        entries.add(entry);
        data.setDirty();
        sendTo(player);
    }

    public static boolean removeForPlayer(ServerPlayer player, UUID instanceId) {
        if (player == null || instanceId == null || player.getServer() == null) return false;
        JournalPlayerData data = JournalPlayerData.get(player.getServer().overworld());
        boolean changed = data.entriesFor(player.getUUID())
            .removeIf(entry -> entry.instanceId().equals(instanceId));
        if (changed) {
            data.setDirty();
            sendTo(player);
        }
        return changed;
    }

    public static int removeForPlayerBySource(ServerPlayer player, String sourceType, String sourceId) {
        if (player == null || player.getServer() == null) return 0;
        JournalPlayerData data = JournalPlayerData.get(player.getServer().overworld());
        List<JournalEntry> entries = data.entriesFor(player.getUUID());
        int before = entries.size();
        entries.removeIf(entry -> entry.sourceType().equals(sourceType == null ? "" : sourceType)
            && entry.sourceId().equals(sourceId == null ? "" : sourceId));
        int removed = before - entries.size();
        if (removed > 0) {
            data.setDirty();
            sendTo(player);
        }
        return removed;
    }

    public static void putForSettlement(MinecraftServer server, Settlement settlement, JournalEntry entry) {
        if (server == null || settlement == null || entry == null) return;
        settlement.putJournalEntry(entry);
        SettlementData.get(server.overworld()).setDirty();
        broadcastSettlement(server, settlement);
    }

    public static boolean removeForSettlement(MinecraftServer server, Settlement settlement, UUID instanceId) {
        if (server == null || settlement == null || instanceId == null) return false;
        boolean changed = settlement.removeJournalEntry(instanceId);
        if (changed) {
            SettlementData.get(server.overworld()).setDirty();
            broadcastSettlement(server, settlement);
        }
        return changed;
    }

    public static boolean removeForSettlementBySource(MinecraftServer server, Settlement settlement,
                                                      String sourceType, String sourceId) {
        if (server == null || settlement == null) return false;
        JournalEntry entry = settlement.findJournalEntry(sourceType == null ? "" : sourceType,
            sourceId == null ? "" : sourceId);
        return entry != null && removeForSettlement(server, settlement, entry.instanceId());
    }

    public static void sendTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        List<JournalEntry> entries = new ArrayList<>();
        if (settlement != null) entries.addAll(settlement.journalEntries());
        entries.addAll(JournalPlayerData.get(server.overworld()).entriesFor(player.getUUID()));
        entries.sort(Comparator
            .comparing((JournalEntry e) -> e.type().ordinal())
            .thenComparing(Comparator.comparingInt(JournalEntry::priority).reversed())
            .thenComparingLong(JournalEntry::createdTick));
        PacketDistributor.sendToPlayer(player, JournalSyncPayload.fromEntries(entries, server.overworld().getGameTime()));
    }

    public static void broadcastSettlement(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        for (java.util.UUID memberId : settlement.members()) {
            ServerPlayer player = server.getPlayerList().getPlayer(memberId);
            if (player != null) sendTo(player);
        }
    }
}
