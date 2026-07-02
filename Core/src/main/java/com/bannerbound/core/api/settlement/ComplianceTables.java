package com.bannerbound.core.api.settlement;

/**
 * Step 12 — refusal probability tables, keyed on compliance (0..100). One probability per
 * refusal scenario:
 *
 * <ul>
 *   <li>{@link #refuseWorkstation(int)} — workstation assignment refusal (most common).</li>
 *   <li>{@link #refuseRodTask(int)} — Foreman's Rod task pick refusal. Same shape as
 *       workstation but a touch sharper at the low end to keep on-the-fly orders snappy.</li>
 *   <li>{@link #refuseFullDay(int)} — dawn full-day-strike. Only fires at compliance ≤ 30
 *       and the curve climbs hard from there.</li>
 * </ul>
 *
 * <p>All three are step-functions on hand-picked compliance breakpoints, with linear
 * interpolation between adjacent steps so a citizen at compliance 53 gets a probability
 * between the 50 and 55 anchors rather than a stair-step jump. Caller rolls a uniform RNG
 * 0..1 and compares.
 *
 * <p><b>Tuning intent</b>: at compliance ≥ 61 nothing ever refuses; mid (30–60) is the
 * band where the player feels gameplay friction; below ~25 the citizen refuses workstation
 * assignments most of the time AND the dawn full-day-strike roll starts firing (the FULL_DAY
 * table is non-zero up to compliance 30 inclusive). Sub-15 compliance is "essentially on
 * permanent strike" — both the workstation and rod tables hit 1.00 there.
 */
public final class ComplianceTables {
    private ComplianceTables() {
    }

    /** Workstation-assignment refusal chance. Plan-specified anchors. */
    public static double refuseWorkstation(int compliance) {
        return interpolate(compliance, WORKSTATION);
    }

    /** Foreman's Rod task-pick refusal chance. Slightly higher at mid-compliance than the
     *  workstation table — rod orders are quicker / more individually demanding. */
    public static double refuseRodTask(int compliance) {
        return interpolate(compliance, ROD_TASK);
    }

    /** Full-day-strike chance, rolled at dawn ONLY when compliance ≤ 30 (caller gates this). */
    public static double refuseFullDay(int compliance) {
        return interpolate(compliance, FULL_DAY);
    }

    // ─── Anchor tables (compliance, probability) — ascending compliance ───────────────────
    private static final double[][] WORKSTATION = {
        {  0.0, 1.00 }, { 15.0, 1.00 }, { 20.0, 0.75 }, { 25.0, 0.60 },
        { 30.0, 0.40 }, { 35.0, 0.30 }, { 40.0, 0.20 }, { 45.0, 0.15 },
        { 50.0, 0.10 }, { 55.0, 0.05 }, { 60.0, 0.02 }, { 61.0, 0.00 },
        {100.0, 0.00 }
    };
    private static final double[][] ROD_TASK = {
        {  0.0, 1.00 }, { 15.0, 1.00 }, { 20.0, 0.75 }, { 25.0, 0.60 },
        { 30.0, 0.40 }, { 35.0, 0.30 }, { 40.0, 0.25 }, { 45.0, 0.20 },
        { 50.0, 0.15 }, { 55.0, 0.10 }, { 60.0, 0.05 }, { 61.0, 0.00 },
        {100.0, 0.00 }
    };
    private static final double[][] FULL_DAY = {
        {  0.0, 1.00 }, { 10.0, 0.75 }, { 15.0, 0.75 }, { 20.0, 0.50 },
        { 25.0, 0.50 }, { 30.0, 0.20 }, { 31.0, 0.00 }, {100.0, 0.00 }
    };

    /** Linear interpolation between the two anchors that bracket {@code compliance}. Tables
     *  are tiny (≤13 entries) so a linear scan is fine — no need for binary search. */
    private static double interpolate(int compliance, double[][] table) {
        double c = Math.max(0, Math.min(100, compliance));
        // Below the first anchor: clamp to its value (shouldn't happen since first is 0).
        if (c <= table[0][0]) return table[0][1];
        for (int i = 1; i < table.length; i++) {
            if (c <= table[i][0]) {
                double lo = table[i - 1][0], loP = table[i - 1][1];
                double hi = table[i][0],     hiP = table[i][1];
                if (hi == lo) return hiP;
                double t = (c - lo) / (hi - lo);
                return loP + t * (hiP - loP);
            }
        }
        // Above the last anchor — shouldn't happen because the last anchor is 100, but
        // defensively clamp to its value rather than throwing.
        return table[table.length - 1][1];
    }
}
