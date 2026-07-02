package com.bannerbound.core.language;

import com.bannerbound.core.api.settlement.Era;

/** Bundle of contextual forms derived from the same underlying generated word. */
public record RegisterForms(
    String spoken,
    String written,
    String formal,
    String common,
    String archaic,
    String technical,
    String handle
) {
    public RegisterForms {
        spoken = safe(spoken);
        written = safe(written);
        formal = safe(formal);
        common = safe(common);
        archaic = safe(archaic);
        technical = safe(technical);
        handle = safe(handle);
    }

    public String get(LanguageRegister register) {
        return switch (register) {
            case SPOKEN -> spoken;
            case WRITTEN -> written;
            case FORMAL -> formal;
            case COMMON -> common;
            case ARCHAIC -> archaic;
            case TECHNICAL -> technical;
            case HANDLE -> handle;
        };
    }

    public String defaultWord(Era era, ConceptRole role) {
        ConceptRole actual = role == null ? ConceptRole.BASE_NOUN : role;
        if (actual == ConceptRole.AUTHORITY || actual == ConceptRole.BUILDING
                || actual == ConceptRole.ABSTRACT || actual == ConceptRole.PLACE) {
            return formal;
        }
        if (era.ordinal() >= Era.INDUSTRIAL.ordinal()
                && (actual == ConceptRole.TOOL || actual == ConceptRole.ACTION)) {
            return technical;
        }
        if (actual == ConceptRole.PROFESSION || actual == ConceptRole.ITEM
                || actual == ConceptRole.FOOD || actual == ConceptRole.MATERIAL
                || actual == ConceptRole.ENTITY || actual == ConceptRole.BASE_NOUN) {
            return common;
        }
        return written;
    }

    public String defaultName(Era era) {
        return era.ordinal() >= Era.MODERN.ordinal() ? common : written;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "na" : value.strip();
    }
}
