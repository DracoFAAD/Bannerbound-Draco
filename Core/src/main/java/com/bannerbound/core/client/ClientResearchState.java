package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.bannerbound.core.api.research.ResearchDefinition;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the settlement's research/insight state, fed by the research-sync payloads and
 * read by ResearchScreen, ore gating, JEI, and other UI. Holds the research tree, completed nodes,
 * active research + per-node progress, science rate, capacity, the unlocked-item id set, the queue,
 * and insight progress/fired sets. {@code hasFlag} scans completed nodes' {@code unlocks.flags} for
 * persistent-flag checks (ore reveals, breeding gates, workshop-type gating via
 * {@link com.bannerbound.core.api.settlement.WorkstationUnlocks#flagForWorkshopType} -- an
 * un-researched workshop reads as "Unknown Workshop" with assign disabled). Prereq and age checks
 * are cross-tree (a science node may require a culture node and vice versa) and fold the tribe gate
 * into {@code ageMet} so every call site treats it as "locked" uniformly. Knowledge listeners fire
 * when the unlocked-item set changes so JEI-style views can refresh.
 *
 * <p>When completed nodes change, {@code replaceState} rebuilds the disguised-ore set and re-bakes
 * only the chunk sections around the player via {@code setSectionDirty} (deliberately NOT
 * {@code allChanged()}, which tears down GPU resources rather than just queuing a re-mesh).
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientResearchState {
    private static volatile Map<String, ResearchDefinition> tree = Collections.emptyMap();

    private static volatile Set<String> completed = Collections.emptySet();
    private static volatile String activeResearch = "";
    private static volatile Map<String, Double> progress = Collections.emptyMap();
    private static volatile double sciencePerSecond = 0.0;
    private static volatile int capacity = 1;
    private static volatile Set<String> unlockedItemIds = Collections.emptySet();
    private static volatile java.util.List<String> queue = java.util.Collections.emptyList();
    private static volatile Map<String, Double> insightProgress = Collections.emptyMap();
    private static volatile Set<String> firedInsights = Collections.emptySet();
    private static final List<Runnable> KNOWLEDGE_LISTENERS = new CopyOnWriteArrayList<>();

    private ClientResearchState() {
    }

    public static void replaceTree(Map<String, ResearchDefinition> newTree) {
        tree = Map.copyOf(newTree);
    }

    public static void replaceState(Set<String> newCompleted, String newActive, Map<String, Double> newProgress,
                                    double newSciPerSec, int newCapacity, Set<String> newUnlocked,
                                    java.util.List<String> newQueue, Map<String, Double> newInsightProgress,
                                    Set<String> newFiredInsights) {
        Set<String> oldCompleted = completed;
        Set<String> oldUnlocked = unlockedItemIds;
        completed = Set.copyOf(newCompleted);
        activeResearch = newActive == null ? "" : newActive;
        progress = Map.copyOf(newProgress);
        sciencePerSecond = newSciPerSec;
        capacity = newCapacity;
        unlockedItemIds = Set.copyOf(newUnlocked);
        queue = java.util.List.copyOf(newQueue);
        insightProgress = Map.copyOf(newInsightProgress);
        firedInsights = Set.copyOf(newFiredInsights);

        if (!oldCompleted.equals(completed)) {
            ClientOreState.recomputeActiveDisguises();
            ClientOreState.invalidateNearbySections();
        }
        if (!oldUnlocked.equals(unlockedItemIds)) {
            notifyKnowledgeListeners();
        }
    }

    public static void addKnowledgeListener(Runnable listener) {
        if (listener != null) {
            KNOWLEDGE_LISTENERS.add(listener);
        }
    }

    /** Item knowledge is bypassed entirely for creative players, so every knowledge consumer
     *  (JEI hiding, disguises) must refresh when the local gamemode flips - otherwise leaving
     *  creative keeps the everything-visible state until the next research sync. */
    public static void refreshKnowledgeIfGamemodeChanged(boolean creative) {
        if (creative == lastKnownCreative) return;
        lastKnownCreative = creative;
        notifyKnowledgeListeners();
    }

    private static boolean lastKnownCreative;

    public static void removeKnowledgeListener(Runnable listener) {
        KNOWLEDGE_LISTENERS.remove(listener);
    }

    private static void notifyKnowledgeListeners() {
        for (Runnable listener : KNOWLEDGE_LISTENERS) {
            listener.run();
        }
    }

    public static boolean hasFlag(String flag) {
        for (String id : completed) {
            com.bannerbound.core.api.research.ResearchDefinition def = tree.get(id);
            if (def != null && def.unlocksFlags().contains(flag)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isWorkshopTypeKnown(String workshopTypeId) {
        String flag = com.bannerbound.core.api.settlement.WorkstationUnlocks
            .flagForWorkshopType(workshopTypeId);
        return flag == null || hasFlag(flag);
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
    public static double getSciencePerSecond() { return sciencePerSecond; }
    public static int getCapacity() { return capacity; }
    public static int getActiveCount() { return activeResearch.isEmpty() ? 0 : 1; }

    public static boolean isCompleted(String id) { return completed.contains(id); }
    public static boolean isActive(String id) { return activeResearch.equals(id); }
    public static double getInsightProgress(String id) { return insightProgress.getOrDefault(id, 0.0); }
    public static boolean hasFiredInsight(String id) { return firedInsights.contains(id); }

    public static boolean prereqsMet(ResearchDefinition def) {
        for (String prereq : def.prerequisites()) {
            if (!completed.contains(prereq) && !ClientCultureState.isCompleted(prereq)) {
                return false;
            }
        }
        return true;
    }

    public static boolean ageMet(ResearchDefinition def) {
        if (def.requiresTribe() && !ClientPopulationState.isTribe()) return false;
        return ClientEraState.getPlayerEra().ordinal() >= def.minAge().ordinal();
    }

    public static boolean isItemUnlocked(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id != null && unlockedItemIds.contains(id.toString());
    }

    public static void clear() {
        boolean hadKnowledge = !unlockedItemIds.isEmpty();
        completed = new HashSet<>();
        activeResearch = "";
        progress = new HashMap<>();
        sciencePerSecond = 0.0;
        capacity = 1;
        unlockedItemIds = new HashSet<>();
        insightProgress = new HashMap<>();
        firedInsights = new HashSet<>();
        if (hadKnowledge) {
            notifyKnowledgeListeners();
        }
    }
}
