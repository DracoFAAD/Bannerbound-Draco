package com.bannerbound.core.crisis;

import java.util.List;

/** A selectable path in a scripted crisis. */
public record CrisisChoice(
    String id,
    String label,
    String description,
    String outcome,
    List<CrisisViabilityRequirement> viability,
    List<CrisisObjectiveDefinition> objectives
) {
    public CrisisChoice {
        id = id == null ? "" : id;
        label = label == null ? "" : label;
        description = description == null ? "" : description;
        outcome = outcome == null ? "" : outcome;
        viability = viability == null ? List.of() : List.copyOf(viability);
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
    }
}
