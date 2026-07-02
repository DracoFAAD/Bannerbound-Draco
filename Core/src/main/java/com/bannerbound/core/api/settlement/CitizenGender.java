package com.bannerbound.core.api.settlement;

/**
 * A citizen's gender. Rolled 50/50 when a citizen immigrates and fixed for life. Drives three
 * cosmetic systems: the name pool it draws from, the gender icon shown before its name, and the
 * body model used to render it (male = wide/Steve, female = slim/Alex).
 * <p>
 * Persisted by {@link #ordinal()} (NBT + synced entity data), so existing entries must keep
 * their position; only append new ones at the end.
 */
public enum CitizenGender {
    MALE,
    FEMALE;

    public static CitizenGender fromOrdinalOrMale(int ord) {
        CitizenGender[] vals = values();
        if (ord < 0 || ord >= vals.length) return MALE;
        return vals[ord];
    }

    /** Lowercase id used in name-pool filenames and texture filenames. */
    public String key() {
        return name().toLowerCase();
    }

    /** Texture-filename prefix — the user's art uses {@code man_*} / {@code woman_*}, not the
     *  enum's {@code male} / {@code female}. */
    public String texturePrefix() {
        return this == MALE ? "man" : "woman";
    }
}
