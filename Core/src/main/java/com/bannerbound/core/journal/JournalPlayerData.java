package com.bannerbound.core.journal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** World SavedData for player-scoped journal entries such as tutorials. */
public final class JournalPlayerData extends SavedData {
    private static final String DATA_NAME = "bannerbound_player_journal";

    private final Map<UUID, List<JournalEntry>> entriesByPlayer = new HashMap<>();

    public static JournalPlayerData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<JournalPlayerData> factory() {
        return new Factory<>(JournalPlayerData::new, JournalPlayerData::load);
    }

    public List<JournalEntry> entriesFor(UUID playerId) {
        return entriesByPlayer.computeIfAbsent(playerId, id -> new ArrayList<>());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, List<JournalEntry>> e : entriesByPlayer.entrySet()) {
            CompoundTag player = new CompoundTag();
            player.putUUID("Player", e.getKey());
            ListTag entries = new ListTag();
            for (JournalEntry entry : e.getValue()) entries.add(entry.save());
            player.put("Entries", entries);
            players.add(player);
        }
        tag.put("Players", players);
        return tag;
    }

    public static JournalPlayerData load(CompoundTag tag, HolderLookup.Provider provider) {
        JournalPlayerData data = new JournalPlayerData();
        ListTag players = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag player = players.getCompound(i);
            if (!player.hasUUID("Player")) continue;
            UUID id = player.getUUID("Player");
            List<JournalEntry> entries = new ArrayList<>();
            ListTag list = player.getList("Entries", Tag.TAG_COMPOUND);
            for (int j = 0; j < list.size(); j++) entries.add(JournalEntry.load(list.getCompound(j)));
            data.entriesByPlayer.put(id, entries);
        }
        return data;
    }
}
