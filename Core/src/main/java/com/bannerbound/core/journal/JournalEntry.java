package com.bannerbound.core.journal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Generic objective/journal entry. Stored either on a settlement or in player-scoped journal
 * data; crises only use settlement entries, but tutorials can use the same shape later.
 */
public final class JournalEntry {
    public static final long HUD_RESOLVED_LINGER_TICKS = 20L * 20L;

    private final UUID instanceId;
    private final String entryId;
    private final JournalEntryType type;
    private String title;
    private String subtitle;
    private int priority;
    private long createdTick;
    private long deadlineTick;
    private long resolvedTick;
    private boolean resolved;
    private boolean failed;
    private String sourceType;
    private String sourceId;
    private String chronicleEntry;
    private List<JournalObjective> objectives;
    /** Optional world target this entry points at (a camp centre, etc.), packed BlockPos; 0 = none.
     *  Drives the client HUD direction text + the world waypoint marker. */
    private long targetPos;

    public JournalEntry(UUID instanceId, String entryId, JournalEntryType type,
                        String title, String subtitle, int priority, long createdTick,
                        long deadlineTick, String sourceType, String sourceId,
                        String chronicleEntry, List<JournalObjective> objectives) {
        this.instanceId = instanceId == null ? UUID.randomUUID() : instanceId;
        this.entryId = entryId == null ? "" : entryId;
        this.type = type == null ? JournalEntryType.QUEST : type;
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.priority = priority;
        this.createdTick = createdTick;
        this.deadlineTick = deadlineTick;
        this.sourceType = sourceType == null ? "" : sourceType;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.chronicleEntry = chronicleEntry == null ? "" : chronicleEntry;
        this.objectives = new ArrayList<>(objectives == null ? List.of() : objectives);
    }

    public UUID instanceId() { return instanceId; }
    public String entryId() { return entryId; }
    public JournalEntryType type() { return type; }
    public String title() { return title; }
    public String subtitle() { return subtitle; }
    public int priority() { return priority; }
    public long createdTick() { return createdTick; }
    public long deadlineTick() { return deadlineTick; }
    public long resolvedTick() { return resolvedTick; }
    public boolean resolved() { return resolved; }
    public boolean failed() { return failed; }
    public String sourceType() { return sourceType; }
    public String sourceId() { return sourceId; }
    public String chronicleEntry() { return chronicleEntry; }
    public List<JournalObjective> objectives() { return List.copyOf(objectives); }
    public long targetPos() { return targetPos; }
    public void setTargetPos(long targetPos) { this.targetPos = targetPos; }

    public void setTitle(String title) { this.title = title == null ? "" : title; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle == null ? "" : subtitle; }
    public void setPriority(int priority) { this.priority = priority; }
    public void setDeadlineTick(long deadlineTick) { this.deadlineTick = deadlineTick; }
    public void setObjectives(List<JournalObjective> objectives) {
        this.objectives = new ArrayList<>(objectives == null ? List.of() : objectives);
    }

    public void resolve(long gameTick, boolean failed) {
        this.resolved = true;
        this.failed = failed;
        this.resolvedTick = gameTick;
    }

    public boolean shouldShowOnHud(long now) {
        return !resolved || now - resolvedTick <= HUD_RESOLVED_LINGER_TICKS;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("InstanceId", instanceId);
        tag.putString("EntryId", entryId);
        tag.putString("Type", type.name());
        tag.putString("Title", title);
        tag.putString("Subtitle", subtitle);
        tag.putInt("Priority", priority);
        tag.putLong("CreatedTick", createdTick);
        tag.putLong("DeadlineTick", deadlineTick);
        tag.putLong("ResolvedTick", resolvedTick);
        tag.putBoolean("Resolved", resolved);
        tag.putBoolean("Failed", failed);
        tag.putString("SourceType", sourceType);
        tag.putString("SourceId", sourceId);
        tag.putString("ChronicleEntry", chronicleEntry);
        if (targetPos != 0L) tag.putLong("TargetPos", targetPos);
        ListTag list = new ListTag();
        for (JournalObjective objective : objectives) list.add(objective.save());
        tag.put("Objectives", list);
        return tag;
    }

    public static JournalEntry load(CompoundTag tag) {
        List<JournalObjective> objectives = new ArrayList<>();
        ListTag list = tag.getList("Objectives", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            objectives.add(JournalObjective.load(list.getCompound(i)));
        }
        JournalEntry entry = new JournalEntry(
            tag.hasUUID("InstanceId") ? tag.getUUID("InstanceId") : UUID.randomUUID(),
            tag.getString("EntryId"),
            JournalEntryType.byName(tag.getString("Type")),
            tag.getString("Title"),
            tag.getString("Subtitle"),
            tag.getInt("Priority"),
            tag.getLong("CreatedTick"),
            tag.getLong("DeadlineTick"),
            tag.getString("SourceType"),
            tag.getString("SourceId"),
            tag.getString("ChronicleEntry"),
            objectives
        );
        entry.resolved = tag.getBoolean("Resolved");
        entry.failed = tag.getBoolean("Failed");
        entry.resolvedTick = tag.getLong("ResolvedTick");
        entry.targetPos = tag.getLong("TargetPos");
        return entry;
    }
}
