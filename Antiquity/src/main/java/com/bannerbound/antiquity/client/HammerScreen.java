package com.bannerbound.antiquity.client;

import java.util.ArrayList;
import java.util.List;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.network.HammerActionPayload;
import com.bannerbound.core.api.quality.QualityMath;
import com.bannerbound.core.api.quality.QualityTier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import org.lwjgl.glfw.GLFW;

/**
 * The cold-hammer minigame (METALWORKING_PLAN.md Part 3) — swing the hammer down. <b>Drag down</b> to
 * start the head falling; it's pulled by <b>exponential gravity</b>, so the longer it falls the faster
 * it goes and the harder it is to drag back up (momentum). Meanwhile the <b>green zone shrinks</b> as
 * the metal work-hardens — wait too long and it's gone (a miss). Release on the green to land the
 * strike. Each strike is graded poor/good/great/perfect (own sound + a screen shake); the piece needs
 * one strike per 50 mB. The server is authoritative for the rank-gated quality.
 */
@OnlyIn(Dist.CLIENT)
public final class HammerScreen extends Screen {
    /** Velocity (track-fractions/s) imparted per pixel of mouse drag (down +, up −). */
    private static final float DRAG_FORCE = 0.0045F;
    /** Base downward acceleration (kept low so it starts SLOW), plus the strongly-exponential term
     *  {@code accel += vel·GROWTH} while falling — so the smack really builds and you feel the weight. */
    private static final float GRAVITY = 0.08F;
    private static final float GROWTH = 2.8F;
    private static final float MAX_VEL = 2.2F;
    /** Green half-width: starts at GREEN_MAX, shrinks GREEN_SHRINK/s; under GREEN_GONE any hit misses. */
    private static final float GREEN_MAX = 0.095F;
    private static final float GREEN_SHRINK = 0.028F;
    private static final float GREEN_GONE = 0.010F;
    private static final int POOR_SCORE = 15;
    private static final int GOOD_SCORE = 55;
    private static final int GREAT_SCORE = 85;
    private static final int PERFECT_SCORE = 100;

    public static volatile boolean MINIGAME_ACTIVE = false;
    private static volatile long shakeStartMs = 0;
    private static volatile float shakeMag = 0;
    /** 0..1, how fast the head is currently falling — drives the FOV pull-in and the vignette. */
    private static volatile float swingIntensity = 0F;
    /** 0..1, how high the first-person hammer arm is cocked — raised once the swing starts, down on impact. */
    private static volatile float handRaiseAmount = 0F;

    private final BlockPos pos;
    private final int strikes;
    private final boolean canSuperior; // hammer strong enough for the top tier (else preview caps Standard)
    private final List<Integer> scores = new ArrayList<>();
    private final float[] trail = new float[10]; // recent cursor positions for the falling-head trail
    private int trailCount = 0;

    private boolean completed = false;
    private boolean dragging = false;
    private boolean falling = false; // gravity only applies AFTER you drag down to start the swing
    private float cursorPos = 0F;
    private float cursorVel = 0F;
    private long lastUpdateMs = 0;
    private long swingStartMs = 0;
    private float greenCenter = 0.5F;
    private long flashStartMs = 0;
    private int flashColor = 0;
    private String flashText = "";

    public HammerScreen(BlockPos pos, int strikes, boolean canSuperior) {
        super(Component.translatable("screen.bannerboundantiquity.hammer"));
        this.pos = pos;
        this.strikes = Math.max(1, strikes);
        this.canSuperior = canSuperior;
    }

    @Override
    protected void init() {
        MINIGAME_ACTIVE = true;
        newStrike();
    }

