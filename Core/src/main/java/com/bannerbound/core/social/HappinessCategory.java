package com.bannerbound.core.social;

/**
 * The four equal pillars of citizen happiness. Every {@link ThoughtType} belongs to exactly one;
 * each pillar's satisfaction is computed independently (0–100), and overall happiness is the average
 * of the four (each worth 25). The Citizen screen renders one filling ring per pillar.
 *
 * <ul>
 *   <li><b>FOOD</b> — nourishment &amp; meals (hunger, eating well, variety, drink).</li>
 *   <li><b>CULTURE</b> — beauty, family &amp; identity (appeal, children, research, faith).</li>
 *   <li><b>COMFORT</b> — shelter, health &amp; safety (home, injury, crises, weather).</li>
 *   <li><b>SOCIETY</b> — work, governance &amp; relationships (employment, policies, friends).</li>
 * </ul>
 */
public enum HappinessCategory {
    FOOD("bannerbound.happiness.food", 0xFF4ECB3B),
    CULTURE("bannerbound.happiness.culture", 0xFFC04EE0),
    COMFORT("bannerbound.happiness.comfort", 0xFF3BA8CB),
    SOCIETY("bannerbound.happiness.society", 0xFFE0A93B);

    /** Translation key for the pillar's display name. */
    public final String labelKey;
    /** ARGB ring colour. */
    public final int color;

    HappinessCategory(String labelKey, int color) {
        this.labelKey = labelKey;
        this.color = color;
    }
}
