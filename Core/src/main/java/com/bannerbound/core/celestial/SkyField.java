package com.bannerbound.core.celestial;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The world's faith sky (FAITH_PLAN.md Part 3): typed star clusters + a procedural solar
 * system, all generated deterministically from one server-rolled "sky seed". Every client
 * builds the identical field from the synced long — nothing else is ever synced.
 * <p>
 * Deliberately PURE JAVA (no Minecraft imports): the whole celestial core is testable
 * headless. {@link java.util.Random} is spec-fixed (LCG), so the same seed yields the
 * same sky on every JVM.
 * <p>
 * Coordinate convention (matches the vanilla sky transform in the renderer):
 * the celestial sphere rotates once per day; the SUN is the fixed point +Y of that frame.
 * The ecliptic is the Y–Z great circle, X is ecliptic latitude. A body with geocentric
 * ecliptic longitude {@code phi} relative to the sun sits at {@code (0, cos phi, sin phi)}.
 * Star positions are stored at absolute ecliptic longitude; the renderer rotates the star
 * pass by {@code -sunGeocentricLon} so the stars drift against the sun over the observer's
 * year — seasonal skies.
 * <p>
 * GENERATION ORDER IS FROZEN: planet identity = list index under a seed. Never reorder or
 * insert rolls before existing ones once player worlds exist.
 */
public final class SkyField {

    /** Default year length (the all-sevens calendar). The REAL year comes from the
     *  configured {@link WorldCalendar} and is passed to {@link #generate(long, int)} —
     *  the calendar, the seasons and the sky are deliberately one machine, so shortening
     *  a month genuinely speeds up every orbit via Kepler. The celestialSpeed gamerule
     *  remains the testing/cinematic time-lapse knob on top. */
    public static final double OBSERVER_YEAR_DAYS = WorldCalendar.DEFAULT.yearDays();
    /** Beyond this orbital radius worlds roll as ringed/mooned gas giants. */
    private static final double FROST_LINE = 2.2;

    /** One typed star: unit direction + pre-rolled quad basis, on the celestial sphere. */
    public static final class Star {
        public final float dx, dy, dz;   // unit direction (celestial frame, pre-drift)
        public final float ux, uy, uz;   // quad basis u (unit, ⊥ dir, random roll baked in)
        public final float vx, vy, vz;   // quad basis v (unit, ⊥ dir and u)
        public final float size;         // quad half-size at sphere radius 100
        public final float alphaMul;     // per-star brightness 0.5–1.0 (variation)
        public final int rgb;            // type color with per-star jitter baked in
        public final StarType type;

        Star(double[] dir, double rollRad, float size, float alphaMul, int rgb, StarType type) {
            this.dx = (float) dir[0];
            this.dy = (float) dir[1];
            this.dz = (float) dir[2];
            // Orthonormal basis perpendicular to dir, rotated by the star's random roll.
            double rx = Math.abs(dir[1]) < 0.99 ? 0 : 1;
            double ry = Math.abs(dir[1]) < 0.99 ? 1 : 0;
            double[] u = normalize(cross(rx, ry, 0, dir[0], dir[1], dir[2]));
            double[] v = cross(dir[0], dir[1], dir[2], u[0], u[1], u[2]);
            double c = Math.cos(rollRad), s = Math.sin(rollRad);
            this.ux = (float) (u[0] * c + v[0] * s);
            this.uy = (float) (u[1] * c + v[1] * s);
            this.uz = (float) (u[2] * c + v[2] * s);
            this.vx = (float) (v[0] * c - u[0] * s);
            this.vy = (float) (v[1] * c - u[1] * s);
            this.vz = (float) (v[2] * c - u[2] * s);
            this.size = size;
            this.alphaMul = alphaMul;
            this.rgb = rgb;
            this.type = type;
        }
    }

    /** A planet's appearance for one moment: where against the sky, and how far away.
     *  Longitude is ABSOLUTE geocentric ecliptic longitude — planets live in the same
     *  yearly-wheeling star frame as everything else. */
    public record PlanetView(double eclipticLonDeg, double eclipticLatDeg, double distance) {
    }

    public final long seed;
    public final List<Star> stars;
    public final List<Planet> planets;
    /** Observer's heliocentric longitude at day 0 — randomizes which "season" worlds start in. */
    public final double observerPhaseDeg;
    /** The calendar year this sky was built against — observer period, in game days. */
    public final double observerYearDays;

