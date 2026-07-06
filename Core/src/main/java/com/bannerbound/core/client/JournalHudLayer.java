package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.blaze3d.systems.RenderSystem;

import com.bannerbound.core.journal.JournalEntry;
import com.bannerbound.core.network.JournalSyncPayload;

import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Left-side objective tracker, below the era/year HUD. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class JournalHudLayer implements LayeredDraw.Layer {
    public static final JournalHudLayer INSTANCE = new JournalHudLayer();

    private static final int X = 8;
    private static final int Y = 50;
    private static final int BASE_WIDTH = 258;
    private static final int MAX_WIDTH = 376;
    private static final int SCREEN_MARGIN = 10;
    private static final int MIN_TOP = 6;
    private static final int PADDING = 8;
    private static final int MAX_ENTRIES = 4;
    private static final int ENTRY_GAP = 8;
    private static final long EXIT_SLIDE_TICKS = 14L;
    private static final int CRISIS_BORDER = 0xFFD4AF37;
    private static final ResourceLocation CHECKBOX =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/checkbox.png");
    private static final ResourceLocation CHECKBOX_CHECKED =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/checkbox_checked.png");
    private static final Map<UUID, Long> ENTRY_FIRST_SEEN = new HashMap<>();
    private static final Map<String, Long> ROW_FIRST_SEEN = new HashMap<>();
    private static final Map<String, Long> ROW_COMPLETED_SEEN = new HashMap<>();

    private JournalHudLayer() {
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        List<JournalSyncPayload.Entry> entries = ClientJournalState.hudEntries();
        if (entries.isEmpty()) {
            ENTRY_FIRST_SEEN.clear();
            return;
        }

        Font font = mc.font;
        long nowMs = Util.getMillis();
        float minimized = ClientJournalState.minimizeProgress(nowMs);

        // Scale the whole layer down on small scaled resolutions so the panel keeps roughly the same
        // fraction of the screen everywhere — shared with the era/year banner and "Currently in" line
        // above it via HudScale, so the top-left cluster shrinks as one. Anchored at the top-left
        // (X,Y); we lay out against the inflated logical space so the same margins/clamps still apply,
        // then the pose shrinks it.
        float uiScale = HudScale.factor(mc);
        int screenW = Math.round(mc.getWindow().getGuiScaledWidth() / uiScale);
        int screenH = Math.round(mc.getWindow().getGuiScaledHeight() / uiScale);

        graphics.pose().pushPose();
        graphics.pose().scale(uiScale, uiScale, 1f);
        try {
            renderEntries(graphics, font, entries, minimized, nowMs, screenW, screenH);
        } finally {
            graphics.pose().popPose();
        }
    }

    private static void renderEntries(GuiGraphics graphics, Font font, List<JournalSyncPayload.Entry> entries,
                                      float minimized, long nowMs, int screenW, int screenH) {
        if (minimized >= 0.98f) {
            ENTRY_FIRST_SEEN.clear();
            drawMinimizedTab(graphics, font, nowMs, entries.size(), minimized);
            return;
        }
        if (minimized <= 0.02f) ClientJournalState.markRead();
        int y = Y;
        int shown = 0;
        Set<UUID> visible = new HashSet<>();
        long nowTick = ClientJournalState.currentGameTick();
        Set<String> visibleRows = new HashSet<>();
        for (JournalSyncPayload.Entry entry : entries) {
            if (shown >= MAX_ENTRIES) break;
            visible.add(entry.instanceId());
            int availableTop = shown == 0 ? MIN_TOP : y;
            EntryLayout layout = layoutFor(font, entry, screenW, screenH - availableTop - SCREEN_MARGIN);
            int h = layout.height();
            int drawY = y;
            if (shown == 0 && y + h > screenH - SCREEN_MARGIN) {
                drawY = Math.max(MIN_TOP, screenH - SCREEN_MARGIN - h);
            }
            if (shown > 0 && drawY + h > screenH - SCREEN_MARGIN) break;
            long first = ENTRY_FIRST_SEEN.computeIfAbsent(entry.instanceId(), id -> nowMs);
            float t = Math.min(1f, (nowMs - first) / 260f);
            float ease = PolishedScreen.easeOutCubic(t);
            float exit = exitProgress(entry, nowTick);
            int slideX = X - Math.round((1f - ease) * 18f)
                - Math.round(exit * (layout.width() + 46f))
                - Math.round(minimized * (layout.width() - 18f));
            int alpha = 160 + Math.round(75f * ease);
            alpha = Math.round(alpha * (1f - exit * 0.35f) * (1f - minimized * 0.80f));
            drawEntry(graphics, font, entry, slideX, drawY, layout.width(), h, alpha, nowMs, visibleRows);
            y = drawY + h + ENTRY_GAP;
            shown++;
        }
        ENTRY_FIRST_SEEN.keySet().removeIf(id -> !visible.contains(id));
        ROW_FIRST_SEEN.keySet().removeIf(key -> !visibleRows.contains(key));
        ROW_COMPLETED_SEEN.keySet().removeIf(key -> !visibleRows.contains(key));
        if (minimized > 0.02f) {
            drawMinimizedTab(graphics, font, nowMs, entries.size(), minimized);
        }
    }

    private static EntryLayout layoutFor(Font font, JournalSyncPayload.Entry entry, int screenW, int availableH) {
        int maxW = Math.max(160, Math.min(MAX_WIDTH, screenW - X - SCREEN_MARGIN));
        int width = Math.min(BASE_WIDTH, maxW);
        int height = entryHeight(font, entry, width);
        if (height <= availableH || width >= maxW) {
            return new EntryLayout(width, height);
        }
        for (int candidate = width + 24; candidate <= maxW; candidate += 24) {
            int candidateHeight = entryHeight(font, entry, candidate);
            width = candidate;
            height = candidateHeight;
            if (candidateHeight <= availableH) break;
        }
        if (width < maxW && height > availableH) {
            width = maxW;
            height = entryHeight(font, entry, width);
        }
        return new EntryLayout(width, height);
    }

    private static int entryHeight(Font font, JournalSyncPayload.Entry entry, int width) {
        int innerW = width - PADDING * 2;
        int h = PADDING + font.lineHeight + 3;
        h += wrappedHeight(font, Component.literal(entry.title()), innerW);
        if (!entry.subtitle().isBlank()) {
            h += 2 + wrappedHeight(font, Component.literal(entry.subtitle()), innerW);
        }
        h += 5;
        for (HudRow row : rowsFor(entry)) {
            int textW = row.subStep() ? innerW - 26 : innerW - 17;
            h += Math.max(font.lineHeight + 2, wrappedHeight(font, Component.literal(row.text()), textW) + 2);
        }
        return h + PADDING - 2;
    }

    private static float exitProgress(JournalSyncPayload.Entry entry, long nowTick) {
        if (!entry.resolved()) return 0f;
        long age = Math.max(0L, nowTick - entry.resolvedTick());
        long start = Math.max(0L, JournalEntry.HUD_RESOLVED_LINGER_TICKS - EXIT_SLIDE_TICKS);
        if (age <= start) return 0f;
        return PolishedScreen.easeOutCubic(Math.min(1f, (age - start) / (float) EXIT_SLIDE_TICKS));
    }

    private static int wrappedHeight(Font font, Component text, int width) {
        return Math.max(1, font.split(text, Math.max(1, width)).size()) * (font.lineHeight + 1);
    }

    private static void drawEntry(GuiGraphics graphics, Font font, JournalSyncPayload.Entry entry,
                                  int x, int y, int width, int h, int alpha, long nowMs, Set<String> visibleRows) {
        // Light, transparent panel (was a near-opaque box + full outline border that "hid the
        // view too much"). Keep only the slim left accent bar so the tracker reads without
        // blocking the world behind it.
        int bgAlpha = Math.min(120, Math.max(35, alpha - 70));
        // Quests (e.g. driving out a barbarian camp) read RED, not the calm green of a tutorial.
        int accent = "CRISIS".equals(entry.type()) ? 0xFFD4AF37
            : "QUEST".equals(entry.type()) ? 0xFFE05050 : 0xFF7BCB6F;
        if (entry.resolved()) accent = entry.failed() ? 0xFFE57761 : 0xFF7BCB6F;

        graphics.fillGradient(x, y, x + width, y + h, withAlpha(0xFF101820, bgAlpha),
            withAlpha(0xFF050808, bgAlpha));
        graphics.fill(x, y, x + 3, y + h, withAlpha(accent, 220));

        int cursorY = y + PADDING;
        graphics.drawString(font, sectionName(entry.type()), x + PADDING, cursorY,
            withAlpha(0xFFB8B2A8, alpha), false);
        cursorY += font.lineHeight + 3;

        int titleColor = "CRISIS".equals(entry.type()) ? 0xFFFFD36A : 0xFFFFFFFF;
        cursorY = drawWrappedLines(graphics, font, Component.literal(entry.title()),
            x + PADDING, cursorY, width - PADDING * 2, withAlpha(titleColor, 255), true);
        if (!entry.subtitle().isBlank()) {
            cursorY += 2;
            cursorY = drawWrappedLines(graphics, font, Component.literal(entry.subtitle()),
                x + PADDING, cursorY, width - PADDING * 2, withAlpha(0xFFC9C0AE, 230), false);
        }
        cursorY += 5;

        for (HudRow row : rowsFor(entry)) {
            cursorY = drawHudRow(graphics, font, row, entry.instanceId() + ":" + row.key(),
                x + PADDING, cursorY, width - PADDING * 2, alpha, nowMs, visibleRows);
        }
    }

    private static void drawMinimizedTab(GuiGraphics graphics, Font font, long nowMs, int count, float progress) {
        boolean unread = ClientJournalState.hasUnreadUpdates();
        boolean pulseWindow = unread && ((nowMs - ClientJournalState.lastUpdatedMs()) % 2400L) < 700L;
        int shake = pulseWindow ? Math.round((float) Math.sin(nowMs / 30.0) * 3f) : 0;
        int x = -10 + shake - Math.round((1f - progress) * 18f);
        int y = Y + 7;
        int w = Math.max(4, Math.round(26f * progress));
        int h = Math.max(6, Math.round(34f * progress));
        int alpha = Math.round(225f * progress);
        graphics.fill(x, y, x + w, y + h, withAlpha(0xFF081010, alpha));
        graphics.fillGradient(x, y, x + w, y + h, withAlpha(0xFF10181A, alpha), withAlpha(0xFF040708, alpha));
        graphics.renderOutline(x, y, w, h, withAlpha(CRISIS_BORDER, alpha));
        graphics.fill(x + w - 3, y + 1, x + w, y + h - 1, withAlpha(CRISIS_BORDER, unread ? alpha : alpha / 2));
        if (unread && progress > 0.75f) {
            graphics.drawString(font, "?", x + 13, y + 6, 0xFFFFF0A0, true);
        }
        if (unread && count > 1 && progress > 0.75f) {
            String s = Integer.toString(Math.min(9, count));
            graphics.drawString(font, s, x + 15, y + 20, 0xFFB8B2A8, false);
        }
    }

    private static int drawHudRow(GuiGraphics graphics, Font font, HudRow row, String rowKey,
                                  int x, int y, int width, int alpha, long nowMs, Set<String> visibleRows) {
        visibleRows.add(rowKey);
        long firstSeen = ROW_FIRST_SEEN.computeIfAbsent(rowKey, key -> nowMs);
        float enter = PolishedScreen.easeOutCubic(Math.min(1f, Math.max(0f, (nowMs - firstSeen) / 220f)));
        int localAlpha = Math.round(alpha * enter);
        int drawX = x - Math.round((1f - enter) * (row.subStep() ? 16f : 10f));
        if (!row.complete()) ROW_COMPLETED_SEEN.remove(rowKey);
        Long completedAt = row.complete() ? ROW_COMPLETED_SEEN.computeIfAbsent(rowKey, key -> nowMs) : null;
        float flourish = completedAt == null ? 0f : Math.max(0f, 1f - (nowMs - completedAt) / 900f);

        int color = row.complete() ? blend(0xFF86E07B, 0xFFFFFFFF, flourish * 0.45f)
            : row.subStep() ? 0xFFCFC7B8 : 0xFFE8E8E8;
        int textX = drawX;
        int textW = width;
        if (flourish > 0f) {
            int fillAlpha = Math.round(75f * flourish * enter);
            graphics.fill(drawX - 2, y - 1, drawX + width, y + font.lineHeight + 2,
                withAlpha(0xFF7BFF79, fillAlpha));
        }
        if (row.subStep()) {
            graphics.fill(drawX + 6, y + 3, drawX + 8, y + 5, withAlpha(0xFFD4AF37, localAlpha));
            graphics.fill(drawX + 8, y + 4, drawX + 16, y + 5, withAlpha(0xFFD4AF37, localAlpha));
            textX = drawX + 22;
            textW = width - 26;
        } else {
            int box = 8;
            drawCheckbox(graphics, row.complete(), drawX, y + 1, box, localAlpha);
            textX = drawX + box + 7;
            textW = width - box - 9;
        }

        int after = drawWrappedLines(graphics, font, Component.literal(row.text()),
            textX, y, textW, withAlpha(color, localAlpha), false);
        return Math.max(y + font.lineHeight + 2, after + 2);
    }

    private static void drawCheckbox(GuiGraphics graphics, boolean complete, int x, int y, int size, int alpha) {
        int color = complete ? 0xFF7BFF79 : 0xFFB8B2A8;
        float a = Math.max(0f, Math.min(1f, alpha / 255f));
        RenderSystem.setShaderColor(((color >> 16) & 0xFF) / 255f,
            ((color >> 8) & 0xFF) / 255f,
            (color & 0xFF) / 255f, a);
        graphics.blit(complete ? CHECKBOX_CHECKED : CHECKBOX, x, y, size, size,
            0f, 0f, 16, 16, 16, 16);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private static int drawWrappedLines(GuiGraphics graphics, Font font, Component text, int x, int y,
                                        int maxWidth, int color, boolean shadow) {
        for (FormattedCharSequence line : font.split(text, Math.max(1, maxWidth))) {
            graphics.drawString(font, line, x, y, color, shadow);
            y += font.lineHeight + 1;
        }
        return y;
    }

    private static List<HudRow> rowsFor(JournalSyncPayload.Entry entry) {
        List<HudRow> rows = new ArrayList<>();
        boolean expanded = false;
        for (JournalSyncPayload.Objective objective : entry.objectives()) {
            rows.add(new HudRow("objective:" + objective.id(), objectiveRowText(entry, objective),
                objective.complete(), false));
            if (!objective.complete() && !expanded && !objective.subSteps().isEmpty()) {
                int index = 0;
                for (String subStep : objective.subSteps()) {
                    if (!subStep.isBlank()) {
                        rows.add(new HudRow("substep:" + objective.id() + ":" + index, subStep, false, true));
                    }
                    index++;
                }
                expanded = true;
            }
        }
        return rows;
    }

    private static String objectiveRowText(JournalSyncPayload.Entry entry,
                                           JournalSyncPayload.Objective objective) {
        // A "reach the target" objective shows a compass bearing computed from the player's LIVE
        // position (not a server-baked one), so it updates as you walk and tracks easily.
        if (!objective.complete() && entry.targetPos() != 0L && isLocator(objective.id())) {
            String dir = directionToTarget(entry.targetPos());
            if (dir != null) return objective.label() + " - to the " + dir;
        }
        return objectiveText(objective);
    }

    private static boolean isLocator(String objectiveId) {
        return "find_camp".equals(objectiveId);
    }

    /** 8-point compass bearing from the client player to a packed BlockPos, or null if unavailable. */
    private static String directionToTarget(long packedTarget) {
        net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return null;
        net.minecraft.core.BlockPos to = net.minecraft.core.BlockPos.of(packedTarget);
        double dx = to.getX() - p.getX();
        double dz = to.getZ() - p.getZ();
        double adx = Math.abs(dx), adz = Math.abs(dz);
        String ns = dz < 0 ? "north" : "south";
        String ew = dx > 0 ? "east" : "west";
        if (adx > adz * 2.414) return ew;   // tan(67.5°) sector split
        if (adz > adx * 2.414) return ns;
        return ns + "-" + ew;
    }

    private static String objectiveText(JournalSyncPayload.Objective objective) {
        if (objective.progressText().isBlank()
                || (objective.complete() && objective.progressText().equalsIgnoreCase("Completed"))) {
            return objective.label();
        }
        return objective.label() + " - " + objective.progressText();
    }

    private static String sectionName(String type) {
        if ("CRISIS".equals(type)) return "Crises";
        if ("TUTORIAL".equals(type)) return "Tutorials";
        return "Quests";
    }

    private static int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static int blend(int from, int to, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        int fr = (from >> 16) & 0xFF;
        int fg = (from >> 8) & 0xFF;
        int fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF;
        int tg = (to >> 8) & 0xFF;
        int tb = to & 0xFF;
        int r = Math.round(fr + (tr - fr) * clamped);
        int g = Math.round(fg + (tg - fg) * clamped);
        int b = Math.round(fb + (tb - fb) * clamped);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private record HudRow(String key, String text, boolean complete, boolean subStep) {}

    private record EntryLayout(int width, int height) {}
}
