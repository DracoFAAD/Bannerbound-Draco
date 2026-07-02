package com.bannerbound.core.crisis;

import java.util.List;

/** One data-authored checklist row attached to a crisis choice. */
public record CrisisObjectiveDefinition(
    String id,
    String type,
    String label,
    String source,
    String researchId,
    String jobType,
    String target,
    double targetRate,
    int targetCount,
    List<String> subSteps
) {
    public CrisisObjectiveDefinition {
        id = id == null ? "" : id;
        type = type == null ? "" : type;
        label = label == null ? "" : label;
        source = source == null ? "" : source;
        researchId = researchId == null ? "" : researchId;
        jobType = jobType == null ? "" : jobType;
        target = target == null ? "" : target;
        subSteps = subSteps == null ? List.of() : List.copyOf(subSteps);
    }
}
