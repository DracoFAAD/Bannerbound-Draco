package com.bannerbound.core.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.bannerbound.core.api.research.ResearchDefinition;
import com.bannerbound.core.network.FaithResearchStatePayload;
import com.bannerbound.core.network.ResearchStateSyncPayload;

import org.jetbrains.annotations.ApiStatus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the FAITH tree (third research tab): synced definitions + the
 * per-faith shared state ({@link FaithResearchStatePayload}). Twin of
 * {@link ClientCultureState}, minus the queue (the faith tree has no research queue).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientFaithTreeState {
    private static volatile Map<String, ResearchDefinition> tree = Map.of();
    private static volatile Set<String> completed = Set.of();
    private static volatile String activeResearch = "";
    private static volatile Map<String, Double> progress = Map.of();
    private static volatile double devotionPerSecond;
    private static volatile java.util.List<String> queue = java.util.List.of();
    private static volatile Map<String, Double> insightProgress = Map.of();
    private static volatile Set<String> firedInsights = Set.of();

    private ClientFaithTreeState() {
    }

    public static void replaceTree(Map<String, ResearchDefinition> newTree) {
        tree = Collections.unmodifiableMap(newTree);
    }

    public static void replaceState(FaithResearchStatePayload payload) {
        Set<String> newCompleted = new HashSet<>(payload.completed());
        Map<String, Double> newProgress = new HashMap<>();
        for (ResearchStateSyncPayload.ProgressEntry e : payload.progress()) {
            newProgress.put(e.researchId(), e.progress());
        }
        Map<String, Double> newInsightProgress = new HashMap<>();
        for (ResearchStateSyncPayload.ProgressEntry e : payload.insightProgress()) {
            newInsightProgress.put(e.researchId(), e.progress());
        }
        completed = Collections.unmodifiableSet(newCompleted);
        activeResearch = payload.activeResearch();
        progress = Collections.unmodifiableMap(newProgress);
        devotionPerSecond = payload.devotionPerSecond();
        queue = java.util.List.copyOf(payload.queue());
        insightProgress = Map.copyOf(newInsightProgress);
        firedInsights = Set.copyOf(payload.firedInsights());
    }

    public static void clear() {
        completed = Set.of();
        activeResearch = "";
        progress = Map.of();
        devotionPerSecond = 0;
        queue = java.util.List.of();
        insightProgress = Map.of();
        firedInsights = Set.of();
    }

    /** 1-based queue badge position (active = 1, first queued = 2…), 0 when not queued.
     *  Mirrors ClientCultureState.getQueuePosition. */
    public static int getQueuePosition(String id) {
        if (!activeResearch.isEmpty() && id.equals(activeResearch)) return 1;
        int idx = queue.indexOf(id);
        if (idx < 0) return 0;
        return activeResearch.isEmpty() ? idx + 1 : idx + 2;
    }

    public static Map<String, ResearchDefinition> getTree() {
        return tree;
    }

    public static boolean isCompleted(String id) {
        return completed.contains(id);
    }

    public static boolean isActive(String id) {
        return activeResearch.equals(id);
    }

    public static boolean hasFiredInsight(String id) { return firedInsights.contains(id); }
    public static double getInsightProgress(String id) { return insightProgress.getOrDefault(id, 0.0); }

    public static boolean hasActive() {
        return !activeResearch.isEmpty();
    }

    public static boolean prereqsMet(ResearchDefinition def) {
        for (String prereq : def.prerequisites()) {
            if (!completed.contains(prereq)) return false;
        }
        return true;
    }

    public static double getProgress(String id) {
        return progress.getOrDefault(id, 0.0);
    }

    /** The faith's summed devotion rate — what fills the active node. */
    public static double getDevotionPerSecond() {
        return devotionPerSecond;
    }

    /** Client mirror of FaithManager.pantheonCap: base 1 + one slot per completed faith
     *  node carrying the pantheon_slot flag (tree defs + completed set are both synced). */
    public static int pantheonCap() {
        int cap = 1;
        for (Map.Entry<String, ResearchDefinition> e : tree.entrySet()) {
            if (completed.contains(e.getKey())
                    && e.getValue().unlocksFlags().contains(
                        com.bannerbound.core.api.faith.FaithManager.PANTHEON_SLOT_FLAG)) {
                cap++;
            }
        }
        return cap;
    }
}
