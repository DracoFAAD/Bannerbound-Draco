package com.bannerbound.core.journal;

/** Broad journal buckets. HUD sorting keeps crises first, then quests, then tutorials. */
public enum JournalEntryType {
    CRISIS,
    QUEST,
    TUTORIAL;

    public static JournalEntryType byName(String name) {
        if (name != null) {
            for (JournalEntryType type : values()) {
                if (type.name().equalsIgnoreCase(name)) return type;
            }
        }
        return QUEST;
    }
}
