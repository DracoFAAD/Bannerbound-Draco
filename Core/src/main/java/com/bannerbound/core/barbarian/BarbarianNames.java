package com.bannerbound.core.barbarian;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.language.SettlementLanguage;

/**
 * Generates a camp's place-name in its own tongue (e.g. "Ratakusupria") from its language seed, by
 * styling a varied base concept through {@link SettlementLanguage}. Deterministic per camp; a fixed
 * era keeps the name stable as the camp's tech advances.
 */
public final class BarbarianNames {
    private static final String[] BASES = {
        "hill", "river", "stone", "home", "fire", "wolf", "ash", "ridge",
        "hollow", "crag", "mire", "reed", "thorn", "bone", "moon", "dust"
    };

    private BarbarianNames() {
    }

    public static String generate(long seed) {
        String base = BASES[(int) Math.floorMod(seed, BASES.length)];
        String name = SettlementLanguage.citizenName(seed, Era.ANCIENT, base, null, null, "place");
        return name == null || name.isBlank() ? "Camp" : name;
    }
}
