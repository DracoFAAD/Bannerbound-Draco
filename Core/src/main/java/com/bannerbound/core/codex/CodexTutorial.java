package com.bannerbound.core.codex;

import java.util.List;

/** Optional data-authored checklist shown when a Chronicle entry is pinned. */
public record CodexTutorial(
    String title,
    String subtitle,
    int priority,
    List<Objective> objectives
) {
    public CodexTutorial {
        title = title == null ? "" : title;
        subtitle = subtitle == null ? "" : subtitle;
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
    }

    public boolean isEmpty() {
        return objectives.isEmpty();
    }

    public Objective objective(String id) {
        if (id == null || id.isBlank()) return null;
        for (Objective objective : objectives) {
            if (objective.id().equals(id)) return objective;
        }
        return null;
    }

    public record Objective(
        String id,
        String label,
        String progressText,
        String completeText,
        CodexCondition trigger,
        List<String> subSteps
    ) {
        public Objective {
            id = id == null ? "" : id;
            label = label == null ? "" : label;
            progressText = progressText == null ? "" : progressText;
            completeText = completeText == null || completeText.isBlank() ? "Completed" : completeText;
            trigger = trigger == null ? new CodexCondition("", "", "", "", "", "", "", "") : trigger;
            subSteps = subSteps == null ? List.of() : List.copyOf(subSteps);
        }
    }
}