    private SkyField(long seed, List<Star> stars, List<Planet> planets, double observerPhaseDeg,
                     double observerYearDays) {
        this.seed = seed;
        this.stars = stars;
        this.planets = planets;
        this.observerPhaseDeg = observerPhaseDeg;
        this.observerYearDays = observerYearDays;
    }

    // ── Generation ────────────────────────────────────────────────────────────────

    /** Default-calendar sky (84-day year) — headless tests and fallbacks. */
    public static SkyField generate(long seed) {
        return generate(seed, WorldCalendar.DEFAULT.yearDays());
    }

    public static SkyField generate(long seed, int yearDays) {
        Random rnd = new Random(seed);
        double observerPhase = rnd.nextDouble() * 360.0;

        // Typed star REGIONS — loose neighborhoods, not pre-drawn constellations: a region
        // makes a dominant-type god *achievable* there, but the player still chooses which
        // stars to connect (vanilla commons interleave and break any silhouette). Centers
        // bias toward the ecliptic band (the zodiac — where planets travel, so transit
        // gameplay is reachable); SEA_GREEN biases toward the celestial poles (always low
        // over the horizon); TWIN pairs roam anywhere and stay tight — that's their identity.
        List<Star> stars = new ArrayList<>();
        List<double[]> clusterCenters = new ArrayList<>(); // {latDeg, lonDeg}
        StarType[] clusterTypes = {StarType.GOLD, StarType.RED, StarType.BLUE,
                StarType.AMBER, StarType.TWIN, StarType.SEA_GREEN};
        int[] weights = {18, 16, 16, 14, 14, 12};
        int clusterCount = 18 + rnd.nextInt(6);
        for (int i = 0; i < clusterCount; i++) {
            StarType type = pickWeighted(rnd, clusterTypes, weights);
            // Regions repel each other (≥18° apart) so the sky doesn't read as one carpet.
            double lat = 0, lon = 0;
            for (int attempt = 0; attempt < 40; attempt++) {
                if (type == StarType.SEA_GREEN) {
                    lat = (rnd.nextBoolean() ? 1 : -1) * (58 + rnd.nextDouble() * 22);
                } else if (type == StarType.TWIN) {
                    lat = clamp(rnd.nextGaussian() * 30, -80, 80);
                } else {
                    lat = clamp(rnd.nextGaussian() * 22, -50, 50);
                }
                lon = rnd.nextDouble() * 360.0;
                if (minAngularDistance(clusterCenters, lat, lon) >= 18.0) break;
            }
            clusterCenters.add(new double[]{lat, lon});

            if (type == StarType.TWIN) {
                double sep = 0.9 + rnd.nextDouble() * 0.6;
                double ang = rnd.nextDouble() * Math.PI * 2;
                for (int t = 0; t < 2; t++) {
                    double off = (t == 0 ? 1 : -1) * sep / 2;
                    addStar(stars, rnd, lat + Math.sin(ang) * off, lon + Math.cos(ang) * off, type);
                }
            } else {
                int members = 5 + rnd.nextInt(5);
                double spread = 5.5 + rnd.nextDouble() * 6.0;
                for (int m = 0; m < members; m++) {
                    addStar(stars, rnd,
                            lat + rnd.nextGaussian() * spread,
                            lon + rnd.nextGaussian() * spread, type);
                }
            }
        }
        // Field singles: typed color WITHOUT region meaning, scattered everywhere — so a
        // colored star alone never proves a cluster, lone finds feel like discoveries, and
        // hybrid gods (FAITH_PLAN: domain combos) have material between the regions.
        int field = 44 + rnd.nextInt(18);
        for (int i = 0; i < field; i++) {
            StarType type = pickWeighted(rnd, clusterTypes, weights);
            addStar(stars, rnd, clamp(rnd.nextGaussian() * 35, -75, 75),
                    rnd.nextDouble() * 360.0, type);
        }
        // Lone pale stars (JOURNEY): isolation IS their signature — resample away from clusters.
        int lone = 18 + rnd.nextInt(8);
        for (int i = 0; i < lone; i++) {
            for (int attempt = 0; attempt < 20; attempt++) {
                double lat = clamp(rnd.nextGaussian() * 45, -85, 85);
                double lon = rnd.nextDouble() * 360.0;
                if (minAngularDistance(clusterCenters, lat, lon) >= 12.0) {
                    addStar(stars, rnd, lat, lon, StarType.LONE_PALE);
                    break;
                }
            }
        }

        // Solar system: geometric orbit spacing, observer at a=1.0, frost line splits
        // rocky worlds from ringed gas giants. Periods via Kepler's third law.
        int[] rockyTints = {0xC97B5A, 0xD9C9A3, 0xBFB8B0, 0xE0A878};
        int[] gasTints = {0xE8D9A0, 0xA9D6E5, 0x9FE0CE, 0xD8B6E0};
        List<Planet> planets = new ArrayList<>();
        int planetCount = 4 + rnd.nextInt(4);
        double a = 0.45 + rnd.nextDouble() * 0.2;
        for (int i = 0; i < planetCount; i++) {
            while (Math.abs(a - 1.0) < 0.15) a *= 1.3; // clear the observer's own orbit
            boolean gas = a > FROST_LINE;
            int tint = gas ? gasTints[rnd.nextInt(gasTints.length)]
                    : rockyTints[rnd.nextInt(rockyTints.length)];
            float baseSize = gas ? (float) (0.75 + rnd.nextDouble() * 0.45)
                    : (float) (0.5 + rnd.nextDouble() * 0.25);
            boolean rings = gas && rnd.nextDouble() < 0.55;
            int moons = gas ? 1 + rnd.nextInt(4) : (rnd.nextDouble() < 0.35 ? 1 : 0);
            double tilt = rnd.nextDouble() * 35.0;
            double inclination = 1.5 + rnd.nextDouble() * 6.5;
            double node = rnd.nextDouble() * 360.0;
            planets.add(new Planet(a, Math.pow(a, 1.5) * yearDays,
                    rnd.nextDouble() * 360.0, baseSize, tint, rings, moons, tilt,
                    inclination, node));
            a *= 1.5 + rnd.nextDouble() * 0.35;
        }

        return new SkyField(seed, List.copyOf(stars), List.copyOf(planets), observerPhase, yearDays);
    }

