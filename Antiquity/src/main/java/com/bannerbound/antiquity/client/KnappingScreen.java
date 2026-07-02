package com.bannerbound.antiquity.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

import com.bannerbound.antiquity.network.KnappingActionPayload;
import com.bannerbound.antiquity.network.OpenKnappingPayload;
import com.bannerbound.core.api.quality.QualityTier;
import com.bannerbound.core.client.PolishButton;
import com.bannerbound.core.client.PolishedScreen;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The two-phase knapping screen (a {@link PolishedScreen}: crisp world behind it — no menu blur —
 * plus the open-settle polish). <b>Phase A (shaping):</b> a 3×3 grid of stone cells; left-click chips
 * a cell away (debris flash + stone-break sound). When the remaining cells match a known head
 * silhouette its preview + name show and <i>Knap</i> enables; chipping every cell away "breaks the
 * stone" (the rock is forfeit). <b>Phase B (knapping):</b> a rhythm minigame — one circle slides in
 * toward a fixed cursor per cell you chipped away; tap SPACE as each meets the cursor (perfect / good
 * / miss). The aggregated taps roll the head's {@link QualityTier}. The server owns the session, the
 * rock, and the roll.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class KnappingScreen extends PolishedScreen {
    private static final ResourceLocation STONE_TEX =
        ResourceLocation.withDefaultNamespace("textures/block/stone.png");
    private static final int CELL = 38;

    // Rhythm minigame (Phase B) tuning.
    private static final long COUNTDOWN_MS = 1800L; // 3-2-1 (600ms each) before circles begin
    private static final long INTERVAL_MS = 620L;   // spacing between circle arrivals
    private static final long TRAVEL_MS = 920L;     // how long a circle is in flight before its beat
    private static final long PERFECT_MS = 33L;     // |timing error| for a perfect (~2 frames — hard to nail)
    private static final long GOOD_MS = 110L;       // |timing error| for a good (miss beyond this)
    private static final long FEEDBACK_MS = 600L;
    private static final int GREEN_SCORE = 100;
    private static final int YELLOW_SCORE = 60;

    private static final int COL_GREEN = 0xFF44D62C;
    private static final int COL_YELLOW = 0xFFE0A020;
    private static final int COL_MISS = 0xFFFF5555;
    private static final int COL_PANEL = 0x88101010;

    private enum Phase { SHAPING, KNAPPING }

    private final List<OpenKnappingPayload.ShapeView> shapes;
    private final RandomSource rng = RandomSource.create();

    private Phase phase = Phase.SHAPING;
    private boolean committed = false; // sent COMMIT on the first chip
    private boolean finished = false;  // sent a terminal action (COMPLETE/BROKE/CANCEL)

    // Phase A — the grid.
    private final boolean[] stone = new boolean[9]; // true = cell still present
    private int gridLeft;
    private int gridTop;
    private OpenKnappingPayload.ShapeView matched; // the shape the remaining cells form, or null
    private Button knapButton;
    private long lastChipMs = 0L;
    private int lastChipCell = -1;

    // Phase B — the rhythm reps (one circle per chipped-away cell).
    private int reps = 0;
    private long phaseStartMs = 0L;
    private boolean[] resolved = new boolean[0];
    private int[] noteScore = new int[0];
    private int resolvedCount = 0;
    private int cursorX;
    private int trackY;
    private int travelDist;
    private long lastFeedbackMs = 0L;
    private Component lastFeedback = null;
    private int lastFeedbackColor = 0;
    private int lastCountShown = -1;
    private long hitRingMs = 0L;
    private int hitRingColor = 0;
    private final List<Spark> sparks = new ArrayList<>();

    /** A short-lived UI debris fleck flung from the cursor on a hit. */
    private static final class Spark {
        final float x0;
        final float y0;
        final float vx;
        final float vy;
        final long born;
        final int color;

        Spark(float x0, float y0, float vx, float vy, long born, int color) {
            this.x0 = x0;
            this.y0 = y0;
            this.vx = vx;
            this.vy = vy;
            this.born = born;
            this.color = color;
        }
    }

    public KnappingScreen(OpenKnappingPayload payload) {
        super(Component.translatable("bannerbound.knapping.title"));
        this.shapes = payload.shapes();
        for (int i = 0; i < 9; i++) stone[i] = true;
    }

    @Override
    protected boolean drawsDimmedBackground() {
        return false; // crisp world behind — this is an open-air hand-craft, not a menu
    }

    @Override
    protected void init() {
        gridLeft = this.width / 2 - (3 * CELL) / 2;
        gridTop = this.height / 2 - (3 * CELL) / 2 - 6;
        knapButton = PolishButton.polished(Component.translatable("bannerbound.knapping.knap"),
                b -> startKnapping())
            .bounds(this.width / 2 - 40, gridTop + 3 * CELL + 14, 80, 20)
            .build();
        knapButton.active = false;
        addRenderableWidget(knapButton);
        cursorX = this.width / 2 - 90;
        trackY = this.height / 2;
        travelDist = this.width / 2 + 60;
        recomputeMatch();
    }

    // ── Phase A: shaping ────────────────────────────────────────────────────────────────────

    private int currentMask() {
        int mask = 0;
        for (int i = 0; i < 9; i++) {
            if (stone[i]) mask |= (1 << i);
        }
        return mask;
    }

    private int removedCount() {
        int n = 0;
        for (int i = 0; i < 9; i++) {
            if (!stone[i]) n++;
        }
        return n;
    }

    private void recomputeMatch() {
        int mask = currentMask();
        matched = null;
        for (OpenKnappingPayload.ShapeView s : shapes) {
            if (s.keepMask() == mask) {
                matched = s;
                break;
            }
        }
        if (knapButton != null) knapButton.active = matched != null;
    }

    private int cellAt(double mx, double my) {
        for (int i = 0; i < 9; i++) {
            int cx = gridLeft + (i % 3) * CELL;
            int cy = gridTop + (i / 3) * CELL;
            if (mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL) return i;
        }
        return -1;
    }

    private void chip(int idx) {
        stone[idx] = false;
        lastChipCell = idx;
        lastChipMs = Util.getMillis();
        playUi(SoundEvents.STONE_BREAK, 0.9F + rng.nextFloat() * 0.2F, 0.8F);
        if (!committed) {
            sendAction(KnappingActionPayload.COMMIT, KnappingActionPayload.NONE, List.of());
            committed = true;
        }
        if (currentMask() == 0) {
            finished = true;
            sendAction(KnappingActionPayload.BROKE, KnappingActionPayload.NONE, List.of());
            if (minecraft != null) {
                minecraft.gui.setOverlayMessage(
                    Component.translatable("bannerbound.knapping.broke").withStyle(ChatFormatting.GRAY), false);
                minecraft.setScreen(null);
            }
            return;
        }
        recomputeMatch();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (phase == Phase.SHAPING && button == 0) {
            int idx = cellAt(mx, my);
            if (idx >= 0 && stone[idx]) {
                chip(idx);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button); // lets the Knap button receive its click
    }

    private void startKnapping() {
        if (matched == null) return;
        phase = Phase.KNAPPING;
        reps = Math.max(1, removedCount()); // one circle per stone chipped away
        resolved = new boolean[reps];
        noteScore = new int[reps];
        resolvedCount = 0;
        phaseStartMs = Util.getMillis();
        knapButton.visible = false;
        knapButton.active = false;
    }

    // ── Phase B: the rhythm minigame ────────────────────────────────────────────────────────

    /** When the 3-2-1 ends and circles begin arriving. */
    private long rhythmStart() {
        return phaseStartMs + COUNTDOWN_MS;
    }

    /** When circle {@code i} should sit exactly on the cursor (first one spawns as the count ends). */
    private long beatTime(int i) {
        return rhythmStart() + TRAVEL_MS + (long) i * INTERVAL_MS;
    }

    /** The first unresolved circle (the one the player is aiming at), or -1 when all are done. */
    private int activeNote() {
        for (int i = 0; i < reps; i++) {
            if (!resolved[i]) return i;
        }
        return -1;
    }

    private void registerTap(long now) {
        int i = activeNote();
        if (i < 0) return;
        long d = now - beatTime(i);
        if (d < -GOOD_MS) {
            playUi(SoundEvents.STONE_HIT, 0.6F, 0.5F); // way too early — stray tap, no score
            return;
        }
        long err = Math.abs(d);
        int score = err <= PERFECT_MS ? GREEN_SCORE : err <= GOOD_MS ? YELLOW_SCORE : 0;
        resolve(i, score, now);
    }

    private void resolve(int i, int score, long now) {
        resolved[i] = true;
        noteScore[i] = score;
        resolvedCount++;
        lastFeedbackMs = now;
        if (score >= GREEN_SCORE) {
            lastFeedback = Component.translatable("bannerbound.fletching.perfect");
            lastFeedbackColor = COL_GREEN;
            hitRingMs = now;
            hitRingColor = COL_GREEN;
            spawnSparks(COL_GREEN, 12, 130F, now);
            // pitch climbs with the perfect streak for a satisfying run
            playUi(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.0F + Math.min(0.5F, perfectStreak() * 0.06F), 0.9F);
        } else if (score >= YELLOW_SCORE) {
            lastFeedback = Component.translatable("bannerbound.fletching.good");
            lastFeedbackColor = COL_YELLOW;
            hitRingMs = now;
            hitRingColor = COL_YELLOW;
            spawnSparks(COL_YELLOW, 7, 95F, now);
            playUi(SoundEvents.NOTE_BLOCK_COW_BELL.value(), 1.0F, 0.9F);
        } else {
            lastFeedback = Component.translatable("bannerbound.fletching.miss");
            lastFeedbackColor = COL_MISS;
            spawnSparks(0xFF888888, 5, 60F, now);
            playUi(SoundEvents.STONE_BREAK, 0.7F, 0.7F);
        }
        if (resolvedCount >= reps) finishComplete();
    }

    private void finishComplete() {
        finished = true;
        ResourceLocation head = matched != null ? matched.head() : KnappingActionPayload.NONE;
        List<Integer> scores = new ArrayList<>(reps);
        for (int s : noteScore) scores.add(s);
        sendAction(KnappingActionPayload.COMPLETE, head, scores);
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (phase == Phase.KNAPPING && keyCode == GLFW.GLFW_KEY_SPACE && !finished) {
            long now = Util.getMillis();
            if (now >= rhythmStart()) registerTap(now); // no scoring during the 3-2-1
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Flings a handful of debris flecks from the cursor on a hit (color reads the result tier). */
    private void spawnSparks(int color, int count, float speed, long now) {
        for (int k = 0; k < count; k++) {
            float ang = (float) (rng.nextFloat() * Math.PI * 2.0);
            float sp = speed * (0.4F + rng.nextFloat());
            float vx = (float) Math.cos(ang) * sp;
            float vy = (float) Math.sin(ang) * sp - speed * 0.4F; // slight upward bias
            sparks.add(new Spark(cursorX, trackY, vx, vy, now, color));
        }
    }

    // ── Networking + sound ──────────────────────────────────────────────────────────────────

    private void sendAction(int action, ResourceLocation head, List<Integer> payloadScores) {
        PacketDistributor.sendToServer(new KnappingActionPayload(action, head, payloadScores));
    }

    private void playUi(SoundEvent sound, float pitch, float volume) {
        if (minecraft != null && minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
        }
    }

    // ── Rendering (rides the PolishedScreen settle pose; widgets drawn after this) ────────────

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (phase == Phase.SHAPING) {
            renderShaping(g);
        } else {
            renderKnapping(g);
        }
    }

    private void renderShaping(GuiGraphics g) {
        int cx = this.width / 2;
        Component hint = Component.translatable("bannerbound.knapping.shape_hint").withStyle(ChatFormatting.GRAY);
        // Backing strip wide enough for BOTH the grid and the title (whichever is wider).
        int halfW = Math.max(3 * CELL / 2 + 14, this.font.width(hint) / 2 + 16);
        g.fill(cx - halfW, gridTop - 28, cx + halfW, gridTop + 3 * CELL + 66, COL_PANEL);
        g.drawCenteredString(this.font, hint, cx, gridTop - 20, 0xFFCCCCCC);

        long now = Util.getMillis();
        for (int i = 0; i < 9; i++) {
            int x = gridLeft + (i % 3) * CELL;
            int y = gridTop + (i / 3) * CELL;
            if (stone[i]) {
                g.blit(STONE_TEX, x + 1, y + 1, CELL - 2, CELL - 2, 0.0F, 0.0F, 16, 16, 16, 16);
                g.renderOutline(x, y, CELL, CELL, 0xFF20211F);
            } else {
                g.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0xC0101010);
                if (i == lastChipCell && now - lastChipMs < 160L) {
                    int a = (int) ((1.0F - (now - lastChipMs) / 160.0F) * 200.0F);
                    g.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, (a << 24) | 0xDDDDDD);
                }
            }
        }

        if (matched != null) {
            Item head = BuiltInRegistries.ITEM.get(matched.head());
            int iconY = gridTop + 3 * CELL + 38;
            g.renderItem(new ItemStack(head), cx - 8, iconY);
            g.drawCenteredString(this.font, new ItemStack(head).getHoverName(), cx, iconY + 20, 0xFFFFFFFF);
        }
    }

    private void renderKnapping(GuiGraphics g) {
        long now = Util.getMillis();
        boolean inCountdown = now < rhythmStart();
        if (!inCountdown) {
            int active = activeNote();
            if (active >= 0 && now - beatTime(active) > GOOD_MS) {
                resolve(active, 0, now); // sailed past the cursor unhit
            }
        }
        int cx = this.width / 2;

        // Backing strip + header.
        g.fill(cx - travelDist / 2 - 30, trackY - 60, cx + travelDist / 2 + 30, trackY + 44, COL_PANEL);
        g.drawCenteredString(this.font,
            Component.translatable("bannerbound.knapping.strike_hint").withStyle(ChatFormatting.BOLD),
            cx, trackY - 50, 0xFFDDDDDD);
        g.drawString(this.font, Math.min(resolvedCount + 1, reps) + "/" + reps,
            cursorX - 24, trackY - 34, 0xFFFFFFFF, false);
        QualityTier tier = com.bannerbound.antiquity.Knapping.rollTier(scoresSoFar());
        g.drawCenteredString(this.font, tier.displayName(), cx, trackY - 34, 0xFFFFFFFF);

        // Track baseline.
        g.fill(cursorX - 8, trackY - 1, cursorX + travelDist, trackY + 1, 0x66FFFFFF);

        // How close the active circle is to its beat — drives the cursor's anticipation pulse.
        int active = activeNote();
        float windowT = 0F;
        if (!inCountdown && active >= 0) {
            long toBeat = Math.abs(beatTime(active) - now);
            if (toBeat <= GOOD_MS) windowT = 1F - toBeat / (float) GOOD_MS;
        }

        // Incoming circles: discs with a soft glow, sliding toward the cursor.
        if (!inCountdown) {
            for (int i = 0; i < reps; i++) {
                if (resolved[i]) continue;
                long toBeat = beatTime(i) - now;
                if (toBeat > TRAVEL_MS || toBeat < -GOOD_MS - 120) continue;
                int x = cursorX + (int) ((toBeat / (float) TRAVEL_MS) * travelDist);
                boolean inWindow = Math.abs(toBeat) <= GOOD_MS;
                fillDisc(g, x, trackY, inWindow ? 14 : 11, 0x33FFFFFF);              // glow
                fillDisc(g, x, trackY, inWindow ? 11 : 9, inWindow ? 0xFFFFFFFF : 0xFFBFD8FF);
                fillDisc(g, x, trackY, inWindow ? 6 : 5, 0xFF2A2A2A);                // hole
            }
        }

        // Expanding hit ring (perfect/good).
        if (now - hitRingMs < 300L && hitRingColor != 0) {
            float t = (now - hitRingMs) / 300F;
            int a = (int) ((1F - t) * 200F);
            drawRing(g, cursorX, trackY, 15 + (int) (t * 22F), 2, (a << 24) | (hitRingColor & 0xFFFFFF));
        }

        // The cursor target ring — pulses bigger/brighter as a circle nears its beat.
        int ringR = 15 + (int) (windowT * 4F);
        int ringCol = windowT > 0F ? blend(0xFFFFE070, 0xFFFFFFFF, windowT) : 0xFFFFE070;
        drawRing(g, cursorX, trackY, ringR, 3, ringCol);
        drawRing(g, cursorX, trackY, ringR, 1, 0x80000000);

        renderSparks(g, now);

        // The 3-2-1 countdown (replaces the circles until the rhythm starts).
        if (inCountdown) {
            int n = (int) Math.min(3, 3 - (now - phaseStartMs) / 600L);
            n = Math.max(1, n);
            if (n != lastCountShown) {
                lastCountShown = n;
                playUi(SoundEvents.NOTE_BLOCK_HAT.value(), 0.8F + (3 - n) * 0.25F, 0.7F);
            }
            float into = ((now - phaseStartMs) % 600L) / 600F; // 0→1 within this tick
            float scale = 3.2F - 0.7F * easeOutCubic(into);     // pops big then settles
            g.pose().pushPose();
            g.pose().translate(cx, trackY - 4, 0);
            g.pose().scale(scale, scale, 1F);
            int a = (int) (Math.min(1F, (1F - into) * 1.6F) * 255F);
            g.drawCenteredString(this.font, String.valueOf(n), 0, -4,
                (Math.max(40, a) << 24) | 0xFFE070);
            g.pose().popPose();
        }

        // Result pips.
        int pipSize = 6;
        int pipPitch = 12;
        int pipsLeft = cx - (reps * pipPitch - (pipPitch - pipSize)) / 2;
        int pipTop = trackY + 28;
        for (int i = 0; i < reps; i++) {
            int x = pipsLeft + i * pipPitch;
            if (resolved[i]) {
                int s = noteScore[i];
                int color = s >= GREEN_SCORE ? COL_GREEN : s >= YELLOW_SCORE ? COL_YELLOW : COL_MISS;
                g.fill(x, pipTop, x + pipSize, pipTop + pipSize, color);
            } else {
                g.renderOutline(x, pipTop, pipSize, pipSize, i == active ? 0xFFFFFFFF : 0xFF555555);
            }
        }

        // Floating Perfect/Good/Miss feedback near the cursor.
        if (lastFeedback != null && now - lastFeedbackMs < FEEDBACK_MS) {
            float t = (now - lastFeedbackMs) / (float) FEEDBACK_MS;
            int alpha = (int) ((1.0F - t) * 255.0F);
            if (alpha > 8) {
                g.drawCenteredString(this.font, lastFeedback, cursorX,
                    trackY - 24 - (int) (t * 8.0F), (alpha << 24) | (lastFeedbackColor & 0xFFFFFF));
            }
        }
    }

    /** Advances + draws the UI sparks (gravity arc, ~0.5s life), culling dead ones. */
    private void renderSparks(GuiGraphics g, long now) {
        sparks.removeIf(s -> now - s.born >= 500L);
        for (Spark s : sparks) {
            float t = (now - s.born) / 1000F; // seconds
            int x = Math.round(s.x0 + s.vx * t);
            int y = Math.round(s.y0 + s.vy * t + 150F * t * t);
            int alpha = (int) ((1F - t / 0.5F) * 255F);
            if (alpha <= 4) continue;
            g.fill(x - 1, y - 1, x + 1, y + 1, (alpha << 24) | (s.color & 0xFFFFFF));
        }
    }

    /** Per-channel lerp between two opaque ARGB colors. */
    private static int blend(int from, int to, float t) {
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int gg = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    /** Consecutive perfects ending at the most recently resolved circle (notes resolve in order). */
    private int perfectStreak() {
        int streak = 0;
        for (int i = resolvedCount - 1; i >= 0; i--) {
            if (noteScore[i] >= GREEN_SCORE) streak++;
            else break;
        }
        return streak;
    }

    private int[] scoresSoFar() {
        int[] arr = new int[resolvedCount];
        int j = 0;
        for (int i = 0; i < reps; i++) {
            if (resolved[i]) arr[j++] = noteScore[i];
        }
        return arr;
    }

    /** Filled disc (a circle that shows the world only at its edge). */
    private static void fillDisc(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.sqrt((double) r * r - dy * dy);
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    /** Hollow ring of the given thickness (annulus — the center stays the live world). */
    private static void drawRing(GuiGraphics g, int cx, int cy, int r, int thick, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int outer = (int) Math.sqrt((double) r * r - dy * dy);
            double innerSq = (double) (r - thick) * (r - thick) - (double) dy * dy;
            if (innerSq <= 0) {
                g.fill(cx - outer, cy + dy, cx + outer + 1, cy + dy + 1, color);
            } else {
                int inner = (int) Math.sqrt(innerSq);
                g.fill(cx - outer, cy + dy, cx - inner, cy + dy + 1, color);
                g.fill(cx + inner + 1, cy + dy, cx + outer + 1, cy + dy + 1, color);
            }
        }
    }

    @Override
    public void onClose() {
        if (!finished) {
            finished = true;
            sendAction(KnappingActionPayload.CANCEL, KnappingActionPayload.NONE, List.of());
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
