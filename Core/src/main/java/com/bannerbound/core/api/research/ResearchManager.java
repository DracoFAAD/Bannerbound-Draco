package com.bannerbound.core.api.research;

import com.bannerbound.core.entity.CitizenEntity;

import com.bannerbound.core.api.research.data.ResearchTreeLoader;
import com.bannerbound.core.api.research.data.ToolAgeLoader;
import com.bannerbound.core.api.settlement.ImmigrationManager;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.network.ResearchStateSyncPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Per-settlement research engine. Ticks science accumulation, handles starting / enqueueing /
 * completing research, fires unlock effects (item unlocks, feature one-shots like
 * {@code bannerbound.advance_age:<era>}, persistent flags), and regresses completed research
 * when a settlement drops below a research's required {@code min_age}.
 * <p>
 * All mutations call {@link #broadcastStateToSettlement} so the client mirror
 * ({@link com.bannerbound.core.client.ClientResearchState}) updates immediately.
 * <p>
 * Adding a new one-shot feature: extend {@link #applyFeature}'s switch. Adding a new
 * persistent flag: define a flag string convention, list it in some research's
 * {@code unlocks.flags}, and query via {@link #hasFlag}.
 */
public final class ResearchManager {
    private static int tickCounter = 0;

    private ResearchManager() {
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

    /** Adds bankable insight progress and completes immediately only when the node is legal now. */
    public static boolean addInsightProgress(MinecraftServer server, Settlement s,
                                             ResearchDefinition def, double points) {
        if (s.hasCompletedResearch(def.id()) || points <= 0.0) return false;
        s.setResearchProgress(def.id(), s.getResearchProgress(def.id()) + points);
        SettlementData.get(server.overworld()).setDirty();
        if (s.getResearchProgress(def.id()) >= def.cost() && isInsightCompletionEligible(s, def)) {
            completeResearch(server, s, def);
            return true;
        }
        broadcastStateToSettlement(server, s);
        return false;
    }

    public static int getCapacity(Era era) {
        // Capacity grows with era; ancient = 1, classical = 2, medieval = 3, ...
        return 1 + era.ordinal();
    }

    public static Set<String> computeUnlockedItems(Settlement settlement) {
        return settlement.computeKnownItems();
    }

    /** Returns true if any completed research in EITHER tree (science or culture) lists
     *  {@code flag} in its {@code unlocks.flags}. A capability flag doesn't care which tree
     *  granted it — culture unlocks are first-class (see CULTURE_PLAN.md). */
    public static boolean hasFlag(Settlement settlement, String flag) {
        if (settlement == null) return false;
        for (String id : settlement.completedResearches()) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def != null && def.unlocksFlags().contains(flag)) {
                return true;
            }
        }
        for (String id : settlement.completedCultureResearches()) {
            ResearchDefinition def =
                com.bannerbound.core.api.research.data.CultureTreeLoader.get(id);
            if (def != null && def.unlocksFlags().contains(flag)) {
                return true;
            }
        }
        return false;
    }

    /** @deprecated {@link #hasFlag} now checks both trees; this alias remains for callers. */
    @Deprecated
    public static boolean hasFlagEitherTree(Settlement settlement, String flag) {
        return hasFlag(settlement, flag);
    }

    /** Total Heraldry points this settlement has EARNED â€” the sum of {@code heraldry_points}
     *  across completed researches in BOTH trees. Points are never "spent away": each banner
     *  pattern layer occupies one point while it exists, so available = earned âˆ’ layers in the
     *  current design (removing a layer frees its point). See FactionBanner. */
    public static int heraldryPointsEarned(Settlement settlement) {
        if (settlement == null) return 0;
        int total = 0;
        for (String id : settlement.completedResearches()) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def != null) total += def.heraldryPoints();
        }
        for (String id : settlement.completedCultureResearches()) {
            ResearchDefinition def =
                com.bannerbound.core.api.research.data.CultureTreeLoader.get(id);
            if (def != null) total += def.heraldryPoints();
        }
        return total;
    }

    public static void initializeAutoUnlocks(MinecraftServer server, Settlement settlement) {
        boolean changed = false;
        SettlementData data = server == null ? null : SettlementData.get(server.overworld());
        for (ResearchDefinition def : ResearchTreeLoader.getAll().values()) {
            if (def.autoUnlock() && !settlement.hasCompletedResearch(def.id())) {
                settlement.markResearchComplete(def.id());
                if (data != null) data.markGloballyResearched(def.id());
                applyUnlockEffects(server, settlement, def);
                changed = true;
            }
        }
        // Push the updated state so newly-completed nodes show as green client-side, and
        // anything that depends on them (e.g. Knapping needing Settlement) becomes clickable.
        if (changed && server != null) {
            broadcastStateToSettlement(server, settlement);
        }
    }

    /**
     * Sweeps every settlement on the server and completes any auto_unlock research it doesn't
     * already have. Idempotent â€” safe to call on every datapack sync, which also covers the
     * "I added a new auto_unlock node to the tree mid-game" case.
     */
    public static void applyAllAutoUnlocks(MinecraftServer server) {
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        for (Settlement s : data.all()) {
            initializeAutoUnlocks(server, s);
            // Re-resolve the settlement's current tool age from its full set of completed
            // research. Covers two retroactive cases: (a) save loaded with research that
            // pre-dated the tool-age system, and (b) JSON authors moving a set_tool_age key
            // between the "features" and "flags" arrays (or adding one to an already-completed
            // node). Always picks the highest-order age unlocked; never downgrades.
            recomputeToolAge(s);
        }
    }

    /** Walks every completed research and applies the highest-order {@code set_tool_age} it finds. */
    private static void recomputeToolAge(Settlement s) {
        for (String id : s.completedResearches()) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def == null) continue;
            for (String feat : def.unlocksFeatures()) {
                if (feat.startsWith(SET_TOOL_AGE_PREFIX)) {
                    applySetToolAge(s, feat);
                }
            }
            for (String flag : def.unlocksFlags()) {
                if (flag.startsWith(SET_TOOL_AGE_PREFIX)) {
                    applySetToolAge(s, flag);
                }
            }
        }
    }

    private static void applySetToolAge(Settlement s, String key) {
        String ageId = key.substring(SET_TOOL_AGE_PREFIX.length()).trim();
        ToolAge incoming = ToolAgeLoader.get(ageId);
        if (incoming == null) return;
        ToolAge current = s.getCurrentToolAge().isEmpty()
            ? null : ToolAgeLoader.get(s.getCurrentToolAge());
        if (current == null || incoming.order() > current.order()) {
            s.setCurrentToolAge(ageId);
        }
    }

    /**
     * Admin/debug entrypoint: completes a research immediately for the given settlement, firing
     * its unlock effects (item unlocks + features like {@code advance_age:<era>}) and broadcasting state.
     * No-op if the research is already complete.
     */
    public static void forceComplete(MinecraftServer server, Settlement settlement, ResearchDefinition def) {
        if (settlement.hasCompletedResearch(def.id())) {
            return;
        }
        completeResearch(server, settlement, def);
    }

    /**
     * Walks the prerequisite tree depth-first and force-completes any uncompleted ancestor
     * before completing {@code def} itself. So /bannerbound unlock iron_working will also
     * unlock smelting, knapping, wood_refining, settlement â€” whatever's missing â€” in dependency
     * order. Cycles are guarded by the {@code visited} set; missing prerequisite definitions
     * are skipped silently (caller already validated {@code def} exists).
     */
    public static void forceCompleteWithPrereqs(MinecraftServer server, Settlement settlement,
                                                  ResearchDefinition def) {
        forceCompleteWithPrereqs(server, settlement, def, new java.util.HashSet<>());
    }

    private static void forceCompleteWithPrereqs(MinecraftServer server, Settlement settlement,
                                                   ResearchDefinition def,
                                                   java.util.Set<String> visited) {
        if (settlement.hasCompletedResearch(def.id())) return;
        if (!visited.add(def.id())) return;
        for (String prereqId : def.prerequisites()) {
            if (settlement.hasCompletedResearch(prereqId)) continue;
            ResearchDefinition prereq = ResearchTreeLoader.get(prereqId);
            if (prereq != null) {
                forceCompleteWithPrereqs(server, settlement, prereq, visited);
            }
        }
        completeResearch(server, settlement, def);
    }

    /**
     * Admin/debug entrypoint: reverses a research. Removes it from the completed set, clears
     * any in-progress accumulator, and re-broadcasts state. Note that any cumulative effects
     * (science/food/culture deltas, tool-age advance) are NOT auto-reverted â€” this is a debug
     * tool, callers should expect some stale settlement values. Returns true if anything
     * changed.
     */
    public static boolean forceUnresearch(MinecraftServer server, Settlement settlement,
                                            ResearchDefinition def) {
        boolean removed = settlement.completedResearches().remove(def.id());
        boolean clearedProgress = settlement.researchProgress().remove(def.id()) != null;
        if (def.id().equals(settlement.activeResearch())) {
            settlement.setActiveResearch(null);
        }
        if (removed || clearedProgress) {
            settlement.recomputeKnownItems();
            broadcastStateToSettlement(server, settlement);
            return true;
        }

        return false;
    }

    public enum StartResult {
        OK,
        UNKNOWN_RESEARCH,
        ALREADY_COMPLETE,
        PREREQ_MISSING,
        AGE_LOCKED,
        NOT_IN_SETTLEMENT
    }

    public enum EnqueueResult {
        OK,
        OK_REMOVED,
        UNKNOWN_RESEARCH,
        ALREADY_COMPLETE,
        AGE_LOCKED,
        NOT_IN_SETTLEMENT
    }

    public static StartResult tryStart(ServerPlayer player, String researchId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return StartResult.NOT_IN_SETTLEMENT;
        }
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) {
            return StartResult.NOT_IN_SETTLEMENT;
        }
        ResearchDefinition def = ResearchTreeLoader.get(researchId);
        if (def == null) {
            return StartResult.UNKNOWN_RESEARCH;
        }
        if (s.hasCompletedResearch(researchId)) {
            return StartResult.ALREADY_COMPLETE;
        }
        for (String prereq : def.prerequisites()) {
            // Cross-tree: a science node may list a culture prereq (or vice versa).
            if (!s.hasCompletedResearchEitherTree(prereq)) {
                return StartResult.PREREQ_MISSING;
            }
        }
        if (def.requiresTribe() && !s.isTribe()) {
            return StartResult.AGE_LOCKED;
        }
        if (def.minAge().ordinal() > s.age().ordinal()) {
            return StartResult.AGE_LOCKED;
        }
        // forceMaxAge cap: a node that belongs to (or would advance into) a capped era is locked.
        if (isEraCapped(def)) {
            return StartResult.AGE_LOCKED;
        }
        // Step 8 cross-tree transfer: if the OTHER tree (culture) is currently researching
        // something, pause it (progress is preserved in the culture progress map â€” same
        // behavior as a right-click toggle-off). The clicked node becomes the new active
        // research. One slot total across both trees, but switching is one-click.
        if (s.activeCultureResearch() != null) {
            s.setActiveCultureResearch(null);
            com.bannerbound.core.api.research.CultureManager.broadcastStateToSettlement(server, s);
        }
        // Left-click "start now" â€” if the research was in queue, pull it out (it's active now).
        s.researchQueue().remove(researchId);
        s.setActiveResearch(researchId);
        // Step 7 polish: clear any non-chief suggestion markers on this node â€” the chief has
        // honoured the suggestion, so the badges have served their purpose.
        s.clearScienceSuggestions(researchId);
        SettlementManager.broadcastSuggestionState(server, s);
        data.setDirty();
        broadcastStateToSettlement(server, s);
        return StartResult.OK;
    }

    /**
     * Right-click "queue up" â€” appends the research (and any missing-but-startable prerequisites,
     * in dependency order) to the settlement's research queue. If nothing is currently active,
     * promotes the first queue entry to active immediately.
     */
    public static EnqueueResult tryEnqueue(ServerPlayer player, String researchId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return EnqueueResult.NOT_IN_SETTLEMENT;
        }
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) {
            return EnqueueResult.NOT_IN_SETTLEMENT;
        }
        ResearchDefinition def = ResearchTreeLoader.get(researchId);
        if (def == null) {
            return EnqueueResult.UNKNOWN_RESEARCH;
        }
        if (s.hasCompletedResearch(researchId)) {
            return EnqueueResult.ALREADY_COMPLETE;
        }
        // Right-click toggle: dropping the active research stops it (progress preserved in the
        // map), then promotes the next queued item if any. Dropping a queued item just removes it.
        if (researchId.equals(s.activeResearch())) {
            s.setActiveResearch(null);
            // The newly-dropped research was an in-progress prereq for anything still queued â€”
            // cascade-remove dependents so the player doesn't end up with a queue position 1 that
            // can never start. promoteFromQueue runs after, in case a later un-blocked item exists.
            pruneOrphanedQueueEntries(s);
            promoteFromQueue(s);
            data.setDirty();
            broadcastStateToSettlement(server, s);
            return EnqueueResult.OK_REMOVED;
        }
        if (s.researchQueue().contains(researchId)) {
            s.researchQueue().remove(researchId);
            pruneOrphanedQueueEntries(s);
            data.setDirty();
            broadcastStateToSettlement(server, s);
            return EnqueueResult.OK_REMOVED;
        }
        // Refuse the entire request if the target node itself is age-locked. Missing prereqs
        // that are age-locked also block the chain (no point queueing something we can't finish).
        if (def.minAge().ordinal() > s.age().ordinal()) {
            return EnqueueResult.AGE_LOCKED;
        }
        // forceMaxAge cap on the target node itself (the chain walk below catches capped prereqs).
        if (isEraCapped(def)) {
            return EnqueueResult.AGE_LOCKED;
        }

        java.util.List<String> chain = new java.util.ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        if (!buildPrereqChain(researchId, s, chain, visited)) {
            return EnqueueResult.AGE_LOCKED;
        }

        for (String id : chain) {
            if (s.hasCompletedResearch(id)) continue;
            if (id.equals(s.activeResearch())) continue;
            if (s.researchQueue().contains(id)) continue;
            s.researchQueue().add(id);
        }

        if (s.activeResearch() == null && s.activeCultureResearch() == null
                && !s.researchQueue().isEmpty()) {
            promoteFromQueue(s);
        }

        data.setDirty();
        broadcastStateToSettlement(server, s);
        return EnqueueResult.OK;
    }

    /**
     * Walks prerequisites depth-first, post-order, appending each unmet prerequisite (and finally
     * the target itself) to {@code out}. Returns false if any node in the chain is age-locked for
     * the settlement â€” caller refuses the whole enqueue in that case.
     */
    private static boolean buildPrereqChain(String researchId, Settlement s,
                                             java.util.List<String> out, java.util.Set<String> visited) {
        if (visited.contains(researchId)) return true;
        visited.add(researchId);
        ResearchDefinition def = ResearchTreeLoader.get(researchId);
        if (def == null) return true;
        if (def.minAge().ordinal() > s.age().ordinal()) return false;
        if (isEraCapped(def)) return false;
        if (def.requiresTribe() && !s.isTribe()) return false;
        for (String prereq : def.prerequisites()) {
            if (s.hasCompletedResearchEitherTree(prereq)) continue;
            if (!buildPrereqChain(prereq, s, out, visited)) return false;
        }
        out.add(researchId);
        return true;
    }

    /**
     * Walks the queue looking for the first item that's actually researchable RIGHT NOW. Stale
     * items (already-completed, unknown to the tree) are pruned; items that are temporarily
     * blocked (age-locked, prereqs not met) are skipped but left in place so the player keeps
     * them after re-queueing a missing prereq.
     */
    /**
     * Drops any queued research whose prerequisites are no longer in the queue, active, or
     * completed â€” i.e. anything orphaned by an upstream un-queue. Repeats until stable so that
     * removing a deep prerequisite cascades through a multi-step chain (e.g. removing smelting
     * removes iron_working, which in turn removes anything that needed iron_working).
     */
    private static void pruneOrphanedQueueEntries(Settlement s) {
        boolean changed;
        do {
            changed = false;
            java.util.List<String> queue = s.researchQueue();
            for (int i = 0; i < queue.size(); ) {
                String id = queue.get(i);
                ResearchDefinition d = ResearchTreeLoader.get(id);
                if (d == null) { queue.remove(i); changed = true; continue; }
                boolean prereqsReachable = true;
                for (String prereq : d.prerequisites()) {
                    if (s.hasCompletedResearchEitherTree(prereq)) continue;
                    if (prereq.equals(s.activeResearch())) continue;
                    if (queue.contains(prereq)) continue;
                    prereqsReachable = false;
                    break;
                }
                if (!prereqsReachable) { queue.remove(i); changed = true; continue; }
                i++;
            }
        } while (changed);
    }

    private static void promoteFromQueue(Settlement s) {
        // Mutual exclusion check at promote time too — if a culture research started
        // between queue-add and now, hold off (mirror of CultureManager.promoteFromQueue).
        if (s.activeCultureResearch() != null) return;
        int i = 0;
        while (i < s.researchQueue().size()) {
            String next = s.researchQueue().get(i);
            if (s.hasCompletedResearch(next)) {
                s.researchQueue().remove(i);
                continue;
            }
            ResearchDefinition d = ResearchTreeLoader.get(next);
            if (d == null) {
                s.researchQueue().remove(i);
                continue;
            }
            boolean ageOk = d.minAge().ordinal() <= s.age().ordinal() && !isEraCapped(d);
            boolean prereqsOk = true;
            for (String prereq : d.prerequisites()) {
                if (!s.hasCompletedResearchEitherTree(prereq)) { prereqsOk = false; break; }
            }
            if (ageOk && prereqsOk) {
                s.researchQueue().remove(i);
                s.setActiveResearch(next);
                return;
            }
            i++;
        }
    }

    /**
     * Called after a settlement's age changes. Researches whose min_age is now above the
     * settlement's age are un-completed and their progress cleared. The active research is
     * also dropped if it's now age-locked.
     */
    public static void regressResearchAfterAgeChange(MinecraftServer server, Settlement s) {
        int currentAge = s.age().ordinal();
        boolean changed = false;
        java.util.Set<String> toRemove = new java.util.HashSet<>();
        for (String id : s.completedResearches()) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def != null && def.minAge().ordinal() > currentAge) {
                toRemove.add(id);
            }
        }
        for (String id : toRemove) {
            s.completedResearches().remove(id);
            s.researchProgress().remove(id);
            changed = true;
        }
        String active = s.activeResearch();
        if (active != null) {
            ResearchDefinition def = ResearchTreeLoader.get(active);
            if (def != null && def.minAge().ordinal() > currentAge) {
                s.researchProgress().remove(active);
                s.setActiveResearch(null);
                changed = true;
            }
        }
        // Also strip age-locked entries from the queue so promote doesn't have to skip them.
        java.util.Iterator<String> qIter = s.researchQueue().iterator();
        while (qIter.hasNext()) {
            String id = qIter.next();
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def != null && def.minAge().ordinal() > currentAge) {
                qIter.remove();
                changed = true;
            }
        }
        if (changed) {
            s.recomputeKnownItems();
            broadcastStateToSettlement(server, s);
            // Item unlocks just shrank â€” refresh the era state so the client recomputes.
            SettlementManager.broadcastEraState(server);
            // Any member wearing armor that's now unknown should drop it.
            for (UUID memberId : s.members()) {
                ServerPlayer m = server.getPlayerList().getPlayer(memberId);
                if (m != null) {
                    com.bannerbound.core.event.UnknownItemBlocker.unequipUnknownGear(m);
                }
            }
        }
    }

    /**
     * Server tick: accumulate science into each settlement's active research; complete and fire
     * unlock effects when full. Broadcasts state to settlement members once per second.
     */
    public static void tickAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        SettlementData data = SettlementData.get(overworld);
        tickCounter++;
        boolean broadcastTick = (tickCounter % 20 == 0);
        boolean anyChange = false;

        for (Settlement s : data.all()) {
            // Frozen "in amber" while every member is offline — no science accrues, nothing
            // completes. Dormancy is refreshed at the top of the tick (ResearchEvents).
            if (s.isDormant()) {
                continue;
            }
            boolean completedBanked = false;
            for (java.util.Map.Entry<String, Double> progress
                    : new java.util.ArrayList<>(s.researchProgress().entrySet())) {
                ResearchDefinition banked = ResearchTreeLoader.get(progress.getKey());
                if (banked != null && progress.getValue() >= banked.cost()
                        && !isEraCapped(banked)
                        && isInsightCompletionEligible(s, banked)) {
                    completeResearch(server, s, banked);
                    completedBanked = true;
                }
            }
            if (completedBanked) anyChange = true;
            String active = s.activeResearch();
            if (active == null) {
                continue;
            }
            ResearchDefinition def = ResearchTreeLoader.get(active);
            if (def == null) {
                s.setActiveResearch(null);
                anyChange = true;
                continue;
            }
            // forceMaxAge was lowered under an in-progress node â€” pause it (progress is preserved
            // in the map, so it resumes if the cap is raised again) and try to promote a queued one.
            if (isEraCapped(def)) {
                s.setActiveResearch(null);
                promoteFromQueue(s);
                anyChange = true;
                continue;
            }
            double current = s.getResearchProgress(active);
            double increment = s.effectiveSciencePerSecond() / 20.0
                * com.bannerbound.core.Config.RESEARCH_SPEED_MULTIPLIER.get();
            current += increment;
            if (current >= def.cost()) {
                completeResearch(server, s, def);
                anyChange = true;
            } else {
                s.setResearchProgress(active, current);
            }
            if (broadcastTick) {
                broadcastStateToSettlement(server, s);
            }
        }
        if (anyChange) {
            data.setDirty();
        }
    }

    private static void completeResearch(MinecraftServer server, Settlement s, ResearchDefinition def) {
        s.markResearchComplete(def.id());
        SettlementData.get(server.overworld()).markGloballyResearched(def.id());
        s.setActiveResearch(null);
        applyUnlockEffects(server, s, def);
        migrateObsoletedJobs(server, s);
        for (UUID memberId : s.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) {
                member.sendSystemMessage(Component.translatable("bannerbound.research.completed_personal", def.name())
                    .withStyle(ChatFormatting.GREEN));
                member.playNotifySound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(),
                    net.minecraft.sounds.SoundSource.MASTER, 1.0f, 1.0f);
            }
        }
        applyProgressedRecentlyThought(server, s);
        // Pull the next research from the queue (if any) so the player doesn't have to re-click.
        promoteFromQueue(s);
        com.bannerbound.core.crisis.CrisisManager.onResearchCompleted(server, s, def.id());
        com.bannerbound.core.codex.CodexManager.onResearchCompleted(server, s, def.id(), false);
        broadcastStateToSettlement(server, s);
    }

    /** Broadcasts the {@code PROGRESSED_RECENTLY} thought to every currently-loaded citizen in
     *  the settlement when a research completes. Citizens whose entity isn't loaded silently
     *  miss this one â€” same forgiving model as the weather thought broadcast. The thought is
     *  single-instance per citizen, so back-to-back research completions just refresh its
     *  5-minute timer rather than stacking. */
    private static void applyProgressedRecentlyThought(MinecraftServer server, Settlement s) {
        net.minecraft.server.level.ServerLevel overworld = server.overworld();
        long now = overworld.getGameTime();
        for (com.bannerbound.core.api.settlement.Citizen c : s.citizens()) {
            net.minecraft.world.entity.Entity raw = overworld.getEntity(c.entityId());
            if (!(raw instanceof com.bannerbound.core.entity.CitizenEntity citizen)) continue;
            citizen.getThoughts().add(
                com.bannerbound.core.social.ThoughtKind.PROGRESSED_RECENTLY,
                null, now, overworld.random);
            citizen.recomputeHappiness();
        }
    }

    /**
     * Retires {@link com.bannerbound.core.api.job.CitizenJobRegistry registry} jobs that a just-completed
     * research has superseded. When a job declares {@code obsoletedByUnit} and that successor unit's
     * unlock flag is now researched, every loaded citizen still holding the obsolete job is re-skilled
     * to the successor's job ({@code CitizenEntity.setJobType} returns the old tool to storage / feet
     * and keeps the drop-off). This is the commanded-settlement counterpart to the anarchy distributor,
     * which drops the obsolete job from {@code unlockedGathererJobs} and re-skills on its own. Example:
     * spear fishers â†’ rod fishers the moment Fishery (the fishing rod) is researched.
     */
    private static void migrateObsoletedJobs(MinecraftServer server, Settlement s) {
        net.minecraft.server.level.ServerLevel overworld = server.overworld();
        for (com.bannerbound.core.api.settlement.Citizen c : s.citizens()) {
            net.minecraft.world.entity.Entity raw = overworld.getEntity(c.entityId());
            if (!(raw instanceof com.bannerbound.core.entity.CitizenEntity citizen)) continue;
            String job = citizen.getJobType();
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef d =
                com.bannerbound.core.api.job.CitizenJobRegistry.byId(job);
            if (d == null || d.obsoletedByUnit() == null) continue;
            if (!hasFlag(s, com.bannerbound.core.api.settlement.WorkstationUnlocks.flagForUnit(d.obsoletedByUnit()))) {
                continue;
            }
            String successor = com.bannerbound.core.api.settlement.WorkstationUnlocks
                .workstationForUnit(d.obsoletedByUnit());
            if (successor != null && !successor.equals(job)) {
                citizen.setJobType(successor);
            }
        }
    }

    /** Shared effect pipeline: science completions, {@link CultureManager} culture
     *  completions, and faith-tree completions (FaithManager â€” applied per member
     *  settlement) all route through here. */
    public static void applyUnlockEffects(MinecraftServer server, Settlement s, ResearchDefinition def) {
        for (String feature : def.unlocksFeatures()) {
            applyFeature(server, s, feature);
        }
        // Forgiving alias: a few effects (currently only set_tool_age) read just as naturally in
        // the "flags" array, since they represent persistent settlement state. Process those
        // entries through the same handler so JSON authors don't need to memorize which array
        // each key belongs in. Plain query flags (no recognised prefix) fall through untouched
        // and remain visible to {@link #hasFlag} as before.
        for (String flag : def.unlocksFlags()) {
            if (flag.startsWith(SET_TOOL_AGE_PREFIX)) {
                applyFeature(server, s, flag);
            }
            com.bannerbound.core.codex.CodexManager.onFlagGained(server, s, flag);
        }
        // After items unlock, the player's effective known set changes â€” push it.
        SettlementManager.broadcastEraState(server);
    }

    private static final String SCIENCE_DELTA_PREFIX = "bannerbound.science_per_second_delta:";
    private static final String FOOD_RATE_DELTA_PREFIX = "bannerbound.food_per_second_delta:";
    private static final String CULTURE_RATE_DELTA_PREFIX = "bannerbound.culture_per_second_delta:";
    private static final String FOOD_CAPACITY_DELTA_PREFIX = "bannerbound.food_capacity_delta:";
    private static final String CULTURE_CAPACITY_DELTA_PREFIX = "bannerbound.culture_capacity_delta:";
    private static final String CITIZEN_SPEED_DELTA_PREFIX = "bannerbound.citizen_speed_delta:";
    private static final String SET_TOOL_AGE_PREFIX = "bannerbound.set_tool_age:";
    private static final String ADVANCE_AGE_PREFIX = "bannerbound.advance_age:";
    /** One-shot: opens the faith founding window (FAITH_PLAN.md â€” the Spiritualism node). */
    private static final String UNLOCK_FAITH_FOUNDING = "bannerbound.unlock_faith_founding";

    /**
     * The {@code /gamerule forceMaxAge} cap, resolved to an {@link Era}. The rule stores a real
     * {@link Era} (the last era = "no cap"); a null server (datagen / no level loaded) is also
     * treated as no cap.
     */
    public static Era forceMaxAge(MinecraftServer server) {
        Era[] vals = Era.values();
        Era last = vals[vals.length - 1];
        if (server == null) {
            return last;
        }
        return server.getGameRules()
            .getRule(com.bannerbound.core.chat.BannerboundGameRules.FORCE_MAX_AGE)
            .era();
    }

    /** {@link #forceMaxAge(MinecraftServer)} resolved against the running server. */
    public static Era forceMaxAge() {
        return forceMaxAge(net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer());
    }

    /**
     * True if {@code forceMaxAge} blocks this node: either it belongs to an era past the cap
     * (min_age &gt; cap) or it would itself advance the age past the cap (carries an
     * {@code advance_age:<era>} feature targeting beyond the cap). Used as an extra "locked" gate
     * alongside the existing min-age / prereq checks.
     */
    public static boolean isEraCapped(ResearchDefinition def) {
        if (def == null) {
            return false;
        }
        Era cap = forceMaxAge();
        if (def.minAge().ordinal() > cap.ordinal()) {
            return true;
        }
        for (String feature : def.unlocksFeatures()) {
            if (feature.startsWith(ADVANCE_AGE_PREFIX)) {
                Era target = Era.fromName(feature.substring(ADVANCE_AGE_PREFIX.length()).trim());
                if (target != null && target.ordinal() > cap.ordinal()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void applyFeature(MinecraftServer server, Settlement s, String featureKey) {
        if (featureKey.equals(UNLOCK_FAITH_FOUNDING)) {
            com.bannerbound.core.api.faith.FaithManager.unlockFounding(server, s);
            return;
        }
        if (featureKey.startsWith(SCIENCE_DELTA_PREFIX)) {
            String valueStr = featureKey.substring(SCIENCE_DELTA_PREFIX.length()).trim();
            try {
                double delta = Double.parseDouble(valueStr);
                double newRate = Math.max(0.0, s.sciencePerSecond() + delta);
                s.setSciencePerSecond(newRate);
                for (UUID memberId : s.members()) {
                    ServerPlayer m = server.getPlayerList().getPlayer(memberId);
                    if (m != null) {
                        m.sendSystemMessage(Component.translatable(
                                "bannerbound.research.science_changed",
                                String.format("%+.2f", delta), String.format("%.2f", newRate))
                            .withStyle(ChatFormatting.AQUA));
                    }
                }
            } catch (NumberFormatException ex) {
                BannerboundCore.LOGGER.warn("Bad science delta value '{}' in feature {}", valueStr, featureKey);
            }
            return;
        }
        if (featureKey.startsWith(FOOD_CAPACITY_DELTA_PREFIX)) {
            applyCapacityDelta(server, s, featureKey, FOOD_CAPACITY_DELTA_PREFIX, true);
            return;
        }
        if (featureKey.startsWith(CULTURE_CAPACITY_DELTA_PREFIX)) {
            applyCapacityDelta(server, s, featureKey, CULTURE_CAPACITY_DELTA_PREFIX, false);
            return;
        }
        if (featureKey.startsWith(FOOD_RATE_DELTA_PREFIX)) {
            applyRateDelta(s, featureKey, FOOD_RATE_DELTA_PREFIX, true);
            return;
        }
        if (featureKey.startsWith(CULTURE_RATE_DELTA_PREFIX)) {
            applyRateDelta(s, featureKey, CULTURE_RATE_DELTA_PREFIX, false);
            return;
        }
        if (featureKey.startsWith(CITIZEN_SPEED_DELTA_PREFIX)) {
            String valueStr = featureKey.substring(CITIZEN_SPEED_DELTA_PREFIX.length()).trim();
            try {
                double delta = Double.parseDouble(valueStr);
                s.addBonusCitizenSpeed(delta);
                // CitizenEntity.aiStep() refreshes its modifier every half-second; no broadcast
                // needed â€” the change is picked up automatically on each citizen's next tick.
            } catch (NumberFormatException ex) {
                BannerboundCore.LOGGER.warn("Bad citizen speed delta value '{}' in feature {}", valueStr, featureKey);
            }
            return;
        }
        if (featureKey.startsWith(SET_TOOL_AGE_PREFIX)) {
            String ageId = featureKey.substring(SET_TOOL_AGE_PREFIX.length()).trim();
            ToolAge incoming = ToolAgeLoader.get(ageId);
            if (incoming == null) {
                BannerboundCore.LOGGER.warn("Unknown tool age '{}' in feature {}", ageId, featureKey);
                return;
            }
            // Only upgrade â€” researching a lower-tier node after a higher one already landed
            // shouldn't roll the settlement back. Compare by order.
            ToolAge current = s.getCurrentToolAge().isEmpty()
                ? null : ToolAgeLoader.get(s.getCurrentToolAge());
            if (current == null || incoming.order() > current.order()) {
                s.setCurrentToolAge(ageId);
            }
            return;
        }
        if (featureKey.startsWith(ADVANCE_AGE_PREFIX)) {
            String eraKey = featureKey.substring(ADVANCE_AGE_PREFIX.length()).trim();
            Era target = Era.fromName(eraKey);
            if (target == null) {
                BannerboundCore.LOGGER.warn("Unknown era '{}' in feature {}", eraKey, featureKey);
                return;
            }
            // forceMaxAge cap: never advance past the configured ceiling. The UX gates below
            // (tryStart/tryEnqueue/promote) normally stop a capped node from ever completing, but
            // this is the hard backstop covering auto-unlock and command-driven completion too.
            Era cap = forceMaxAge(server);
            if (target.ordinal() > cap.ordinal()) {
                BannerboundCore.LOGGER.info("Era advance to '{}' blocked by forceMaxAge cap '{}'",
                    target.key(), cap.key());
                return;
            }
            // Only advance â€” a research that targets an earlier era than the settlement's current
            // age should be a no-op, not a regression.
            if (target.ordinal() <= s.age().ordinal()) {
                return;
            }
            s.setAge(target);
            SettlementData data = SettlementData.get(server.overworld());
            if (target.ordinal() > data.getWorldAge().ordinal()) {
                data.setWorldAge(target);
            }
            data.setDirty();
            SettlementManager.broadcastEraState(server);

            // Fireworks at the town hall, with the era-specific jingle.
            if (s.townHallPos() != null) {
                SettlementManager.launchCelebrationFireworks(server.overworld(), s.townHallPos(), s, 5,
                    BannerboundCore.getAgeAdvanceSound(target));
            }

            // Global announcement to every player on the server
            Component announcement = Component.translatable(
                "bannerbound.settlement.advanced_age", s.name(), target.displayName())
                .withStyle(s.identityFormatting());
            server.getPlayerList().broadcastSystemMessage(announcement, false);
            com.bannerbound.core.codex.CodexManager.onEraReached(server, s);
            return;
        }
        BannerboundCore.LOGGER.warn("Unknown research feature: {}", featureKey);
    }

    /**
     * Applies a delta to the settlement's per-second food or culture generation. Mirrors the
     * science rate handler â€” the new rate is clamped at 0 so a negative delta from a "drawback"
     * research can't push generation below zero. The {@code ImmigrationManager} ticker reads
     * these rates directly each tick; no broadcast needed (the visible counter updates next tick).
     */
    private static void applyRateDelta(Settlement s, String featureKey, String prefix, boolean isFood) {
        String valueStr = featureKey.substring(prefix.length()).trim();
        try {
            double delta = Double.parseDouble(valueStr);
            if (isFood) {
                double newRate = Math.max(0.0, s.foodPerSecond() + delta);
                s.setFoodPerSecond(newRate);
            } else {
                double newRate = Math.max(0.0, s.culturePerSecond() + delta);
                s.setCulturePerSecond(newRate);
            }
        } catch (NumberFormatException ex) {
            BannerboundCore.LOGGER.warn("Bad rate delta value '{}' in feature {}", valueStr, featureKey);
        }
    }

    private static void applyCapacityDelta(MinecraftServer server, Settlement s, String featureKey,
                                            String prefix, boolean isFood) {
        String valueStr = featureKey.substring(prefix.length()).trim();
        try {
            double delta = Double.parseDouble(valueStr);
            if (isFood) {
                s.addBonusFoodCapacity(delta);
            } else {
                s.addBonusCultureCapacity(delta);
            }
            // Effect is visible in the research tooltip's effects list â€” no chat spam needed.
            com.bannerbound.core.api.settlement.ImmigrationManager.broadcastState(server, s);
        } catch (NumberFormatException ex) {
            BannerboundCore.LOGGER.warn("Bad capacity delta value '{}' in feature {}", valueStr, featureKey);
        }
    }

    public static void broadcastStateToSettlement(MinecraftServer server, Settlement s) {
        if (server == null || s == null) {
            return;
        }
        ResearchStateSyncPayload payload = buildStatePayload(s);
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) {
                PacketDistributor.sendToPlayer(m, payload);
            }
        }
    }

    public static void sendStateTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        ResearchStateSyncPayload payload = s == null ? emptyStatePayload() : buildStatePayload(s);
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static ResearchStateSyncPayload buildStatePayload(Settlement s) {
        // The client's known-item set is one flat union, so culture unlocks.items ride this
        // payload too — CultureManager.completeResearch re-broadcasts it on completion.
        java.util.Set<String> knownItems = computeUnlockedItems(s);
        knownItems.addAll(CultureManager.computeUnlockedItems(s));
        return new ResearchStateSyncPayload(
            new java.util.ArrayList<>(s.completedResearches()),
            s.activeResearch() == null ? "" : s.activeResearch(),
            ResearchStateSyncPayload.flattenProgress(s.researchProgress()),
            s.effectiveSciencePerSecond(),
            getCapacity(s.age()),
            new java.util.ArrayList<>(knownItems),
            new java.util.ArrayList<>(s.researchQueue()),
            InsightManager.counterEntries(s, InsightManager.TreeType.SCIENCE,
                ResearchTreeLoader.getAll().values()),
            InsightManager.firedNodeIds(s.firedInsights(), InsightManager.TreeType.SCIENCE)
        );
    }

    private static ResearchStateSyncPayload emptyStatePayload() {
        return new ResearchStateSyncPayload(
            java.util.List.of(),
            "",
            java.util.List.of(),
            0.0,
            getCapacity(Era.ANCIENT),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of()
        );
    }
}
