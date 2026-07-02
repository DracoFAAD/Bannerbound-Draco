package com.bannerbound.antiquity.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.network.FletchingActionPayload;
import com.bannerbound.antiquity.network.OpenFletchingPayload;
import com.bannerbound.core.api.quality.QualityMath;
import com.bannerbound.core.api.quality.QualityTier;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The fletching stretch minigame — a transparent in-world overlay (a plain {@link Screen}, not a
 * container menu; same family as {@code TribeVoteScreen}). Hold SPACE to drive a rising cursor up the
 * bar, release in the green zone. The green zone is randomized per stretch (anti-muscle-memory) and
 * narrows each rep. Per-stretch scores are sent to the server at completion, which rolls the quality.
 *
 * <p>Opening this screen naturally locks the player (no movement, SPACE won't jump, mouselook off);
 * the world still renders behind it ({@link #isPauseScreen()} is false) so the FOV widen and the
 * station particles read. ESC cancels (forfeiting the pile only if a stretch was already committed —
 * see {@link #onClose()}).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class FletchingScreen extends Screen {
    /** Milliseconds of holding for the cursor to traverse the whole bar. */
    private static final long FULL_RISE_MS = 1400L;
    /** Rise exponent (>1 → accelerating "stretch fights back" feel). */
    private static final float RISE_EXP = 1.6F;
    /** Random green-zone center is clamped to this band so a high zone never becomes unfair. */
    private static final float MIN_CENTER = 0.30F;
    private static final float MAX_CENTER = 0.82F;
    private static final int GREEN_SCORE = 100;
    private static final int YELLOW_SCORE = 60;
    /** How long the post-release flash/result text linger (ms). */
    private static final long FLASH_MS = 250L;
    private static final long RESULT_TEXT_MS = 900L;

    // Vanilla-tooltip palette for the frame (instantly reads as native MC UI).
    private static final int COL_BG = 0xF0100010;
    private static final int COL_BORDER_TOP = 0x505000FF;
    private static final int COL_BORDER_BOTTOM = 0x5028007F;
    // Bar palette — flat colors in the vanilla-slot idiom.
    private static final int COL_TRACK = 0xFF5C5C5C;
    private static final int COL_GREEN = 0xFF3FB911;
    private static final int COL_YELLOW = 0xFFC88A2D;
    private static final int COL_CURSOR = 0xFFFFFFFF;
    private static final int COL_CURSOR_GLOW = 0x50FFFFFF;
    // Result colors (also used for pips + flash).
    private static final int COL_RESULT_GREEN = 0xFF44D62C;
    private static final int COL_RESULT_YELLOW = 0xFFE0A020;
    private static final int COL_RESULT_MISS = 0xFFFF5555;

    /** Read by the FOV event handler: true while the minigame is open. */
    public static volatile boolean MINIGAME_ACTIVE = false;
    /** Read by the FOV event handler: the current cursor fraction (0–1) for the widen amount. */
    public static volatile float STRETCH_FRACTION = 0.0F;

    private final BlockPos pos;
    private final int stretches;
    private final float baseZonePct;
    private final float zoneDecay;
    private final float minZonePct;
    private final float yellowPadPct;

    private final RandomSource rng = RandomSource.create();
    private final List<Integer> scores = new ArrayList<>();
    private int stretchIndex = 0;

    private boolean holding = false;
    private long holdStartMs = 0L;
    private boolean committed = false;
    private boolean completed = false;

    // Current stretch's zone (bar fractions 0–1).
    private float greenStart;
    private float greenEnd;
    private float padPct;

    // Post-release feedback (bar flash + floating result text).
    private long lastReleaseMs = 0L;
    private int lastReleaseColor = 0;
    private Component lastResultText = null;

    public FletchingScreen(OpenFletchingPayload payload) {
        super(Component.translatable("bannerbound.fletching.title"));
        this.pos = payload.pos();
        this.stretches = Math.max(1, payload.stretches());
        this.baseZonePct = payload.baseZonePct();
        this.zoneDecay = payload.zoneDecay();
        this.minZonePct = payload.minZonePct();
        this.yellowPadPct = payload.yellowPadPct();
    }

    @Override
    protected void init() {
        MINIGAME_ACTIVE = true;
        newStretch();
    }

    /** Rolls the next stretch's randomized green zone (narrower each rep). */
    private void newStretch() {
        float width = Math.max(minZonePct, (float) (baseZonePct * Math.pow(zoneDecay, stretchIndex)));
        padPct = yellowPadPct;
        float halfSpan = width / 2.0F + padPct;
        float lo = Math.max(MIN_CENTER, halfSpan + 0.02F);
        float hi = Math.min(MAX_CENTER, 1.0F - halfSpan - 0.02F);
        float center = lo >= hi ? (lo + hi) / 2.0F : Mth.lerp(rng.nextFloat(), lo, hi);
        greenStart = center - width / 2.0F;
        greenEnd = center + width / 2.0F;
    }

    /** Cursor fraction (0–1) for the current hold; 0 when not holding. */
    private float cursorFraction() {
        if (!holding) return 0.0F;
        float progress = Mth.clamp((System.currentTimeMillis() - holdStartMs) / (float) FULL_RISE_MS,
            0.0F, 1.0F);
        return (float) Math.pow(progress, RISE_EXP);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE && !holding && stretchIndex < stretches && !completed) {
            holding = true;
            holdStartMs = System.currentTimeMillis();
            playUi(BannerboundAntiquity.FLETCHING_STRETCH_SOUND.get(), 1.0F, 1.0F);
            return true;
        }
        // Everything else (incl. ESC → onClose) falls through to vanilla handling.
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE && holding) {
            releaseStretch(cursorFraction());
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /** Scores the just-released stretch, plays feedback, and advances (or completes). */
    private void releaseStretch(float cursor) {
        holding = false;
        int score;
        SoundEvent sound;
        float pitch = 1.0F;
        if (cursor >= greenStart && cursor <= greenEnd) {
            score = GREEN_SCORE;
            sound = SoundEvents.NOTE_BLOCK_CHIME.value();
            lastReleaseColor = COL_RESULT_GREEN;
            lastResultText = Component.translatable("bannerbound.fletching.perfect");
            spawnStationParticles(ParticleTypes.HAPPY_VILLAGER, 8);
        } else if (cursor >= greenStart - padPct && cursor <= greenEnd + padPct) {
            score = YELLOW_SCORE;
            sound = SoundEvents.NOTE_BLOCK_COW_BELL.value();
            lastReleaseColor = COL_RESULT_YELLOW;
            lastResultText = Component.translatable("bannerbound.fletching.good");
            spawnStationParticles(ParticleTypes.CRIT, 5);
        } else {
            score = 0;
            sound = SoundEvents.NOTE_BLOCK_BASS.value();
            pitch = 0.7F;
            lastReleaseColor = COL_RESULT_MISS;
            lastResultText = Component.translatable("bannerbound.fletching.miss");
            spawnStationParticles(ParticleTypes.SMOKE, 6);
        }
        lastReleaseMs = System.currentTimeMillis();
        playUi(sound, pitch, 1.0F);

        // First release is the commitment point — tell the server to consume the pile.
        if (!committed) {
            sendAction(FletchingActionPayload.COMMIT, List.of());
            committed = true;
        }
        scores.add(score);
        stretchIndex++;
        if (stretchIndex >= stretches) {
            finishComplete();
        } else {
            newStretch();
        }
    }

    private void finishComplete() {
        completed = true;
        sendAction(FletchingActionPayload.COMPLETE, new ArrayList<>(scores));
        if (minecraft != null) minecraft.setScreen(null);
    }

    private void sendAction(int action, List<Integer> payloadScores) {
        PacketDistributor.sendToServer(new FletchingActionPayload(pos, action, payloadScores));
    }

    private void playUi(SoundEvent sound, float pitch, float volume) {
        if (minecraft != null && minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
        }
    }

    /** Client-side particle puff on/above the station's tabletop (only the crafter sees these;
     *  the completion burst everyone sees is server-side in {@code Fletching.complete}). */
    private void spawnStationParticles(ParticleOptions type, int count) {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.level == null) return;
        for (int i = 0; i < count; i++) {
            mc.level.addParticle(type,
                pos.getX() + 0.5 + (rng.nextDouble() - 0.5) * 0.6,
                pos.getY() + 0.9 + rng.nextDouble() * 0.35,
                pos.getZ() + 0.5 + (rng.nextDouble() - 0.5) * 0.6,
                0.0, 0.02, 0.0);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // No renderBackground() → the world stays visible behind the overlay.
        long now = System.currentTimeMillis();
        if (holding && now - holdStartMs >= FULL_RISE_MS) {
            releaseStretch(1.0F); // overstretched: held past the top → miss
        }
        float cursor = cursorFraction();
        STRETCH_FRACTION = cursor;

        // Faint string-tension particles at the station while pulling.
        if (holding && rng.nextFloat() < 0.12F) {
            spawnStationParticles(ParticleTypes.CRIT, 1);
        }

        int cx = this.width / 2;
        int barW = 360;
        int barH = 22;
        int boxLeft = cx - barW / 2 - 14;
        int boxRight = cx + barW / 2 + 14;
        int boxTop = this.height - 110;
        int boxBottom = boxTop + 72;

        // Title — gently pulses while waiting, solid while pulling.
        float pulse = holding ? 1.0F : 0.70F + 0.30F * (float) Math.sin(now / 280.0);
        int titleColor = ((int) (pulse * 255.0F) << 24) | 0xCCCCCC;
        Component title = Component.translatable("bannerbound.fletching.hold_space")
            .withStyle(ChatFormatting.BOLD);
        g.pose().pushPose();
        g.pose().scale(2.0F, 2.0F, 1.0F);
        g.drawCenteredString(this.font, title, cx / 2, (boxTop - 32) / 2, titleColor);
        g.pose().popPose();

        // Floating result text ("Perfect!" / "Good" / "Miss") — rises and fades after each release.
        if (lastResultText != null && now - lastReleaseMs < RESULT_TEXT_MS) {
            float t = (now - lastReleaseMs) / (float) RESULT_TEXT_MS;
            int alpha = (int) ((1.0F - t) * 255.0F);
            if (alpha > 8) {
                int y = boxTop - 14 - (int) (t * 8.0F);
                g.drawCenteredString(this.font, lastResultText, cx, y,
                    (alpha << 24) | (lastReleaseColor & 0xFFFFFF));
            }
        }

        // Frame — vanilla-tooltip style: dark plum fill + purple gradient border.
        g.fill(boxLeft, boxTop, boxRight, boxBottom, COL_BG);
        g.fillGradient(boxLeft, boxTop, boxLeft + 1, boxBottom, COL_BORDER_TOP, COL_BORDER_BOTTOM);
        g.fillGradient(boxRight - 1, boxTop, boxRight, boxBottom, COL_BORDER_TOP, COL_BORDER_BOTTOM);
        g.fill(boxLeft, boxTop, boxRight, boxTop + 1, COL_BORDER_TOP);
        g.fill(boxLeft, boxBottom - 1, boxRight, boxBottom, COL_BORDER_BOTTOM);

        // Header row: "n/total" (left) + running quality estimate (centered, tier-colored).
        int n = Math.min(stretchIndex + 1, stretches);
        g.drawString(this.font, n + "/" + stretches, boxLeft + 9, boxTop + 8, 0xFFFFFFFF, false);
        QualityTier tier = QualityTier.fromScore(QualityMath.aggregate(toArray(scores)));
        Component quality = Component.translatable("bannerbound.fletching.quality")
            .append(Component.literal(" ")).append(tier.displayName());
        g.drawCenteredString(this.font, quality, cx, boxTop + 8, 0xFFFFFFFF);

        // Bar: classic inventory-slot inset — dark top/left bevel, light bottom/right bevel, flat
        // gray track and flat zone colors. Vanilla UI language; no gradients, ticks, or end caps.
        int barLeft = cx - barW / 2;
        int barTop = boxTop + 24;
        int barBottom = barTop + barH;
        g.fill(barLeft - 1, barTop - 1, barLeft + barW + 1, barTop, 0xFF373737);        // top bevel
        g.fill(barLeft - 1, barTop - 1, barLeft, barBottom + 1, 0xFF373737);            // left bevel
        g.fill(barLeft, barBottom, barLeft + barW + 1, barBottom + 1, 0xFFFFFFFF);      // bottom bevel
        g.fill(barLeft + barW, barTop, barLeft + barW + 1, barBottom + 1, 0xFFFFFFFF);  // right bevel
        g.fill(barLeft, barTop, barLeft + barW, barBottom, COL_TRACK);
        // Zones: flat colors, amber pads under the green.
        int yLo = barLeft + (int) ((greenStart - padPct) * barW);
        int yHi = barLeft + (int) ((greenEnd + padPct) * barW);
        int gLo = barLeft + (int) (greenStart * barW);
        int gHi = barLeft + (int) (greenEnd * barW);
        g.fill(Math.max(barLeft, yLo), barTop, Math.min(barLeft + barW, yHi), barBottom, COL_YELLOW);
        g.fill(gLo, barTop, gHi, barBottom, COL_GREEN);
        // Aim line at the green zone's center — the point players are actually trying to hit.
        int aimX = (gLo + gHi) / 2;
        g.fill(aimX, barTop + 1, aimX + 1, barBottom - 1, 0x90FFFFFF);
        // Post-release flash washes over the bar for a beat.
        if (now - lastReleaseMs < FLASH_MS && lastReleaseColor != 0) {
            float t = (now - lastReleaseMs) / (float) FLASH_MS;
            int alpha = (int) ((1.0F - t) * 120.0F);
            g.fill(barLeft, barTop, barLeft + barW, barBottom,
                (alpha << 24) | (lastReleaseColor & 0xFFFFFF));
        }
        // Cursor: comet trail while pulling, soft glow, crisp 2px line.
        int cxPix = barLeft + (int) (cursor * barW);
        if (holding) {
            for (int i = 1; i <= 6; i++) {
                int tail = cxPix - i * 3;
                if (tail < barLeft) break;
                int alpha = 0x48 * (7 - i) / 7;
                g.fill(Math.max(barLeft, tail - 3), barTop + 1, tail, barBottom - 1,
                    (alpha << 24) | 0xFFFFFF);
            }
        }
        g.fill(cxPix - 3, barTop, cxPix + 3, barBottom, COL_CURSOR_GLOW);
        g.fill(cxPix - 1, barTop - 2, cxPix + 1, barBottom + 2, COL_CURSOR);

        // Stretch pips: one per rep — filled with that rep's result color once played, white outline
        // for the current rep, dim outline for upcoming ones.
        int pipSize = 6;
        int pipPitch = 12;
        int pipsLeft = cx - (stretches * pipPitch - (pipPitch - pipSize)) / 2;
        int pipTop = barBottom + 8;
        for (int i = 0; i < stretches; i++) {
            int x = pipsLeft + i * pipPitch;
            if (i < scores.size()) {
                int s = scores.get(i);
                int color = s >= GREEN_SCORE ? COL_RESULT_GREEN
                    : s >= YELLOW_SCORE ? COL_RESULT_YELLOW : COL_RESULT_MISS;
                g.fill(x, pipTop, x + pipSize, pipTop + pipSize, color);
            } else if (i == stretchIndex) {
                g.renderOutline(x, pipTop, pipSize, pipSize, 0xFFFFFFFF);
            } else {
                g.renderOutline(x, pipTop, pipSize, pipSize, 0xFF555555);
            }
        }
    }

    private static int[] toArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }

    @Override
    public void onClose() {
        // ESC (or any external close) before completion = cancel. Forfeit semantics are server-side:
        // if a stretch already committed, the pile is gone; otherwise it's untouched.
        if (!completed) {
            sendAction(FletchingActionPayload.CANCEL, List.of());
        }
        super.onClose();
    }

    @Override
    public void removed() {
        MINIGAME_ACTIVE = false;
        STRETCH_FRACTION = 0.0F;
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
