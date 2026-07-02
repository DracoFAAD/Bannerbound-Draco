package com.bannerbound.core.language;

/**
 * A normalized concept extracted from a registry id, job id, or curated gloss.
 *
 * <p>{@code base} is the readable English-ish gloss used for debugging/fallback. {@code family}
 * is the semantic root that should visibly bind related generated words together. {@code modifier}
 * and {@code kind} are derivational parts: oak/log, copper/ingot, iron/axe, fish/worker, etc.
 */
public record LanguageConcept(
    String id,
    String base,
    ConceptRole role,
    String family,
    String modifier,
    String kind
) {
    public LanguageConcept(String id, String base, ConceptRole role) {
        this(id, base, role, base, "", "");
    }

    public LanguageConcept {
        id = clean(id, "unknown");
        base = clean(base, id);
        role = role == null ? ConceptRole.BASE_NOUN : role;
        family = clean(family, base);
        modifier = cleanOptional(modifier);
        kind = cleanOptional(kind);
    }

    public LanguageConcept withOverride(String nextBase, String nextFamily, ConceptRole nextRole) {
        return new LanguageConcept(
            id,
            clean(nextBase, base),
            nextRole == null ? role : nextRole,
            clean(nextFamily, family),
            modifier,
            kind);
    }

    private static String clean(String value, String fallback) {
        String out = value == null ? "" : value.trim();
        if (!out.isBlank()) return out;
        return fallback == null || fallback.isBlank() ? "concept" : fallback.trim();
    }

    private static String cleanOptional(String value) {
        return value == null ? "" : value.trim();
    }
}
