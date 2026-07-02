package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

/**
 * A single "this is a poor spot to settle" finding raised by the founding screen's site
 * assessment (see {@code SettlementSiteAssessor}). Each warning has a translation key shown to
 * the player. Warnings travel to the client as a compact bitmask on {@code OpenSettleScreenPayload}
 * — this enum is the shared (both-dist) source of truth for that encoding.
 */
@ApiStatus.Internal
public enum SiteWarning {
    /** No open water in the founding area — hits fishing and the livestock water bonus. */
    NO_WATER("bannerbound.settle.site.no_water"),
    /** Barren ground (sand/stone, little grass) — poor foraging and farming. */
    POOR_SOIL("bannerbound.settle.site.poor_soil");

    private static final SiteWarning[] VALUES = values();

    private final String translationKey;

    SiteWarning(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public static int toMask(Collection<SiteWarning> warnings) {
        int mask = 0;
        for (SiteWarning w : warnings) {
            mask |= w.bit();
        }
        return mask;
    }

    public static List<SiteWarning> fromMask(int mask) {
        List<SiteWarning> out = new ArrayList<>();
        for (SiteWarning w : VALUES) {
            if ((mask & w.bit()) != 0) {
                out.add(w);
            }
        }
        return out;
    }
}
