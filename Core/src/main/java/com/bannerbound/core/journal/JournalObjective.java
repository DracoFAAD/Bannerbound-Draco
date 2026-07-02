package com.bannerbound.core.journal;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** One checklist row inside a journal entry. Text is intentionally data-authored plain text. */
public record JournalObjective(
    String id,
    String label,
    String progressText,
    boolean complete,
    List<String> subSteps
) {
    public JournalObjective {
        id = id == null ? "" : id;
        label = label == null ? "" : label;
        progressText = progressText == null ? "" : progressText;
        subSteps = subSteps == null ? List.of() : List.copyOf(subSteps);
    }

    public JournalObjective(String id, String label, String progressText, boolean complete) {
        this(id, label, progressText, complete, List.of());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        tag.putString("Label", label);
        tag.putString("Progress", progressText);
        tag.putBoolean("Complete", complete);
        if (!subSteps.isEmpty()) {
            ListTag list = new ListTag();
            for (String step : subSteps) list.add(StringTag.valueOf(step == null ? "" : step));
            tag.put("SubSteps", list);
        }
        return tag;
    }

    public static JournalObjective load(CompoundTag tag) {
        List<String> subSteps = new ArrayList<>();
        ListTag list = tag.getList("SubSteps", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) subSteps.add(list.getString(i));
        return new JournalObjective(
            tag.getString("Id"),
            tag.getString("Label"),
            tag.getString("Progress"),
            tag.getBoolean("Complete"),
            subSteps
        );
    }
}
