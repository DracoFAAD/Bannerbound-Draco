package com.bannerbound.core.citystate;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.language.SettlementLanguage;

/**
 * Generates a city-state's place-name in its own generated tongue from its language seed, by styling a
 * varied base concept through {@link SettlementLanguage} (same approach as {@code BarbarianNames}).
 * Deterministic per city-state; a fixed era keeps the name stable as the city-state's tech advances.
 */
public final class CityStateNames {
    private static final String[] BASES = {
        "market", "harbor", "field", "spring", "gate", "vale", "hearth", "meadow",
        "ford", "bridge", "well", "grove", "haven", "cross", "mound", "shore"
    };

    private CityStateNames() {
    }

    public static String generate(long seed) {
        String base = BASES[(int) Math.floorMod(seed, BASES.length)];
        String name = SettlementLanguage.citizenName(seed, Era.ANCIENT, base, null, null, "place");
        return name == null || name.isBlank() ? "City-state" : name;
    }
}
