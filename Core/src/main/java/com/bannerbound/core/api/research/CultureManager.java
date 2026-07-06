package com.bannerbound.core.api.research;

import java.util.Set;
import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.data.CultureTreeLoader;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.network.CultureStateSyncPayload;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Per-settlement engine for the Culture research tree. Mirror of {@link ResearchManager} â€”
 * same shape, separate state (the culture fields on {@link Settlement}) and a separate
 * datapack ({@link CultureTreeLoader}).
 *
 * <p><b>Mutual exclusion</b>: only one research slot is active across the two trees at any
 * time. {@link #tryStart} refuses to begin a culture research while either the science slot
 * (activeResearch) or the culture slot is occupied; {@link ResearchManager#tryStart} runs
 * the same check on the science side. The simple rule keeps tuning simple â€” players can't
 * stack a science and a culture research in parallel to double the per-second drain.
 *
 * <p>Research rate: culture research accumulates at the settlement's effective
 * {@link Settlement#effectiveCulturePerSecond} per real-time second, same shape as
 * science. Culture-the-resource is NOT depleted (it's a rate, not a stockpile); the
 * cultureStored field continues to feed immigration independently.
 *
 * <p>v1 keeps the surface minimal: tryStart, tryEnqueue, initializeAutoUnlocks, tickAll,
 * broadcast/send state. Polish features ({@code unlocksItems}, regress-on-age-change, etc.)
 * already work via {@link ResearchManager}'s code path for the science tree; the culture
 * tree can grow into those features as content lands.
 */
public final class CultureManager {
    private static int tickCounter = 0;

    private CultureManager() {
    }

    public static boolean isInsightCompletionEligible(Settlement s, ResearchDefinition def) {
        if (def.minAge().ordinal() > s.age().ordinal()) return false;
        if (def.requiresTribe() && !s.isTribe()) return false;
        if (def.governmentType() != null && def.governmentType() != s.governmentType()) return false;
        for (String prereq : def.prerequisites()) {
            if (!s.hasCompletedResearchEitherTree(prereq)) return false;
        }
        return true;
    }

    public static boolean addInsightProgress(MinecraftServer server, Settlement s,
                                             ResearchDefinition def, double points) {
        if (s.hasCompletedCultureResearch(def.id()) || points <= 0.0) return false;
        s.setCultureResearchProgress(def.id(), s.getCultureResearchProgress(def.id()) + points);
        SettlementData.get(server.overworld()).setDirty();
        if (s.getCultureResearchProgress(def.id()) >= def.cost() && isInsightCompletionEligible(s, def)) {
            completeResearch(server, s, def);
            return true;
        }
        broadcastStateToSettlement(server, s);
        return false;
    }

    /** Capacity gating is currently shared 1:1 with science (so the GUI shows the same
     *  active/cap counter on both tabs). If we ever want a distinct culture cap, return a
     *  different formula here without touching callers. */
    public static int getCapacity(Era era) {
        return 1 + era.ordinal();
    }

    public static Set<String> computeUnlockedItems(Settlement settlement) {
        return settlement.computeKnownCultureItems();
    }

    /** Age-regression twin of {@link ResearchManager#regressResearchAfterAgeChange}: culture
     *  nodes whose {@code min_age} is above the settlement's (new, lower) age uncomplete, and
     *  age-locked entries drop from the active slot + queue. Item/flag unlocks shrink with them
     *  (both trees are first-class in ItemKnowledge/hasFlag). Applied scalar bonuses from
     *  {@code unlocks.features} are NOT reversed — same known limitation as the science twin. */
    public static void regressResearchAfterAgeChange(MinecraftServer server, Settlement s) {
        int currentAge = s.age().ordinal();
        boolean changed = false;
        java.util.Set<String> toRemove = new java.util.HashSet<>();
        for (String id : s.completedCultureResearches()) {
            ResearchDefinition def = CultureTreeLoader.get(id);
            if (def != null && def.minAge().ordinal() > currentAge) {
                toRemove.add(id);
            }
        }
        for (String id : toRemove) {
            s.completedCultureResearches().remove(id);
            s.cultureResearchProgress().remove(id);
            changed = true;
        }
        String active = s.activeCultureResearch();
        if (active != null) {
            ResearchDefinition def = CultureTreeLoader.get(active);
            if (def != null && def.minAge().ordinal() > currentAge) {
                s.cultureResearchProgress().remove(active);
                s.setActiveCultureResearch(null);
                changed = true;
            }
        }
        java.util.Iterator<String> qIter = s.cultureResearchQueue().iterator();
        while (qIter.hasNext()) {
            ResearchDefinition def = CultureTreeLoader.get(qIter.next());
            if (def != null && def.minAge().ordinal() > currentAge) {
                qIter.remove();
                changed = true;
            }
        }
        if (changed) {
            s.recomputeKnownItems();
            broadcastStateToSettlement(server, s);
            // Culture unlocks.items ride the science payload's known-item union — refresh it.
            ResearchManager.broadcastStateToSettlement(server, s);
        }
    }

    /** Marks every {@code auto_unlock} culture node complete for {@code settlement} on first
     *  load + after datapack reload. Mirrors {@link ResearchManager#initializeAutoUnlocks}.
     *  No unlock-effect side effects beyond marking complete (no items, no era jump). */
    public static void initializeAutoUnlocks(MinecraftServer server, Settlement settlement) {
        boolean changed = false;
        for (ResearchDefinition def : CultureTreeLoader.getAll().values()) {
            if (def.autoUnlock() && !settlement.hasCompletedCultureResearch(def.id())) {
                settlement.markCultureResearchComplete(def.id());
                changed = true;
            }
        }
        if (changed && server != null) broadcastStateToSettlement(server, settlement);
    }

    /** Sweep every settlement and apply culture auto-unlocks. Twin of
     *  {@link ResearchManager#applyAllAutoUnlocks}; safe to call on datapack reload. */
    public static void applyAllAutoUnlocks(MinecraftServer server) {
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        for (Settlement s : data.all()) {
            initializeAutoUnlocks(server, s);
        }
        data.setDirty();
    }

    public static ResearchManager.StartResult tryStart(ServerPlayer player, String researchId) {
        MinecraftServer server = player.getServer();
        if (server == null) return ResearchManager.StartResult.NOT_IN_SETTLEMENT;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) return ResearchManager.StartResult.NOT_IN_SETTLEMENT;
        ResearchDefinition def = CultureTreeLoader.get(researchId);
        if (def == null) return ResearchManager.StartResult.UNKNOWN_RESEARCH;
        if (s.hasCompletedCultureResearch(researchId)) return ResearchManager.StartResult.ALREADY_COMPLETE;
        for (String prereq : def.prerequisites()) {
            // Cross-tree: a culture node may require a science node (e.g. Roads â† PAVING).
            if (!s.hasCompletedResearchEitherTree(prereq)) return ResearchManager.StartResult.PREREQ_MISSING;
        }
        if (def.minAge().ordinal() > s.age().ordinal()) return ResearchManager.StartResult.AGE_LOCKED;
        if (ResearchManager.isEraCapped(def)) return ResearchManager.StartResult.AGE_LOCKED;
        // Cross-tree transfer: if science is currently researching something, pause it
        // (progress preserved). Switching trees is a one-click action â€” the design is "one
        // research at a time across both," not "you have to manually unqueue first."
        if (s.activeResearch() != null) {
            s.setActiveResearch(null);
            ResearchManager.broadcastStateToSettlement(server, s);
        }
        s.cultureResearchQueue().remove(researchId);
        s.setActiveCultureResearch(researchId);
        // Step 7 polish: clear any non-chief suggestion markers on this node â€” the chief
        // has honoured the suggestion, so the badge has served its purpose.
        s.clearCultureSuggestions(researchId);
        com.bannerbound.core.api.settlement.SettlementManager.broadcastSuggestionState(server, s);
        data.setDirty();
        broadcastStateToSettlement(server, s);
        return ResearchManager.StartResult.OK;
    }

    public static ResearchManager.EnqueueResult tryEnqueue(ServerPlayer player, String researchId) {
        MinecraftServer server = player.getServer();
        if (server == null) return ResearchManager.EnqueueResult.NOT_IN_SETTLEMENT;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) return ResearchManager.EnqueueResult.NOT_IN_SETTLEMENT;
        ResearchDefinition def = CultureTreeLoader.get(researchId);
        if (def == null) return ResearchManager.EnqueueResult.UNKNOWN_RESEARCH;
        if (s.hasCompletedCultureResearch(researchId)) return ResearchManager.EnqueueResult.ALREADY_COMPLETE;

        // Toggle: clicking active drops it (progress preserved); clicking queued removes it.
        if (researchId.equals(s.activeCultureResearch())) {
            s.setActiveCultureResearch(null);
            promoteFromQueue(s);
            data.setDirty();
            broadcastStateToSettlement(server, s);
            return ResearchManager.EnqueueResult.OK_REMOVED;
        }
        if (s.cultureResearchQueue().contains(researchId)) {
            s.cultureResearchQueue().remove(researchId);
            data.setDirty();
            broadcastStateToSettlement(server, s);
            return ResearchManager.EnqueueResult.OK_REMOVED;
        }
        if (def.minAge().ordinal() > s.age().ordinal()) return ResearchManager.EnqueueResult.AGE_LOCKED;
        if (ResearchManager.isEraCapped(def)) return ResearchManager.EnqueueResult.AGE_LOCKED;

        // Append the target after any unmet prerequisites (DFS post-order).
        java.util.List<String> chain = new java.util.ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        if (!buildPrereqChain(researchId, s, chain, visited)) {
            return ResearchManager.EnqueueResult.AGE_LOCKED;
        }
        for (String id : chain) {
            if (s.hasCompletedCultureResearch(id)) continue;
            if (id.equals(s.activeCultureResearch())) continue;
            if (s.cultureResearchQueue().contains(id)) continue;
            s.cultureResearchQueue().add(id);
        }
        if (s.activeCultureResearch() == null && s.activeResearch() == null
                && !s.cultureResearchQueue().isEmpty()) {
            promoteFromQueue(s);
        }
        data.setDirty();
        broadcastStateToSettlement(server, s);
        return ResearchManager.EnqueueResult.OK;
    }

    private static boolean buildPrereqChain(String researchId, Settlement s,
                                             java.util.List<String> out, java.util.Set<String> visited) {
        if (visited.contains(researchId)) return true;
        visited.add(researchId);
        ResearchDefinition def = CultureTreeLoader.get(researchId);
        if (def == null) return true;
        if (def.minAge().ordinal() > s.age().ordinal()) return false;
        if (ResearchManager.isEraCapped(def)) return false;
        for (String prereq : def.prerequisites()) {
            if (s.hasCompletedResearchEitherTree(prereq)) continue;
            if (!buildPrereqChain(prereq, s, out, visited)) return false;
        }
        out.add(researchId);
        return true;
    }

    private static void promoteFromQueue(Settlement s) {
        // Mutual exclusion check at promote time too â€” if a science research started
        // between queue-add and now, hold off.
        if (s.activeResearch() != null) return;
        int i = 0;
        while (i < s.cultureResearchQueue().size()) {
            String next = s.cultureResearchQueue().get(i);
            if (s.hasCompletedCultureResearch(next)) {
                s.cultureResearchQueue().remove(i);
                continue;
            }
            ResearchDefinition d = CultureTreeLoader.get(next);
            if (d == null) {
                s.cultureResearchQueue().remove(i);
                continue;
            }
            boolean ageOk = d.minAge().ordinal() <= s.age().ordinal() && !ResearchManager.isEraCapped(d);
            boolean prereqsOk = true;
            for (String prereq : d.prerequisites()) {
                if (!s.hasCompletedResearchEitherTree(prereq)) { prereqsOk = false; break; }
            }
            if (ageOk && prereqsOk) {
                s.cultureResearchQueue().remove(i);
                s.setActiveCultureResearch(next);
                return;
            }
            i++;
        }
    }

    /** Per-tick accumulation: drains effectiveCulturePerSecond / 20 into the active culture
     *  research's progress. Twin of {@link ResearchManager#tickAll} for science. */
    public static void tickAll(MinecraftServer server) {
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        SettlementData data = SettlementData.get(overworld);
        tickCounter++;
        boolean broadcastTick = (tickCounter % 20 == 0);
        boolean anyChange = false;

        for (Settlement s : data.all()) {
            // Frozen "in amber" while every member is offline — no culture accrues, nothing
            // completes. Dormancy is refreshed at the top of the tick (ResearchEvents).
            if (s.isDormant()) continue;
            boolean completedBanked = false;
            for (java.util.Map.Entry<String, Double> progress
                    : new java.util.ArrayList<>(s.cultureResearchProgress().entrySet())) {
                ResearchDefinition banked = CultureTreeLoader.get(progress.getKey());
                if (banked != null && progress.getValue() >= banked.cost()
                        && !ResearchManager.isEraCapped(banked)
                        && isInsightCompletionEligible(s, banked)) {
                    completeResearch(server, s, banked);
                    completedBanked = true;
                }
            }
            if (completedBanked) anyChange = true;
            String active = s.activeCultureResearch();
            if (active == null) continue;
            ResearchDefinition def = CultureTreeLoader.get(active);
            if (def == null) {
                s.setActiveCultureResearch(null);
                anyChange = true;
                continue;
            }
            // forceMaxAge lowered under an in-progress culture node â€” pause it (progress preserved).
            if (ResearchManager.isEraCapped(def)) {
                s.setActiveCultureResearch(null);
                promoteFromQueue(s);
                anyChange = true;
                continue;
            }
            double current = s.getCultureResearchProgress(active);
            double increment = s.effectiveCulturePerSecond(overworld) / 20.0
                * com.bannerbound.core.Config.RESEARCH_SPEED_MULTIPLIER.get();
            current += increment;
            if (current >= def.cost()) {
                completeResearch(server, s, def);
                anyChange = true;
            } else {
                s.setCultureResearchProgress(active, current);
            }
            if (broadcastTick) broadcastStateToSettlement(server, s);
        }
        if (anyChange) data.setDirty();
    }

    /** Debug twin of {@link ResearchManager#forceUnresearch} for the culture tree:
     *  un-completes the node, clears its progress, halts it if active. */
    public static boolean forceUnresearch(MinecraftServer server, Settlement settlement,
                                          ResearchDefinition def) {
        boolean removed = settlement.completedCultureResearches().remove(def.id());
        boolean clearedProgress = settlement.cultureResearchProgress().remove(def.id()) != null;
        if (def.id().equals(settlement.activeCultureResearch())) {
            settlement.setActiveCultureResearch(null);
        }
        if (removed || clearedProgress) {
            settlement.recomputeKnownItems();
            broadcastStateToSettlement(server, settlement);
            return true;
        }
        return false;
    }

    private static void completeResearch(MinecraftServer server, Settlement s, ResearchDefinition def) {
        s.markCultureResearchComplete(def.id());
        s.setActiveCultureResearch(null);
        BannerboundCore.LOGGER.info("Settlement {} completed culture research {}",
            s.name(), def.id());
        // Culture nodes run the SAME unlock-effect pipeline as science (rate deltas,
        // advance_age, unlock_faith_founding, ...). Before this call, culture
        // `unlocks.features` were silently ignored.
        ResearchManager.applyUnlockEffects(server, s, def);
        promoteFromQueue(s);
        com.bannerbound.core.crisis.CrisisManager.onResearchCompleted(server, s, def.id());
        com.bannerbound.core.codex.CodexManager.onResearchCompleted(server, s, def.id(), true);
        broadcastStateToSettlement(server, s);
        // Culture unlocks.items ride the SCIENCE state payload's known-item union — refresh it
        // so newly known items un-mask on clients without a relog.
        ResearchManager.broadcastStateToSettlement(server, s);
    }

    public static void broadcastStateToSettlement(MinecraftServer server, Settlement s) {
        if (server == null || s == null) return;
        CultureStateSyncPayload payload = buildStatePayload(server.overworld(), s);
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) PacketDistributor.sendToPlayer(m, payload);
        }
    }

    public static void sendStateTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        CultureStateSyncPayload payload = s == null
            ? emptyStatePayload()
            : buildStatePayload(server.overworld(), s);
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static CultureStateSyncPayload buildStatePayload(ServerLevel level, Settlement s) {
        return new CultureStateSyncPayload(
            new java.util.ArrayList<>(s.completedCultureResearches()),
            s.activeCultureResearch() == null ? "" : s.activeCultureResearch(),
            CultureStateSyncPayload.flattenProgress(s.cultureResearchProgress()),
            s.effectiveCulturePerSecond(level),
            getCapacity(s.age()),
            new java.util.ArrayList<>(s.cultureResearchQueue()),
            InsightManager.counterEntries(s, InsightManager.TreeType.CULTURE,
                CultureTreeLoader.getAll().values()),
            InsightManager.firedNodeIds(s.firedInsights(), InsightManager.TreeType.CULTURE)
        );
    }

    private static CultureStateSyncPayload emptyStatePayload() {
        return new CultureStateSyncPayload(
            java.util.List.of(), "", java.util.List.of(), 0.0,
            getCapacity(Era.ANCIENT), java.util.List.of(), java.util.List.of(), java.util.List.of());
    }
}
