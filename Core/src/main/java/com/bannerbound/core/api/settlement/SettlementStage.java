package com.bannerbound.core.api.settlement;

/**
 * Growth stage of a settlement — a named milestone ladder layered on top of population + government.
 * Each step-up is a visible moment (chat + fireworks) and gates how much per-citizen detail the
 * settlement simulates:
 * <ul>
 *   <li>{@link #HEARTH} — pre-government start, fully 1:1 (today's behaviour).</li>
 *   <li>{@link #TRIBE} — Code of Laws enacted (a government exists), still fully 1:1.</li>
 *   <li>{@link #VILLAGE} — pop ≥ {@link #VILLAGE_THRESHOLD}: the cost transition (cheap-brain
 *       citizens, aggregate mood, auto-professions, worker groups). Not yet wired — Phase 1 only
 *       detects the stage + celebrates it.</li>
 *   <li>{@link #TOWN}/{@link #CITY} — reserved for later (decorative-mover crowd, density-aware).</li>
 * </ul>
 * Ordinal order matters: a stage-up is detected when {@code stage().ordinal()} rises.
 */
public enum SettlementStage {
    HEARTH,
    TRIBE,
    VILLAGE,
    TOWN,
    CITY;

    /** Population at/above which a Tribe becomes a Village. */
    public static final int VILLAGE_THRESHOLD = 25;

    /** Lowercase id used for translation keys ({@code bannerbound.stage.<key>}). */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