    @Override
    public void removed() {
        MINIGAME_ACTIVE = false;
        swingIntensity = 0F;
        handRaiseAmount = 0F;
        if (!completed) {
            sendAction(HammerActionPayload.CANCEL, List.of());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void newStrike() {
        dragging = false;
        falling = false;
        cursorPos = 0F;
        cursorVel = 0F;
        trailCount = 0;
        greenCenter = 0.42F + random().nextFloat() * 0.30F;
    }

    private void pushTrail(float p) {
        if (trailCount < trail.length) {
            trail[trailCount++] = p;
        } else {
            System.arraycopy(trail, 1, trail, 0, trail.length - 1);
            trail[trail.length - 1] = p;
        }
    }

    private RandomSource random() {
        return Minecraft.getInstance().level != null
            ? Minecraft.getInstance().level.random : RandomSource.create();
    }

    /** Green half-width right now — shrinks the longer this strike runs. */
    private float greenHalf() {
        if (!dragging) return GREEN_MAX;
        float elapsed = (System.currentTimeMillis() - swingStartMs) / 1000F;
        return Math.max(0F, GREEN_MAX - elapsed * GREEN_SHRINK);
    }

    private void beginDrag() {
        if (completed || scores.size() >= strikes) return;
        dragging = true;
        falling = false;
        cursorPos = 0F;
        cursorVel = 0F;
        trailCount = 0;
        long now = System.currentTimeMillis();
        lastUpdateMs = now;
        swingStartMs = now;
    }

    /** Real-time physics: exponential gravity while falling; drag adds velocity (and fights it going up). */
    private void advance() {
        if (!dragging) { // ease the FOV/vignette/arm back out when idle (between and after strikes)
            swingIntensity *= 0.85F;
            handRaiseAmount *= 0.80F;
            return;
        }
        long now = System.currentTimeMillis();
        float dt = Math.min(0.05F, (now - lastUpdateMs) / 1000F);
        lastUpdateMs = now;
        if (falling) { // gravity only kicks in once you've started the swing with a downward drag
            float accel = GRAVITY + Math.max(0F, cursorVel) * GROWTH; // exponential while falling
            cursorVel = Math.min(MAX_VEL, cursorVel + accel * dt);
        }
        swingIntensity = Mth.clamp(Math.max(0F, cursorVel) / MAX_VEL, 0F, 1F);
        handRaiseAmount = 1F - Mth.clamp(cursorPos, 0F, 1F); // arm raised at the top, swung down at impact
        cursorPos += cursorVel * dt;
        pushTrail(cursorPos);
        if (cursorPos <= 0F) {
            cursorPos = 0F;
            cursorVel = 0F;
            falling = false; // pulled all the way back to rest — the swing resets, no auto-fall
        }
        if (cursorPos >= 1.0F) {
            cursorPos = 1.0F;
            release(); // overshot the bottom → a miss
        }
    }

    private void release() {
        if (!dragging) return;
        float gh = greenHalf();
        dragging = false;
        strike(cursorPos, gh);
    }

    private void strike(float p, float gh) {
        int score;
        SoundEvent sound;
        float d = Math.abs(p - greenCenter);
        if (gh < GREEN_GONE || d > gh) {
            score = POOR_SCORE; flashColor = 0xFFFF5555; flashText = gh < GREEN_GONE ? "Too Slow" : "Poor";
            sound = BannerboundAntiquity.HAMMER_POOR_SOUND.get(); shake(0.35F);
        } else if (d <= gh * 0.30F) {
            score = PERFECT_SCORE; flashColor = 0xFF55FF55; flashText = "Perfect";
            sound = BannerboundAntiquity.HAMMER_PERFECT_SOUND.get(); shake(0.9F);
        } else if (d <= gh * 0.65F) {
            score = GREAT_SCORE; flashColor = 0xFF9BE04B; flashText = "Great";
            sound = BannerboundAntiquity.HAMMER_GREAT_SOUND.get(); shake(0.7F);
        } else {
            score = GOOD_SCORE; flashColor = 0xFFFFE255; flashText = "Good";
            sound = BannerboundAntiquity.HAMMER_GOOD_SOUND.get(); shake(0.5F);
        }
        scores.add(score);
        flashStartMs = System.currentTimeMillis();
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.playSound(sound, 0.9F, 1.0F); // local: zero-latency grade feedback for the smith
        }
        if (scores.size() == 1) {
            sendAction(HammerActionPayload.COMMIT, List.of());
        }
        // The server fires the world-visible sparks/clang/swing (so other players see it) on this STRIKE.
        sendAction(HammerActionPayload.STRIKE, List.of(score));
        if (scores.size() >= strikes) {
            completed = true;
            sendAction(HammerActionPayload.COMPLETE, new ArrayList<>(scores));
            if (minecraft != null) minecraft.setScreen(null);
        } else {
            newStrike();
        }
    }

    private static void shake(float magnitude) {
        shakeStartMs = System.currentTimeMillis();
        shakeMag = magnitude;
    }

    private void sendAction(int action, List<Integer> sc) {
        PacketDistributor.sendToServer(new HammerActionPayload(pos, action, sc));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) { beginDrag(); return true; }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && dragging) {
            cursorVel += (float) dy * DRAG_FORCE; // a flick down builds momentum; up fights it
            if (dy > 0) falling = true; // the first downward drag begins the swing → gravity engages
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) { release(); return true; }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_SPACE) { beginDrag(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_SPACE) { release(); return true; }
        return super.keyReleased(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        advance();
        renderVignette(g);

        int cx = width / 2;
        int top = (int) (height * 0.22F);
        int bottom = (int) (height * 0.80F);
        int h = bottom - top;
        int trackW = 14;

        g.drawCenteredString(font, Component.translatable("screen.bannerboundantiquity.hammer"),
            cx, top - 28, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.literal(scores.size() + " / " + strikes),
            cx, top - 16, 0xFFB0B0B0);

        g.fill(cx - trackW / 2 - 1, top - 1, cx + trackW / 2 + 1, bottom + 1, 0xFF000000);
        g.fill(cx - trackW / 2, top, cx + trackW / 2, bottom, 0xFF2B2B2B);

        // Green zone — its current (shrinking) size.
        float gh = greenHalf();
        boolean inGreen = false;
        if (gh >= GREEN_GONE) {
            int gTop = top + (int) ((greenCenter - gh) * h);
            int gBot = top + (int) ((greenCenter + gh) * h);
            int gCore0 = top + (int) ((greenCenter - gh * 0.30F) * h);
            int gCore1 = top + (int) ((greenCenter + gh * 0.30F) * h);
            g.fill(cx - trackW / 2, gTop, cx + trackW / 2, gBot, 0x80FFE255);
            g.fill(cx - trackW / 2, gCore0, cx + trackW / 2, gCore1, 0xC055FF55);
            // "Release now" telegraph: when the falling head is inside the band, glow brighter and flare
            // out so the timing window reads clearly. Pulses with a sine so it pops even while shrinking.
            inGreen = dragging && Math.abs(cursorPos - greenCenter) <= gh;
            if (inGreen) {
                float pulse = 0.55F + 0.45F * Mth.sin(System.currentTimeMillis() * 0.018F);
                int a = (int) (0x70 + 0x80 * pulse) << 24;
                g.fill(cx - trackW / 2 - 3, gTop, cx - trackW / 2, gBot, a | 0x00FFFFFF);
                g.fill(cx + trackW / 2, gTop, cx + trackW / 2 + 3, gBot, a | 0x00FFFFFF);
                g.fill(cx - trackW / 2, gCore0, cx + trackW / 2, gCore1, (a & 0xFF000000) | 0x00AAFFAA);
            }
        }

        // Motion trail of the falling head — older samples fainter, selling the acceleration.
        for (int i = 0; i < trailCount; i++) {
            float age = (i + 1) / (float) (trailCount + 1); // newest → ~1
            int ty = top + (int) (Mth.clamp(trail[i], 0F, 1F) * h);
            int a = (int) (0x60 * age);
            if (a <= 4) continue;
            g.fill(cx - trackW / 2, ty, cx + trackW / 2, ty + 1, (a << 24) | 0x00FFFFFF);
        }

        int cy = top + (int) (Mth.clamp(cursorPos, 0F, 1F) * h);
        int cursorCol = inGreen ? 0xFF9BFF6B : 0xFFFFFFFF;
        int cursorReach = inGreen ? 6 : 4; // the bar flares wider when it's in the sweet spot
        g.fill(cx - trackW / 2 - cursorReach, cy - 1, cx + trackW / 2 + cursorReach, cy + 1, cursorCol);

        // ── Bottom stack (clear vertical bands so nothing overlaps): pips → hint → quality bar+label ──
        int pipY = bottom + 10;
        int pipW = 9;
        int startX = cx - (strikes * pipW) / 2;
        for (int i = 0; i < strikes; i++) {
            int color = i < scores.size() ? gradeColor(scores.get(i)) : 0xFF555555;
            g.fill(startX + i * pipW + 1, pipY, startX + i * pipW + pipW - 1, pipY + 5, color);
        }

        g.drawCenteredString(font, Component.translatable("screen.bannerboundantiquity.hammer_hint"),
            cx, pipY + 12, 0xFF808080);

        // Quality preview — the tier this piece is on pace for (rank-capped), filling a graded bar.
        QualityTier projected = projectedTier();
        if (projected != null) {
            int segW = 16, segs = QualityTier.values().length;
            int barX = cx - (segs * segW) / 2;
            int barY = pipY + 28;
            for (int i = 0; i < segs; i++) {
                boolean lit = i <= projected.ordinal();
                int col = lit ? QualityTier.values()[i].color() : 0xFF3A3A3A;
                g.fill(barX + i * segW + 1, barY, barX + i * segW + segW - 1, barY + 4, col);
            }
            var label = Component.translatable("bannerbound.fletching.quality")
                .append(" ").append(projected.displayName());
            if (!canSuperior) {
                label.append(Component.translatable("screen.bannerboundantiquity.hammer_capped")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            }
            g.drawCenteredString(font, label, cx, barY + 8, 0xFFFFFFFF);
        }

        long since = System.currentTimeMillis() - flashStartMs;
        if (!flashText.isEmpty() && since < 500) {
            g.drawCenteredString(font, Component.literal(flashText), cx, top + h / 2 - 4, flashColor);
        }
    }

    /** The quality tier this piece is currently on pace for (from the strikes landed so far), with the
     *  same hammer-rank cap the server applies; {@code null} before the first strike. */
    private QualityTier projectedTier() {
        if (scores.isEmpty()) return null;
        int[] arr = new int[scores.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = scores.get(i);
        QualityTier rolled = QualityMath.npcTierFromScore(QualityMath.aggregate(arr));
        if (!canSuperior && rolled.ordinal() > QualityTier.STANDARD.ordinal()) {
            return QualityTier.STANDARD;
        }
        return rolled;
    }

    private static int gradeColor(int score) {
        if (score >= PERFECT_SCORE) return 0xFF55FF55;
        if (score >= GREAT_SCORE) return 0xFF9BE04B;
        if (score >= GOOD_SCORE) return 0xFFFFE255;
        return 0xFFFF5555;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partialTick) {
        // Transparent — the world (and the screen shake) stays visible behind the minigame.
    }

    /** Darken the screen edges, building with the swing and pulsing on each strike — the "tunnel
     *  vision" of bearing down on the smack. Drawn over the (transparent) world, under the track. */
    private void renderVignette(GuiGraphics g) {
        float intensity = 0.16F + 0.55F * swingIntensity;
        long sinceFlash = System.currentTimeMillis() - flashStartMs;
        if (!flashText.isEmpty() && sinceFlash < 200) {
            intensity += 0.32F * (1F - sinceFlash / 200F); // a darken pulse the instant the hammer lands
        }
        intensity = Math.min(0.86F, intensity);
        int maxA = (int) (intensity * 255F);
        if (maxA <= 0) return;
        int bandY = (int) (height * 0.34F);
        int bandX = (int) (width * 0.30F);
        // Top/bottom: fillGradient interpolates vertically, so use it directly there.
        g.fillGradient(0, 0, width, bandY, maxA << 24, 0x00000000);
        g.fillGradient(0, height - bandY, width, height, 0x00000000, maxA << 24);
        // Left/right: fillGradient is vertical-only, so fake a horizontal fade with thin strips.
        for (int i = 0; i < bandX; i++) {
            int a = (int) (maxA * (1F - (float) i / bandX));
            if (a <= 0) continue;
            int col = a << 24;
            g.fill(i, 0, i + 1, height, col);
            g.fill(width - i - 1, 0, width - i, height, col);
        }
    }

    /** FOV multiplier for the swing — pulls IN (narrows) as the head accelerates, for the tunnel-vision
     *  "bracing for the blow" feel; 1.0 when idle. Read by the {@code ComputeFovModifierEvent} hook. */
    public static float fovEffect() {
        if (!MINIGAME_ACTIVE) return 1.0F;
        return 1.0F - 0.14F * Mth.clamp(swingIntensity, 0F, 1F);
    }

    /** How high the first-person hammer arm is cocked (0..1) — read by the {@code RenderHandEvent} hook
     *  to raise the held hammer overhead during the swing. 0 when the minigame isn't active. */
    public static float handRaise() {
        return MINIGAME_ACTIVE ? Mth.clamp(handRaiseAmount, 0F, 1F) : 0F;
    }

    /** Current camera-shake roll offset (degrees) for the world-render hook; 0 when idle. */
    public static float cameraShake() {
        if (!MINIGAME_ACTIVE || shakeMag <= 0) return 0F;
        long el = System.currentTimeMillis() - shakeStartMs;
        if (el > 220) return 0F;
        float decay = 1.0F - el / 220F;
        return Mth.sin(el * 0.13F) * shakeMag * 1.6F * decay;
    }
}
