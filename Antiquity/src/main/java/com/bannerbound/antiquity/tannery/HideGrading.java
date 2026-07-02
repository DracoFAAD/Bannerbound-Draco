package com.bannerbound.antiquity.tannery;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.item.HideQuality;

import net.minecraft.world.entity.EntityType;

/**
 * Maps how an animal was obtained to a {@link HideQuality}. Two paths:
 * <ul>
 *   <li><b>Hunting</b> (wild animals): the weapon used vs the species' preferred weapon
 *       ({@link HidePreferenceLoader}). Preferred → GREAT, another valid weapon → STANDARD, no valid
 *       weapon → POOR (you always get <i>something</i>).</li>
 *   <li><b>Herding</b> (domesticated animals): living conditions + herder skill, no weapon needed.</li>
 * </ul>
 */
public final class HideGrading {
    private HideGrading() {
    }

    /** XP at which a herder counts as fully skilled for hide grading. */
    private static final double SKILL_FULL_XP = 200.0;
    private static final double POOR_BELOW = 0.34;
    private static final double GREAT_AT_OR_ABOVE = 0.70;

    /** Hunting grade: weapon category vs the species' preferred category. */
    public static HideQuality gradeHunt(EntityType<?> prey, @Nullable WeaponCategory used) {
        if (used == null) return HideQuality.POOR;
        WeaponCategory preferred = HidePreferenceLoader.preferred(prey);
        if (preferred != null && used == preferred) return HideQuality.GREAT;
        return HideQuality.STANDARD;
    }

    /** Herding grade: a 0..1 living-conditions score and the herder's job XP (0 for a player kill). */
    public static HideQuality gradeHerd(double conditions01, int herderXp) {
        double skill = Math.min(1.0, Math.max(0, herderXp) / SKILL_FULL_XP);
        double conditions = Math.min(1.0, Math.max(0.0, conditions01));
        double score = 0.5 * conditions + 0.5 * skill;
        if (score < POOR_BELOW) return HideQuality.POOR;
        if (score >= GREAT_AT_OR_ABOVE) return HideQuality.GREAT;
        return HideQuality.STANDARD;
    }
}