    // ── Orbital math (closed-form, circular orbits — see "on rails" in the plan) ────

    /** Observer world's heliocentric longitude (degrees) at {@code days}. */
    public double observerLonDeg(double days) {
        return wrapDeg(observerPhaseDeg + 360.0 * days / observerYearDays);
    }

    /** Where the sun sits against the stars: geocentric longitude (degrees) at {@code days}. */
    public double sunGeocentricLonDeg(double days) {
        return wrapDeg(observerLonDeg(days) + 180.0);
    }

    /**
     * A planet as seen from the observer world at {@code days}: ecliptic longitude
     * relative to the sun point (+Y of the celestial frame) and distance in orbit units.
     * Retrograde, morning/evening stars and brightness all fall out of this one method.
     */
    public PlanetView view(Planet p, double days) {
        double lo = Math.toRadians(observerLonDeg(days));
        double ox = Math.cos(lo), oy = Math.sin(lo);          // observer at a = 1.0, on the ecliptic
        double lp = Math.toRadians(p.helioLonDeg(days));
        double px = p.a() * Math.cos(lp), py = p.a() * Math.sin(lp);
        // Small-inclination circular orbit: the out-of-plane component oscillates over
        // the orbit, crossing zero at the nodes — planets ride NEAR the zodiac band.
        double pz = p.a() * Math.sin(Math.toRadians(p.inclinationDeg()))
                * Math.sin(lp - Math.toRadians(p.nodeDeg()));
        double rx = px - ox, ry = py - oy, rz = pz;
        double dist = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double geoLon = Math.toDegrees(Math.atan2(ry, rx));
        double lat = Math.toDegrees(Math.asin(rz / dist));
        return new PlanetView(wrapDeg(geoLon), lat, dist);
    }

    // ── Star identity & picking (FAITH_PLAN M2 — Pantheon mode) ─────────────────
    // A pickable star id is stable forever: ids 0..VanillaStars.count()-1 are vanilla
    // COMMONS (accepted-order index), ids TYPED_ID_BASE+i are this sky's typed stars
    // (generation-order index under the frozen sky seed). Planets are never pickable.

    public static final int TYPED_ID_BASE = 10_000;

    public boolean isValidStarId(int starId) {
        if (starId >= TYPED_ID_BASE) return starId - TYPED_ID_BASE < stars.size();
        return starId >= 0 && starId < VanillaStars.count();
    }

