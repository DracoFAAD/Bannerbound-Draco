package com.bannerbound.core.crisis;

/** Advisory requirement for a crisis choice. Failing one marks the button with a warning. */
public record CrisisViabilityRequirement(String type, String warning) {
    public CrisisViabilityRequirement {
        type = type == null ? "" : type;
        warning = warning == null ? "" : warning;
    }
}
