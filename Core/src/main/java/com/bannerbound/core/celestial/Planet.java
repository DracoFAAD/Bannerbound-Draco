package com.bannerbound.core.celestial;

/**
 * One wandering star of the world's procedural solar system (FAITH_PLAN.md Part 3).
 * Pure orbital elements + dormant physical data — ON RAILS, never N-body: positions are
 * closed-form functions of game time, derived in {@link SkyField}.
 * <p>
 * The dormant fields (rings/moons/axialTilt) are rolled at generation but unrendered:
 * later ages REVEAL them (Renaissance telescopes), they never re-generate. Planet
 * identity is the list index under a fixed sky seed — stable as long as the generation
 * code for earlier rolls doesn't change, which is why generation order below must never
 * be reordered once worlds exist.
 *
 * @param a           orbital radius in observer-orbit units (observer world = 1.0)
 * @param periodDays  orbital period in Minecraft days (T = a^1.5 × observer year)
 * @param phaseDeg    heliocentric longitude at day 0
 * @param baseSize    sprite half-size on the r=100 celestial sphere at distance 1.0
 * @param rgb         tint
 * @param rings       dormant — has a ring system (gas worlds beyond the frost line)
 * @param moonCount   dormant — number of moons
 * @param axialTiltDeg dormant — axial tilt
 * @param inclinationDeg orbital tilt vs the ecliptic — scatters planets AROUND the
 *                       zodiac band instead of pinning them to a line
 * @param nodeDeg     longitude of ascending node (where the tilted orbit crosses the ecliptic)
 */
public record Planet(
        double a,
        double periodDays,
        double phaseDeg,
        float baseSize,
        int rgb,
        boolean rings,
        int moonCount,
        double axialTiltDeg,
        double inclinationDeg,
        double nodeDeg) {

    /** Heliocentric ecliptic longitude (degrees) at {@code days} game-days. */
    public double helioLonDeg(double days) {
        return SkyField.wrapDeg(phaseDeg + 360.0 * days / periodDays);
    }
}