    /** The typed star for {@code starId}, or null when it's a common. */
    public Star typedStar(int starId) {
        return starId >= TYPED_ID_BASE ? stars.get(starId - TYPED_ID_BASE) : null;
    }

    /**
     * The star's direction in the celestial frame — FIXED for commons AND typed stars:
     * since the heavens wheel once per YEAR (the whole star sphere rotates by
     * {@link #observerLonDeg}), there is no per-star drift any more. The {@code days}
     * parameter is kept for signature stability.
     */
    public double[] starCelestialDir(int starId, double days) {
        if (starId < TYPED_ID_BASE) {
            VanillaStars.CommonStar s = VanillaStars.get(starId);
            return new double[]{s.dx, s.dy, s.dz};
        }
        Star s = stars.get(starId - TYPED_ID_BASE);
        return new double[]{s.dx, s.dy, s.dz};
    }

    /**
     * Picks the star nearest {@code lookCelestial} (unit, celestial frame) within
     * {@code maxAngleDeg}, skipping ids in {@code excluded}. Returns -1 for none.
     */
    public int pickStar(double[] lookCelestial, double days, double maxAngleDeg,
                        java.util.function.IntPredicate excluded) {
        double best = Math.cos(Math.toRadians(maxAngleDeg));
        int bestId = -1;
        for (int i = 0; i < VanillaStars.count(); i++) {
            if (excluded.test(i)) continue;
            VanillaStars.CommonStar s = VanillaStars.get(i);
            double dot = lookCelestial[0] * s.dx + lookCelestial[1] * s.dy + lookCelestial[2] * s.dz;
            if (dot > best) {
                best = dot;
                bestId = i;
            }
        }
        for (int i = 0; i < stars.size(); i++) {
            int id = TYPED_ID_BASE + i;
            if (excluded.test(id)) continue;
            Star s = stars.get(i);
            double dot = lookCelestial[0] * s.dx + lookCelestial[1] * s.dy + lookCelestial[2] * s.dz;
            if (dot > best) {
                best = dot;
                bestId = id;
            }
        }
        return bestId;
    }

    /** Unit direction on the celestial sphere for ecliptic (latDeg, lonDeg). */
    public static double[] direction(double latDeg, double lonDeg) {
        double lat = Math.toRadians(latDeg), lon = Math.toRadians(lonDeg);
        return new double[]{Math.sin(lat), Math.cos(lat) * Math.cos(lon), Math.cos(lat) * Math.sin(lon)};
    }

    public static double wrapDeg(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static void addStar(List<Star> stars, Random rnd, double latDeg, double lonDeg, StarType type) {
        // Wide size + brightness + tint variation so same-type stars don't read as
        // copy-pasted sprites; warm giants (GOLD/RED) run slightly larger.
        float size = (float) (0.12 + rnd.nextDouble() * 0.22);
        if (type == StarType.GOLD || type == StarType.RED) size += 0.04f;
        float alphaMul = (float) (0.5 + rnd.nextDouble() * 0.5);
        stars.add(new Star(direction(clamp(latDeg, -89, 89), lonDeg),
                rnd.nextDouble() * Math.PI * 2, size, alphaMul, jitterRgb(rnd, type.rgb), type));
    }

    /** ±12% brightness jitter on the type color, channel-uniform so the hue holds. */
    private static int jitterRgb(Random rnd, int rgb) {
        double f = 0.88 + rnd.nextDouble() * 0.24;
        int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * f);
        int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * f);
        int b = (int) Math.min(255, (rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    private static StarType pickWeighted(Random rnd, StarType[] types, int[] weights) {
        int total = 0;
        for (int w : weights) total += w;
        int roll = rnd.nextInt(total);
        for (int i = 0; i < types.length; i++) {
            roll -= weights[i];
            if (roll < 0) return types[i];
        }
        return types[types.length - 1];
    }

    private static double minAngularDistance(List<double[]> centers, double latDeg, double lonDeg) {
        double[] d = direction(latDeg, lonDeg);
        double best = 180.0;
        for (double[] c : centers) {
            double[] cd = direction(c[0], c[1]);
            double dot = clamp(d[0] * cd[0] + d[1] * cd[1] + d[2] * cd[2], -1, 1);
            best = Math.min(best, Math.toDegrees(Math.acos(dot)));
        }
        return best;
    }

    private static double[] cross(double ax, double ay, double az, double bx, double by, double bz) {
        return new double[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }

    private static double[] normalize(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{v[0] / len, v[1] / len, v[2] / len};
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
