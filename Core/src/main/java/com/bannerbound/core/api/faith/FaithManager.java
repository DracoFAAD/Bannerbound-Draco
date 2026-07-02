package com.bannerbound.core.api.faith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.network.FaithStatePayload;
import com.bannerbound.core.network.OpenChooseFaithScreenPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * All faith mutations + the devotion tick (FAITH_PLAN.md). Mirrors the
 * SettlementManager/CultureManager split: settlements reference faiths by UUID, the
 * cross-faction {@link Faith} objects live in {@link FaithData}, and every change runs
 * through here so persistence + client sync stay consistent.
 *
 * <p>The founding vote copies the Choose-Government machinery: every online member votes,
 * majority wins, ties broadcast "divided" and reset. Options are string keys —
 * {@code found:ASTROLOGY}, {@code found:TOTEMIC}, {@code adopt:<faithUuid>} — so adopting
 * any existing faith rides the same vote as founding a new one.
 */
public final class FaithManager {
    /** M1 devotion source: a flat trickle per believer citizen. Shrine-appeal multipliers
     *  and capacity arrive with the shrine milestone (see the plan). */
    public static final double DEVOTION_PER_CITIZEN_PER_SECOND = 0.01;
    /** Each player who follows the faith (a member of a faithful settlement) adds a stronger trickle
     *  on top of their settlement's believer citizens — so a faith with more human followers, not just
     *  more towns, accrues devotion faster. */
    public static final double DEVOTION_PER_PLAYER_PER_SECOND = 0.05;
    /** Same majority rule as the government vote. */
    private static final double FAITH_VOTE_THRESHOLD_RATIO = 0.5;

    public static final String OPTION_FOUND_ASTROLOGY = "found:ASTROLOGY";
    public static final String OPTION_FOUND_TOTEMIC = "found:TOTEMIC";
    public static final String OPTION_ADOPT_PREFIX = "adopt:";

    private static int tickCounter = 0;

    private FaithManager() {
    }

    public static boolean addInsightProgress(MinecraftServer server, Faith faith,
                                             com.bannerbound.core.api.research.ResearchDefinition def,
                                             double points) {
        if (faith.completedResearches().contains(def.id()) || points <= 0.0) return false;
        faith.researchProgress().merge(def.id(), points, Double::sum);
        FaithData.get(server.overworld()).setDirty();
        if (faith.researchProgress().getOrDefault(def.id(), 0.0) >= def.cost()
                && isInsightCompletionEligible(server, faith, def)) {
            completeFaithResearch(server, faith, def);
            return true;
        }
        broadcastTreeState(server, faith);
        return false;
    }

    private static boolean isInsightCompletionEligible(
            MinecraftServer server, Faith faith,
            com.bannerbound.core.api.research.ResearchDefinition def) {
        if (def.faithPath() != null && def.faithPath() != faith.path()) return false;
        for (String prereq : def.prerequisites()) {
            if (!faith.completedResearches().contains(prereq)) return false;
        }
        int maxAge = -1;
        SettlementData data = SettlementData.get(server.overworld());
        for (UUID memberId : faith.memberSettlements()) {
            Settlement member = data.getById(memberId);
            if (member != null) maxAge = Math.max(maxAge, member.age().ordinal());
        }
        return maxAge >= def.minAge().ordinal();
    }

    // ── Devotion tick ────────────────────────────────────────────────────────────

    public static double devotionPerSecond(Settlement s) {
        if (!s.hasFaith()) return 0.0;
        // Stacks on three axes: believer citizens (population), the settlement's human followers
        // (its members — so two players sharing a town out-faith a lone founder), and any KINSHIP-god
        // self-boost from the pantheon. Summed per-settlement, faithDevotionPerSecond pools it across
        // every town, so a faith grows with citizens AND players AND territory — not just town count.
        return s.population() * DEVOTION_PER_CITIZEN_PER_SECOND
            + s.members().size() * DEVOTION_PER_PLAYER_PER_SECOND
            + s.faithEffects().devotion();
    }

