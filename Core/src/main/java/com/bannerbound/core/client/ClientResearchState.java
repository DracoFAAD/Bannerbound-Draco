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
            // Active flags may have changed (ore reveals, etc.) — rebuild the disguised-ore set
            // and re-bake the chunk sections around the player. setSectionDirty is way lighter
            // than allChanged() since it doesn't tear down GPU resources, just queues a re-mesh.
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

    public static void removeKnowledgeListener(Runnable listener) {
        KNOWLEDGE_LISTENERS.remove(listener);
    }

    private static void notifyKnowledgeListeners() {
        for (Runnable listener : KNOWLEDGE_LISTENERS) {
            listener.run();
        }
    }

    /**
     * Returns true if any completed research's {@code unlocks.flags} contains the given flag.
     * Used by ore-disguise gating, animal-breeding gating, and other persistent-flag checks.
     */
    public static boolean hasFlag(String flag) {
        for (String id : completed) {
            com.bannerbound.core.api.research.ResearchDefinition def = tree.get(id);
            if (def != null && def.unlocksFlags().contains(flag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Client mirror of the workshop-type research gate (server side:
     * {@link com.bannerbound.core.api.settlement.WorkstationUnlocks#flagForWorkshopType} +
     * {@code ResearchManager.hasFlag} in {@code WorkshopMenu.handleAssignWorker}). A workshop whose
     * craft the settlement hasn't researched reads as "Unknown Workshop" with assign disabled, so a
     * station pre-placed on the ruins of an old settlement can't be operated before the research is
     * earned. Ungated types (mixed/none, or any type with no declared unit) are always known.
     */
    public static boolean isWorkshopTypeKnown(String workshopTypeId) {
        String flag = com.bannerbound.core.api.settlement.WorkstationUnlocks
            .flagForWorkshopType(workshopTypeId);
        return flag == null || hasFlag(flag);
    }

    public static java.util.List<String> getQueue() { return queue; }

    /**
     * Returns the queue position to display above the node (1 = current active, 2+ = queue
     * order). If nothing is active, the first queued item is shown as [1]. Returns 0 for nodes
     * that are neither active nor queued — caller should skip rendering the badge.
     */
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
            // Cross-tree: a science node may require a culture node (and vice versa).
            if (!completed.contains(prereq) && !ClientCultureState.isCompleted(prereq)) {
                return false;
            }
        }
        return true;
    }

    /** True if the local player's settlement age is at or above the research's min_age AND, for
     *  tribe-gated nodes, the settlement has reached the Tribe stage. Folding the tribe gate into
     *  ageMet keeps every existing call site (node colouring, click gate, tooltip) respecting it
     *  with no extra plumbing — both read as "locked" the same way. */
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
