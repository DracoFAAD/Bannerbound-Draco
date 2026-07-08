package com.bannerbound.core.codex;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Overworld SavedData holding each player's Chronicle state: which entries are unlocked, which
 * have been seen, and the auto-pin-tutorial preference. Always fetched via get() against the
 * overworld data storage so every dimension shares one store. PlayerState mutators return true
 * only when something actually changed, letting callers decide when to setDirty and re-sync.
 */
public final class CodexPlayerData extends SavedData {
    private static final String DATA_NAME = "bannerbound_chronicle_players";
    private final Map<UUID, PlayerState> states = new HashMap<>();

    public static CodexPlayerData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<CodexPlayerData> factory() {
        return new Factory<>(CodexPlayerData::new, CodexPlayerData::load);
    }

    public PlayerState state(UUID playerId) {
        return states.computeIfAbsent(playerId, id -> new PlayerState());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, PlayerState> entry : states.entrySet()) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("Player", entry.getKey());
            playerTag.put("Unlocked", writeStrings(entry.getValue().unlocked));
            playerTag.put("Seen", writeStrings(entry.getValue().seen));
            playerTag.put("PopupsFired", writeStrings(entry.getValue().popupsFired));
            playerTag.putBoolean("AutoPinTutorial", entry.getValue().autoPinTutorial);
            playerTag.putBoolean("PopupsEnabled", entry.getValue().popupsEnabled);
            players.add(playerTag);
        }
        tag.put("Players", players);
        return tag;
    }

    public static CodexPlayerData load(CompoundTag tag, HolderLookup.Provider provider) {
        CodexPlayerData data = new CodexPlayerData();
        ListTag players = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag playerTag = players.getCompound(i);
            if (!playerTag.hasUUID("Player")) continue;
            PlayerState state = new PlayerState();
            state.unlocked.addAll(readStrings(playerTag.getList("Unlocked", Tag.TAG_STRING)));
            state.seen.addAll(readStrings(playerTag.getList("Seen", Tag.TAG_STRING)));
            state.popupsFired.addAll(readStrings(playerTag.getList("PopupsFired", Tag.TAG_STRING)));
            // Save-format invariant: absent tag = pre-toggle save, so default auto-pin ON.
            state.autoPinTutorial = !playerTag.contains("AutoPinTutorial")
                || playerTag.getBoolean("AutoPinTutorial");
            state.popupsEnabled = !playerTag.contains("PopupsEnabled")
                || playerTag.getBoolean("PopupsEnabled");
            data.states.put(playerTag.getUUID("Player"), state);
        }
        return data;
    }

    private static ListTag writeStrings(Set<String> values) {
        ListTag list = new ListTag();
        for (String value : values) list.add(StringTag.valueOf(value));
        return list;
    }

    private static Set<String> readStrings(ListTag list) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < list.size(); i++) out.add(list.getString(i));
        return out;
    }

    public static final class PlayerState {
        private final Set<String> unlocked = new HashSet<>();
        private final Set<String> seen = new HashSet<>();
        private final Set<String> popupsFired = new HashSet<>();
        private boolean autoPinTutorial = true;
        private boolean popupsEnabled = true;

        public boolean popupsEnabled() {
            return popupsEnabled;
        }

        boolean setPopupsEnabled(boolean value) {
            if (popupsEnabled == value) return false;
            popupsEnabled = value;
            return true;
        }

        boolean isPopupFired(String id) {
            return popupsFired.contains(id);
        }

        boolean firePopup(String id) {
            return popupsFired.add(id);
        }

        public boolean resetPopups() {
            boolean changed = !popupsFired.isEmpty();
            popupsFired.clear();
            return changed;
        }

        public Set<String> unlocked() {
            return Set.copyOf(unlocked);
        }

        public boolean autoPinTutorial() {
            return autoPinTutorial;
        }

        boolean setAutoPinTutorial(boolean value) {
            if (autoPinTutorial == value) return false;
            autoPinTutorial = value;
            return true;
        }

        public Set<String> seen() {
            return Set.copyOf(seen);
        }

        boolean isUnlocked(String id) {
            return unlocked.contains(id);
        }

        boolean unlock(String id) {
            return unlocked.add(id);
        }

        boolean lock(String id) {
            boolean a = unlocked.remove(id);
            boolean b = seen.remove(id);
            return a || b;
        }

        boolean markSeen(String id) {
            return unlocked.contains(id) && seen.add(id);
        }

        boolean reset() {
            boolean changed = !unlocked.isEmpty() || !seen.isEmpty();
            unlocked.clear();
            seen.clear();
            return changed;
        }
    }
}