    /** Recompute the passive bundle for every member of {@code faith} (instant feedback on
     *  pantheon change). FAITH_PLAN Part 3 — effects apply to ALL member settlements. */
    public static void recomputeFaithEffects(MinecraftServer server, Faith faith) {
        if (faith == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        for (UUID memberId : faith.memberSettlements()) {
            Settlement member = data.getById(memberId);
            if (member != null) FaithEffects.computeInto(member.faithEffects(), faith);
        }
    }

    /** Called once per server tick (ResearchEvents). Accrues devotion for every faithful
     *  settlement, fills each faith's active tree node from its summed member rate, and
     *  broadcasts faith state to members once per second. */
    public static void tickAll(MinecraftServer server) {
        if (server == null) return;
        if (server.overworld() == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        tickCounter++;
        boolean broadcastTick = (tickCounter % 20 == 0);
        boolean anyChange = false;

        for (Settlement s : data.all()) {
            if (s.hasFaith()) {
                // Refresh the passive bundle once a second — self-heals after world load
                // (transient bundle starts empty) and any missed mutation path.
                if (broadcastTick) {
                    Faith faith = FaithData.get(server.overworld()).byId(s.faithId());
                    FaithEffects.computeInto(s.faithEffects(), faith);
                }
                double rate = devotionPerSecond(s);
                if (rate > 0.0) {
                    s.setDevotionStored(s.devotionStored() + rate / 20.0);
                    anyChange = true;
                }
            }
            if (broadcastTick && (s.hasFaith() || s.faithChoiceWindowOpen())) {
                broadcastState(server, s);
            }
        }
        if (anyChange) data.setDirty();

        // Faith tree: per-FAITH shared progress — the RATE fills the active node (the
        // stockpile above is untouched; it pays for deeds). FAITH_PLAN Part 2.5.
        FaithData faiths = FaithData.get(server.overworld());
        boolean faithChange = false;
        for (Faith faith : faiths.all()) {
            for (Map.Entry<String, Double> progress
                    : new ArrayList<>(faith.researchProgress().entrySet())) {
                com.bannerbound.core.api.research.ResearchDefinition banked =
                    com.bannerbound.core.api.research.data.FaithTreeLoader.get(progress.getKey());
                if (banked != null && progress.getValue() >= banked.cost()
                        && isInsightCompletionEligible(server, faith, banked)) {
                    completeFaithResearch(server, faith, banked);
                    faithChange = true;
                }
            }
            String active = faith.activeResearch();
            if (active == null) continue;
            com.bannerbound.core.api.research.ResearchDefinition def =
                com.bannerbound.core.api.research.data.FaithTreeLoader.get(active);
            if (def == null) {
                faith.setActiveResearch(null);
                faithChange = true;
                continue;
            }
            double progress = faith.researchProgress().getOrDefault(active, 0.0)
                + faithDevotionPerSecond(server, faith) / 20.0;
            if (progress >= def.cost()) {
                completeFaithResearch(server, faith, def);
                faithChange = true;
            } else {
                faith.researchProgress().put(active, progress);
            }
            if (broadcastTick) broadcastTreeState(server, faith);
        }
        if (faithChange) faiths.setDirty();
    }

    // ── Faith tree (per-FAITH shared progress — FAITH_PLAN Part 2.5) ─────────────

    /** Total devotion rate pooling into the faith from ALL member settlements. */
    public static double faithDevotionPerSecond(MinecraftServer server, Faith faith) {
        SettlementData data = SettlementData.get(server.overworld());
        double total = 0.0;
        for (UUID memberId : faith.memberSettlements()) {
            Settlement member = data.getById(memberId);
            if (member != null) total += devotionPerSecond(member);
        }
        return total;
    }

    public static ResearchManagerResult tryStartFaithResearch(ServerPlayer player, String researchId) {
        MinecraftServer server = player.getServer();
        if (server == null) return ResearchManagerResult.NOT_IN_SETTLEMENT;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null || !settlement.hasFaith()) return ResearchManagerResult.NOT_IN_SETTLEMENT;
        Faith faith = FaithData.get(server.overworld()).byId(settlement.faithId());
        if (faith == null) return ResearchManagerResult.NOT_IN_SETTLEMENT;
        com.bannerbound.core.api.research.ResearchDefinition def =
            com.bannerbound.core.api.research.data.FaithTreeLoader.get(researchId);
        if (def == null) return ResearchManagerResult.UNKNOWN;
        if (faith.completedResearches().contains(researchId)) return ResearchManagerResult.ALREADY_COMPLETE;
        // Path gate: the other path's branch is invisible AND unresearchable.
        if (def.faithPath() != null && def.faithPath() != faith.path()) return ResearchManagerResult.UNKNOWN;
        for (String prereq : def.prerequisites()) {
            if (!faith.completedResearches().contains(prereq)) return ResearchManagerResult.PREREQ_MISSING;
        }
        if (def.minAge().ordinal() > settlement.age().ordinal()) return ResearchManagerResult.AGE_LOCKED;
        faith.setActiveResearch(researchId);
        FaithData.get(server.overworld()).setDirty();
        broadcastTreeState(server, faith);
        return ResearchManagerResult.OK;
    }

    /** Mirror of ResearchManager.StartResult, local so api.faith doesn't reach into its twin. */
    public enum ResearchManagerResult { OK, UNKNOWN, ALREADY_COMPLETE, PREREQ_MISSING, AGE_LOCKED, NOT_IN_SETTLEMENT }

    /** Right-click enqueue, mirroring CultureManager.tryEnqueue: toggle off active/queued,
     *  else append the node behind its unmet prerequisites (DFS post-order). No mutual
     *  exclusion — faith research runs in parallel with science/culture by design. */
    public static com.bannerbound.core.api.research.ResearchManager.EnqueueResult
            tryEnqueueFaithResearch(ServerPlayer player, String researchId) {
        MinecraftServer server = player.getServer();
        if (server == null) return com.bannerbound.core.api.research.ResearchManager.EnqueueResult.NOT_IN_SETTLEMENT;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null || !settlement.hasFaith()) {
            return com.bannerbound.core.api.research.ResearchManager.EnqueueResult.NOT_IN_SETTLEMENT;
        }
        FaithData faiths = FaithData.get(server.overworld());
        Faith faith = faiths.byId(settlement.faithId());
        if (faith == null) return com.bannerbound.core.api.research.ResearchManager.EnqueueResult.NOT_IN_SETTLEMENT;
        com.bannerbound.core.api.research.ResearchDefinition def =
            com.bannerbound.core.api.research.data.FaithTreeLoader.get(researchId);
        if (def == null || (def.faithPath() != null && def.faithPath() != faith.path())) {
            return com.bannerbound.core.api.research.ResearchManager.EnqueueResult.UNKNOWN_RESEARCH;
        }
        if (faith.completedResearches().contains(researchId)) {
            return com.bannerbound.core.api.research.ResearchManager.EnqueueResult.ALREADY_COMPLETE;
        }
        // Toggle: clicking active drops it (progress preserved); clicking queued removes it.
        if (researchId.equals(faith.activeResearch())) {
            faith.setActiveResearch(null);
            promoteFaithQueue(server, faith);
            faiths.setDirty();
            broadcastTreeState(server, faith);
            return com.bannerbound.core.api.research.ResearchManager.EnqueueResult.OK_REMOVED;
        }
        if (faith.researchQueue().contains(researchId)) {
            faith.researchQueue().remove(researchId);
            faiths.setDirty();
            broadcastTreeState(server, faith);
            return com.bannerbound.core.api.research.ResearchManager.EnqueueResult.OK_REMOVED;
        }
        if (def.minAge().ordinal() > settlement.age().ordinal()) {
            return com.bannerbound.core.api.research.ResearchManager.EnqueueResult.AGE_LOCKED;
        }
        java.util.List<String> chain = new ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        if (!buildFaithPrereqChain(researchId, faith, settlement, chain, visited)) {
            return com.bannerbound.core.api.research.ResearchManager.EnqueueResult.AGE_LOCKED;
        }
        for (String id : chain) {
            if (faith.completedResearches().contains(id)) continue;
            if (id.equals(faith.activeResearch())) continue;
            if (faith.researchQueue().contains(id)) continue;
            faith.researchQueue().add(id);
        }
        if (faith.activeResearch() == null && !faith.researchQueue().isEmpty()) {
            promoteFaithQueue(server, faith);
        }
        faiths.setDirty();
        broadcastTreeState(server, faith);
        return com.bannerbound.core.api.research.ResearchManager.EnqueueResult.OK;
    }

