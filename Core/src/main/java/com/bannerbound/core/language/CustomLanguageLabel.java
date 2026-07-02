package com.bannerbound.core.language;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.network.chat.Component;

/** Public label helpers for controlled, name-like custom-language surfaces. */
public final class CustomLanguageLabel {
    private CustomLanguageLabel() {
    }

    public static Component job(Settlement settlement, String jobTypeId, boolean quarryUnlocked) {
        LanguageConcept concept = LanguageConceptResolver.forJob(jobTypeId, quarryUnlocked);
        String label = SettlementLanguage.word(settlement, concept, LanguageRegister.COMMON);
        return Component.literal(capitalize(label));
    }

    public static Component clientJob(long seed, Era era, String jobTypeId, boolean quarryUnlocked) {
        LanguageConcept concept = LanguageConceptResolver.forJob(jobTypeId, quarryUnlocked);
        String label = LanguageProfile.of(seed)
            .formsForConcept(concept, era)
            .defaultWord(era, concept.role());
        return Component.literal(capitalize(label));
    }

    /** Styles a freshly-drawn base given name into the settlement's language. Called ONCE at citizen
     *  creation so the stored name IS the in-language name (and so chat / recall / workshop / roster
     *  surfaces all read an already-styled name). Detached citizens (null settlement) keep the base
     *  verbatim. */
    public static String styleGiven(Settlement settlement, String base, String salt) {
        if (base == null) return null;
        if (settlement == null) return base;
        return SettlementLanguage.citizenName(settlement, base, null, null, salt);
    }

    /** Composes a citizen's full display name from an already-language-styled given name plus the
     *  (separately styled) earned surname. The given name is baked at creation, so it is used
     *  verbatim here — only the earned surname is resolved per-call. */
    public static String compose(Settlement settlement, String styledGiven, String surnameConcept,
                                 String surnameJob, String salt) {
        if (styledGiven == null) return "";
        if (surnameConcept == null || surnameConcept.isBlank()) return styledGiven;
        String surname = settlement != null
            ? SettlementLanguage.surname(settlement, surnameConcept, surnameJob, salt)
            : LanguageConceptResolver.surnameFallback(surnameConcept, surnameJob);
        return (surname == null || surname.isBlank()) ? styledGiven : styledGiven + " " + surname;
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) return "Na";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
