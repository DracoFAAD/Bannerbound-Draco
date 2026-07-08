package com.bannerbound.core.client;

import com.bannerbound.core.api.research.data.ToolAgeLoader;
import com.bannerbound.core.api.research.ToolAge;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.network.EnqueueResearchPayload;
import com.bannerbound.core.network.MenuOpenedPayload;
import com.bannerbound.core.network.StartResearchPayload;
import com.bannerbound.core.api.research.ResearchDefinition;
import com.bannerbound.core.api.research.ResearchPonderBridge;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-only Screen for the research board: a pan/zoom graph of research nodes wired by
 * prerequisite edges, opened from the Town Hall (parent != null returns there on Esc).
 *
 * Three tabs -- SCIENCE, CULTURE, FAITH -- share all board geometry, hover logic and era
 * dividers; only the data source and colour palette swap. The current*(...) accessors centralise
 * that per-tab branch so render/click code never ternaries inline. The Culture tab appears only
 * once a government exists (post Code-of-Laws) and Faith only while the settlement follows a
 * faith; losing either gate snaps activeTab back to Science.
 *
 * Board transform: nodes, prereq lines and badges are drawn inside a pose translated to the board
 * centre pivot + pan and scaled by zoom, so they zoom together; pan stays in SCREEN pixels so a
 * drag tracks the cursor 1:1, and hover hit-tests inverse-transform via screenToBoard*(). The
 * camera eases toward pan/zoom TARGETS each frame (tickPanZoomEase); dragging writes both current
 * AND target so the board never swims behind the hand. Every animation gates on Config.UI_ANIMATIONS
 * and snaps when it is off. requestFocus()/pendingFocusId is a one-shot camera focus consumed by
 * init() then cleared, so a window resize does not re-snap.
 *
 * Render-order gotchas: hover uses LAST frame's pick (a one-frame lag that is invisible), and
 * hit-testing stays on the UNGROWN node rect so the hover grow-pop never changes what is clickable;
 * the ease maps drop entries below 0.01 so they only ever hold the node(s) lit or fading. Prereq
 * lines draw in two Z passes (dim first, lit on top) so a grey line never overdraws a bright path
 * edge. The tooltip is drawn in render() AFTER the manual renderables loop (never registered as a
 * renderable) and pushed to Z 400 so it cannot bleed behind node text or badges; the unlocked-items
 * grid bypasses the unknown-item swap so players can preview locked unlocks.
 *
 * Interaction: a Chiefdom non-chief cannot start/enqueue -- their click becomes a suggest/retract
 * to the chief (sendSuggestOrRetract); Council and NONE pass straight through. The per-tab ambience
 * backdrops are era-aware screen-space cosmetics only (no real data). prereqClosure() is bounded by
 * a visited set so a malformed cyclic graph cannot loop forever.
 *
 * init() re-runs on every resize, so announcedOpen gates the one-shot MenuOpenedPayload (fires
 * the "menu_opened"/"research_tree" tutorial trigger) to send exactly once per screen instance.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class ResearchScreen extends Screen {
    private static final int NODE_WIDTH = 110;
    private static final int NODE_HEIGHT = 36;

    private static final int ELBOW_GAP = 14;
    private static final int INSIGHT_LABEL_GAP = 3;
    private static final int INSIGHT_LABEL_HEIGHT = 14;
    private static final int PANEL_MARGIN = 20;

    // Sized so the tab strip sits ABOVE boardY(); a shorter header drops tabs into the board's drag-capture zone.
    private static final int HEADER_HEIGHT = 46;
    private static final int FOOTER_HEIGHT = 32;
    private static final int QUEUE_BADGE_OFFSET_Y = 14;

    private double panX = 0;
    private double panY = 0;

    private double panXTarget = 0;
    private double panYTarget = 0;
    private double zoomTarget = 1.0;
    private long lastEaseMs = net.minecraft.Util.getMillis();

    private final long openedAtMs = net.minecraft.Util.getMillis();

    private final java.util.Map<String, Float> nodeHoverEase = new java.util.HashMap<>();

    private final java.util.Map<String, Float> progressEase = new java.util.HashMap<>();

    private final java.util.Map<String, Float> edgeHighlightEase = new java.util.HashMap<>();
    private final java.util.Map<String, Float> nodeHighlightEase = new java.util.HashMap<>();
    private float highlightDimEase = 0f;

    private double lastFrameDt = 0;

    private long tooltipShownAtMs = 0L;

    private String lastHoveredId = null;

    private double zoom = 1.0;
    private static final double MIN_ZOOM = 0.45;
    private static final double MAX_ZOOM = 2.0;

    private static final double ZOOM_FACTOR = 1.12;
    private boolean dragging = false;
    private boolean announcedOpen = false;
    private ResearchDefinition hovered;

    private ResearchDefinition hoveredInsight;

    private final TransientClickFeedback feedback = new TransientClickFeedback();

    public enum Tab { SCIENCE, CULTURE, FAITH }
    private Tab activeTab = Tab.SCIENCE;

    private static String pendingFocusId = null;
    private static boolean pendingFocusCulture = false;

    public static void requestFocus(String researchId, boolean culture) {
        pendingFocusId = researchId;
        pendingFocusCulture = culture;
    }

    @org.jetbrains.annotations.Nullable
    private final Screen parent;

    public ResearchScreen() {
        this(null);
    }

    public ResearchScreen(@org.jetbrains.annotations.Nullable Screen parent) {
        super(Component.translatable("bannerbound.research.title"));
        this.parent = parent;
    }

    @Override
    public void onClose() {

        if (this.minecraft != null && parent != null) {
            this.minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    private java.util.Map<String, ResearchDefinition> currentTree() {
        if (activeTab == Tab.FAITH) return ClientFaithTreeState.getTree();
        return activeTab == Tab.SCIENCE
            ? ClientResearchState.getTree()
            : ClientCultureState.getTree();
    }
    private boolean currentIsCompleted(String id) {
        if (activeTab == Tab.FAITH) return ClientFaithTreeState.isCompleted(id);
        return activeTab == Tab.SCIENCE
            ? ClientResearchState.isCompleted(id)
            : ClientCultureState.isCompleted(id);
    }
    private boolean currentIsActive(String id) {
        if (activeTab == Tab.FAITH) return ClientFaithTreeState.isActive(id);
        return activeTab == Tab.SCIENCE
            ? ClientResearchState.isActive(id)
            : ClientCultureState.isActive(id);
    }
    private boolean currentPrereqsMet(ResearchDefinition def) {
        if (activeTab == Tab.FAITH) return ClientFaithTreeState.prereqsMet(def);
        return activeTab == Tab.SCIENCE
            ? ClientResearchState.prereqsMet(def)
            : ClientCultureState.prereqsMet(def);
    }
    private boolean currentAgeMet(ResearchDefinition def) {

        if (activeTab == Tab.FAITH) return ClientResearchState.ageMet(def);
        return activeTab == Tab.SCIENCE
            ? ClientResearchState.ageMet(def)
            : ClientCultureState.ageMet(def);
    }
    private boolean isFutureEra(ResearchDefinition def) {
        return ClientEraState.getPlayerEra().ordinal() < def.minAge().ordinal();
    }
    private double currentProgress(String id) {
        if (activeTab == Tab.FAITH) return ClientFaithTreeState.getProgress(id);
        return activeTab == Tab.SCIENCE
            ? ClientResearchState.getProgress(id)
            : ClientCultureState.getProgress(id);
    }
    private int currentQueuePosition(String id) {
        if (activeTab == Tab.FAITH) return ClientFaithTreeState.getQueuePosition(id);
        return activeTab == Tab.SCIENCE
            ? ClientResearchState.getQueuePosition(id)
            : ClientCultureState.getQueuePosition(id);
    }
    private boolean currentInsightFired(String id) {
        if (activeTab == Tab.FAITH) return ClientFaithTreeState.hasFiredInsight(id);
        return activeTab == Tab.SCIENCE
            ? ClientResearchState.hasFiredInsight(id)
            : ClientCultureState.hasFiredInsight(id);
    }
    private double currentInsightProgress(String id) {
        if (activeTab == Tab.FAITH) return ClientFaithTreeState.getInsightProgress(id);
        return activeTab == Tab.SCIENCE
            ? ClientResearchState.getInsightProgress(id)
            : ClientCultureState.getInsightProgress(id);
    }

    private int[] nodeColors(boolean isComplete, boolean isActive, boolean prereqAndAgeMet) {
        if (activeTab == Tab.FAITH) {

            if (isComplete) return new int[]{0xFF36301F, 0xFFE8D9A0};
            if (isActive)   return new int[]{0xFF3F381F, 0xFFFFE08A};
            if (prereqAndAgeMet) return new int[]{0xFF24221C, 0xFFC9C4B4};
            return new int[]{0xFF1A1915, 0xFF45413A};
        }
        if (activeTab == Tab.CULTURE) {
            if (isComplete) return new int[]{0xFF2B1B3A, 0xFFD055E0};
            if (isActive)   return new int[]{0xFF3A2055, 0xFFE070FF};
            if (prereqAndAgeMet) return new int[]{0xFF1B0B2B, 0xFFAAAAAA};
            return new int[]{0xFF15101A, 0xFF404040};
        }

        if (isComplete)      return new int[]{0xFF12243A, 0xFF55B0FF};
        if (isActive)        return new int[]{0xFF1A2C44, 0xFF6AD0FF};
        if (prereqAndAgeMet) return new int[]{0xFF12182B, 0xFFAAAAAA};
        return new int[]{0xFF141821, 0xFF404040};
    }
    private int activeProgressBarColor() {
        if (activeTab == Tab.FAITH) return 0xFFD9A94A;
        return activeTab == Tab.CULTURE ? 0xFFD055E0 : 0xFF55B0FF;
    }
    private int queueBadgeBorderFor(boolean isComplete, boolean isActive, boolean prereqAndAgeMet) {
        if (activeTab == Tab.FAITH) {
            if (isComplete) return 0xFFE8D9A0;
            if (isActive)   return 0xFFD9A94A;
            if (prereqAndAgeMet) return 0xFFC9C4B4;
            return 0xFF45413A;
        }
        if (activeTab == Tab.CULTURE) {
            if (isComplete) return 0xFF9933CC;
            if (isActive)   return 0xFFD055E0;
            if (prereqAndAgeMet) return 0xFFAAAAAA;
            return 0xFF404040;
        }
        if (isComplete)      return 0xFF55B0FF;
        if (isActive)        return 0xFF6AD0FF;
        if (prereqAndAgeMet) return 0xFFAAAAAA;
        return 0xFF404040;
    }

    private boolean isNodeVisible(ResearchDefinition def) {

        if (def.faithPath() != null
                && def.faithPath().ordinal() != ClientFaithState.pathOrdinal()) {
            return false;
        }
        com.bannerbound.core.api.settlement.Settlement.Government gov = def.governmentType();
        if (gov == null) return true;
        return gov.ordinal() == ClientPopulationState.getGovernmentOrdinal();
    }

    private void drawFaithAmbience(GuiGraphics graphics, int bx, int by, int bw, int bh) {
        boolean animate = com.bannerbound.core.Config.UI_ANIMATIONS.get();
        long ms = net.minecraft.Util.getMillis();
        for (int i = 0; i < 90; i++) {

            int h = i * 0x9E3779B1;
            int sx = bx + Math.floorMod(h, Math.max(1, bw));
            int sy = by + Math.floorMod(h >> 11, Math.max(1, bh));
            float alpha = 0.35f;
            if (animate) {
                alpha = 0.18f + 0.30f * (float) (0.5 + 0.5 * Math.sin(ms / (520.0 + (i % 7) * 90.0) + i * 1.7));
            }
            int a = (int) (alpha * 255.0f);
            int color = (a << 24) | 0xCFC9B8;
            graphics.fill(sx, sy, sx + 1, sy + 1, color);
            if (i % 9 == 0) {
                graphics.fill(sx - 1, sy, sx, sy + 1, color);
                graphics.fill(sx + 1, sy, sx + 2, sy + 1, color);
            }
        }
        if (!animate) return;
        for (int i = 0; i < 14; i++) {
            int h = (i + 31) * 0x85EBCA6B;
            float cycle = 9_000.0f + (i % 5) * 2_300.0f;
            float t = ((ms + h) % (long) cycle) / cycle;
            int mx = bx + Math.floorMod(h, Math.max(1, bw))
                + (int) (Math.sin(ms / 1400.0 + i) * 6.0);
            int my = by + bh - (int) (t * (bh + 8)) - 4;
            float fade = (float) Math.sin(Math.PI * t);
            int a = (int) (fade * 0.55f * 255.0f);
            int gold = (a << 24) | 0xD9A94A;

            graphics.fill(mx, my - 1, mx + 1, my + 2, gold);
            graphics.fill(mx - 1, my, mx + 2, my + 1, gold);
        }
    }

    private static void drawSegment(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int steps = Math.max(dx, dy);
        if (steps == 0) { graphics.fill(x0, y0, x0 + 1, y0 + 1, color); return; }
        for (int s = 0; s <= steps; s++) {
            int x = x0 + (x1 - x0) * s / steps;
            int y = y0 + (y1 - y0) * s / steps;
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static void drawArc(GuiGraphics graphics, int cx, int cy, int r,
                                double startRad, double sweepRad, float drawFrac, int color) {
        int segs = Math.max(6, (int) (Math.abs(sweepRad) / (Math.PI * 2) * Math.max(10, r * 3)));
        int upto = (int) (segs * Math.min(1f, Math.max(0f, drawFrac)));
        for (int s = 0; s < upto; s++) {
            double a0 = startRad + sweepRad * (s / (double) segs);
            double a1 = startRad + sweepRad * ((s + 1) / (double) segs);
            drawSegment(graphics,
                cx + (int) (Math.cos(a0) * r), cy + (int) (Math.sin(a0) * r),
                cx + (int) (Math.cos(a1) * r), cy + (int) (Math.sin(a1) * r), color);
        }
    }

    private static final int[] SCI_POOL_ANCIENT = {0, 1, 2, 3, 4, 5, 6, 7};
    private static final int[] SCI_POOL_MEDIEVAL  = {8, 9, 10, 11, 12};
    private static final int[] SCI_POOL_BLUEPRINT = {13, 14, 15, 16, 8, 11};

    private void drawScienceAmbience(GuiGraphics graphics, int bx, int by, int bw, int bh) {
        boolean animate = com.bannerbound.core.Config.UI_ANIMATIONS.get();
        long ms = net.minecraft.Util.getMillis();

        Era era = ClientEraState.getPlayerEra();
        int style = era == Era.ANCIENT ? 0 : era.ordinal() <= Era.MEDIEVAL.ordinal() ? 1 : 2;

        final double parallax = 0.1;

        if (style == 0) {

            int doy = (int) Math.round(panY * parallax);
            int ruleCol = (0x14 << 24) | 0x5A6675;
            int idx = 0;
            for (int y = by + 16 + Math.floorMod(doy, 30); y <= by + bh; ) {
                if (y >= by) graphics.fill(bx + 4, y, bx + bw - 4, y + 1, ruleCol);
                int jitter = Math.floorMod((idx * 0x9E3779B1) >> 24, 7) - 3;
                y += 30 + jitter;
                idx++;
            }
        } else if (style == 1) {

            final int cell = 40;
            int dox = Math.floorMod((int) Math.round(panX * parallax), cell);
            int doy = Math.floorMod((int) Math.round(panY * parallax), cell);
            int line = (0x12 << 24) | 0x5A86B0;
            for (int x = bx - cell + dox; x <= bx + bw; x += cell)
                if (x >= bx && x <= bx + bw) graphics.fill(x, by, x + 1, by + bh, line);
            for (int y = by - cell + doy; y <= by + bh; y += cell)
                if (y >= by && y <= by + bh) graphics.fill(bx, y, bx + bw, y + 1, line);
        } else {

            final int cell = 26;
            int dox = Math.floorMod((int) Math.round(panX * parallax), cell);
            int doy = Math.floorMod((int) Math.round(panY * parallax), cell);
            int line = (0x18 << 24) | 0x4A86C8;
            int stud = (0x33 << 24) | 0x6FB0E8;
            for (int x = bx - cell + dox; x <= bx + bw; x += cell)
                if (x >= bx && x <= bx + bw) graphics.fill(x, by, x + 1, by + bh, line);
            for (int y = by - cell + doy; y <= by + bh; y += cell)
                if (y >= by && y <= by + bh) graphics.fill(bx, y, bx + bw, y + 1, line);
            for (int x = bx - cell + dox; x <= bx + bw; x += cell * 2)
                for (int y = by - cell + doy; y <= by + bh; y += cell * 2)
                    if (x >= bx && x <= bx + bw && y >= by && y <= by + bh)
                        graphics.fill(x, y, x + 1, y + 1, stud);
        }
        if (!animate) return;

        int[] pool = style == 0 ? SCI_POOL_ANCIENT : style == 1 ? SCI_POOL_MEDIEVAL : SCI_POOL_BLUEPRINT;
        for (int i = 0; i < 5; i++) {
            int h = (i + 5) * 0x9E3779B1;
            float period = 6_500f + (i % 4) * 1_700f;
            long phase = ms + Math.floorMod(h, 5000);
            float t = (phase % (long) period) / period;
            int ph = (int) ((phase / (long) period) * 2654435761L) ^ h;
            int cx = bx + 44 + Math.floorMod(ph + (int) Math.round(panX * parallax), Math.max(1, bw - 88));
            int cy = by + 44 + Math.floorMod((ph >> 9) + (int) Math.round(panY * parallax), Math.max(1, bh - 88));
            float env = (float) Math.sin(Math.PI * t);
            float draw = Math.min(1f, t / 0.45f);
            int a = (int) (env * 0.22f * 255f);
            if (a <= 3) continue;

            int col = (a << 24) | (style == 0 ? 0x9DAEC0 : 0x5E97C4);
            drawConstruction(graphics, pool[Math.floorMod(ph, pool.length)], cx, cy, draw, col);
        }

        for (int i = 0; i < 6; i++) {
            int h = (i + 17) * 0x85EBCA6B;
            float period = 11_000f + (i % 4) * 2_600f;
            float t = ((ms + Math.floorMod(h, 9000)) % (long) period) / period;
            int mx = bx + (int) (t * (bw + 12)) - 6;
            int my = by + Math.floorMod(h, Math.max(1, bh)) + (int) (Math.sin(ms / 1300.0 + i) * 5.0);
            float fade = (float) Math.sin(Math.PI * t);
            int a = (int) (fade * 0.30f * 255f);
            if (a <= 4) continue;
            graphics.fill(mx, my, mx + 1, my + 1, (a << 24) | 0xAFD4F0);
        }
    }

    private static void drawConstruction(GuiGraphics graphics, int variant, int cx, int cy, float draw, int col) {
        switch (variant) {

            case 0 -> {
                int sp = 5, shown = (int) (5 * draw + 0.001f);
                for (int k = 0; k < Math.min(4, shown); k++)
                    drawSegment(graphics, cx + k * sp, cy, cx + k * sp, cy - 16, col);
                if (shown >= 5) drawSegment(graphics, cx - 2, cy - 3, cx + 3 * sp + 2, cy - 13, col);
            }
            case 1 -> {
                int w = Math.min(4, (int) (4 * draw + 0.999f));
                for (int k = 0; k < w; k++) {
                    int xx = cx + k * 9;
                    drawSegment(graphics, xx, cy - 3, xx + 6, cy, col);
                    drawSegment(graphics, xx, cy + 3, xx + 6, cy, col);
                    drawSegment(graphics, xx, cy - 3, xx, cy + 3, col);
                }
            }
            case 2 -> {
                int[][] s = {{0, 0}, {12, -7}, {22, 3}, {31, -9}, {9, 9}};
                int shown = Math.min(s.length, (int) (s.length * draw + 0.999f));
                for (int k = 0; k < shown; k++) {
                    int sx = cx + s[k][0], sy = cy + s[k][1];
                    graphics.fill(sx - 1, sy, sx + 2, sy + 1, col);
                    graphics.fill(sx, sy - 1, sx + 1, sy + 2, col);
                    if (k > 0) drawSegment(graphics, cx + s[k - 1][0], cy + s[k - 1][1], sx, sy, col);
                }
            }
            case 3 -> {
                drawArc(graphics, cx, cy, 12, -Math.PI / 2, Math.PI, draw, col);
                drawArc(graphics, cx + 4, cy, 11, -Math.PI / 2, Math.PI, draw, col);
                int dots = (int) (5 * draw);
                for (int k = 0; k < dots; k++) graphics.fill(cx - 8 + k * 5, cy + 16, cx - 7 + k * 5, cy + 17, col);
            }
            case 4 -> {
                drawArc(graphics, cx, cy, 7, 0, Math.PI * 2, draw, col);
                if (draw > 0.5f) for (int k = 0; k < 8; k++) {
                    double an = k * Math.PI / 4;
                    drawSegment(graphics, cx + (int) (Math.cos(an) * 9), cy + (int) (Math.sin(an) * 9),
                        cx + (int) (Math.cos(an) * 13), cy + (int) (Math.sin(an) * 13), col);
                }
            }
            case 5 -> {
                int segs = 6, shown = (int) (segs * draw), sp = 7, prevx = cx, prevy = cy;
                for (int k = 1; k <= shown; k++) {
                    int xx = cx + k * sp, yy = cy + ((k % 2 == 0) ? -4 : 4);
                    drawSegment(graphics, prevx, prevy, xx, yy, col);
                    prevx = xx; prevy = yy;
                }
            }
            case 6 -> {
                int stem = (int) (22 * draw);
                drawSegment(graphics, cx, cy, cx, cy - stem, col);
                for (int k = 1; k <= 3; k++) {
                    int ey = cy - k * 6;
                    if (cy - stem <= ey) {
                        drawSegment(graphics, cx, ey, cx - 5, ey - 4, col);
                        drawSegment(graphics, cx, ey, cx + 5, ey - 4, col);
                    }
                }
            }
            case 7 -> {
                int cols = 5, rows = 2, total = cols * rows, shown = (int) (total * draw);
                for (int k = 0; k < shown; k++) {
                    int dxp = (k % cols) * 6, dyp = (k / cols) * 7;
                    graphics.fill(cx + dxp, cy - dyp, cx + dxp + 2, cy - dyp + 2, col);
                }
            }

            case 8 -> {
                drawArc(graphics, cx, cy, 18, 0, Math.PI * 2, draw, col);
                if (draw > 0.6f) {
                    drawSegment(graphics, cx, cy, cx + 18, cy, col);
                    drawSegment(graphics, cx, cy, cx, cy - 18, col);
                }
            }
            case 9 -> {
                int s = 26;
                int[][] pts = {{cx, cy}, {cx + s, cy}, {cx, cy - s}};
                int upto = Math.min(3, (int) (3 * draw + 0.999f));
                for (int e = 0; e < upto; e++)
                    drawSegment(graphics, pts[e][0], pts[e][1], pts[(e + 1) % 3][0], pts[(e + 1) % 3][1], col);
            }
            case 10 -> {
                int r = 24;
                double ang = Math.toRadians(52);
                drawSegment(graphics, cx, cy, cx + r, cy, col);
                drawSegment(graphics, cx, cy, cx + (int) (Math.cos(ang) * r), cy - (int) (Math.sin(ang) * r), col);
                drawArc(graphics, cx, cy, 13, 0, -ang, draw, col);
            }
            case 11 -> {
                drawArc(graphics, cx, cy, 10, 0, Math.PI * 2, draw, col);
                if (draw > 0.5f) drawArc(graphics, cx, cy, 19, 0, Math.PI * 2, (draw - 0.5f) * 2f, col);
            }
            case 12 -> {
                int s = 24;
                int[][] pts = {{cx, cy}, {cx + s, cy}, {cx + s, cy - s}, {cx, cy - s}, {cx, cy}, {cx + s, cy - s}};
                int upto = Math.min(pts.length - 1, (int) ((pts.length - 1) * draw + 0.999f));
                for (int e = 0; e < upto; e++)
                    drawSegment(graphics, pts[e][0], pts[e][1], pts[e + 1][0], pts[e + 1][1], col);
            }

            case 13 -> {
                int len = 46, x1 = cx - len / 2, x2 = x1 + (int) (len * draw);
                drawSegment(graphics, x1, cy, x2, cy, col);
                graphics.fill(x1, cy - 3, x1 + 1, cy + 4, col);
                if (draw > 0.95f) graphics.fill(x1 + len, cy - 3, x1 + len + 1, cy + 4, col);
            }
            case 14 -> {
                int len = 40, ex = cx + (int) (len * draw), ey = cy - (int) (len * 0.5f * draw);
                drawSegment(graphics, cx, cy, ex, ey, col);
                if (draw > 0.9f) {
                    drawSegment(graphics, ex, ey, ex - 7, ey + 1, col);
                    drawSegment(graphics, ex, ey, ex - 2, ey + 7, col);
                }
            }
            case 15 -> {
                int span = 46, half = span / 2, prevx = cx - half, prevy = cy;
                int upto = (int) (span * draw);
                for (int dxp = 1; dxp <= upto; dxp++) {
                    int xx = cx - half + dxp;
                    double nx = (dxp - half) / (double) half;
                    int yy = cy - (int) (nx * nx * 26);
                    drawSegment(graphics, prevx, prevy, xx, yy, col);
                    prevx = xx; prevy = yy;
                }
            }
            default -> {
                int s = 24, cells = 3, step = s / cells;
                drawSegment(graphics, cx, cy, cx + s, cy, col);
                drawSegment(graphics, cx, cy - s, cx + s, cy - s, col);
                drawSegment(graphics, cx, cy, cx, cy - s, col);
                drawSegment(graphics, cx + s, cy, cx + s, cy - s, col);
                if (draw > 0.5f) for (int k = 1; k < cells; k++) {
                    drawSegment(graphics, cx + k * step, cy, cx + k * step, cy - s, col);
                    drawSegment(graphics, cx, cy - k * step, cx + s, cy - k * step, col);
                }
            }
        }
    }

    private void drawCultureAmbience(GuiGraphics graphics, int bx, int by, int bw, int bh) {
        boolean animate = com.bannerbound.core.Config.UI_ANIMATIONS.get();
        long ms = net.minecraft.Util.getMillis();
        final double parallax = 0.1;
        Era era = ClientEraState.getPlayerEra();
        int style = era == Era.ANCIENT ? 0 : era.ordinal() <= Era.MEDIEVAL.ordinal() ? 1 : 2;
        if (style != 2) {
            drawCultureMotifField(graphics, bx, by, bw, bh, style, animate, ms, parallax);
            return;
        }

        final int n = 28;
        int[] px = new int[n];
        int[] py = new int[n];
        for (int i = 0; i < n; i++) {
            int h = i * 0x9E3779B1;

            px[i] = bx + Math.floorMod(h + (int) Math.round(panX * parallax), Math.max(1, bw));
            py[i] = by + Math.floorMod((h >> 11) + (int) Math.round(panY * parallax), Math.max(1, bh));
        }

        double thresh = Math.sqrt((double) bw * bh / Math.max(1, n)) * 1.3;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int dx = px[j] - px[i], dy = py[j] - py[i];
                double dist = Math.sqrt((double) dx * dx + (double) dy * dy);
                if (dist < 1 || dist > thresh) continue;
                int eh = (i * 131 + j) * 0x85EBCA6B;

                float breathe = animate
                    ? 0.55f + 0.45f * (float) (0.5 + 0.5 * Math.sin(ms / (2600.0 + Math.floorMod(eh, 1500)) + (eh & 0xFF)))
                    : 0.8f;
                float prox = (float) (1.0 - dist / thresh);
                int a = (int) (breathe * (0.16f + 0.16f * prox) * 255f);
                if (a <= 4) continue;
                drawSegment(graphics, px[i], py[i], px[j], py[j], (a << 24) | 0xA85CD8);

                if (animate && (eh & 7) == 0) {
                    float p = ((ms + Math.floorMod(eh, 3000)) % 2000L) / 2000f;
                    int tx = px[i] + (int) (dx * p), ty = py[i] + (int) (dy * p);
                    int pa = Math.max(0, (int) ((1f - Math.abs(0.5f - p) * 2f) * 0.85f * 255f));
                    graphics.fill(tx, ty, tx + 2, ty + 2, (pa << 24) | 0xE9A0FF);
                }
            }
        }

        for (int i = 0; i < n; i++) {
            float br = animate ? 0.4f + 0.35f * (float) (0.5 + 0.5 * Math.sin(ms / 900.0 + i * 1.3)) : 0.55f;
            int a = (int) (br * 255f);
            graphics.fill(px[i], py[i], px[i] + 2, py[i] + 2, (a << 24) | 0xC77BE8);
        }
    }

    private static final int[] CUL_POOL_CAVE     = {0, 1, 2, 3, 4, 5};
    private static final int[] CUL_POOL_MEDIEVAL = {6, 7, 8, 9, 10, 11};

    private void drawCultureMotifField(GuiGraphics graphics, int bx, int by, int bw, int bh,
                                       int style, boolean animate, long ms, double parallax) {
        if (style == 0) {

            int doy = (int) Math.round(panY * parallax);
            int rock = (0x10 << 24) | 0x6A5560;
            int idx = 0;
            for (int y = by + 20 + Math.floorMod(doy, 46); y <= by + bh; ) {
                if (y >= by) graphics.fill(bx + 6, y, bx + bw - 6, y + 1, rock);
                int j = Math.floorMod((idx * 0x9E3779B1) >> 24, 11) - 5;
                y += 44 + j;
                idx++;
            }
            for (int i = 0; i < 40; i++) {
                int hh = i * 0x85EBCA6B;
                int sx = bx + Math.floorMod(hh, Math.max(1, bw));
                int sy = by + Math.floorMod(hh >> 11, Math.max(1, bh));
                graphics.fill(sx, sy, sx + 1, sy + 1, rock);
            }
        } else {

            final int cell = 34;
            int doy = Math.floorMod((int) Math.round(panY * parallax), cell);
            int rule = (0x12 << 24) | 0x6A4A86;
            for (int y = by - cell + doy; y <= by + bh; y += cell)
                if (y >= by && y <= by + bh) graphics.fill(bx, y, bx + bw, y + 1, rule);
            int mx = bx + 46;
            graphics.fill(mx, by, mx + 1, by + bh, (0x1A << 24) | 0x9A4A6A);
            graphics.fill(mx + 4, by, mx + 5, by + bh, (0x1A << 24) | 0x9A4A6A);
        }
        if (!animate) return;

        int[] pool = style == 0 ? CUL_POOL_CAVE : CUL_POOL_MEDIEVAL;

        int rgb = style == 0 ? 0xB07A6A : 0xC59AE0;
        for (int i = 0; i < 5; i++) {
            int h = (i + 9) * 0x9E3779B1;
            float period = 6_500f + (i % 4) * 1_700f;
            long phase = ms + Math.floorMod(h, 5000);
            float t = (phase % (long) period) / period;
            int ph = (int) ((phase / (long) period) * 2654435761L) ^ h;
            int cx = bx + 46 + Math.floorMod(ph + (int) Math.round(panX * parallax), Math.max(1, bw - 92));
            int cy = by + 46 + Math.floorMod((ph >> 9) + (int) Math.round(panY * parallax), Math.max(1, bh - 92));
            float env = (float) Math.sin(Math.PI * t);
            float draw = Math.min(1f, t / 0.45f);
            int a = (int) (env * 0.26f * 255f);
            if (a <= 3) continue;
            drawCultureMotif(graphics, pool[Math.floorMod(ph, pool.length)], cx, cy, draw, (a << 24) | rgb);
        }
    }

    private static void drawCultureMotif(GuiGraphics graphics, int variant, int cx, int cy, float draw, int col) {
        switch (variant) {

            case 0 -> {
                drawArc(graphics, cx, cy, 6, 0, Math.PI * 2, draw, col);
                if (draw > 0.4f) for (int f = 0; f < 5; f++) {
                    double an = -Math.PI / 2 + (f - 2) * 0.42;
                    drawSegment(graphics, cx + (int) (Math.cos(an) * 6), cy + (int) (Math.sin(an) * 6),
                        cx + (int) (Math.cos(an) * 16), cy + (int) (Math.sin(an) * 16), col);
                }
            }
            case 1 -> {
                int parts = (int) (7 * draw + 0.999f);
                if (parts >= 1) drawSegment(graphics, cx - 14, cy, cx + 12, cy - 2, col);
                if (parts >= 2) drawSegment(graphics, cx - 12, cy, cx - 12, cy + 12, col);
                if (parts >= 3) drawSegment(graphics, cx - 6, cy + 1, cx - 6, cy + 12, col);
                if (parts >= 4) drawSegment(graphics, cx + 6, cy - 1, cx + 6, cy + 11, col);
                if (parts >= 5) drawSegment(graphics, cx + 10, cy - 2, cx + 10, cy + 10, col);
                if (parts >= 6) drawSegment(graphics, cx + 12, cy - 2, cx + 18, cy - 10, col);
                if (parts >= 7) {
                    drawSegment(graphics, cx + 18, cy - 10, cx + 22, cy - 16, col);
                    drawSegment(graphics, cx + 18, cy - 10, cx + 14, cy - 16, col);
                }
            }
            case 2 -> {
                int parts = (int) (6 * draw + 0.999f);
                if (parts >= 1) drawArc(graphics, cx, cy - 14, 3, 0, Math.PI * 2, 1f, col);
                if (parts >= 2) drawSegment(graphics, cx, cy - 11, cx, cy, col);
                if (parts >= 3) drawSegment(graphics, cx, cy, cx - 5, cy + 9, col);
                if (parts >= 4) drawSegment(graphics, cx, cy, cx + 5, cy + 9, col);
                if (parts >= 5) drawSegment(graphics, cx, cy - 8, cx + 9, cy - 12, col);
                if (parts >= 6) drawSegment(graphics, cx + 4, cy - 18, cx + 13, cy - 6, col);
            }
            case 3 -> {
                int shown = Math.min(3, (int) (3 * draw + 0.999f));
                for (int d = 0; d < shown; d++) {
                    int hx = cx + d * 14;
                    drawArc(graphics, hx, cy - 12, 2, 0, Math.PI * 2, 1f, col);
                    drawSegment(graphics, hx, cy - 10, hx, cy - 2, col);
                    drawSegment(graphics, hx, cy - 2, hx - 4, cy + 6, col);
                    drawSegment(graphics, hx, cy - 2, hx + 4, cy + 6, col);
                    drawSegment(graphics, hx, cy - 8, hx - 5, cy - 13, col);
                    drawSegment(graphics, hx, cy - 8, hx + 5, cy - 13, col);
                }
            }
            case 4 -> {
                int segs = (int) (3 * 24 * draw);
                int prevx = cx, prevy = cy;
                for (int s = 1; s <= segs; s++) {
                    double th = s * 0.26, r = th * 1.3;
                    int xx = cx + (int) (Math.cos(th) * r), yy = cy + (int) (Math.sin(th) * r);
                    drawSegment(graphics, prevx, prevy, xx, yy, col);
                    prevx = xx; prevy = yy;
                }
            }
            case 5 -> {
                int shown = (int) (6 * draw);
                for (int k = 0; k < shown; k++) graphics.fill(cx + k * 8, cy, cx + k * 8 + 4, cy + 3, col);
            }

            case 6 -> {
                int w = (int) (42 * draw);
                for (int l = 0; l < 4; l++) {
                    int yy = cy - 6 + l * 4;
                    drawSegment(graphics, cx, yy, cx + w, yy, col);
                }
                if (draw > 0.6f) {
                    int[] ny = {-6, -2, -10, -4};
                    for (int q = 0; q < 4; q++) {
                        int nx = cx + 6 + q * 10, y = cy + ny[q];
                        graphics.fill(nx, y, nx + 4, y + 3, col);
                    }
                }
            }
            case 7 -> {
                int wq = 11, top = cy - 14, midY = cy + 2, botY = cy + 14;
                int parts = (int) (6 * draw + 0.999f);
                if (parts >= 1) drawSegment(graphics, cx - wq, top, cx + wq, top, col);
                if (parts >= 2) drawSegment(graphics, cx - wq, top, cx - wq, midY, col);
                if (parts >= 3) drawSegment(graphics, cx + wq, top, cx + wq, midY, col);
                if (parts >= 4) drawSegment(graphics, cx - wq, midY, cx, botY, col);
                if (parts >= 5) drawSegment(graphics, cx + wq, midY, cx, botY, col);
                if (parts >= 6) {
                    drawSegment(graphics, cx - wq, top, cx, cy, col);
                    drawSegment(graphics, cx + wq, top, cx, cy, col);
                }
            }
            case 8 -> {
                int s = 22;
                int parts = (int) (4 * draw + 0.999f);
                if (parts >= 1) drawSegment(graphics, cx, cy, cx + s, cy, col);
                if (parts >= 2) drawSegment(graphics, cx, cy - s, cx + s, cy - s, col);
                if (parts >= 3) {
                    drawSegment(graphics, cx, cy, cx, cy - s, col);
                    drawSegment(graphics, cx + s, cy, cx + s, cy - s, col);
                }
                if (parts >= 4) drawArc(graphics, cx + s / 2, cy - s / 2, 6, 0, Math.PI * 2.5, 1f, col);
            }
            case 9 -> {
                int ext = (int) (14 * draw);
                drawSegment(graphics, cx, cy - ext, cx, cy + ext, col);
                drawSegment(graphics, cx - ext, cy, cx + ext, cy, col);
                if (draw > 0.85f) {
                    drawSegment(graphics, cx - 3, cy - 14, cx + 3, cy - 14, col);
                    drawSegment(graphics, cx - 3, cy + 14, cx + 3, cy + 14, col);
                    drawSegment(graphics, cx - 14, cy - 3, cx - 14, cy + 3, col);
                    drawSegment(graphics, cx + 14, cy - 3, cx + 14, cy + 3, col);
                }
            }
            case 10 -> {
                int segs = 8, shown = (int) (segs * draw), sp = 6, prevx = cx, prevy = cy;
                for (int k = 1; k <= shown; k++) {
                    int xx = cx + k * sp, yy = cy + ((k % 2 == 0) ? -3 : 3);
                    drawSegment(graphics, prevx, prevy, xx, yy, col);
                    if (k % 2 == 0) drawSegment(graphics, xx, yy, xx + 2, yy - 5, col);
                    prevx = xx; prevy = yy;
                }
            }
            default -> {
                drawArc(graphics, cx, cy, 16, 0, Math.PI * 2, draw, col);
                if (draw > 0.4f) drawArc(graphics, cx, cy, 8, 0, Math.PI * 2, (draw - 0.4f) / 0.6f, col);
                if (draw > 0.6f) for (int k = 0; k < 8; k++) {
                    double an = k * Math.PI / 4;
                    drawSegment(graphics, cx + (int) (Math.cos(an) * 8), cy + (int) (Math.sin(an) * 8),
                        cx + (int) (Math.cos(an) * 16), cy + (int) (Math.sin(an) * 16), col);
                }
            }
        }
    }

    private int boardX() { return PANEL_MARGIN; }
    private int boardY() { return PANEL_MARGIN + HEADER_HEIGHT; }
    private int boardWidth() { return this.width - PANEL_MARGIN * 2; }
    private int boardHeight() { return this.height - PANEL_MARGIN * 2 - HEADER_HEIGHT - FOOTER_HEIGHT; }

    private double pivotX() { return boardX() + boardWidth() / 2.0 + panX; }
    private double pivotY() { return boardY() + boardHeight() / 2.0 + panY; }

    private int nodeBoardX(ResearchDefinition def) { return def.x() - NODE_WIDTH / 2; }
    private int nodeBoardY(ResearchDefinition def) { return def.y() - NODE_HEIGHT / 2; }

    private double screenToBoardX(double screenX) { return (screenX - pivotX()) / zoom; }
    private double screenToBoardY(double screenY) { return (screenY - pivotY()) / zoom; }

    @Override
    protected void init() {

        if (!announcedOpen) {
            announcedOpen = true;
            PacketDistributor.sendToServer(new MenuOpenedPayload("research_tree"));
        }
        if (pendingFocusId != null) {
            activeTab = pendingFocusCulture ? Tab.CULTURE : Tab.SCIENCE;
            ResearchDefinition focusDef = currentTree().get(pendingFocusId);
            if (focusDef != null) {
                zoomTarget = 1.0;
                panXTarget = -focusDef.x();
                panYTarget = -focusDef.y();
                if (!com.bannerbound.core.Config.UI_ANIMATIONS.get()) {
                    zoom = zoomTarget;
                    panX = panXTarget;
                    panY = panYTarget;
                }
            }
            pendingFocusId = null;
        }
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {

            boolean culture = activeTab == Tab.CULTURE;
            boolean faith = activeTab == Tab.FAITH;
            int panelFill   = faith ? 0xFF14120C : culture ? 0xFF14081E : 0xFF080E18;
            int frameColor  = faith ? 0xFFB8A56A : culture ? 0xFF7A33B0 : 0xFF3A6AB0;
            int dividerLine = faith ? 0xFF5A523A : culture ? 0xFF4A2A6A : 0xFF243A5A;
            graphics.fill(PANEL_MARGIN, PANEL_MARGIN, this.width - PANEL_MARGIN, this.height - PANEL_MARGIN, panelFill);
            graphics.renderOutline(PANEL_MARGIN, PANEL_MARGIN, this.width - PANEL_MARGIN * 2, this.height - PANEL_MARGIN * 2, frameColor);
            graphics.fill(PANEL_MARGIN + 2, PANEL_MARGIN + HEADER_HEIGHT, this.width - PANEL_MARGIN - 2, PANEL_MARGIN + HEADER_HEIGHT + 1, dividerLine);
        });

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            int active;
            int cap;
            double rate;
            MutableComponent rateIcon;
            if (activeTab == Tab.FAITH) {

                MutableComponent header = Component.literal(String.format("%d/1  ·  %.2f",
                        ClientFaithTreeState.hasActive() ? 1 : 0,
                        ClientFaithTreeState.getDevotionPerSecond()))
                    .append(Icons.faith())
                    .append(Component.literal("/s"));
                graphics.drawCenteredString(this.font, header,
                    this.width / 2, PANEL_MARGIN + 10, 0xFFE8D9A0);
                return;
            }
            if (activeTab == Tab.CULTURE) {
                active = ClientCultureState.getActiveCount();
                cap = ClientCultureState.getCapacity();
                rate = ClientCultureState.getCulturePerSecond();
                rateIcon = Icons.culture();
            } else {
                active = ClientResearchState.getActiveCount();
                cap = ClientResearchState.getCapacity();
                rate = ClientResearchState.getSciencePerSecond();
                rateIcon = Icons.science();
            }
            MutableComponent header = Component.literal(String.format("%d/%d  ·  %.2f", active, cap, rate))
                .append(rateIcon)
                .append(Component.literal("/s"));
            graphics.drawCenteredString(this.font, header,
                this.width / 2, PANEL_MARGIN + 10, 0xFFFFFFFF);
        });

        boolean cultureUnlocked = ClientPopulationState.getGovernmentOrdinal() != 0;
        if (!cultureUnlocked && activeTab == Tab.CULTURE) {
            activeTab = Tab.SCIENCE;
        }

        boolean faithUnlocked = ClientFaithState.hasFaith();
        if (!faithUnlocked && activeTab == Tab.FAITH) {
            activeTab = Tab.SCIENCE;
        }
        int tabY = PANEL_MARGIN + HEADER_HEIGHT - 18;
        int tabH = 14;
        int tabW = 70;
        int tabX = PANEL_MARGIN + 4;
        Button scienceTab = PolishButton.polished(
            Component.translatable("bannerbound.research.tab.science"),
            b -> { if (activeTab != Tab.SCIENCE) { activeTab = Tab.SCIENCE; this.rebuildWidgets(); } }
        ).bounds(tabX, tabY, tabW, tabH).build();
        scienceTab.active = (activeTab != Tab.SCIENCE);
        this.addRenderableWidget(scienceTab);
        if (cultureUnlocked) {
            Button cultureTab = PolishButton.polished(
                Component.translatable("bannerbound.research.tab.culture"),
                b -> { if (activeTab != Tab.CULTURE) { activeTab = Tab.CULTURE; this.rebuildWidgets(); } }
            ).bounds(tabX + tabW + 4, tabY, tabW, tabH).build();
            cultureTab.active = (activeTab != Tab.CULTURE);
            this.addRenderableWidget(cultureTab);
        }
        if (faithUnlocked) {
            Button faithTab = PolishButton.polished(
                Component.translatable("bannerbound.research.tab.faith"),
                b -> { if (activeTab != Tab.FAITH) { activeTab = Tab.FAITH; this.rebuildWidgets(); } }
            ).bounds(tabX + (tabW + 4) * 2, tabY, tabW, tabH).build();
            faithTab.active = (activeTab != Tab.FAITH);
            this.addRenderableWidget(faithTab);
        }

        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            int bx = boardX(), by = boardY(), bw = boardWidth(), bh = boardHeight();
            int boardBg = activeTab == Tab.FAITH ? 0xFF0E0D08
                : activeTab == Tab.CULTURE ? 0xFF0E0518 : 0xFF05080E;
            graphics.fill(bx, by, bx + bw, by + bh, boardBg);

            graphics.enableScissor(bx, by, bx + bw, by + bh);

            if (activeTab == Tab.FAITH) {
                drawFaithAmbience(graphics, bx, by, bw, bh);
            } else if (activeTab == Tab.CULTURE) {
                drawCultureAmbience(graphics, bx, by, bw, bh);
            } else {
                drawScienceAmbience(graphics, bx, by, bw, bh);
            }

            if (activeTab != Tab.FAITH) {
                drawEraDividers(graphics, bx, by, bw, bh);
            }

            graphics.pose().pushPose();
            graphics.pose().translate(pivotX(), pivotY(), 0);
            graphics.pose().scale((float) zoom, (float) zoom, 1f);

            double boardMouseX = screenToBoardX(mouseX);
            double boardMouseY = screenToBoardY(mouseY);

            java.util.Set<String> highlightSet = hovered != null
                ? prereqClosure(hovered.id())
                : java.util.Collections.emptySet();
            boolean anyHighlight = !highlightSet.isEmpty();
            boolean animate = com.bannerbound.core.Config.UI_ANIMATIONS.get();

            float dimTarget = anyHighlight ? 1f : 0f;
            highlightDimEase = animate
                ? highlightDimEase + (dimTarget - highlightDimEase) * 0.3f
                : dimTarget;
            if (highlightDimEase < 0.01f) highlightDimEase = 0f;

            for (ResearchDefinition def : currentTree().values()) {
                if (!isNodeVisible(def)) continue;
                for (String prereq : def.prerequisites()) {
                    ResearchDefinition pdef = currentTree().get(prereq);
                    if (pdef == null || !isNodeVisible(pdef)) continue;
                    String ekey = prereq + ">" + def.id();
                    boolean onPath = anyHighlight
                        && highlightSet.contains(def.id()) && highlightSet.contains(pdef.id());
                    float e = edgeHighlightEase.getOrDefault(ekey, 0f);
                    e = animate ? e + ((onPath ? 1f : 0f) - e) * 0.3f : (onPath ? 1f : 0f);
                    if (e < 0.01f && !onPath) edgeHighlightEase.remove(ekey);
                    else edgeHighlightEase.put(ekey, e);
                }
            }

            for (int pass = 0; pass < 2; pass++) {
                for (ResearchDefinition def : currentTree().values()) {
                    if (!isNodeVisible(def)) continue;
                    int x2 = def.x();
                    int y2 = def.y();
                    for (String prereq : def.prerequisites()) {
                        ResearchDefinition pdef = currentTree().get(prereq);
                        if (pdef == null || !isNodeVisible(pdef)) continue;
                        float e = edgeHighlightEase.getOrDefault(prereq + ">" + def.id(), 0f);
                        boolean top = e > 0.5f;
                        if (top != (pass == 1)) continue;
                        int off = lerpColor(0xFF505050, 0xFF26262C, highlightDimEase);
                        int color = lerpColor(off, highlightLineColor(), e);

                        drawPrereqEdge(graphics, pdef.x(), pdef.y(), x2, y2, color, top,
                            activeTab == Tab.CULTURE);
                    }
                }
            }

            ResearchDefinition foundHover = null;
            ResearchDefinition foundInsightHover = null;
            for (ResearchDefinition def : currentTree().values()) {
                if (!isNodeVisible(def)) continue;
                int nx = nodeBoardX(def);
                int ny = nodeBoardY(def);
                boolean isComplete = currentIsCompleted(def.id());
                boolean isActive = currentIsActive(def.id());
                boolean prereqMet = currentPrereqsMet(def);
                boolean ageMet = currentAgeMet(def);

                int[] colors = nodeColors(isComplete, isActive, prereqMet && ageMet);
                int fillColor = colors[0];
                int borderColor = colors[1];

                float ease = nodeHoverEase.getOrDefault(def.id(), 0f);
                boolean isHoverNow = hovered != null && hovered.id().equals(def.id());
                if (com.bannerbound.core.Config.UI_ANIMATIONS.get()) {
                    ease += ((isHoverNow ? 1f : 0f) - ease) * 0.3f;
                } else {
                    ease = isHoverNow ? 1f : 0f;
                }
                if (ease < 0.01f) nodeHoverEase.remove(def.id());
                else nodeHoverEase.put(def.id(), ease);
                int grow = Math.round(ease * 2f);
                int fillDrawn = brighten(fillColor, ease * 0.18f);
                int borderDrawn = brighten(borderColor, ease * 0.45f);

                graphics.fill(nx - grow, ny - grow,
                    nx + NODE_WIDTH + grow, ny + NODE_HEIGHT + grow, fillDrawn);
                graphics.renderOutline(nx - grow, ny - grow,
                    NODE_WIDTH + 2 * grow, NODE_HEIGHT + 2 * grow, borderDrawn);

                float ringTarget = anyHighlight && highlightSet.contains(def.id())
                        && !(hovered != null && hovered.id().equals(def.id())) ? 1f : 0f;
                float ring = nodeHighlightEase.getOrDefault(def.id(), 0f);
                ring = animate ? ring + (ringTarget - ring) * 0.3f : ringTarget;
                if (ring < 0.01f) nodeHighlightEase.remove(def.id());
                else nodeHighlightEase.put(def.id(), ring);
                if (ring > 0.01f) {
                    graphics.renderOutline(nx - grow - 2, ny - grow - 2,
                        NODE_WIDTH + 2 * grow + 4, NODE_HEIGHT + 2 * grow + 4,
                        withAlpha(highlightLineColor(), ring));
                }

                if (def.important()) {
                    float pulse = 1.0f;
                    if (!isComplete && com.bannerbound.core.Config.UI_ANIMATIONS.get()) {
                        pulse = 0.78f + 0.22f * (float) Math.sin(net.minecraft.Util.getMillis() / 400.0);
                    }
                    int gr = (int) (0xD9 * pulse), gg = (int) (0xA9 * pulse), gb = (int) (0x4A * pulse);
                    int gold = 0xFF000000 | (gr << 16) | (gg << 8) | gb;
                    int ox0 = nx - grow - 3, oy0 = ny - grow - 3;
                    int ox1 = nx + NODE_WIDTH + grow + 3, oy1 = ny + NODE_HEIGHT + grow + 3;
                    graphics.renderOutline(ox0, oy0, ox1 - ox0, oy1 - oy0, gold);

                    graphics.fill(ox0 - 2, oy0 - 2, ox0 + 3, oy0 + 3, gold);
                    graphics.fill(ox1 - 3, oy0 - 2, ox1 + 2, oy0 + 3, gold);
                    graphics.fill(ox0 - 2, oy1 - 3, ox0 + 3, oy1 + 2, gold);
                    graphics.fill(ox1 - 3, oy1 - 3, ox1 + 2, oy1 + 2, gold);
                }

                if (def.cost() > 0) {
                    double prog = currentProgress(def.id());
                    if (prog > 0 || isActive) {
                        double drawnProg = prog;
                        if (com.bannerbound.core.Config.UI_ANIMATIONS.get()) {
                            float disp = progressEase.getOrDefault(def.id(), (float) prog);
                            float barAlpha = (float) (1.0 - Math.exp(-lastFrameDt / 0.35));
                            disp += (float) (prog - disp) * barAlpha;
                            if (Math.abs(prog - disp) < 0.005) disp = (float) prog;
                            progressEase.put(def.id(), disp);
                            drawnProg = disp;
                        }
                        double frac = Math.max(0.0, Math.min(1.0, drawnProg / def.cost()));
                        int barWidth = (int) ((NODE_WIDTH - 4) * frac);
                        int barColor = isActive ? activeProgressBarColor() : 0xFF707070;
                        graphics.fill(nx + 2, ny + NODE_HEIGHT - 5, nx + 2 + barWidth, ny + NODE_HEIGHT - 2, barColor);
                    }
                }

                int nameColor = (prereqMet && ageMet) || isComplete ? 0xFFFFFFFF : 0xFF808080;

                String displayName = isFutureEra(def) ? "?" : def.name();
                int maxNameWidth = NODE_WIDTH - 6;
                if (this.font.width(displayName) > maxNameWidth) {
                    displayName = this.font.plainSubstrByWidth(displayName, maxNameWidth - this.font.width("..")) + "..";
                }
                graphics.drawCenteredString(this.font, Component.literal(displayName),
                    nx + NODE_WIDTH / 2, ny + (NODE_HEIGHT - this.font.lineHeight) / 2 - 2, nameColor);

                drawInsightLabel(graphics, def, nx, ny + NODE_HEIGHT + INSIGHT_LABEL_GAP);

                int hoverBottom = ny + NODE_HEIGHT;
                boolean hasInsightLabel = def.insight() != null && !isFutureEra(def);
                int insightTop = ny + NODE_HEIGHT + INSIGHT_LABEL_GAP;
                int insightBottom = insightTop + INSIGHT_LABEL_HEIGHT;
                if (hasInsightLabel) {
                    hoverBottom += INSIGHT_LABEL_GAP + INSIGHT_LABEL_HEIGHT;
                }
                boolean cursorInScreenBoard = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
                if (boardMouseX >= nx && boardMouseX < nx + NODE_WIDTH
                        && boardMouseY >= ny && boardMouseY < hoverBottom
                        && cursorInScreenBoard) {
                    foundHover = def;
                }

                if (hasInsightLabel
                        && boardMouseX >= nx && boardMouseX < nx + NODE_WIDTH
                        && boardMouseY >= insightTop && boardMouseY < insightBottom
                        && cursorInScreenBoard) {
                    foundInsightHover = def;
                }
            }
            hovered = foundHover;
            hoveredInsight = foundInsightHover;

            for (ResearchDefinition def : currentTree().values()) {
                if (!isNodeVisible(def)) continue;
                int queuePos = currentQueuePosition(def.id());
                if (queuePos <= 0) continue;
                int nx = nodeBoardX(def);
                int ny = nodeBoardY(def);
                boolean isComplete = currentIsCompleted(def.id());
                boolean isActive = currentIsActive(def.id());
                boolean prereqMet = currentPrereqsMet(def);
                boolean ageMet = currentAgeMet(def);
                int borderColor = queueBadgeBorderFor(isComplete, isActive, prereqMet && ageMet);
                drawQueueBadge(graphics, nx + NODE_WIDTH - 2, ny - QUEUE_BADGE_OFFSET_Y, queuePos, borderColor);
            }

            for (ResearchDefinition def : currentTree().values()) {
                if (!isNodeVisible(def)) continue;
                java.util.List<java.util.UUID> suggesters = activeTab == Tab.CULTURE
                    ? ClientSuggestionState.getCultureSuggesters(def.id())
                    : ClientSuggestionState.getScienceSuggesters(def.id());
                if (suggesters.isEmpty()) continue;
                int nx = nodeBoardX(def);
                int ny = nodeBoardY(def);
                drawSuggestionBadge(graphics, nx, ny + QUEUE_BADGE_OFFSET_Y - 2, suggesters);
            }

            graphics.pose().popPose();
            graphics.disableScissor();
        });

        int btnY = this.height - PANEL_MARGIN - 20;
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.cancel"),
            btn -> this.onClose())
            .bounds(this.width - PANEL_MARGIN - 80, btnY, 80, 20)
            .build());

    }

    private void drawEraDividers(GuiGraphics graphics, int bx, int by, int bw, int bh) {
        Map<Era, int[]> bounds = new EnumMap<>(Era.class);
        for (ResearchDefinition def : currentTree().values()) {
            Era era = def.minAge();
            int left = def.x() - NODE_WIDTH / 2;
            int right = def.x() + NODE_WIDTH / 2;
            int[] cur = bounds.get(era);
            if (cur == null) {
                bounds.put(era, new int[]{left, right});
            } else {
                cur[0] = Math.min(cur[0], left);
                cur[1] = Math.max(cur[1], right);
            }
        }
        if (bounds.isEmpty()) return;

        final int padding = 24;
        int boardCenterX = bx + bw / 2;
        int top = by;
        int bottom = by + bh;

        List<Era> eras = new ArrayList<>(bounds.keySet());
        eras.sort((a, b) -> Integer.compare(bounds.get(a)[0], bounds.get(b)[0]));

        int n = eras.size();
        int[] lineX = new int[n + 1];
        lineX[0] = (int) Math.round(boardCenterX + panX + (bounds.get(eras.get(0))[0] - padding) * zoom);
        for (int i = 1; i < n; i++) {
            double prevRight = boardCenterX + panX + bounds.get(eras.get(i - 1))[1] * zoom;
            double curLeft = boardCenterX + panX + bounds.get(eras.get(i))[0] * zoom;
            lineX[i] = (int) Math.round((prevRight + curLeft) / 2);
        }
        lineX[n] = (int) Math.round(boardCenterX + panX + (bounds.get(eras.get(n - 1))[1] + padding) * zoom);

        for (int x : lineX) {
            if (x + 2 < bx || x > bx + bw) continue;
            graphics.fill(x, top, x + 2, bottom, 0xFF505050);
        }

        for (int i = 0; i < n; i++) {
            if (lineX[i + 1] < bx || lineX[i] > bx + bw) continue;
            net.minecraft.network.chat.Component title = eras.get(i).displayName();
            int textW = this.font.width(title);
            int titleX = (lineX[i] + lineX[i + 1]) / 2 - textW / 2;
            int titleY = top + 4;
            graphics.fill(titleX - 4, titleY - 2,
                titleX + textW + 4, titleY + this.font.lineHeight + 1,
                0xFF101010);
            graphics.drawString(this.font, title, titleX, titleY, 0xFFCCCCCC, false);
        }
    }

    private void drawSuggestionBadge(GuiGraphics graphics, int rightX, int y,
                                      java.util.List<java.util.UUID> suggesters) {
        int n = suggesters.size();
        String text = "[+" + n + "]";
        int textW = this.font.width(text);
        int headSize = 8;
        int padding = 2;
        int shown = Math.min(MAX_FACES, n);
        int overflow = n - shown;
        String overflowText = overflow > 0 ? " +" + overflow : "";
        int overflowW = overflow > 0 ? this.font.width(overflowText) : 0;
        int boxW = textW + 4 + (shown * (headSize + padding)) + overflowW + 4;
        int boxH = Math.max(headSize, this.font.lineHeight) + 4;
        int boxX = rightX - boxW;
        int boxY = y - boxH / 2;

        graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF101010);
        graphics.renderOutline(boxX, boxY, boxW, boxH, 0xFF55E055);

        int cursorX = boxX + 3;
        int textY = boxY + (boxH - this.font.lineHeight) / 2 + 1;
        graphics.drawString(this.font, text, cursorX, textY, 0xFF66FF66, false);
        cursorX += textW + 3;

        java.util.UUID localId = this.minecraft != null && this.minecraft.player != null
            ? this.minecraft.player.getUUID() : null;
        int faceY = boxY + (boxH - headSize) / 2;
        for (int i = 0; i < shown; i++) {
            java.util.UUID id = suggesters.get(i);
            net.minecraft.resources.ResourceLocation skin = resolveSkin(id);
            if (skin != null) {

                net.minecraft.client.gui.components.PlayerFaceRenderer.draw(
                    graphics, new net.minecraft.client.resources.PlayerSkin(
                        skin, null, null, null,
                        net.minecraft.client.resources.PlayerSkin.Model.WIDE, true),
                    cursorX, faceY, headSize);
            } else {

                graphics.fill(cursorX, faceY, cursorX + headSize, faceY + headSize, 0xFF55EE55);
            }

            if (id.equals(localId)) {
                graphics.renderOutline(cursorX - 1, faceY - 1, headSize + 2, headSize + 2, 0xFFEEFFEE);
            }
            cursorX += headSize + padding;
        }
        if (overflow > 0) {
            graphics.drawString(this.font, overflowText, cursorX, textY, 0xFF99FF99, false);
        }
    }

    private static final int MAX_FACES = 4;

    private net.minecraft.resources.ResourceLocation resolveSkin(java.util.UUID id) {
        if (this.minecraft == null || this.minecraft.getConnection() == null) return null;
        net.minecraft.client.multiplayer.PlayerInfo info =
            this.minecraft.getConnection().getPlayerInfo(id);
        return info == null ? null : info.getSkin().texture();
    }

    private void drawQueueBadge(GuiGraphics graphics, int rightX, int y, int num, int outlineColor) {
        String text = "[" + num + "]";
        int textW = this.font.width(text);
        int boxW = textW + 6;
        int boxH = this.font.lineHeight + 2;
        int x = rightX - boxW;
        graphics.fill(x, y, x + boxW, y + boxH, 0xFF101010);
        graphics.renderOutline(x, y, boxW, boxH, outlineColor);
        graphics.drawString(this.font, text, x + 3, y + 2, 0xFFFFFFFF, false);
    }

    private static final int ITEMS_PER_ROW = 6;
    private static final int ITEM_CELL = 18;

    private static String applyAmpFormatting(String s) {
        return s.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
    }

    private void renderNodeTooltip(GuiGraphics graphics, ResearchDefinition def, int mouseX, int mouseY) {
        if (isFutureEra(def)) {
            renderCustomTooltip(graphics,
                List.of(Component.literal("?").withStyle(ChatFormatting.GRAY)),
                null, List.of(),
                List.of(Component.translatable("bannerbound.research.future_unknown")
                    .withStyle(ChatFormatting.DARK_GRAY)),
                mouseX, mouseY);
            return;
        }

        List<Component> header = new ArrayList<>();
        header.add(Component.literal(def.name()).withStyle(ChatFormatting.WHITE));
        if (!def.description().isEmpty()) {

            String formatted = applyAmpFormatting(def.description());
            for (String line : formatted.split("\n", -1)) {
                header.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
            }
        }
        appendEffectLines(def, header);
        if (def.heraldryPoints() > 0) {
            header.add(Component.translatable("bannerbound.research.heraldry_points",
                def.heraldryPoints()).withStyle(ChatFormatting.GOLD));
        }

        List<net.minecraft.world.item.ItemStack> items = new ArrayList<>();
        for (String id : def.unlocksItems()) {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (rl == null) continue;
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
            if (item == net.minecraft.world.item.Items.AIR) continue;
            items.add(new net.minecraft.world.item.ItemStack(item));
        }

        List<Component> footer = new ArrayList<>();
        if (def.cost() > 0) {
            boolean complete = currentIsCompleted(def.id());
            if (complete) {
                footer.add(Component.translatable("bannerbound.research.node.completed")
                    .withStyle(ChatFormatting.YELLOW));
            } else {
                double progress = currentProgress(def.id());
                double remaining = Math.max(0.0, def.cost() - progress);
                String costText = String.format("%.1f / %.1f ", progress, def.cost());
                double rate = activeTab == Tab.CULTURE
                    ? ClientCultureState.getCulturePerSecond()
                    : ClientResearchState.getSciencePerSecond();
                String timePrefix = "(" + formatTimeRemaining(remaining, rate) + ") ";
                MutableComponent line = Component.literal(timePrefix + costText).withStyle(ChatFormatting.YELLOW)
                    .append(activeTab == Tab.CULTURE ? Icons.culture() : Icons.science());
                footer.add(line);
            }
        }
        int queuePos = currentQueuePosition(def.id());
        if (queuePos > 1) {
            footer.add(Component.translatable("bannerbound.research.queue_position", queuePos)
                .withStyle(ChatFormatting.AQUA));
        }
        if (!currentIsCompleted(def.id())) {
            if (isFutureEra(def)) {
                footer.add(Component.translatable("bannerbound.research.age_locked",
                        def.minAge().displayName())
                    .withStyle(ChatFormatting.RED));
            }
            if (def.requiresTribe() && !ClientPopulationState.isTribe()) {
                footer.add(Component.translatable("bannerbound.research.tribe_locked")
                    .withStyle(ChatFormatting.RED));
            }
        }
        if (!currentPrereqsMet(def) && !currentIsCompleted(def.id())) {
            footer.add(Component.translatable("bannerbound.research.prereq_locked").withStyle(ChatFormatting.RED));
        }

        if (!def.ponderScene().isEmpty() && ResearchPonderBridge.isAvailable()) {
            footer.add(ResearchPonderBridge.holdToPonderHint(this.font));
        }

        Component itemsHeader = items.isEmpty() ? null
            : Component.translatable("bannerbound.research.node.unlocked_items")
                .withStyle(ChatFormatting.GRAY);
        renderCustomTooltip(graphics, header, itemsHeader, items, footer, mouseX, mouseY);
    }

    private void renderInsightTooltip(GuiGraphics graphics, ResearchDefinition def, int mouseX, int mouseY) {
        com.bannerbound.core.api.research.InsightDefinition insight = def.insight();
        if (insight == null) return;

        int accent = activeTab == Tab.FAITH ? 0xFFD9A94A
            : activeTab == Tab.CULTURE ? 0xFFD055E0 : 0xFF55B0FF;

        List<Component> header = new ArrayList<>();
        header.add(Component.literal(formatInsight(insight)).withColor(accent));

        if (insight.boostPoints() > 0) {
            header.add(Component.literal(String.format("Grants +%.0f research progress.", insight.boostPoints()))
                .withStyle(ChatFormatting.GRAY));
        } else if (insight.boostFraction() > 0) {
            header.add(Component.literal(String.format("Grants a head start worth %.0f%% of this research.",
                    insight.boostFraction() * 100))
                .withStyle(ChatFormatting.GRAY));
        }

        boolean fired = currentInsightFired(def.id());
        if (fired) {
            header.add(Component.literal("\u2714 Discovered").withStyle(ChatFormatting.GREEN));
        } else {
            int target = Math.max(1, insight.trigger().count());
            int count = (int) Math.min(target, Math.max(0, Math.floor(currentInsightProgress(def.id()))));
            header.add(Component.translatable("bannerbound.research.node.progress", count, target)
                .withStyle(ChatFormatting.YELLOW));
        }

        renderCustomTooltip(graphics, header, null, java.util.List.of(), java.util.List.of(), mouseX, mouseY);
    }

    private void drawInsightLabel(GuiGraphics graphics, ResearchDefinition def, int x, int y) {
        if (def.insight() == null || isFutureEra(def)) return;

        boolean fired = currentInsightFired(def.id());
        double count = currentInsightProgress(def.id());
        double target = Math.max(1, def.insight().trigger().count());
        double fraction = fired ? 1.0 : Math.max(0.0, Math.min(1.0, count / target));

        int accent = activeTab == Tab.FAITH ? 0xFFD9A94A
            : activeTab == Tab.CULTURE ? 0xFFD055E0 : 0xFF55B0FF;
        int fill = activeTab == Tab.FAITH ? 0xFF4A3B1D
            : activeTab == Tab.CULTURE ? 0xFF3B174A : 0xFF17364D;
        graphics.fill(x, y, x + NODE_WIDTH, y + INSIGHT_LABEL_HEIGHT, 0xFF111319);
        int fillWidth = (int) Math.round((NODE_WIDTH - 2) * fraction);
        if (fillWidth > 0) {
            graphics.fill(x + 1, y + 1, x + 1 + fillWidth, y + INSIGHT_LABEL_HEIGHT - 1, fill);
        }

        if (fired) {
            float pulse = com.bannerbound.core.Config.UI_ANIMATIONS.get()
                ? 0.65f + 0.35f * (float) Math.sin(net.minecraft.Util.getMillis() / 320.0)
                : 1.0f;
            graphics.renderOutline(x - 2, y - 2, NODE_WIDTH + 4, INSIGHT_LABEL_HEIGHT + 4,
                brighten(accent, pulse * 0.35f));
        }
        graphics.renderOutline(x, y, NODE_WIDTH, INSIGHT_LABEL_HEIGHT, fired ? accent : 0xFF55585F);

        String label = formatInsight(def.insight());
        int maxWidth = NODE_WIDTH - 6;
        if (this.font.width(label) > maxWidth) {
            label = this.font.plainSubstrByWidth(label, maxWidth - this.font.width("..")) + "..";
        }
        int textColor = fired ? 0xFFFFFFB0 : fraction > 0 ? 0xFFFFFFFF : 0xFF9A9DA5;
        graphics.drawString(this.font, label, x + 3,
            y + (INSIGHT_LABEL_HEIGHT - this.font.lineHeight) / 2, textColor, false);
    }

    private static String formatInsight(com.bannerbound.core.api.research.InsightDefinition insight) {
        var trigger = insight.trigger();
        String target = trigger.target();
        if (target.startsWith("#")) target = target.substring(1);
        int colon = target.indexOf(':');
        if (colon >= 0) target = target.substring(colon + 1);
        target = target.replace('_', ' ');
        String action = switch (trigger.type()) {
            case "mine_block" -> "Mine";
            case "kill_entity" -> "Defeat";
            case "place_block" -> "Place";
            case "claim_chunk" -> "Claim";
            case "reach_population" -> "Reach population";
            case "obtain_item" -> "Obtain";
            case "breed_animal" -> "Breed";
            default -> trigger.type().replace('_', ' ');
        };

        if (trigger.type().equals("breed_animal") && target.isEmpty()) target = "animals";
        String condition = trigger.type().equals("reach_population")
            ? action + " " + trigger.count()
            : action + " " + trigger.count() + (target.isEmpty() ? "" : " " + target);
        return "Insight: " + condition;
    }

    private static final int MAX_TOOLTIP_TEXT_W = 260;

    private List<net.minecraft.util.FormattedCharSequence> wrapLines(List<Component> lines) {
        List<net.minecraft.util.FormattedCharSequence> out = new ArrayList<>();
        for (Component c : lines) {
            List<net.minecraft.util.FormattedCharSequence> split = this.font.split(c, MAX_TOOLTIP_TEXT_W);
            if (split.isEmpty()) out.add(c.getVisualOrderText());
            else out.addAll(split);
        }
        return out;
    }

    private void renderCustomTooltip(GuiGraphics graphics, List<Component> header,
                                      Component itemsHeader, List<net.minecraft.world.item.ItemStack> items,
                                      List<Component> footer, int mouseX, int mouseY) {
        int lineH = this.font.lineHeight + 1;
        int itemRows = items.isEmpty() ? 0 : (int) Math.ceil(items.size() / (double) ITEMS_PER_ROW);
        int itemsBlockH = items.isEmpty() ? 0 : (lineH + itemRows * ITEM_CELL + 2);

        List<net.minecraft.util.FormattedCharSequence> headerLines = wrapLines(header);
        List<net.minecraft.util.FormattedCharSequence> footerLines = wrapLines(footer);

        int widestText = 0;
        for (net.minecraft.util.FormattedCharSequence c : headerLines) widestText = Math.max(widestText, this.font.width(c));
        for (net.minecraft.util.FormattedCharSequence c : footerLines) widestText = Math.max(widestText, this.font.width(c));
        if (itemsHeader != null) widestText = Math.max(widestText, this.font.width(itemsHeader));
        int itemsGridW = items.isEmpty() ? 0 : Math.min(items.size(), ITEMS_PER_ROW) * ITEM_CELL;
        int contentW = Math.max(widestText, itemsGridW);
        int panelW = contentW + 8;
        int panelH = headerLines.size() * lineH + itemsBlockH + footerLines.size() * lineH + 8;

        int panelX = mouseX + 12;
        int panelY = mouseY - panelH;
        if (panelX + panelW > this.width) panelX = this.width - panelW - 2;
        if (panelY < 2) panelY = mouseY + 12;
        if (panelY + panelH > this.height) panelY = this.height - panelH - 2;

        graphics.pose().pushPose();
        // Depth (not insertion order) keeps this above node text/badges; vanilla tooltips sit at ~400.
        graphics.pose().translate(0, 0, 400);
        try {

            graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF101010);
            graphics.renderOutline(panelX, panelY, panelW, panelH, 0xFF606060);

            int textX = panelX + 4;
            int y = panelY + 4;
            for (net.minecraft.util.FormattedCharSequence c : headerLines) {
                graphics.drawString(this.font, c, textX, y, 0xFFFFFFFF, false);
                y += lineH;
            }
            if (!items.isEmpty()) {
                graphics.drawString(this.font, itemsHeader, textX, y, 0xFFCCCCCC, false);
                y += lineH;

                // Bypass the global unknown-item swap so locked unlocks preview as real icons; reset in the finally.
                UnknownItemHelper.setBypassUnknownSwap(true);
                try {
                    for (int i = 0; i < items.size(); i++) {
                        int col = i % ITEMS_PER_ROW;
                        int row = i / ITEMS_PER_ROW;
                        int ix = textX + col * ITEM_CELL;
                        int iy = y + row * ITEM_CELL;
                        graphics.renderItem(items.get(i), ix, iy);
                    }
                } finally {
                    UnknownItemHelper.setBypassUnknownSwap(false);
                }
                y += itemRows * ITEM_CELL + 2;
            }
            for (net.minecraft.util.FormattedCharSequence c : footerLines) {
                graphics.drawString(this.font, c, textX, y, 0xFFFFFFFF, false);
                y += lineH;
            }
        } finally {
            graphics.pose().popPose();
        }
    }

    private static void appendEffectLines(ResearchDefinition def, List<Component> lines) {
        for (String feature : def.unlocksFeatures()) {
            Component line = describeFeature(feature);
            if (line != null) {
                lines.add(line);
            }
        }

        for (String flag : def.unlocksFlags()) {
            Component line = describeFeature(flag);
            if (line != null) {
                lines.add(line);
            }
        }
    }

    private static Component describeFeature(String feature) {
        if (feature.startsWith("bannerbound.food_capacity_delta:")) {
            return Component.literal("+" + feature.substring("bannerbound.food_capacity_delta:".length()) + " ")
                .append(Icons.food())
                .append(Component.literal(" capacity"))
                .withStyle(ChatFormatting.GREEN);
        }
        if (feature.startsWith("bannerbound.culture_capacity_delta:")) {
            return Component.literal("+" + feature.substring("bannerbound.culture_capacity_delta:".length()) + " ")
                .append(Icons.culture())
                .append(Component.literal(" capacity"))
                .withStyle(ChatFormatting.GREEN);
        }
        if (feature.startsWith("bannerbound.science_per_second_delta:")) {
            return Component.literal("+" + feature.substring("bannerbound.science_per_second_delta:".length()) + " ")
                .append(Icons.science())
                .append(Component.literal("/s"))
                .withStyle(ChatFormatting.GREEN);
        }
        if (feature.startsWith("bannerbound.food_per_second_delta:")) {
            return Component.literal("+" + feature.substring("bannerbound.food_per_second_delta:".length()) + " ")
                .append(Icons.food())
                .append(Component.literal("/s"))
                .withStyle(ChatFormatting.GREEN);
        }
        if (feature.startsWith("bannerbound.culture_per_second_delta:")) {
            return Component.literal("+" + feature.substring("bannerbound.culture_per_second_delta:".length()) + " ")
                .append(Icons.culture())
                .append(Component.literal("/s"))
                .withStyle(ChatFormatting.GREEN);
        }
        if (feature.startsWith("bannerbound.citizen_speed_delta:")) {
            return Component.literal("+" + feature.substring("bannerbound.citizen_speed_delta:".length())
                    + " citizen speed")
                .withStyle(ChatFormatting.GREEN);
        }
        if (feature.startsWith("bannerbound.advance_age:")) {
            String eraKey = feature.substring("bannerbound.advance_age:".length());
            com.bannerbound.core.api.settlement.Era era =
                com.bannerbound.core.api.settlement.Era.fromName(eraKey);
            Component eraName = era != null ? era.displayName() : Component.literal(eraKey);
            return Component.translatable("bannerbound.research.effect.advance_age", eraName)
                .withStyle(ChatFormatting.GREEN);
        }
        if (feature.startsWith("bannerbound.set_tool_age:")) {
            String ageId = feature.substring("bannerbound.set_tool_age:".length());
            com.bannerbound.core.api.research.ToolAge age =
                com.bannerbound.core.api.research.data.ToolAgeLoader.get(ageId);
            Component ageName = age != null ? age.displayName() : Component.literal(ageId);
            return Component.translatable("bannerbound.research.effect.set_tool_age", ageName)
                .withStyle(ChatFormatting.GREEN);
        }

        if (feature.startsWith("bannerbound.showore:")) {
            String itemId = feature.substring("bannerbound.showore:".length());
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            Component itemName;
            if (rl != null) {
                net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
                itemName = item == net.minecraft.world.item.Items.AIR
                    ? Component.literal(itemId)
                    : item.getDescription();
            } else {
                itemName = Component.literal(itemId);
            }
            return Component.translatable("bannerbound.research.effect.showore", itemName)
                .withStyle(ChatFormatting.GREEN);
        }

        return autoDescribeByLangKey(feature);
    }

    private static Component autoDescribeByLangKey(String feature) {

        int colon = feature.indexOf(':');
        String prefix = colon >= 0 ? feature.substring(0, colon) : feature;
        String arg = colon >= 0 ? feature.substring(colon + 1) : "";
        int lastDot = prefix.lastIndexOf('.');
        String suffix = lastDot >= 0 ? prefix.substring(lastDot + 1) : prefix;
        String langKey = "bannerbound.research.effect." + suffix;
        if (net.minecraft.client.resources.language.I18n.exists(langKey)) {
            return arg.isEmpty()
                ? Component.translatable(langKey).withStyle(ChatFormatting.GREEN)
                : Component.translatable(langKey, arg).withStyle(ChatFormatting.GREEN);
        }

        return null;
    }

    private static String formatTimeRemaining(double remaining, double ratePerSecond) {
        if (ratePerSecond <= 0.0) {
            return "∞";
        }
        int total = (int) Math.ceil(remaining / ratePerSecond);
        if (total <= 0) {
            return "0s";
        }
        int h = total / 3600;
        int m = (total / 60) % 60;
        int s = total % 60;
        if (h > 0) return String.format("%d:%02d:%02dh", h, m, s);
        if (m > 0) return String.format("%d:%02dm", m, s);
        return String.format("%ds", s);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        tickPanZoomEase();

        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        boolean animate = com.bannerbound.core.Config.UI_ANIMATIONS.get();
        float open = animate
            ? easeOutCubic(Math.min(1f, (net.minecraft.Util.getMillis() - openedAtMs) / 160f)) : 1f;
        boolean posed = animate && open < 1f;
        if (posed) {
            float scale = 0.96f + 0.04f * open;
            float cx = this.width / 2f;
            float cy = this.height / 2f;
            graphics.pose().pushPose();
            graphics.pose().translate(cx, cy, 0);
            graphics.pose().scale(scale, scale, 1f);
            graphics.pose().translate(-cx, -cy + (1f - open) * 10f, 0);
        }
        for (net.minecraft.client.gui.components.Renderable renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
        if (posed) {
            graphics.pose().popPose();
        }

        String hoveredIdNow = hovered == null ? null : hovered.id();
        if (hoveredIdNow != null && !hoveredIdNow.equals(lastHoveredId)) {
            tooltipShownAtMs = net.minecraft.Util.getMillis();
        }
        lastHoveredId = hoveredIdNow;
        if (hovered != null) {
            float pop = animate
                ? easeOutCubic(Math.min(1f, (net.minecraft.Util.getMillis() - tooltipShownAtMs) / 120f))
                : 1f;
            boolean popping = pop < 1f;
            if (popping) {
                float popScale = 0.92f + 0.08f * pop;
                graphics.pose().pushPose();
                graphics.pose().translate(mouseX, mouseY, 0);
                graphics.pose().scale(popScale, popScale, 1f);
                graphics.pose().translate(-mouseX, -mouseY, 0);
            }

            if (hoveredInsight != null) {
                renderInsightTooltip(graphics, hoveredInsight, mouseX, mouseY);
            } else {
                renderNodeTooltip(graphics, hovered, mouseX, mouseY);
            }
            if (popping) {
                graphics.pose().popPose();
            }
        }
        feedback.render(graphics);
    }

    private void tickPanZoomEase() {
        long now = net.minecraft.Util.getMillis();
        double dt = (now - lastEaseMs) / 1000.0;
        lastEaseMs = now;
        lastFrameDt = dt;
        if (!com.bannerbound.core.Config.UI_ANIMATIONS.get()) {
            panX = panXTarget;
            panY = panYTarget;
            zoom = zoomTarget;
            return;
        }
        double alpha = 1.0 - Math.exp(-dt / 0.08);
        panX += (panXTarget - panX) * alpha;
        panY += (panYTarget - panY) * alpha;
        zoom += (zoomTarget - zoom) * alpha;
        if (Math.abs(panXTarget - panX) < 0.1) panX = panXTarget;
        if (Math.abs(panYTarget - panY) < 0.1) panY = panYTarget;
        if (Math.abs(zoomTarget - zoom) < 0.001) zoom = zoomTarget;
    }

    private static float easeOutCubic(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    private static int brighten(int argb, float f) {
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        r += (int) ((255 - r) * f);
        g += (int) ((255 - g) * f);
        b += (int) ((255 - b) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpColor(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int fa = from >>> 24, fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int ta = to >>> 24,   tr = (to >> 16) & 0xFF,   tg = (to >> 8) & 0xFF,   tb = to & 0xFF;
        int a = fa + (int) ((ta - fa) * t);
        int r = fr + (int) ((tr - fr) * t);
        int g = fg + (int) ((tg - fg) * t);
        int b = fb + (int) ((tb - fb) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int withAlpha(int argb, float factor) {
        int a = (int) (((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private void playClickSound() {
        Minecraft mc = this.minecraft;
        if (mc != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f));
        }
    }

    private void sendSuggestOrRetract(String nodeId) {
        java.util.UUID me = this.minecraft != null && this.minecraft.player != null
            ? this.minecraft.player.getUUID() : null;
        boolean culture = activeTab == Tab.CULTURE;
        java.util.List<java.util.UUID> suggesters = culture
            ? com.bannerbound.core.client.ClientSuggestionState.getCultureSuggesters(nodeId)
            : com.bannerbound.core.client.ClientSuggestionState.getScienceSuggesters(nodeId);
        boolean already = me != null && suggesters.contains(me);
        if (already) {
            int kind = culture
                ? com.bannerbound.core.network.RetractSuggestionPayload.KIND_CULTURE
                : com.bannerbound.core.network.RetractSuggestionPayload.KIND_SCIENCE;
            PacketDistributor.sendToServer(
                new com.bannerbound.core.network.RetractSuggestionPayload(kind, nodeId));
        } else {
            int suggestTreeType = culture
                ? com.bannerbound.core.network.SuggestResearchPayload.TREE_CULTURE
                : com.bannerbound.core.network.SuggestResearchPayload.TREE_SCIENCE;
            PacketDistributor.sendToServer(
                new com.bannerbound.core.network.SuggestResearchPayload(nodeId, suggestTreeType));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (hovered != null) {
            boolean isComplete = currentIsCompleted(hovered.id());
            boolean isActive = currentIsActive(hovered.id());
            boolean prereqMet = currentPrereqsMet(hovered);
            boolean ageMet = currentAgeMet(hovered);

            boolean chiefdom = ClientPopulationState.getGovernmentOrdinal()
                == com.bannerbound.core.api.settlement.Settlement.Government.CHIEFDOM.ordinal();
            boolean suggestMode = chiefdom && !ClientPopulationState.isPlayerChief();

            if (button == 0) {
                if (!isComplete && !isActive && prereqMet && ageMet) {
                    if (activeTab == Tab.FAITH) {

                        PacketDistributor.sendToServer(
                            new com.bannerbound.core.network.StartFaithResearchPayload(hovered.id()));
                    } else if (suggestMode) {
                        sendSuggestOrRetract(hovered.id());
                        feedback.spawn((int) mouseX, (int) mouseY);
                    } else if (activeTab == Tab.CULTURE) {
                        PacketDistributor.sendToServer(new com.bannerbound.core.network.StartCultureResearchPayload(hovered.id()));
                    } else {
                        PacketDistributor.sendToServer(new StartResearchPayload(hovered.id()));
                    }
                    playClickSound();
                    return true;
                }
            } else if (button == 1) {
                if (!isComplete && activeTab == Tab.FAITH) {
                    PacketDistributor.sendToServer(
                        new com.bannerbound.core.network.EnqueueFaithResearchPayload(hovered.id()));
                    playClickSound();
                    return true;
                }
                if (!isComplete && activeTab != Tab.FAITH) {
                    if (suggestMode) {
                        sendSuggestOrRetract(hovered.id());
                        feedback.spawn((int) mouseX, (int) mouseY);
                    } else if (activeTab == Tab.CULTURE) {
                        PacketDistributor.sendToServer(new com.bannerbound.core.network.EnqueueCultureResearchPayload(hovered.id()));
                    } else {
                        PacketDistributor.sendToServer(new EnqueueResearchPayload(hovered.id()));
                    }
                    playClickSound();
                    return true;
                }
            }
        }
        if (button == 0) {
            int bx = boardX(), by = boardY();
            if (mouseX >= bx && mouseX < bx + boardWidth() && mouseY >= by && mouseY < by + boardHeight()) {
                dragging = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            dragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {

        if (ResearchPonderBridge.beginHold(hoveredPonderSceneId(), keyCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (ResearchPonderBridge.cancelHold(keyCode)) {
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        super.tick();
        ResearchPonderBridge.tickHold(hoveredPonderSceneId());
    }

    private String hoveredPonderSceneId() {
        if (hovered == null) return null;
        String id = hovered.ponderScene();
        return id.isEmpty() ? null : id;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {

            panX += dragX;
            panY += dragY;
            panXTarget = panX;
            panYTarget = panY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {

        int bx = boardX(), by = boardY(), bw = boardWidth(), bh = boardHeight();
        if (scrollY == 0 || mouseX < bx || mouseX >= bx + bw || mouseY < by || mouseY >= by + bh) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        double oldZoom = zoomTarget;
        double newZoom = scrollY > 0 ? zoomTarget * ZOOM_FACTOR : zoomTarget / ZOOM_FACTOR;
        newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        if (newZoom == oldZoom) return true;

        double worldX = (mouseX - (boardX() + bw / 2.0 + panXTarget)) / oldZoom;
        double worldY = (mouseY - (boardY() + bh / 2.0 + panYTarget)) / oldZoom;
        panXTarget = mouseX - (boardX() + bw / 2.0) - worldX * newZoom;
        panYTarget = mouseY - (boardY() + bh / 2.0) - worldY * newZoom;
        zoomTarget = newZoom;
        return true;
    }

    private static void drawLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {

        if (x1 == x2) {
            graphics.fill(x1, Math.min(y1, y2), x1 + 1, Math.max(y1, y2) + 1, color);
            return;
        }
        int dir = x2 > x1 ? 1 : -1;
        int elbowX = x1 + dir * (NODE_WIDTH / 2 + ELBOW_GAP);

        elbowX = dir > 0 ? Math.min(elbowX, x2) : Math.max(elbowX, x2);

        int dirH1 = Integer.signum(elbowX - x1);
        int dirV  = Integer.signum(y2 - y1);
        int dirH2 = Integer.signum(x2 - elbowX);
        if (dirV == 0) {
            graphics.fill(Math.min(x1, x2), y1, Math.max(x1, x2) + 1, y1 + 1, color);
            return;
        }

        int r1 = Math.min(EDGE_CORNER_RADIUS, Math.min(Math.abs(elbowX - x1), Math.abs(y2 - y1) / 2));
        int r2 = Math.min(EDGE_CORNER_RADIUS, Math.min(Math.abs(x2 - elbowX), Math.abs(y2 - y1) / 2));

        graphics.fill(Math.min(x1, elbowX - dirH1 * r1), y1,
                      Math.max(x1, elbowX - dirH1 * r1) + 1, y1 + 1, color);

        if (r1 > 0) drawFillet(graphics, elbowX - dirH1 * r1, y1 + dirV * r1,
                               elbowX - dirH1 * r1, y1, elbowX, y1 + dirV * r1, color);

        graphics.fill(elbowX, Math.min(y1 + dirV * r1, y2 - dirV * r2),
                      elbowX + 1, Math.max(y1 + dirV * r1, y2 - dirV * r2) + 1, color);

        if (r2 > 0) drawFillet(graphics, elbowX + dirH2 * r2, y2 - dirV * r2,
                               elbowX, y2 - dirV * r2, elbowX + dirH2 * r2, y2, color);

        graphics.fill(Math.min(elbowX + dirH2 * r2, x2), y2,
                      Math.max(elbowX + dirH2 * r2, x2) + 1, y2 + 1, color);
    }

    private static final int EDGE_CORNER_RADIUS = 7;

    private static void drawFillet(GuiGraphics graphics, int cx, int cy, int ax, int ay,
                                   int bx, int by, int color) {
        double a0 = Math.atan2(ay - cy, ax - cx);
        double a1 = Math.atan2(by - cy, bx - cx);
        double d = a1 - a0;
        while (d <= -Math.PI) d += 2 * Math.PI;
        while (d > Math.PI) d -= 2 * Math.PI;
        double r = Math.hypot(ax - cx, ay - cy);
        int segs = Math.max(4, (int) r);
        int px = ax, py = ay;
        for (int i = 1; i <= segs; i++) {
            double a = a0 + d * (i / (double) segs);
            int nx = (int) Math.round(cx + Math.cos(a) * r);
            int ny = (int) Math.round(cy + Math.sin(a) * r);
            drawSegment(graphics, px, py, nx, ny, color);
            px = nx; py = ny;
        }
    }

    private static void drawPrereqEdge(GuiGraphics graphics, int x1, int y1, int x2, int y2,
                                       int color, boolean emphasize, boolean diagonal) {
        if (diagonal) {
            drawPrereqEdgeDiagonal(graphics, x1, y1, x2, y2, color, emphasize);
            return;
        }
        drawLine(graphics, x1, y1, x2, y2, color);
        int s = emphasize ? 5 : 4;
        if (x1 != x2) {

            int dir = Integer.signum(x2 - (x1 + x2) / 2);
            if (dir == 0) dir = 1;
            int tipX = x2 - dir * (NODE_WIDTH / 2);
            drawSegment(graphics, tipX, y2, tipX - dir * s, y2 - s, color);
            drawSegment(graphics, tipX, y2, tipX - dir * s, y2 + s, color);
            if (emphasize) {
                drawSegment(graphics, tipX - dir, y2, tipX - dir * (s + 1), y2 - s, color);
                drawSegment(graphics, tipX - dir, y2, tipX - dir * (s + 1), y2 + s, color);
            }
        } else {

            int dir = Integer.signum(y2 - y1);
            if (dir == 0) dir = 1;
            int tipY = y2 - dir * (NODE_HEIGHT / 2);
            drawSegment(graphics, x2, tipY, x2 - s, tipY - dir * s, color);
            drawSegment(graphics, x2, tipY, x2 + s, tipY - dir * s, color);
            if (emphasize) {
                drawSegment(graphics, x2, tipY - dir, x2 - s, tipY - dir * (s + 1), color);
                drawSegment(graphics, x2, tipY - dir, x2 + s, tipY - dir * (s + 1), color);
            }
        }
    }

    private static void drawPrereqEdgeDiagonal(GuiGraphics graphics, int x1, int y1, int x2, int y2,
                                               int color, boolean emphasize) {
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0) return;
        double ux = dx / len, uy = dy / len;

        double hw = NODE_WIDTH / 2.0, hh = NODE_HEIGHT / 2.0;
        double tx = Math.abs(ux) < 1e-6 ? Double.MAX_VALUE : hw / Math.abs(ux);
        double ty = Math.abs(uy) < 1e-6 ? Double.MAX_VALUE : hh / Math.abs(uy);
        double edge = Math.min(tx, ty);
        int tipX = (int) Math.round(x2 - ux * edge);
        int tipY = (int) Math.round(y2 - uy * edge);
        drawSegment(graphics, x1, y1, tipX, tipY, color);

        int s = emphasize ? 7 : 6;
        double spread = 0.5;
        double cos = Math.cos(spread), sin = Math.sin(spread);

        int b1x = (int) Math.round(tipX + s * (-ux * cos - -uy * sin));
        int b1y = (int) Math.round(tipY + s * (-ux * sin + -uy * cos));
        int b2x = (int) Math.round(tipX + s * (-ux * cos + -uy * sin));
        int b2y = (int) Math.round(tipY + s * (-ux * -sin + -uy * cos));
        drawSegment(graphics, tipX, tipY, b1x, b1y, color);
        drawSegment(graphics, tipX, tipY, b2x, b2y, color);
        if (emphasize) {

            int ox = (int) Math.round(ux), oy = (int) Math.round(uy);
            drawSegment(graphics, tipX - ox, tipY - oy, b1x - ox, b1y - oy, color);
            drawSegment(graphics, tipX - ox, tipY - oy, b2x - ox, b2y - oy, color);
        }
    }

    private java.util.Set<String> prereqClosure(String rootId) {
        java.util.Map<String, ResearchDefinition> tree = currentTree();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (!seen.add(id)) continue;
            ResearchDefinition d = tree.get(id);
            if (d == null) continue;
            for (String p : d.prerequisites()) stack.push(p);
        }
        return seen;
    }

    private int highlightLineColor() {
        if (activeTab == Tab.FAITH) return 0xFFFFE8A0;
        return activeTab == Tab.CULTURE ? 0xFFE9A0FF : 0xFF8AD4FF;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