    private static boolean buildFaithPrereqChain(String researchId, Faith faith, Settlement ageRef,
                                                 java.util.List<String> out, java.util.Set<String> visited) {
        if (visited.contains(researchId)) return true;
        visited.add(researchId);
        com.bannerbound.core.api.research.ResearchDefinition def =
            com.bannerbound.core.api.research.data.FaithTreeLoader.get(researchId);
        if (def == null) return true;
        if (def.faithPath() != null && def.faithPath() != faith.path()) return false;
        if (def.minAge().ordinal() > ageRef.age().ordinal()) return false;
        for (String prereq : def.prerequisites()) {
            if (faith.completedResearches().contains(prereq)) continue;
            if (!buildFaithPrereqChain(prereq, faith, ageRef, out, visited)) return false;
        }
        out.add(researchId);
        return true;
    }

    /** Promotes the first runnable queued node. Age gate uses the faith's MOST progressed
     *  member settlement — the furthest believer defines what the faith can reach. */
    private static void promoteFaithQueue(MinecraftServer server, Faith faith) {
        int maxAge = 0;
        SettlementData data = SettlementData.get(server.overworld());
        for (UUID memberId : faith.memberSettlements()) {
            Settlement member = data.getById(memberId);
            if (member != null) maxAge = Math.max(maxAge, member.age().ordinal());
        }
        int i = 0;
        while (i < faith.researchQueue().size()) {
            String next = faith.researchQueue().get(i);
            com.bannerbound.core.api.research.ResearchDefinition d =
                com.bannerbound.core.api.research.data.FaithTreeLoader.get(next);
            if (d == null || faith.completedResearches().contains(next)
                    || (d.faithPath() != null && d.faithPath() != faith.path())) {
                faith.researchQueue().remove(i);
                continue;
            }
            boolean ageOk = d.minAge().ordinal() <= maxAge;
            boolean prereqsOk = true;
            for (String prereq : d.prerequisites()) {
                if (!faith.completedResearches().contains(prereq)) { prereqsOk = false; break; }
            }
            if (ageOk && prereqsOk) {
                faith.researchQueue().remove(i);
                faith.setActiveResearch(next);
                return;
            }
            i++;
        }
    }

    private static void completeFaithResearch(MinecraftServer server, Faith faith,
                                              com.bannerbound.core.api.research.ResearchDefinition def) {
        faith.completedResearches().add(def.id());
        faith.researchProgress().remove(def.id());
        faith.setActiveResearch(null);
        // Completed nodes apply to EVERY member settlement — the cross-faction payoff.
        SettlementData data = SettlementData.get(server.overworld());
        for (UUID memberId : faith.memberSettlements()) {
            Settlement member = data.getById(memberId);
            if (member == null) continue;
            com.bannerbound.core.api.research.ResearchManager.applyUnlockEffects(server, member, def);
            SettlementManager.broadcastToSettlement(server, member,
                Component.translatable("bannerbound.faith.research_complete", faith.name(), def.name())
                    .withStyle(ChatFormatting.GOLD));
        }
        data.setDirty();
        promoteFaithQueue(server, faith);
        broadcastTreeState(server, faith);
    }

    /** True while the settlement's faith has completed a node carrying {@code flag}. */
    public static boolean hasFaithFlag(MinecraftServer server, Settlement settlement, String flag) {
        if (settlement == null || !settlement.hasFaith()) return false;
        Faith faith = FaithData.get(server.overworld()).byId(settlement.faithId());
        if (faith == null) return false;
        for (String id : faith.completedResearches()) {
            com.bannerbound.core.api.research.ResearchDefinition def =
                com.bannerbound.core.api.research.data.FaithTreeLoader.get(id);
            if (def != null && def.unlocksFlags().contains(flag)) return true;
        }
        return false;
    }

