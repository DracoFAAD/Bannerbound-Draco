package com.bannerbound.core.client.sky;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.celestial.Planet;
import com.bannerbound.core.celestial.SkyField;

import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws the faith sky (FAITH_PLAN.md Part 3) over the vanilla one: typed star clusters +
 * the wandering planets. {@link #onRenderLevelStage} draws the additive celestial layer POST-terrain
 * at {@code RenderLevelStageEvent.AFTER_WEATHER} (LevelRendererMixin only suppresses vanilla's own
 * stars). Drawing after terrain — against the populated depth buffer with a LEQUAL depth test —
 * is what makes terrain occlude the stars in BOTH the vanilla and Iris paths; the earlier
 * inside-{@code renderSky} approach left stars bleeding through hills under a shaderpack because no
 * terrain depth existed yet for the deferred pipeline to test against.
 * <p>
 * Uses the vanilla star alpha ({@code getStarBrightness × (1 − rain)}) so our sky rises, sets
 * and rains out with the vanilla stars. The whole field shares one celestial sphere that wheels
 * once per calendar YEAR ({@code −observerLonDeg}) rather than vanilla's daily spin: within a
 * night the stars are fixed landmarks, and across the year the constellations turn seasonally.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class FaithSkyRenderer {
    /** Celestial sphere radius — matches vanilla's star distance. */
    private static final float SKY_RADIUS = 100.0f;

    private FaithSkyRenderer() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSkyState.clear();
        com.bannerbound.core.client.ClientFaithState.clear();
        com.bannerbound.core.client.ClientFaithTreeState.clear();
        ClientConstellationState.clear();
        PantheonMode.exit();
        METEORS.clear();
        nextMeteorAt = -1.0f;
    }

    // ── Ambient meteors (visual polish — random, client-only, night-only) ─────────

    /** One shooting star: start direction + tangent angular velocity (rad/s). */
    private record Meteor(float sx, float sy, float sz, float velx, float vely, float velz,
                          float spawnSec, float lifeSec, float width) {
    }

    private static final java.util.Random METEOR_RND = new java.util.Random();
    private static final java.util.List<Meteor> METEORS = new java.util.ArrayList<>();
    private static float nextMeteorAt = -1.0f;
    /** Tonight's shower RADIANT — the point meteors fan out from (real showers share one;
     *  that's why they're named for constellations). Re-rolled each game day. */
    private static float[] radiant;
    private static long radiantDay = Long.MIN_VALUE;

    private static void tickAndRenderMeteors(VertexConsumer vc, Matrix4f mat,
                                             float animSec, float starBrightness, long dayIndex) {
        if (radiant == null || dayIndex != radiantDay) {
            radiantDay = dayIndex;
            float ry = 0.35f + METEOR_RND.nextFloat() * 0.5f;
            float rr = (float) Math.sqrt(1.0f - ry * ry);
            float raz = METEOR_RND.nextFloat() * (float) (Math.PI * 2.0);
            radiant = new float[]{(float) Math.cos(raz) * rr, ry, (float) Math.sin(raz) * rr};
        }
        // meteorAmount gamerule ≈ meteors per minute (default 2 = the designed rate;
        // 0 = none, big = meteor storm for testing).
        int amount = ClientSkyState.meteorAmount();
        float baseInterval = amount > 0 ? 60.0f / amount : Float.MAX_VALUE;
        // Resync guard: animSec wraps every few hours — never strand the schedule.
        if (nextMeteorAt < 0 || nextMeteorAt - animSec > baseInterval * 4.0f + 120.0f) {
            nextMeteorAt = animSec + baseInterval * (0.6f + METEOR_RND.nextFloat() * 0.8f);
        }
        if (amount > 0 && starBrightness > 0.1f && animSec >= nextMeteorAt
                && METEORS.size() < Math.min(32, Math.max(2, amount))) {
            METEORS.add(spawnMeteor(animSec));
            nextMeteorAt = animSec + baseInterval * (0.6f + METEOR_RND.nextFloat() * 0.8f);
        }
        METEORS.removeIf(m -> animSec - m.spawnSec() > m.lifeSec() || animSec < m.spawnSec());

        for (Meteor m : METEORS) {
            float t = animSec - m.spawnSec();
            if (t < 0.02f) continue;
            // Head and a short trailing tail along the path (re-normalized to the sphere).
            float[] head = pointAt(m, t);
            float[] tail = pointAt(m, Math.max(0.0f, t - 0.25f));
            // Streak width direction ⊥ the path.
            float wx = head[1] * tail[2] - head[2] * tail[1];
            float wy = head[2] * tail[0] - head[0] * tail[2];
            float wz = head[0] * tail[1] - head[1] * tail[0];
            float wl = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (wl < 1.0e-6f) continue;
            float w = m.width() / wl;
            wx *= w; wy *= w; wz *= w;
            // Fade in/out over the life; head bright → tail transparent (per-vertex alpha).
            float alpha = (float) Math.sin(Math.PI * t / m.lifeSec())
                    * 0.6f * Math.min(1.0f, starBrightness * 1.4f);
            int a = (int) (Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
            float hx = head[0] * SKY_RADIUS, hy = head[1] * SKY_RADIUS, hz = head[2] * SKY_RADIUS;
            float tx = tail[0] * SKY_RADIUS, ty = tail[1] * SKY_RADIUS, tz = tail[2] * SKY_RADIUS;
            vc.addVertex(mat, hx + wx, hy + wy, hz + wz).setColor(255, 244, 220, a);
            vc.addVertex(mat, hx - wx, hy - wy, hz - wz).setColor(255, 244, 220, a);
            vc.addVertex(mat, tx - wx, ty - wy, tz - wz).setColor(255, 244, 220, 0);
            vc.addVertex(mat, tx + wx, ty + wy, tz + wz).setColor(255, 244, 220, 0);
        }
    }

    /** Position on the unit sphere {@code t} seconds along the meteor's path. */
    private static float[] pointAt(Meteor m, float t) {
        float x = m.sx() + m.velx() * t;
        float y = m.sy() + m.vely() * t;
        float z = m.sz() + m.velz() * t;
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / len, y / len, z / len};
    }

    private static Meteor spawnMeteor(float animSec) {
        // Slower than before (18–35°/s) and shorter-lived — a brief brush, not a laser.
        float omega = (float) Math.toRadians(18.0 + METEOR_RND.nextFloat() * 17.0);
        float life = 0.8f + METEOR_RND.nextFloat() * 0.6f;
        float width = 0.10f + METEOR_RND.nextFloat() * 0.08f;

        // ~95% belong to tonight's shower: start 10–45° from the radiant and fly directly
        // AWAY from it (how real showers look). ~5% are sporadics — fully random.
        if (radiant != null && METEOR_RND.nextFloat() < 0.95f) {
            for (int attempt = 0; attempt < 6; attempt++) {
                float[] start = offsetFromRadiant(
                        (float) Math.toRadians(10.0 + METEOR_RND.nextFloat() * 35.0));
                if (start[1] < 0.08f) continue; // keep it above the horizon
                // Tangent at start pointing away from the radiant: -(radiant ⊥-projected onto start).
                float dot = radiant[0] * start[0] + radiant[1] * start[1] + radiant[2] * start[2];
                float ax = radiant[0] - dot * start[0];
                float ay = radiant[1] - dot * start[1];
                float az = radiant[2] - dot * start[2];
                float al = (float) Math.sqrt(ax * ax + ay * ay + az * az);
                if (al < 1.0e-5f) continue;
                return new Meteor(start[0], start[1], start[2],
                        -ax / al * omega, -ay / al * omega, -az / al * omega,
                        animSec, life, width);
            }
        }
        // Sporadic: random position above the horizon, random direction.
        float y = 0.25f + METEOR_RND.nextFloat() * 0.6f;
        float r = (float) Math.sqrt(1.0f - y * y);
        float az = METEOR_RND.nextFloat() * (float) (Math.PI * 2.0);
        float x = (float) Math.cos(az) * r;
        float z = (float) Math.sin(az) * r;
        float t1x = -z, t1z = x; // cross(dir, Y-up), unnormalized (never degenerate: y ≤ 0.85)
        float t1l = (float) Math.sqrt(t1x * t1x + t1z * t1z);
        t1x /= t1l; t1z /= t1l;
        float t2x = y * t1z, t2y = z * t1x - x * t1z, t2z = -y * t1x;
        float ang = METEOR_RND.nextFloat() * (float) (Math.PI * 2.0);
        float ca = (float) Math.cos(ang) * omega, sa = (float) Math.sin(ang) * omega;
        return new Meteor(x, y, z,
                t1x * ca + t2x * sa, t2y * sa, t1z * ca + t2z * sa,
                animSec, life, width);
    }

    /** A point {@code angRad} away from the radiant, at a random azimuth around it. */
    private static float[] offsetFromRadiant(float angRad) {
        float rx = radiant[0], ry = radiant[1], rz = radiant[2];
        float t1x = -rz, t1z = rx; // cross(radiant, Y-up); radiant y ≤ 0.85 so never degenerate
        float t1l = (float) Math.sqrt(t1x * t1x + t1z * t1z);
        t1x /= t1l; t1z /= t1l;
        float t2x = ry * t1z, t2y = rz * t1x - rx * t1z, t2z = -ry * t1x;
        float az = METEOR_RND.nextFloat() * (float) (Math.PI * 2.0);
        float ca = (float) Math.cos(az), sa = (float) Math.sin(az);
        float cA = (float) Math.cos(angRad), sA = (float) Math.sin(angRad);
        return new float[]{
                rx * cA + (t1x * ca + t2x * sa) * sA,
                ry * cA + (t2y * sa) * sA,
                rz * cA + (t1z * ca + t2z * sa) * sA};
    }

    /**
     * Draws the faith sky POST-terrain, at {@code RenderLevelStageEvent.AFTER_WEATHER}.
     * <p>
     * Why here and not inside {@code renderSky}: the celestial layer is additive geometry that must
     * be OCCLUDED by terrain. Drawing it during {@code renderSky} (before terrain) left the depth
     * buffer empty, so under an Iris shaderpack the deferred pipeline composited our stars without
     * ever testing them against terrain depth — stars bled through hills. No render-state tweak can
     * reach that, because the terrain depth simply isn't there yet. By {@code AFTER_WEATHER} terrain
     * and entities are drawn and the real depth buffer is populated, so a plain LEQUAL depth test
     * (see {@link SkyRenderTypes#SKY_ADDITIVE}) makes terrain occlude the stars in BOTH the vanilla
     * and Iris paths.
     * <p>
     * Matrices: the event hands us the SAME camera modelview {@code renderSky} used
     * ({@code getModelViewMatrix()} — camera rotation, NO translation: stars are at infinity) plus
     * the active world projection ({@code getProjectionMatrix()}). We push the modelview onto the
     * global stack exactly as the old path did, leaving the projection alone. The dome is then
     * uniformly scaled out to just inside the projection's FAR plane (see {@code domeScale}) so the
     * stars project to depth ≈ 1.0 and ANY terrain — near or far — occludes them. Because we now
     * control the matrices and draw through the world program against real depth, the old Iris
     * STARS-phase override is neither needed nor wanted (it would re-route us into Iris's sky pass,
     * which does not depth-test against terrain), so it is gone from this path.
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        SkyField sky = ClientSkyState.field();
        if (level == null || sky == null) return;
        if (level.dimension() != Level.OVERWORLD) return;

        // Camera modelview = rotation only, no translation — identical to the frustum matrix the
        // old renderSky path was handed (stars sit at infinity).
        Matrix4f frustumMatrix = event.getModelViewMatrix();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        // Push the dome out to just inside the world projection's far clip so every star projects to
        // depth ≈ 1.0; terrain (any distance) is nearer and so occludes it. We scale the WHOLE pose
        // (centres AND quad sizes) uniformly, preserving each object's angular size. Guard against a
        // tiny/garbage far plane (e.g. a non-perspective projection) by falling back to the radius.
        float far = event.getProjectionMatrix().perspectiveFar();
        float targetRadius = (far > SKY_RADIUS * 1.5f && Float.isFinite(far))
                ? far * 0.98f : SKY_RADIUS;
        float domeScale = targetRadius / SKY_RADIUS;

        float clearSky = 1.0f - level.getRainLevel(partial);
        float starBrightness = level.getStarBrightness(partial) * clearSky;
        // Planets outshine the stars and appear in twilight BEFORE them (the Venus-at-dusk
        // effect): same curve, steeper ramp.
        float planetBrightness = Math.min(1.0f, level.getStarBrightness(partial) * 1.7f) * clearSky;
        if (planetBrightness <= 0.02f) return;

        // celestialSpeed gamerule: orbital/seasonal time multiplier (testing). Scales the
        // model clock only — the daily sky rotation stays vanilla.
        double days = level.getDayTime() / 24000.0 * ClientSkyState.celestialSpeed();
        // THE HEAVENS WHEEL ONCE A YEAR (deliberate artistic license — see FAITH_PLAN):
        // the sun/moon keep vanilla's daily arc, but the star sphere (commons + typed +
        // planets + constellations) rotates once per calendar year. Within a night the
        // stars are fixed landmarks — celestial navigation works, pole stars exist —
        // and across the year the sky turns: seasonal constellations, literally.
        float wheelDeg = (float) -sky.observerLonDeg(days);
        // Animation clock in real seconds (gameTime is 20 ticks/s) — drives the star
        // twinkle and the planet-glow rotation. Stars twinkle, planets burn steady:
        // the same discriminator real naked-eye astronomy uses.
        float animSec = (level.getGameTime() % 240000L + partial) / 20.0f;

        // The camera/frustum matrix goes into the GLOBAL modelview, applied exactly ONCE by
        // whoever ends up drawing — vanilla's POSITION_COLOR shader (which reads
        // RenderSystem.getModelViewMatrix()) OR Iris's world program (whose iris_ModelViewMat
        // is fed the same camera modelview for all world geometry). We must NOT also bake the
        // camera into the vertices: under Iris that double-applies it and the whole sky swims
        // with the camera (it looked fine in vanilla only because there the global modelview is
        // identity, so the single baked copy was the whole transform). Vertices therefore carry
        // ONLY the celestial frame (meteors: none; stars: the yearly wheel). We force-SET the
        // stack (not multiply) so a non-identity global left by another backend can't double up.
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        mvStack.set(frustumMatrix);
        RenderSystem.applyModelViewMatrix();

        PoseStack pose = new PoseStack();
        // Blow the unit-100 dome out to the far clip (uniform scale → angular size preserved, but
        // post-projection depth ≈ 1.0 so terrain occludes). Applied to the BASE pose so meteors AND
        // the wheeled celestial frame both inherit it; quad/line/glow sizes (which are added in this
        // same scaled space) grow with it and keep their on-screen size.
        pose.scale(domeScale, domeScale, domeScale);
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(SkyRenderTypes.SKY_ADDITIVE);

        // Ambient meteors — atmospheric, so they live in the plain view frame (they do
        // NOT ride the celestial rotation). Pure cosmetics: random, client-only.
        tickAndRenderMeteors(vc, pose.last().pose(), animSec, starBrightness,
                Math.floorDiv(level.getDayTime(), 24000L));

        pose.pushPose();
        // Same axis as vanilla's celestial transform, but the YEARLY angle.
        pose.mulPose(Axis.YP.rotationDegrees(-90.0f));
        pose.mulPose(Axis.XP.rotationDegrees(wheelDeg));

        // One frame for everything: planets sit at their ABSOLUTE geocentric ecliptic
        // longitude in the same yearly-wheeling sphere as the stars.
        Matrix4f planetMat = pose.last().pose();
        for (Planet p : sky.planets) {
            SkyField.PlanetView view = sky.view(p, days);
            double phi = Math.toRadians(view.eclipticLonDeg());
            double lat = Math.toRadians(view.eclipticLatDeg());
            // Direction with ecliptic latitude: planets ride NEAR the zodiac band, not on it.
            float dx = (float) Math.sin(lat);
            float dy = (float) (Math.cos(lat) * Math.cos(phi));
            float dz = (float) (Math.cos(lat) * Math.sin(phi));
            // Quad basis ⊥ dir. ref=(1,0,0) is always safe: |dx| = sin(lat) ≤ sin(8°).
            // u = normalize(cross(ref, d)), v = cross(d, u).
            float ul = (float) Math.sqrt(dz * dz + dy * dy);
            float ux = 0, uy = dz / ul, uz = -dy / ul;
            float vx = dy * uz - dz * uy;
            float vy = dz * ux - dx * uz;
            float vz = dx * uy - dy * ux;
            // Apparent size and brightness fall out of current distance (FAITH_PLAN:
            // planets genuinely brighten as they approach).
            float size = 1.25f * p.baseSize() * (float) clamp(1.0 / view.distance(), 0.45, 1.8);
            float alpha = planetBrightness * (float) clamp(1.15 / view.distance(), 0.55, 1.0);
            // Steady core + a slowly ROTATING gradient glow (bright center → transparent
            // rim): reads as "planet, not star" at a glance.
            quad(vc, planetMat, dx, dy, dz, ux, uy, uz, vx, vy, vz, size, p.rgb(), alpha);
            glow(vc, planetMat, dx, dy, dz, ux, uy, uz, vx, vy, vz,
                    size * 3.0f, p.rgb(), alpha * 0.55f, animSec * 0.12f);
        }

        // ── Constellations + Pantheon mode (FAITH_PLAN M2) ────────────────────────
        // Everything here lives in the BASE celestial frame: starCelestialDir() bakes the
        // typed-star seasonal drift, so commons and typed endpoints sit exactly where
        // they're rendered — one math path for lines, glows AND picking.
        if (PantheonMode.isActive() && starBrightness <= 0.05f) {
            PantheonMode.exit(); // dawn or weather closed the sky mid-session
        }
        if (PantheonMode.isActive()) {
            for (int id : PantheonMode.chain()) {
                if (!sky.isValidStarId(id)) {
                    PantheonMode.exit(); // sky rerolled mid-draft
                    break;
                }
            }
        }
        if (com.bannerbound.core.client.ClientFaithState.hasFaith()) {
            // Believer sky: the confirmed pantheon, faint silver lines + member glows.
            for (com.bannerbound.core.network.ConstellationsSyncPayload.Entry entry
                    : ClientConstellationState.entries()) {
                int[] ids = entry.starIds();
                boolean allValid = true;
                for (int id : ids) {
                    if (!sky.isValidStarId(id)) {
                        allValid = false;
                        break;
                    }
                }
                if (!allValid) continue; // stale constellation from a rerolled sky
                float lineAlpha = starBrightness * 0.45f;
                for (int i = 0; i + 1 < ids.length; i++) {
                    lineQuad(vc, planetMat, sky.starCelestialDir(ids[i], days),
                        sky.starCelestialDir(ids[i + 1], days), 0.10f, 0xE8E2D0, lineAlpha);
                }
                for (int id : ids) {
                    double[] d = sky.starCelestialDir(id, days);
                    float[] uv = basisFor(d);
                    glow(vc, planetMat, (float) d[0], (float) d[1], (float) d[2],
                        uv[0], uv[1], uv[2], uv[3], uv[4], uv[5],
                        0.7f, 0xE8E2D0, starBrightness * 0.30f, 0.0f);
                }
            }
        }
        if (PantheonMode.isActive()) {
            // Crosshair pick: look vector → celestial frame (inverse of the render
            // transform: Rx(-skyAngle) after Ry(+90)).
            double[] lookCel = null;
            if (mc.screen == null && mc.player != null) {
                net.minecraft.world.phys.Vec3 look = mc.player.getViewVector(partial);
                double ang = Math.toRadians(wheelDeg);               // inverse of the wheel
                double x1 = look.z, y1 = look.y, z1 = -look.x;       // Ry(+90)
                double c = Math.cos(-ang), s = Math.sin(-ang);
                lookCel = new double[]{x1, y1 * c - z1 * s, y1 * s + z1 * c}; // Rx(-ang)
                PantheonMode.setHovered(sky.pickStar(lookCel, days,
                    PantheonMode.PICK_CONE_DEG, ClientConstellationState::starUsed));
            }
            // Draft chain — gold, brighter than the confirmed sky.
            java.util.List<Integer> chain = PantheonMode.chain();
            for (int i = 0; i + 1 < chain.size(); i++) {
                lineQuad(vc, planetMat, sky.starCelestialDir(chain.get(i), days),
                    sky.starCelestialDir(chain.get(i + 1), days),
                    0.14f, 0xFFE08A, starBrightness * 0.9f);
            }
            // Selected stars read unmistakably: a bright steady core + a slow-spinning
            // outer ring per chain member.
            for (int id : chain) {
                double[] d = sky.starCelestialDir(id, days);
                float[] uv = basisFor(d);
                glow(vc, planetMat, (float) d[0], (float) d[1], (float) d[2],
                    uv[0], uv[1], uv[2], uv[3], uv[4], uv[5],
                    0.6f, 0xFFF2C8, starBrightness * 0.85f, 0.0f);
                glow(vc, planetMat, (float) d[0], (float) d[1], (float) d[2],
                    uv[0], uv[1], uv[2], uv[3], uv[4], uv[5],
                    1.3f, 0xFFE08A, starBrightness * 0.4f, animSec * 0.5f);
            }
            int hovered = PantheonMode.hoveredStarId();
            if (hovered >= 0 && !sky.isValidStarId(hovered)) hovered = -1;
            // Rubber band: the next segment follows the crosshair in real time, snapping
            // onto the hovered star when one is in the cone.
            if (!chain.isEmpty() && (hovered >= 0 || lookCel != null)) {
                double[] from = sky.starCelestialDir(chain.get(chain.size() - 1), days);
                double[] to = hovered >= 0 ? sky.starCelestialDir(hovered, days) : lookCel;
                lineQuad(vc, planetMat, from, to, 0.08f, 0xFFE08A, starBrightness * 0.45f);
            }
            if (hovered >= 0) {
                double[] d = sky.starCelestialDir(hovered, days);
                float[] uv = basisFor(d);
                float pulse = 0.55f + 0.25f * (float) Math.sin(animSec * 5.0);
                glow(vc, planetMat, (float) d[0], (float) d[1], (float) d[2],
                    uv[0], uv[1], uv[2], uv[3], uv[4], uv[5],
                    1.4f, 0xFFD27A, starBrightness * pulse, animSec * 0.8f);
            }
        }

        // All stars share the yearly frame now — typed AND the 780 commons (vanilla's own
        // star pass is suppressed by LevelRendererMixin; we render them so they wheel with
        // everything else instead of shearing off on the daily rotation).
        if (starBrightness > 0.02f) {
            int i = 0;
            for (SkyField.Star s : sky.stars) {
                // Per-star twinkle: subtle alpha shimmer, frequency/phase varied by index.
                float twinkle = 0.78f + 0.22f * (float) Math.sin(
                        animSec * (1.5f + (i % 7) * 0.35f) + i * 2.1f);
                quad(vc, planetMat, s.dx, s.dy, s.dz, s.ux, s.uy, s.uz, s.vx, s.vy, s.vz,
                        s.size, s.rgb, starBrightness * s.alphaMul * twinkle);
                i++;
            }
            int commonCount = com.bannerbound.core.celestial.VanillaStars.count();
            for (int ci = 0; ci < commonCount; ci++) {
                com.bannerbound.core.celestial.VanillaStars.CommonStar s =
                    com.bannerbound.core.celestial.VanillaStars.get(ci);
                float twinkle = 0.80f + 0.20f * (float) Math.sin(
                        animSec * (1.4f + (ci % 5) * 0.3f) + ci * 1.3f);
                double[] d = {s.dx, s.dy, s.dz};
                float[] uv = basisFor(d);
                quad(vc, planetMat, s.dx, s.dy, s.dz, uv[0], uv[1], uv[2], uv[3], uv[4], uv[5],
                        s.size, 0xEAEAEA, starBrightness * twinkle);
            }
        }

        pose.popPose();
        // Flush in the world program against the populated depth buffer. NO Iris STARS-phase
        // override here (unlike the old renderSky path): at AFTER_WEATHER we control the modelview
        // ourselves, so the camera double-transform that override fixed cannot occur — and routing
        // into Iris's sky pass would skip the depth test against terrain, reintroducing the
        // stars-through-terrain bug we are fixing. We deliberately stay on the depth-testing world
        // program (see IrisSkyCompat for the retired rationale).
        try {
            buffer.endBatch(SkyRenderTypes.SKY_ADDITIVE);
        } finally {
            mvStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /**
     * Radial gradient glow: a fan of 4 quads from a bright center vertex to alpha-zero rim
     * vertices — the GPU interpolates a real falloff (a flat low-alpha quad just reads as
     * a sticker). {@code rotRad} slowly spins the rim so the glow visibly lives.
     */
    private static void glow(VertexConsumer vc, Matrix4f mat,
                             float dx, float dy, float dz,
                             float ux, float uy, float uz,
                             float vx, float vy, float vz,
                             float s, int rgb, float alpha, float rotRad) {
        float cx = dx * SKY_RADIUS, cy = dy * SKY_RADIUS, cz = dz * SKY_RADIUS;
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int a = (int) (Math.min(1.0f, alpha) * 255.0f);
        // 4 rim points of a rotated square around the center.
        float[] rim = new float[12];
        for (int i = 0; i < 4; i++) {
            double ang = rotRad + i * (Math.PI / 2.0);
            float cu = (float) Math.cos(ang) * s, cv = (float) Math.sin(ang) * s;
            rim[i * 3] = cx + cu * ux + cv * vx;
            rim[i * 3 + 1] = cy + cu * uy + cv * vy;
            rim[i * 3 + 2] = cz + cu * uz + cv * vz;
        }
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            vc.addVertex(mat, cx, cy, cz).setColor(r, g, b, a);
            vc.addVertex(mat, rim[i * 3], rim[i * 3 + 1], rim[i * 3 + 2]).setColor(r, g, b, 0);
            vc.addVertex(mat, rim[j * 3], rim[j * 3 + 1], rim[j * 3 + 2]).setColor(r, g, b, 0);
            vc.addVertex(mat, cx, cy, cz).setColor(r, g, b, a);
        }
    }

    /** Orthonormal quad basis ⊥ {@code d} — same construction the typed stars bake. */
    private static float[] basisFor(double[] d) {
        double rx = Math.abs(d[1]) < 0.99 ? 0 : 1;
        double ry = Math.abs(d[1]) < 0.99 ? 1 : 0;
        double ux = ry * d[2], uy = -rx * d[2], uz = rx * d[1] - ry * d[0];
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;
        double vx = d[1] * uz - d[2] * uy;
        double vy = d[2] * ux - d[0] * uz;
        double vz = d[0] * uy - d[1] * ux;
        return new float[]{(float) ux, (float) uy, (float) uz, (float) vx, (float) vy, (float) vz};
    }

    /** A constant-width line segment between two celestial directions (constellation edges). */
    private static void lineQuad(VertexConsumer vc, Matrix4f mat, double[] a, double[] b,
                                 float width, int rgb, float alpha) {
        double cx = a[1] * b[2] - a[2] * b[1];
        double cy = a[2] * b[0] - a[0] * b[2];
        double cz = a[0] * b[1] - a[1] * b[0];
        double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (len < 1.0e-6) return;
        float wx = (float) (cx / len * width);
        float wy = (float) (cy / len * width);
        float wz = (float) (cz / len * width);
        float ax = (float) a[0] * SKY_RADIUS, ay = (float) a[1] * SKY_RADIUS, az = (float) a[2] * SKY_RADIUS;
        float bx = (float) b[0] * SKY_RADIUS, by = (float) b[1] * SKY_RADIUS, bz = (float) b[2] * SKY_RADIUS;
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, bl = rgb & 0xFF;
        int al = (int) (Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        vc.addVertex(mat, ax + wx, ay + wy, az + wz).setColor(r, g, bl, al);
        vc.addVertex(mat, ax - wx, ay - wy, az - wz).setColor(r, g, bl, al);
        vc.addVertex(mat, bx - wx, by - wy, bz - wz).setColor(r, g, bl, al);
        vc.addVertex(mat, bx + wx, by + wy, bz + wz).setColor(r, g, bl, al);
    }

    /** One billboard quad on the celestial sphere: center 100·d, half-extents s·u and s·v. */
    private static void quad(VertexConsumer vc, Matrix4f mat,
                             float dx, float dy, float dz,
                             float ux, float uy, float uz,
                             float vx, float vy, float vz,
                             float s, int rgb, float alpha) {
        float cx = dx * SKY_RADIUS, cy = dy * SKY_RADIUS, cz = dz * SKY_RADIUS;
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int a = (int) (Math.min(1.0f, alpha) * 255.0f);
        vc.addVertex(mat, cx - s * ux - s * vx, cy - s * uy - s * vy, cz - s * uz - s * vz).setColor(r, g, b, a);
        vc.addVertex(mat, cx + s * ux - s * vx, cy + s * uy - s * vy, cz + s * uz - s * vz).setColor(r, g, b, a);
        vc.addVertex(mat, cx + s * ux + s * vx, cy + s * uy + s * vy, cz + s * uz + s * vz).setColor(r, g, b, a);
        vc.addVertex(mat, cx - s * ux + s * vx, cy - s * uy + s * vy, cz - s * uz + s * vz).setColor(r, g, b, a);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Additive star/glow quads. Depth TEST on (LEQUAL) so terrain occludes the dome; depth WRITE off
     * (COLOR_WRITE write mask) so the overlapping additive glows still blend instead of z-fighting
     * each other. Blend is LIGHTNING_TRANSPARENCY (additive).
     * <p>
     * This render state ONLY occludes correctly because the layer is now drawn POST-terrain at
     * {@code RenderLevelStageEvent.AFTER_WEATHER} (see {@link #onRenderLevelStage}), where the depth
     * buffer holds real terrain depth in BOTH the vanilla and Iris paths. The two earlier fixes
     * (explicit LEQUAL test, then depth write) could not work from inside {@code renderSky}: terrain
     * had not been drawn yet, so there was no depth to test against — Iris's deferred pipeline
     * composited the geometry over the finished scene regardless. The dome is scaled to the far clip
     * so its depth ≈ 1.0 and any terrain is nearer.
     */
    private static final class SkyRenderTypes extends RenderType {
        private SkyRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                               boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
            super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
        }

        static final RenderType SKY_ADDITIVE = create(
                "bannerbound_sky_additive",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                4096, false, false,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false));
    }
}
