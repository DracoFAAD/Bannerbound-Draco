package com.bannerbound.core.api.settlement;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.research.ResearchDefinition;
import com.bannerbound.core.api.research.data.ResearchTreeLoader;

/**
 * Per-settlement caps on how big a home may be. A home's marked region may span at most
 * {@code 2·maxRadius + 1} blocks per axis — small in the Antiquity era, raised by research.
 *
 * <p><b>Research authoring:</b> put a {@code bannerbound.home_radius:N} flag in a research node's
 * {@code unlocks.flags} to allow homes up to {@code N} blocks of half-span once that node is
 * completed; the settlement's limit is the largest such {@code N} it has unlocked (never below
 * {@link #BASE_RADIUS}, never above the hard cap {@link #MAX_RADIUS}). {@code Homes.validate}
 * enforces it as a union-span check (homes have no anchor block to measure a radius from).
 */
public final class HousingLimits {
    /** Max half-span a home may reach with no housing research — the Antiquity starter size. Kept
     *  small so early homes are cosy; research grows it. */
    public static final int BASE_RADIUS = 5;
    /** Absolute ceiling on the per-settlement home half-span, regardless of research. */
    public static final int MAX_RADIUS = 32;

    private static final String RADIUS_FLAG_PREFIX = "bannerbound.home_radius:";

    private HousingLimits() {
    }

    /** The largest home half-span (blocks) this settlement may use, clamped to {@link #MAX_RADIUS}. */
    public static int maxRadius(@Nullable Settlement settlement) {
        int max = BASE_RADIUS;
        if (settlement != null) {
            for (String id : settlement.completedResearches()) {
                ResearchDefinition def = ResearchTreeLoader.get(id);
                if (def == null) continue;
                for (String flag : def.unlocksFlags()) {
                    if (flag.startsWith(RADIUS_FLAG_PREFIX)) {
                        try {
                            max = Math.max(max, Integer.parseInt(
                                flag.substring(RADIUS_FLAG_PREFIX.length()).trim()));
                        } catch (NumberFormatException ignored) {
                            // Bad number in the flag — skip it, keep the current best.
                        }
                    }
                }
            }
        }
        return Math.min(max, MAX_RADIUS);
    }
}
