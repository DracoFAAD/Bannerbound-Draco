package com.bannerbound.core.api.settlement;

/**
 * Categorical icon used by a {@link StatusEffect} when rendered in the town hall Statuses tab.
 * Three values mirror the three top-level resource rates the icon font already has glyphs for —
 * food, culture, science. Extensible: add a new entry, add a glyph in the font, and add a case
 * in the client-side icon-rendering switch.
 * <p>
 * Persisted by {@link #ordinal()} (NBT + network), so existing entries must keep their position;
 * only append new ones at the end.
 */
public enum StatusEffectIcon {
    FOOD,
    CULTURE,
    SCIENCE,
    /** Warning/event marker (outpost lost, future attack notices) — renders as ⚠, no rate value. */
    ALERT;

    public static StatusEffectIcon fromOrdinalOrFood(int ord) {
        StatusEffectIcon[] vals = values();
        if (ord < 0 || ord >= vals.length) return FOOD;
        return vals[ord];
    }
}
