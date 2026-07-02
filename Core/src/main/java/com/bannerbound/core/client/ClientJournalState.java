package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

import com.bannerbound.core.journal.JournalEntry;
import com.bannerbound.core.network.JournalSyncPayload;

import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Client mirror of the current player's merged journal snapshot. */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientJournalState {
    // Unbound by default: the old K default collided with Iris Shaders' shader-pack selector
    // (and was unintuitive). Players bind their own key under Controls → Bannerbound.
    public static final KeyMapping TOGGLE_KEY = new KeyMapping(
        "key.bannerbound.journal", GLFW.GLFW_KEY_UNKNOWN, "key.categories.bannerbound");

    private static final List<JournalSyncPayload.Entry> entries = new ArrayList<>();
    private static long gameTick;
    private static boolean minimized;
    private static boolean unreadUpdates;
    private static long lastUpdatedMs;
    private static long minimizeToggledAtMs;

    private ClientJournalState() {
    }

    public static void replace(JournalSyncPayload payload) {
        String before = signature(entries);
        entries.clear();
        entries.addAll(payload.entries());
        entries.sort(Comparator
            .comparing((JournalSyncPayload.Entry e) -> typeOrder(e.type()))
            .thenComparing(Comparator.comparingInt(JournalSyncPayload.Entry::priority).reversed())
            .thenComparingLong(JournalSyncPayload.Entry::createdTick));
        gameTick = payload.gameTick();
        String after = signature(entries);
        if (!after.equals(before) && !hudEntries().isEmpty()) {
            lastUpdatedMs = Util.getMillis();
            if (minimized && !unreadUpdates) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) mc.player.playSound(SoundEvents.BOOK_PAGE_TURN, 0.25f, 0.72f);
            }
            unreadUpdates = minimized;
        }
    }

    public static List<JournalSyncPayload.Entry> entries() {
        return List.copyOf(entries);
    }

    public static List<JournalSyncPayload.Entry> hudEntries() {
        List<JournalSyncPayload.Entry> out = new ArrayList<>();
        long now = currentGameTick();
        for (JournalSyncPayload.Entry entry : entries) {
            if (shouldShowOnHud(entry, now)) out.add(entry);
        }
        return out;
    }

    public static long gameTick() {
        return gameTick;
    }

    public static long currentGameTick() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? gameTick : mc.level.getGameTime();
    }

    public static boolean isMinimized() {
        return minimized;
    }

    public static void toggleMinimized() {
        minimized = !minimized;
        minimizeToggledAtMs = Util.getMillis();
        if (!minimized) markRead();
    }

    public static float minimizeProgress(long nowMs) {
        float t = Math.min(1f, Math.max(0f, (nowMs - minimizeToggledAtMs) / 220f));
        float eased = PolishedScreen.easeOutCubic(t);
        return minimized ? eased : 1f - eased;
    }

    public static boolean hasUnreadUpdates() {
        return unreadUpdates;
    }

    public static boolean isChroniclePinned(String entryId) {
        if (entryId == null || entryId.isBlank()) return false;
        for (JournalSyncPayload.Entry entry : entries) {
            if ("codex_pin".equals(entry.sourceType()) && entryId.equals(entry.sourceId()) && !entry.resolved()) {
                return true;
            }
        }
        return false;
    }

    public static long lastUpdatedMs() {
        return lastUpdatedMs;
    }

    public static void markRead() {
        unreadUpdates = false;
    }

    private static boolean shouldShowOnHud(JournalSyncPayload.Entry entry, long now) {
        if (!entry.resolved()) return entry.showOnHud();
        long age = Math.max(0L, now - entry.resolvedTick());
        return entry.showOnHud() && age <= JournalEntry.HUD_RESOLVED_LINGER_TICKS;
    }

    private static String signature(List<JournalSyncPayload.Entry> snapshot) {
        StringBuilder sb = new StringBuilder();
        for (JournalSyncPayload.Entry entry : snapshot) {
            sb.append(entry.instanceId()).append('|')
                .append(entry.title()).append('|')
                .append(entry.subtitle()).append('|')
                .append(entry.resolved()).append('|')
                .append(entry.failed()).append('|');
            for (JournalSyncPayload.Objective objective : entry.objectives()) {
                sb.append(objective.id()).append(':')
                    .append(objective.label()).append(':')
                    .append(objective.progressText()).append(':')
                    .append(objective.complete()).append(';');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static int typeOrder(String type) {
        if ("CRISIS".equals(type)) return 0;
        if ("QUEST".equals(type)) return 1;
        if ("TUTORIAL".equals(type)) return 2;
        return 3;
    }
}
