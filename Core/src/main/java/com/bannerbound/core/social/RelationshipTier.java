package com.bannerbound.core.social;

/**
 * Bucket label for a relationship's signed score. Positive tiers are user-defined and stable;
 * negative tiers are placeholders until the user lands on names — only swap the labels here.
 */
public enum RelationshipTier {
    HATED,           // <= -80
    ENEMIES,         // <= -50
    RIVALS,          // <= -25
    DISLIKED,        // <= -10
    STRANGERS,       // -9..9
    ACQUAINTANCES,   // 10..24
    FRIENDS,         // 25..49
    CLOSE_FRIENDS,   // 50..79
    FRIENDS_FOR_LIFE, // >= 80
    /** Permanent parent ↔ child bond. Stronger than every score-based tier, never decays,
     *  never removable except by the cleanup that already drops dead-citizen entries.
     *  Resolved by {@link Relationship#tier()} when {@code isFamily} is set — score on
     *  family relationships is locked at 100 and never moves through conversations. */
    FAMILY;

    /** Human-readable label: {@code "Friends for Life"}, {@code "Close Friends"}, etc. Built
     *  from the enum name by lowercasing, splitting on {@code _}, and capitalising each word. */
    public String displayLabel() {
        String[] parts = name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }

    /** Resolves a tier from a raw -100..100 score. Bounds are inclusive on the lower side. */
    public static RelationshipTier of(int score) {
        if (score >= Relationships.FRIENDS_FOR_LIFE) return FRIENDS_FOR_LIFE;
        if (score >= Relationships.CLOSE_FRIENDS)    return CLOSE_FRIENDS;
        if (score >= Relationships.FRIENDS)          return FRIENDS;
        if (score >= Relationships.ACQUAINTANCES)    return ACQUAINTANCES;
        if (score > -Relationships.ACQUAINTANCES)    return STRANGERS;
        if (score > -Relationships.FRIENDS)          return DISLIKED;
        if (score > -Relationships.CLOSE_FRIENDS)    return RIVALS;
        if (score > -Relationships.FRIENDS_FOR_LIFE) return ENEMIES;
        return HATED;
    }
}