    public static void broadcastTreeState(MinecraftServer server, Faith faith) {
        SettlementData data = SettlementData.get(server.overworld());
        com.bannerbound.core.network.FaithResearchStatePayload payload = buildTreeStatePayload(server, faith);
        for (UUID memberId : faith.memberSettlements()) {
            Settlement member = data.getById(memberId);
            if (member == null) continue;
            for (UUID playerId : member.members()) {
                ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                if (p != null) PacketDistributor.sendToPlayer(p, payload);
            }
        }
    }

    /** Login/datapack-sync push of the player's faith-tree state (no-op without a faith). */
    public static void sendTreeStateTo(MinecraftServer server, ServerPlayer player) {
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        if (settlement == null || !settlement.hasFaith()) return;
        Faith faith = FaithData.get(server.overworld()).byId(settlement.faithId());
        if (faith == null) return;
        PacketDistributor.sendToPlayer(player, buildTreeStatePayload(server, faith));
    }

    private static com.bannerbound.core.network.FaithResearchStatePayload buildTreeStatePayload(
            MinecraftServer server, Faith faith) {
        List<com.bannerbound.core.network.ResearchStateSyncPayload.ProgressEntry> progress = new ArrayList<>();
        for (Map.Entry<String, Double> e : faith.researchProgress().entrySet()) {
            progress.add(new com.bannerbound.core.network.ResearchStateSyncPayload.ProgressEntry(
                e.getKey(), e.getValue()));
        }
        return new com.bannerbound.core.network.FaithResearchStatePayload(
            new ArrayList<>(faith.completedResearches()),
            faith.activeResearch() == null ? "" : faith.activeResearch(),
            progress,
            faithDevotionPerSecond(server, faith),
            new ArrayList<>(faith.researchQueue()),
            com.bannerbound.core.api.research.InsightManager.counterEntries(
                faith, com.bannerbound.core.api.research.InsightManager.TreeType.FAITH,
                com.bannerbound.core.api.research.data.FaithTreeLoader.getAll().values()),
            com.bannerbound.core.api.research.InsightManager.firedNodeIds(
                faith.firedInsights(), com.bannerbound.core.api.research.InsightManager.TreeType.FAITH));
    }

    // ── Founding / adoption ──────────────────────────────────────────────────────

