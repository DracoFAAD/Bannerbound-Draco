package com.bannerbound.core.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

import com.bannerbound.core.network.CodexSyncPayload;
import com.bannerbound.core.network.CodexToastPayload;
import com.bannerbound.core.network.MarkCodexSeenPayload;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client mirror of the server-owned Chronicle (codex) catalog and the current player's read
 * state: categories, entries, and the unlocked/seen sets, all replaced wholesale by
 * {@link CodexSyncPayload} (sorted here for stable display order). Also drives the pop-up toast
 * queue for newly unlocked entries and the auto-pin-tutorial preference; toggling that preference
 * updates locally for instant UI feedback and echoes to the server. {@code OPEN_KEY} (default J)
 * opens the Chronicle screen.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientChronicleState {
    public static final KeyMapping OPEN_KEY = new KeyMapping(
        "key.bannerbound.chronicle", GLFW.GLFW_KEY_J, "key.categories.bannerbound");

    private static final List<CodexSyncPayload.Category> categories = new ArrayList<>();
    private static final List<CodexSyncPayload.Entry> entries = new ArrayList<>();
    private static final Map<String, CodexSyncPayload.Entry> byId = new HashMap<>();
    private static final Set<String> unlocked = new HashSet<>();
    private static final Set<String> seen = new HashSet<>();
    private static final Queue<ToastEntry> toasts = new ArrayDeque<>();
    private static boolean autoPinTutorial = true;
    private static boolean tutorialPopupsEnabled = true;

    private ClientChronicleState() {
    }

    public static void replace(CodexSyncPayload payload) {
        categories.clear();
        categories.addAll(payload.categories());
        categories.sort(Comparator.comparingInt(CodexSyncPayload.Category::order)
            .thenComparing(CodexSyncPayload.Category::title));

        entries.clear();
        entries.addAll(payload.entries());
        entries.sort(Comparator.comparing(CodexSyncPayload.Entry::category)
            .thenComparingInt(CodexSyncPayload.Entry::order)
            .thenComparing(CodexSyncPayload.Entry::title));

        byId.clear();
        for (CodexSyncPayload.Entry entry : entries) byId.put(entry.id(), entry);

        unlocked.clear();
        unlocked.addAll(payload.unlocked());
        seen.clear();
        seen.addAll(payload.seen());
        autoPinTutorial = payload.autoPinTutorial();
        tutorialPopupsEnabled = payload.tutorialPopupsEnabled();
    }

    public static boolean autoPinTutorial() {
        return autoPinTutorial;
    }

    public static void toggleAutoPinTutorial() {
        autoPinTutorial = !autoPinTutorial;
        PacketDistributor.sendToServer(
            new com.bannerbound.core.network.SetAutoPinTutorialPayload(autoPinTutorial));
    }

    public static boolean tutorialPopupsEnabled() {
        return tutorialPopupsEnabled;
    }

    public static void toggleTutorialPopups() {
        tutorialPopupsEnabled = !tutorialPopupsEnabled;
        PacketDistributor.sendToServer(
            new com.bannerbound.core.network.SetTutorialPopupsPayload(tutorialPopupsEnabled));
    }

    public static void enqueueToast(CodexToastPayload payload) {
        toasts.add(new ToastEntry(payload.count(), payload.title(), payload.icon(), net.minecraft.Util.getMillis()));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.35f, 1.35f);
        }
    }

    public static ToastEntry peekToast() {
        ToastEntry entry = toasts.peek();
        if (entry == null) return null;
        long age = net.minecraft.Util.getMillis() - entry.startedAtMs();
        if (age > 4500L) {
            toasts.poll();
            return toasts.peek();
        }
        return entry;
    }

    public static List<CodexSyncPayload.Category> categories() {
        return List.copyOf(categories);
    }

    public static List<CodexSyncPayload.Entry> entries() {
        return List.copyOf(entries);
    }

    public static List<CodexSyncPayload.Entry> visibleEntries(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<CodexSyncPayload.Entry> out = new ArrayList<>();
        for (CodexSyncPayload.Entry entry : entries) {
            // Locked entries never show - no "???" teaser rows - which also hides any category
            // whose entries are all locked (the sidebar only draws categories with visible rows).
            if (!unlocked.contains(entry.id())) continue;
            if (!q.isEmpty() && !entry.searchableText().contains(q)) continue;
            out.add(entry);
        }
        return out;
    }

    public static CodexSyncPayload.Entry get(String id) {
        return byId.get(id);
    }

    public static boolean isUnlocked(String id) {
        return unlocked.contains(id);
    }

    public static boolean isSeen(String id) {
        return seen.contains(id);
    }

    public static boolean isUnread(String id) {
        return isUnlocked(id) && !isSeen(id);
    }

    public static boolean categoryHasUnread(String categoryId) {
        for (CodexSyncPayload.Entry entry : entries) {
            if (entry.category().equals(categoryId) && isUnread(entry.id())) return true;
        }
        return false;
    }

    public static void markSeen(String id) {
        if (id == null || id.isBlank() || !isUnlocked(id) || !seen.add(id)) return;
        PacketDistributor.sendToServer(new MarkCodexSeenPayload(id));
    }

    public record ToastEntry(int count, String title, String icon, long startedAtMs) {
    }
}
