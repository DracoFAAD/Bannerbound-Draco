package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import com.bannerbound.core.api.research.ResearchDefinition;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side mirror of the settlement's Culture-tree state. Twin of
 * {@link ClientResearchState} — separate fields so the {@code ResearchScreen}'s Culture tab
 * can render an independent board with its own active research, progress map, and queue.
 * The same {@link ResearchDefinition} record powers both trees.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientCultureState {
    private static volatile Map<String, ResearchDefinition> tree = Collections.emptyMap();

    private static volatile Set<String> completed = Collections.emptySet();
    private static volatile String activeResearch = "";
    private static volatile Map<String, Double> progress = Collections.emptyMap();
    private static volatile double culturePerSecond = 0.0;
    private static volatile int capacity = 1;
    private static volatile java.util.List<String> queue = java.util.Collections.emptyList();
    private static volatile Map<String, Double> insightProgress = Collections.emptyMap();
    private static volatile Set<String> firedInsights = Collections.emptySet();

    private ClientCultureState() {
    }

    public static void replaceTree(Map<String, ResearchDefinition> newTree) {
        tree = Map.copyOf(newTree);
    }

    public static void replaceState(Set<String> newCompleted, String newActive, Map<String, Double> newProgress,
                                    double newCulPerSec, int newCapacity,
                                    java.util.List<String> newQueue, Map<String, Double> newInsightProgress,
                                    Set<String> newFiredInsights) {
        completed = Set.copyOf(newCompleted);
        activeResearch = newActive == null ? "" : newActive;
        progress = Map.copyOf(newProgress);
        culturePerSecond = newCulPerSec;
        capacity = newCapacity;
        queue = java.util.List.copyOf(newQueue);
        insightProgress = Map.copyOf(newInsightProgress);
        firedInsights = Set.copyOf(newFiredInsights);
    }

    public static java.util.List<String> getQueue() { return queue; }

    public static int getQueuePosition(String id) {
        if (!activeResearch.isEmpty() && id.equals(activeResearch)) return 1;
        int idx = queue.indexOf(id);
        if (idx < 0) return 0;
        return activeResearch.isEmpty() ? idx + 1 : idx + 2;
    }

    public static Map<String, ResearchDefinition> getTree() { return tree; }
    public static Set<String> getCompleted() { return completed; }
    public static String getActiveResearch() { return activeResearch; }
    public static double getProgress(String id) {
        Double v = progress.get(id);
        return v == null ? 0.0 : v;
    }
    public static double getCulturePerSecond() { return culturePerSecond; }
    public static int getCapacity() { return capacity; }
    public static int getActiveCount() { return activeResearch.isEmpty() ? 0 : 1; }

    public static boolean isCompleted(String id) { return completed.contains(id); }
    public static boolean isActive(String id) { return activeResearch.equals(id); }
    public static double getInsightProgress(String id) { return insightProgress.getOrDefault(id, 0.0); }
    public static boolean hasFiredInsight(String id) { return firedInsights.contains(id); }

    public static boolean prereqsMet(ResearchDefinition def) {
        for (String prereq : def.prerequisites()) {
            // Cross-tree: a culture node may require a science node (e.g. Roads ← PAVING).
            if (!completed.contains(prereq) && !ClientResearchState.isCompleted(prereq)) {
                return false;
            }
        }
        return true;
    }

    public static boolean ageMet(ResearchDefinition def) {
        if (def.requiresTribe() && !ClientPopulationState.isTribe()) return false;
        return ClientEraState.getPlayerEra().ordinal() >= def.minAge().ordinal();
    }
}