    /** The Spiritualism feature fired: open the window + announce it. Idempotent. */
    public static void unlockFounding(MinecraftServer server, Settlement settlement) {
        if (settlement.faithFoundingUnlocked()) return;
        settlement.setFaithFoundingUnlocked(true);
        SettlementData.get(server.overworld()).setDirty();
        SettlementManager.broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.faith.founding_unlocked")
                .withStyle(ChatFormatting.GOLD));
        broadcastState(server, settlement);
    }

    public static Faith foundFaith(MinecraftServer server, Settlement settlement,
                                   FaithPath path, String name) {
        FaithData faiths = FaithData.get(server.overworld());
        Faith faith = faiths.createFaith(name, path, settlement.id());
        settlement.setFaithId(faith.id());
        settlement.clearFaithVote();
        SettlementData.get(server.overworld()).setDirty();
        SettlementManager.broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.faith.founded", name,
                Component.translatable(pathKey(path)))
                .withStyle(ChatFormatting.GREEN));
        playFanfare(server, settlement);
        recomputeFaithEffects(server, faith);
        broadcastState(server, settlement);
        return faith;
    }

    public static boolean adoptFaith(MinecraftServer server, Settlement settlement, UUID faithId) {
        FaithData faiths = FaithData.get(server.overworld());
        Faith faith = faiths.byId(faithId);
        if (faith == null) return false;
        faith.addMember(settlement.id());
        faiths.setDirty();
        settlement.setFaithId(faith.id());
        settlement.clearFaithVote();
        SettlementData.get(server.overworld()).setDirty();
        SettlementManager.broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.faith.adopted", faith.name())
                .withStyle(ChatFormatting.GREEN));
        playFanfare(server, settlement);
        // Adopting an established faith inherits its drawn gods — apply their passives now.
        FaithEffects.computeInto(settlement.faithEffects(), faith);
        broadcastState(server, settlement);
        return true;
    }

    // ── Constellations (FAITH_PLAN M2 — Pantheon mode) ──────────────────────────

    /** Base pantheon size — research grows it (each completed faith node carrying the
     *  {@code bannerbound.pantheon_slot} flag adds one god). */
    public static final int BASE_PANTHEON_CAP = 1;
    public static final String PANTHEON_SLOT_FLAG = "bannerbound.pantheon_slot";
    /** Pantheon mode + constellation drawing unlock (granted by Star Charts). */
    public static final String STAR_CHARTS_FLAG = "bannerbound.star_charts";
    /** Devotion cost of the Nth god: BASE × N — pantheons get pricier as they grow. */
    public static final double CONSTELLATION_BASE_COST = 25.0;

    /** Research-driven pantheon cap: base 1 + one slot per completed pantheon_slot node. */
    public static int pantheonCap(Faith faith) {
        int cap = BASE_PANTHEON_CAP;
        for (String id : faith.completedResearches()) {
            com.bannerbound.core.api.research.ResearchDefinition def =
                com.bannerbound.core.api.research.data.FaithTreeLoader.get(id);
            if (def != null && def.unlocksFlags().contains(PANTHEON_SLOT_FLAG)) cap++;
        }
        return cap;
    }

    private static com.bannerbound.core.celestial.SkyField cachedSky;
    private static long cachedSkySeed;
    private static int cachedSkyYear;

    /** The server's authoritative sky — same seed+calendar the clients render. */
    public static com.bannerbound.core.celestial.SkyField sky(MinecraftServer server) {
        long seed = FaithData.get(server.overworld()).skySeed();
        int year = new com.bannerbound.core.celestial.WorldCalendar(
            com.bannerbound.core.Config.calendarMonthDays()).yearDays();
        if (cachedSky == null || cachedSkySeed != seed || cachedSkyYear != year) {
            cachedSky = com.bannerbound.core.celestial.SkyField.generate(seed, year);
            cachedSkySeed = seed;
            cachedSkyYear = year;
        }
        return cachedSky;
    }

    /**
     * The confirm transaction (FAITH_PLAN: server is authoritative, first confirm wins).
     * Governance gates WHO submits — chief/owner decide alone; COUNCIL currently lets any
     * member confirm (the pick-one-of-N ballot is the next governance pass). Validation:
     * astrology path, 3–12 stars, valid + unclaimed ids, pantheon cap, per-faith name
     * uniqueness, devotion cost from the SUBMITTER settlement's stockpile, and at least
     * one typed star (every god has a domain).
     */
    public static void submitConstellation(ServerPlayer player, String rawName,
                                           String rawDeity, int[] starIds) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null || !settlement.hasFaith()) return;
        FaithData faiths = FaithData.get(server.overworld());
        Faith faith = faiths.byId(settlement.faithId());
        if (faith == null || faith.path() != FaithPath.ASTROLOGY) return;

        Settlement.Government gov = settlement.governmentType();
        if (gov == Settlement.Government.CHIEFDOM || gov == Settlement.Government.NONE) {
            java.util.UUID leader = gov == Settlement.Government.CHIEFDOM
                ? settlement.chiefPlayerId() : settlement.owner();
            if (!player.getUUID().equals(leader)) {
                player.sendSystemMessage(Component.translatable("bannerbound.faith.vote.leader_only")
                    .withStyle(ChatFormatting.YELLOW));
                return;
            }
        }

        // Star Charts gates the drawing itself — the client checks too; reject tampering.
        if (!faith.completedResearches().contains("bannerboundantiquity:star_charts")
                && !hasFaithFlag(server, settlement, STAR_CHARTS_FLAG)) {
            player.sendSystemMessage(Component.translatable("bannerbound.pantheon.uncharted")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        String name = rawName == null ? "" : rawName.trim();
        String deity = rawDeity == null ? "" : rawDeity.trim();
        if (name.isBlank() || deity.isBlank()) {
            player.sendSystemMessage(Component.translatable("bannerbound.faith.constellation.name_required")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        int cap = pantheonCap(faith);
        if (faith.constellations().size() >= cap) {
            player.sendSystemMessage(Component.translatable("bannerbound.faith.constellation.cap",
                cap).withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (starIds.length < 3 || starIds.length > 12) {
            player.sendSystemMessage(Component.translatable("bannerbound.faith.constellation.invalid")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        com.bannerbound.core.celestial.SkyField sky = sky(server);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int id : starIds) {
            if (!sky.isValidStarId(id) || !seen.add(id) || faith.starUsed(id)) {
                // "The heavens have shifted" — a star got claimed (or the packet is junk).
                player.sendSystemMessage(Component.translatable("bannerbound.faith.constellation.star_taken")
                    .withStyle(ChatFormatting.YELLOW));
                return;
            }
        }
        for (Constellation existing : faith.constellations()) {
            if (existing.name().equalsIgnoreCase(name) || existing.deityName().equalsIgnoreCase(deity)) {
                player.sendSystemMessage(Component.translatable("bannerbound.faith.constellation.name_taken")
                    .withStyle(ChatFormatting.YELLOW));
                return;
            }
        }
        // Domain profile from typed members (hybrid rules: primary = dominant type,
        // secondary = second type with ≥2 stars; ties resolve in enum order for now —
        // the drawer-picks tie UI is later polish).
        java.util.Map<DeityDomain, Integer> counts = new java.util.EnumMap<>(DeityDomain.class);
        for (int id : starIds) {
            com.bannerbound.core.celestial.SkyField.Star typed = sky.typedStar(id);
            if (typed != null) {
                counts.merge(DeityDomain.fromStarType(typed.type), 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            player.sendSystemMessage(Component.translatable("bannerbound.faith.constellation.no_typed")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        DeityDomain primary = null;
        int primaryCount = 0;
        for (java.util.Map.Entry<DeityDomain, Integer> e : counts.entrySet()) {
            if (e.getValue() > primaryCount) {
                primary = e.getKey();
                primaryCount = e.getValue();
            }
        }
        DeityDomain secondary = null;
        int secondaryCount = 0;
        for (java.util.Map.Entry<DeityDomain, Integer> e : counts.entrySet()) {
            if (e.getKey() != primary && e.getValue() >= 2 && e.getValue() > secondaryCount) {
                secondary = e.getKey();
                secondaryCount = e.getValue();
            }
        }
        double cost = CONSTELLATION_BASE_COST * (faith.constellations().size() + 1);
        if (settlement.devotionStored() < cost) {
            player.sendSystemMessage(Component.translatable("bannerbound.faith.constellation.devotion",
                String.format("%.0f", cost)).withStyle(ChatFormatting.YELLOW));
            return;
        }

        settlement.setDevotionStored(settlement.devotionStored() - cost);
        faith.constellations().add(new Constellation(
            UUID.randomUUID(), name, deity, starIds, primary, secondary));
        faiths.setDirty();
        data.setDirty();
        for (UUID memberId : faith.memberSettlements()) {
            Settlement member = data.getById(memberId);
            if (member == null) continue;
            SettlementManager.broadcastToSettlement(server, member,
                Component.translatable("bannerbound.faith.constellation.confirmed",
                    deity, name).withStyle(ChatFormatting.GOLD));
        }
        playFanfare(server, settlement);
        recomputeFaithEffects(server, faith);
        syncConstellations(server, faith);
        broadcastState(server, settlement);
    }

    /** Forget a god (governance-gated like creation): the constellation fades, its stars
     *  return to the sky's pool. No devotion refund — the gods do not give back. */
    public static void forgetConstellation(ServerPlayer player, String constellationId) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null || !settlement.hasFaith()) return;
        FaithData faiths = FaithData.get(server.overworld());
        Faith faith = faiths.byId(settlement.faithId());
        if (faith == null) return;
        Settlement.Government gov = settlement.governmentType();
        if (gov == Settlement.Government.CHIEFDOM || gov == Settlement.Government.NONE) {
            java.util.UUID leader = gov == Settlement.Government.CHIEFDOM
                ? settlement.chiefPlayerId() : settlement.owner();
            if (!player.getUUID().equals(leader)) {
                player.sendSystemMessage(Component.translatable("bannerbound.faith.vote.leader_only")
                    .withStyle(ChatFormatting.YELLOW));
                return;
            }
        }
        UUID id;
        try {
            id = UUID.fromString(constellationId);
        } catch (IllegalArgumentException ex) {
            return;
        }
        Constellation removed = null;
        for (Constellation c : faith.constellations()) {
            if (c.id().equals(id)) {
                removed = c;
                break;
            }
        }
        if (removed == null) return;
        faith.constellations().remove(removed);
        faiths.setDirty();
        for (UUID memberId : faith.memberSettlements()) {
            Settlement member = data.getById(memberId);
            if (member == null) continue;
            SettlementManager.broadcastToSettlement(server, member,
                Component.translatable("bannerbound.faith.constellation.forgotten",
                    removed.deityName(), removed.name()).withStyle(ChatFormatting.YELLOW));
        }
        recomputeFaithEffects(server, faith);
        syncConstellations(server, faith);
    }

    public static void syncConstellations(MinecraftServer server, Faith faith) {
        SettlementData data = SettlementData.get(server.overworld());
        com.bannerbound.core.network.ConstellationsSyncPayload payload = buildConstellationsPayload(faith);
        for (UUID memberId : faith.memberSettlements()) {
            Settlement member = data.getById(memberId);
            if (member == null) continue;
            for (UUID playerId : member.members()) {
                ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                if (p != null) PacketDistributor.sendToPlayer(p, payload);
            }
        }
    }

    /** Login push (no-op for the faithless — they also get an empty list to clear stale state). */
    public static void sendConstellationsTo(MinecraftServer server, ServerPlayer player) {
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        Faith faith = settlement == null ? null
            : FaithData.get(server.overworld()).byId(settlement.faithId());
        PacketDistributor.sendToPlayer(player, faith == null
            ? new com.bannerbound.core.network.ConstellationsSyncPayload(new ArrayList<>())
            : buildConstellationsPayload(faith));
    }

    private static com.bannerbound.core.network.ConstellationsSyncPayload buildConstellationsPayload(Faith faith) {
        List<com.bannerbound.core.network.ConstellationsSyncPayload.Entry> entries = new ArrayList<>();
        for (Constellation c : faith.constellations()) {
            entries.add(new com.bannerbound.core.network.ConstellationsSyncPayload.Entry(
                c.id().toString(), c.name(), c.deityName(), c.primaryDomain().ordinal(),
                c.secondaryDomain() == null ? -1 : c.secondaryDomain().ordinal(),
                c.starIds()));
        }
        return new com.bannerbound.core.network.ConstellationsSyncPayload(entries);
    }

    /** The Choose-Faith window, including the apostasy rejoin cooldown. */
    public static boolean choiceWindowOpen(MinecraftServer server, Settlement settlement) {
        return settlement.faithChoiceWindowOpen()
            && server.overworld().getGameTime() >= settlement.faithRejoinAfterGameTime();
    }

    /** A member clicked Abandon Faith. Chief/owner decide alone (like founding); under
     *  COUNCIL it's a yes-only vote — resolution when half the online members have clicked
     *  (closing the screen = abstain, mirroring the disband vote's shape). */
    public static void handleAbandonFaith(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null || !settlement.hasFaith()) return;

        Settlement.Government gov = settlement.governmentType();
        if (gov == Settlement.Government.CHIEFDOM || gov == Settlement.Government.NONE) {
            java.util.UUID leader = gov == Settlement.Government.CHIEFDOM
                ? settlement.chiefPlayerId() : settlement.owner();
            if (!player.getUUID().equals(leader)) {
                player.sendSystemMessage(Component.translatable("bannerbound.faith.vote.leader_only")
                    .withStyle(ChatFormatting.YELLOW));
                return;
            }
            leaveFaith(server, settlement);
            return;
        }
        // COUNCIL: yes-only tally.
        if (!settlement.abandonFaithVotes().add(player.getUUID())) return; // already voted
        int online = SettlementManager.countOnlineMembers(server, settlement);
        int needed = (int) Math.ceil(online * FAITH_VOTE_THRESHOLD_RATIO);
        int votes = 0;
        for (UUID voter : settlement.abandonFaithVotes()) {
            if (server.getPlayerList().getPlayer(voter) != null) votes++;
        }
        if (votes >= needed) {
            leaveFaith(server, settlement);
        } else {
            SettlementManager.broadcastToSettlement(server, settlement,
                Component.translatable("bannerbound.faith.abandon_progress", votes, needed)
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    /** Apostasy proper (FAITH_PLAN Part 1): membership severed, ALL devotion lost, every
     *  citizen grieves ({@code FORSOOK_THE_GODS}), and a rejoin cooldown opens before the
     *  Choose-Faith window returns. Empty faiths are deleted — gods fade unbelieved. */
    public static void leaveFaith(MinecraftServer server, Settlement settlement) {
        FaithData faiths = FaithData.get(server.overworld());
        Faith faith = faiths.byId(settlement.faithId());
        String faithName = faith != null ? faith.name() : "?";
        if (faith != null) {
            faith.removeMember(settlement.id());
            faiths.removeIfEmpty(faith.id());
            faiths.setDirty();
        }
        settlement.setFaithId(null);
        settlement.faithEffects().clear();
        settlement.setDevotionStored(0.0);
        settlement.clearFaithVote();
        long now = server.overworld().getGameTime();
        settlement.setFaithRejoinAfterGameTime(now + 48_000L); // 2 in-game days
        SettlementData.get(server.overworld()).setDirty();
        for (com.bannerbound.core.entity.CitizenEntity citizen
                : SettlementManager.allCitizensOf(server.overworld(), settlement)) {
            citizen.getThoughts().add(com.bannerbound.core.social.ThoughtKind.FORSOOK_THE_GODS,
                null, now, server.overworld().random);
        }
        SettlementManager.broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.faith.abandoned", settlement.name(), faithName)
                .withStyle(ChatFormatting.RED));
        broadcastState(server, settlement);
    }

    /** Op/debug (/bannerbound reset_religion): strips the settlement's faith — membership,
     *  devotion, pending votes. The founding window REOPENS if Spiritualism is researched
     *  (faithFoundingUnlocked is untouched). A faith with no members left is deleted. */
    public static boolean resetReligion(MinecraftServer server, Settlement settlement) {
        boolean hadAnything = settlement.hasFaith()
            || !settlement.faithVotes().isEmpty() || settlement.devotionStored() > 0.0;
        FaithData faiths = FaithData.get(server.overworld());
        Faith faith = faiths.byId(settlement.faithId());
        if (faith != null) {
            faith.removeMember(settlement.id());
            faiths.removeIfEmpty(faith.id());
            faiths.setDirty();
        }
        settlement.setFaithId(null);
        settlement.faithEffects().clear();
        settlement.setDevotionStored(0.0);
        settlement.clearFaithVote();
        SettlementData.get(server.overworld()).setDirty();
        broadcastState(server, settlement);
        return hadAnything;
    }

    /** found_religion.ogg at every online member's position — everyone hears the moment,
     *  wherever they are (celebrateGovernmentEnacted pattern). */
    private static void playFanfare(MinecraftServer server, Settlement settlement) {
        for (UUID member : settlement.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(member);
            if (p == null) continue;
            p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(),
                com.bannerbound.core.BannerboundCore.FOUND_RELIGION_SOUND.get(),
                net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
        }
    }

    // ── The Choose-Faith vote (government-vote machinery) ───────────────────────

    public static void handleFaithVote(ServerPlayer player, String optionKey, String proposedName) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) return;
        if (!choiceWindowOpen(server, settlement)) {
            player.sendSystemMessage(Component.translatable("bannerbound.faith.vote.not_open")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (!isValidOption(server, optionKey)) return;
        // Founding REQUIRES a name (the client enforces this too — reject tampered packets;
        // no settlement worships at the altar of "<Town> Stars" by accident).
        if (optionKey.startsWith("found:") && (proposedName == null || proposedName.isBlank())) {
            player.sendSystemMessage(Component.translatable("bannerbound.faith.name.required")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }

        // Government decides WHO chooses (FAITH_PLAN: governance gates who submits).
        // CHIEFDOM → the Chief decides alone, instantly. No government yet → the owner.
        // COUNCIL → majority vote of all online members.
        Settlement.Government gov = settlement.governmentType();
        if (gov == Settlement.Government.CHIEFDOM || gov == Settlement.Government.NONE) {
            java.util.UUID leader = gov == Settlement.Government.CHIEFDOM
                ? settlement.chiefPlayerId() : settlement.owner();
            if (!player.getUUID().equals(leader)) {
                player.sendSystemMessage(Component.translatable("bannerbound.faith.vote.leader_only")
                    .withStyle(ChatFormatting.YELLOW));
                return;
            }
            executeChoice(server, settlement, data, optionKey, proposedName);
            return;
        }

        if (settlement.faithVotes().containsKey(player.getUUID())) return; // locked, no retract
        settlement.castFaithVote(player.getUUID(), optionKey, proposedName);
        tryResolveFaithVote(server, settlement, data);
    }

    private static boolean isValidOption(MinecraftServer server, String optionKey) {
        if (OPTION_FOUND_ASTROLOGY.equals(optionKey) || OPTION_FOUND_TOTEMIC.equals(optionKey)) {
            return true;
        }
        if (optionKey.startsWith(OPTION_ADOPT_PREFIX)) {
            try {
                UUID id = UUID.fromString(optionKey.substring(OPTION_ADOPT_PREFIX.length()));
                return FaithData.get(server.overworld()).byId(id) != null;
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
        return false;
    }

    private static void tryResolveFaithVote(MinecraftServer server, Settlement settlement,
                                            SettlementData data) {
        int online = SettlementManager.countOnlineMembers(server, settlement);
        if (online <= 0) return;
        // Wait until every online member has voted (offline members forfeit, same as government).
        for (UUID member : settlement.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(member);
            if (p != null && !settlement.faithVotes().containsKey(member)) return;
        }
        int needed = (int) Math.ceil(online * FAITH_VOTE_THRESHOLD_RATIO);

        // Plurality across ALL options cast, majority threshold, strict winner.
        String winner = null;
        int winnerCount = 0;
        boolean tie = false;
        for (Map.Entry<UUID, String> vote : settlement.faithVotes().entrySet()) {
            String option = vote.getValue();
            int count = settlement.faithVoteCountFor(option);
            if (count > winnerCount) {
                winner = option;
                winnerCount = count;
                tie = false;
            } else if (count == winnerCount && !option.equals(winner)) {
                tie = true;
            }
        }
        if (winner == null || tie || winnerCount < needed) {
            settlement.clearFaithVote();
            SettlementManager.broadcastToSettlement(server, settlement,
                Component.translatable("bannerbound.faith.vote.divided")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }

        executeChoice(server, settlement, data, winner, settlement.faithNameProposalFor(winner));
    }

    /** Final execution of a resolved choice — shared by the council vote and the
     *  chief/owner direct pick. */
    private static void executeChoice(MinecraftServer server, Settlement settlement,
                                      SettlementData data, String optionKey, String name) {
        if (optionKey.startsWith(OPTION_ADOPT_PREFIX)) {
            adoptFaith(server, settlement,
                UUID.fromString(optionKey.substring(OPTION_ADOPT_PREFIX.length())));
        } else {
            FaithPath path = OPTION_FOUND_TOTEMIC.equals(optionKey)
                ? FaithPath.TOTEMIC : FaithPath.ASTROLOGY;
            // Names are required at cast time; this fallback only guards data corruption.
            String finalName = (name == null || name.isBlank())
                ? settlement.name() + " Faith" : name.trim();
            foundFaith(server, settlement, path, finalName);
        }
        data.setDirty();
    }

    private static String pathKey(FaithPath path) {
        return path == FaithPath.TOTEMIC
            ? "bannerbound.faith.path.totemic" : "bannerbound.faith.path.astrology";
    }

    // ── Client sync ──────────────────────────────────────────────────────────────

    public static void broadcastState(MinecraftServer server, Settlement settlement) {
        FaithStatePayload payload = buildStatePayload(server, settlement);
        for (UUID member : settlement.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(member);
            if (p != null) PacketDistributor.sendToPlayer(p, payload);
        }
    }

    public static void sendStateTo(MinecraftServer server, Settlement settlement, ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, buildStatePayload(server, settlement));
    }

    private static FaithStatePayload buildStatePayload(MinecraftServer server, Settlement settlement) {
        Faith faith = FaithData.get(server.overworld()).byId(settlement.faithId());
        if (faith == null) {
            return new FaithStatePayload(false, "", 0, 0, 0.0, 0.0,
                choiceWindowOpen(server, settlement));
        }
        return new FaithStatePayload(true, faith.name(), faith.path().ordinal(),
            faith.memberSettlements().size(), settlement.devotionStored(),
            devotionPerSecond(settlement), false);
    }

    /** Builds the Choose-Faith screen snapshot for one player: tallies + adoptable faiths. */
    public static OpenChooseFaithScreenPayload buildScreenPayload(MinecraftServer server,
                                                                  Settlement settlement,
                                                                  ServerPlayer player) {
        FaithData faiths = FaithData.get(server.overworld());
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Integer> paths = new ArrayList<>();
        List<Integer> memberCounts = new ArrayList<>();
        List<Integer> adoptVotes = new ArrayList<>();
        for (Faith faith : faiths.all()) {
            ids.add(faith.id().toString());
            names.add(faith.name());
            paths.add(faith.path().ordinal());
            memberCounts.add(faith.memberSettlements().size());
            adoptVotes.add(settlement.faithVoteCountFor(OPTION_ADOPT_PREFIX + faith.id()));
        }
        String playerVote = settlement.faithVotes().getOrDefault(player.getUUID(), "");
        return new OpenChooseFaithScreenPayload(
            settlement.faithVoteCountFor(OPTION_FOUND_ASTROLOGY),
            settlement.faithVoteCountFor(OPTION_FOUND_TOTEMIC),
            SettlementManager.countOnlineMembers(server, settlement),
            playerVote, ids, names, paths, memberCounts, adoptVotes);
    }
}
