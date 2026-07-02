package com.bannerbound.core.api.quality;

/**
 * Single source of truth for turning per-step minigame scores into a {@link QualityTier}. Shared by
 * every quality-producing minigame (the fletching stretch bar, metalworking's cold-hammer, …) and by
 * Crafter-NPC simulation, so quality is computed identically no matter who produced it.
 */
public final class QualityMath {
    private QualityMath() {
    }

    /** Clamps a single step score into the valid 0–100 range (defends against bad client input). */
    public static int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    /**
     * Aggregates per-step scores (each 0–100) into a single 0–100 craft score by clamped average.
     * An empty array scores 0 (= Crude).
     */
    public static int aggregate(int[] stepScores) {
        if (stepScores == null || stepScores.length == 0) return 0;
        long sum = 0;
        for (int s : stepScores) sum += clampScore(s);
        return (int) Math.round((double) sum / stepScores.length);
    }

    /** Convenience: per-step scores straight to a player/NPC-reachable tier. */
    public static QualityTier tierFromScores(int[] stepScores) {
        return QualityTier.fromScore(aggregate(stepScores));
    }

    /**
     * The CRAFTER-NPC tier mapping: identical to {@link QualityTier#fromScore} except a near-
     * perfect aggregate (≥{@value #NPC_MASTERWORK_MIN}) rolls {@link QualityTier#MASTERWORK} —
     * the tier reserved for veteran crafters (player hand-craft stays capped at FINE). With the
     * XP-driven simulation, only a high-experience crafter's spread reaches it regularly.
     */
    public static QualityTier npcTierFromScore(int score) {
        return score >= NPC_MASTERWORK_MIN ? QualityTier.MASTERWORK : QualityTier.fromScore(score);
    }

    /** Aggregate score at which an NPC craft becomes MASTERWORK. */
    public static final int NPC_MASTERWORK_MIN = 93;

    /**
     * The shared CRAFTER-NPC quality simulation: rolls {@code samples} step scores from an
     * XP-driven gaussian — a novice averages ~40 with wild variance (Crude/Standard), a veteran
     * ~92 with a tight hand (reliable Fine, regular {@link QualityTier#MASTERWORK}) — and maps
     * the aggregate through {@link #npcTierFromScore}. One implementation so every crafter
     * profession (fletcher, general crafts, future smiths/potters) levels on the same curve.
     */
    public static QualityTier simulateNpcTier(net.minecraft.util.RandomSource rng, float xp, int samples) {
        float skill = xp / (xp + NPC_XP_HALF);      // 0 → 1 as crafts accumulate
        double mean = 40.0 + 55.0 * skill;
        double sd = 30.0 - 22.0 * skill;
        int[] scores = new int[Math.max(1, samples)];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = clampScore((int) Math.round(mean + rng.nextGaussian() * sd));
        }
        return npcTierFromScore(aggregate(scores));
    }

    /** XP saturation constant for {@link #simulateNpcTier}: mean/sd are ~halfway to veteran
     *  after this many crafts. */
    public static final float NPC_XP_HALF = 30.0F;

    /**
     * Display skill tier for a crafter's per-profession XP (≈ completed crafts), used by the Job
     * tab's skill line. Returns the lang-key suffix: {@code bannerbound.skill.<suffix>}. Bands
     * mirror the XP→quality curve: a journeyman (~30 crafts) reliably makes Standard/Fine; a
     * master (200+) is deep in MASTERWORK territory.
     */
    public static String skillTierKey(int xp) {
        if (xp < 10) return "novice";
        if (xp < 30) return "apprentice";
        if (xp < 80) return "journeyman";
        if (xp < 200) return "veteran";
        return "master";
    }

    /** Progress through the CURRENT skill band, 0–1 (1 once Master) — drives the Job tab's
     *  villager-style XP bar. Bands match {@link #skillTierKey}. */
    public static float skillProgress(int xp) {
        int lo;
        int hi;
        if (xp < 10) { lo = 0; hi = 10; }
        else if (xp < 30) { lo = 10; hi = 30; }
        else if (xp < 80) { lo = 30; hi = 80; }
        else if (xp < 200) { lo = 80; hi = 200; }
        else return 1.0F;
        return (xp - lo) / (float) (hi - lo);
    }
}
