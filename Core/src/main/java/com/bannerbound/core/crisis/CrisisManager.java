package com.bannerbound.core.crisis;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.api.settlement.StatusEffect;
import com.bannerbound.core.api.settlement.StatusEffectIcon;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.WorkerPathing;
import com.bannerbound.core.journal.JournalEntry;
import com.bannerbound.core.journal.JournalEntryType;
import com.bannerbound.core.journal.JournalManager;
import com.bannerbound.core.journal.JournalObjective;
import com.bannerbound.core.network.CrisisStatePayload;
import com.bannerbound.core.network.OpenCrisisScreenPayload;
import com.bannerbound.core.social.ThoughtKind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-side scripted-crisis runtime. */
public final class CrisisManager {
    private static final String SOURCE_TYPE = "crisis";
    private static final Map<String, Integer> PASSIVE_TRIGGER_STREAKS = new HashMap<>();
    private static final Map<String, PendingCrisisStart> PENDING_STARTS = new HashMap<>();

    private CrisisManager() {
    }

    public static void tickAll(MinecraftServer server) {
        if (server == null) return;
        long now = server.overworld().getGameTime();
        boolean passiveTick = now % 20L == 0L;
        if (!passiveTick && PENDING_STARTS.isEmpty()) return;
        SettlementData data = SettlementData.get(server.overworld());
        boolean dirty = false;
        for (Settlement settlement : data.all()) {
            CrisisState active = settlement.activeCrisis();
            if (active != null) {
                PENDING_STARTS.remove(pendingKey(settlement));
                if (passiveTick) dirty |= tickActive(server, settlement, active, now);
                continue;
            }
            // Frozen "in amber": no new crises start (pending or passive-trigger) while every
            // member is offline. Dormancy is refreshed at the top of the tick (ResearchEvents).
            if (settlement.isDormant()) continue;
            dirty |= tickPendingStart(server, settlement, now);
            if (!passiveTick || settlement.activeCrisis() != null) continue;
            dirty |= tryStartMatching(server, settlement,
                def -> passiveTriggerMatches(server.overworld(), settlement, def, 20),
                false);
        }
        if (dirty) data.setDirty();
    }

    private static boolean tickActive(MinecraftServer server, Settlement settlement, CrisisState state, long now) {
        if (state.resolved()) {
            settlement.setActiveCrisis(null);
            broadcastState(server, settlement);
            return true;
        }
        CrisisDefinition def = CrisisDefinitionLoader.get(state.crisisId());
        if (def == null) return false;
        if (!state.hasChoice()) return false;
        JournalUpdate update = updateJournal(server, settlement, def, state, false, now);
        if (allObjectivesComplete(server, settlement, def, state)) {
            completeCrisis(server, settlement, def, state, now);
            return true;
        }
        if (update.changed()) JournalManager.broadcastSettlement(server, settlement);
        return update.changed();
    }

    public static void onGovernmentEnacted(MinecraftServer server, Settlement settlement) {
        tryStartMatching(server, settlement, def -> {
            CrisisDefinition.Trigger trigger = def.trigger();
            if (!trigger.type().equalsIgnoreCase("government_enacted")
                    && !trigger.type().equalsIgnoreCase("government_selected")) {
                return false;
            }
            if (!trigger.government().isBlank()
                    && !trigger.government().equalsIgnoreCase(settlement.governmentType().name())) {
                return false;
            }
            return trigger.target().isBlank()
                || trigger.target().equalsIgnoreCase("tribe")
                || trigger.target().equalsIgnoreCase(settlement.governmentType().name());
        }, true);
    }

    public static void onResearchCompleted(MinecraftServer server, Settlement settlement, String researchId) {
        tryStartMatching(server, settlement, def -> {
            CrisisDefinition.Trigger trigger = def.trigger();
            return trigger.type().equalsIgnoreCase("research_completed")
                && matchesTarget(trigger.target(), researchId);
        }, true);
    }

    public static void onItemObtained(MinecraftServer server, Settlement settlement, String itemId, int count) {
        tryStartMatching(server, settlement, def -> {
            CrisisDefinition.Trigger trigger = def.trigger();
            return trigger.type().equalsIgnoreCase("item_obtained")
                && count >= Math.max(1, trigger.count())
                && matchesTarget(trigger.target(), itemId);
        }, true);
    }

    public static void onItemCrafted(MinecraftServer server, Settlement settlement, String itemId, int count) {
        tryStartMatching(server, settlement, def -> {
            CrisisDefinition.Trigger trigger = def.trigger();
            return trigger.type().equalsIgnoreCase("item_crafted")
                && count >= Math.max(1, trigger.count())
                && matchesTarget(trigger.target(), itemId);
        }, true);
    }

    public static void onBlockPlaced(MinecraftServer server, Settlement settlement, String blockId) {
        tryStartMatching(server, settlement, def -> {
            CrisisDefinition.Trigger trigger = def.trigger();
            return (trigger.type().equalsIgnoreCase("block_placed")
                    || trigger.type().equalsIgnoreCase("block_place"))
                && matchesTarget(trigger.target(), blockId);
        }, true);
    }

    public static void onBlockBroken(MinecraftServer server, Settlement settlement, String blockId) {
        tryStartMatching(server, settlement, def -> {
            CrisisDefinition.Trigger trigger = def.trigger();
            return (trigger.type().equalsIgnoreCase("block_broken")
                    || trigger.type().equalsIgnoreCase("block_break"))
                && matchesTarget(trigger.target(), blockId);
        }, true);
    }

    private static boolean tryStartMatching(MinecraftServer server, Settlement settlement,
                                            Predicate<CrisisDefinition> predicate,
                                            boolean dirtyIfStarted) {
        if (server == null || settlement == null || settlement.activeCrisis() != null) return false;
        String pendingKey = pendingKey(settlement);
        if (PENDING_STARTS.containsKey(pendingKey)) return false;
        List<CrisisDefinition> matches = new ArrayList<>();
        for (CrisisDefinition def : CrisisDefinitionLoader.getAll().values()) {
            if (settlement.completedCrises().contains(def.id())) continue;
            if (settlement.failedCrises().contains(def.id())) continue;
            // A crisis flagged requires_government waits for a tribe: an anarchy settlement (no
            // government enacted) has no chief/council to answer it, so it must not fire yet.
            if (def.trigger().requiresGovernment()
                    && settlement.governmentType() == Settlement.Government.NONE) continue;
            if (predicate.test(def)) matches.add(def);
        }
        if (matches.isEmpty()) return false;
        matches.sort(Comparator.comparingInt(CrisisDefinition::priority).reversed());
        CrisisDefinition chosen = matches.get(0);
        int delayTicks = chosen.trigger().delayTicks();
        if (delayTicks > 0) {
            PENDING_STARTS.put(pendingKey,
                new PendingCrisisStart(chosen.id(), server.overworld().getGameTime() + delayTicks));
            if (dirtyIfStarted) SettlementData.get(server.overworld()).setDirty();
            return true;
        }
        startCrisis(server, settlement, chosen);
        if (dirtyIfStarted) SettlementData.get(server.overworld()).setDirty();
        return true;
    }

    private static boolean tickPendingStart(MinecraftServer server, Settlement settlement, long now) {
        String key = pendingKey(settlement);
        PendingCrisisStart pending = PENDING_STARTS.get(key);
        if (pending == null) return false;
        CrisisDefinition def = CrisisDefinitionLoader.get(pending.crisisId());
        if (def == null
                || settlement.completedCrises().contains(def.id())
                || settlement.failedCrises().contains(def.id())) {
            PENDING_STARTS.remove(key);
            return false;
        }
        if (now < pending.dueTick()) return false;
        PENDING_STARTS.remove(key);
        startCrisis(server, settlement, def);
        return true;
    }

    private static boolean passiveTriggerMatches(ServerLevel level, Settlement settlement,
                                                 CrisisDefinition def, int tickStep) {
        if (level == null || settlement == null || def == null) return false;
        CrisisDefinition.Trigger trigger = def.trigger();
        String type = trigger.type().toLowerCase(Locale.ROOT);
        boolean matches = switch (type) {
            case "population" -> settlement.population() >= Math.max(1, trigger.count());
            case "territory_biome", "claimed_biome", "biome" ->
                hasClaimedBiome(level, settlement, firstNonBlank(trigger.biome(), trigger.target()));
            case "territory_condition", "claimed_condition", "territory" ->
                requirementPasses(level, settlement,
                    new CrisisViabilityRequirement(firstNonBlank(trigger.target(), trigger.source()), ""));
            case "resource_rate", "food_rate", "food_source_rate", "source_rate" ->
                compare(valueForResourceTrigger(settlement, trigger), trigger.rate(), trigger.comparison(), true);
            case "food_surplus" ->
                compare(settlement.effectiveFoodPerSecond(), trigger.rate(), trigger.comparison(), true);
            case "stored_food" ->
                compare(settlement.foodStored(), trigger.rate() > 0.0 ? trigger.rate() : trigger.count(),
                    trigger.comparison(), true);
            case "resource_below", "food_shortage", "starvation", "repeated_shortage" ->
                foodShortageMatches(settlement, trigger);
            case "homeless_count", "homeless", "housing_demand" ->
                settlement.homelessCitizens().size() >= Math.max(1, trigger.count());
            default -> false;
        };
        return durationGate(settlement, def, matches, trigger.durationTicks(), tickStep);
    }

    private static boolean durationGate(Settlement settlement, CrisisDefinition def,
                                        boolean matches, int durationTicks, int tickStep) {
        if (durationTicks <= 0) return matches;
        String key = settlement.id() + "|" + def.id();
        if (!matches) {
            PASSIVE_TRIGGER_STREAKS.remove(key);
            return false;
        }
        int next = PASSIVE_TRIGGER_STREAKS.getOrDefault(key, 0) + Math.max(1, tickStep);
        PASSIVE_TRIGGER_STREAKS.put(key, next);
        return next >= durationTicks;
    }

    private static boolean foodShortageMatches(Settlement settlement, CrisisDefinition.Trigger trigger) {
        double threshold = trigger.rate() > 0.0 ? trigger.rate() : 0.0;
        boolean storedLow = settlement.foodStored() <= Math.max(0, trigger.count());
        double rate = trigger.source().isBlank()
            ? settlement.effectiveFoodPerSecond()
            : settlement.foodSourceRate(trigger.source());
        return settlement.isStarving()
            || (storedLow && compare(rate, threshold, firstNonBlank(trigger.comparison(), "<="), false));
    }

    private static double valueForResourceTrigger(Settlement settlement, CrisisDefinition.Trigger trigger) {
        String source = trigger.source();
        if (source == null || source.isBlank() || source.equalsIgnoreCase("net_food")) {
            return settlement.effectiveFoodPerSecond();
        }
        if (source.equalsIgnoreCase("stored_food") || source.equalsIgnoreCase("food_stored")) {
            return settlement.foodStored();
        }
        if (source.equalsIgnoreCase("stored_food_rate") || source.equalsIgnoreCase("storage_food")) {
            return settlement.storedFoodPerSecond();
        }
        if (source.equalsIgnoreCase("population")) {
            return settlement.population();
        }
        return settlement.foodSourceRate(source);
    }

    private static boolean compare(double actual, double threshold, String comparison, boolean defaultAtLeast) {
        String op = comparison == null ? "" : comparison.trim().toLowerCase(Locale.ROOT);
        if (op.isBlank()) op = defaultAtLeast ? ">=" : "<=";
        return switch (op) {
            case ">", "above", "greater_than" -> actual > threshold;
            case ">=", "at_least", "minimum", "min" -> actual >= threshold;
            case "<", "below", "less_than" -> actual < threshold;
            case "<=", "at_most", "maximum", "max" -> actual <= threshold;
            case "=", "==", "equals" -> Math.abs(actual - threshold) < 0.000_001;
            default -> defaultAtLeast ? actual >= threshold : actual <= threshold;
        };
    }

    private static boolean hasClaimedBiome(ServerLevel level, Settlement settlement, String biomeId) {
        if (biomeId == null || biomeId.isBlank() || settlement.claimedChunks().isEmpty()) return false;
        String wanted = biomeId.trim();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long packed : settlement.claimedChunks()) {
            ChunkPos chunk = new ChunkPos(packed);
            if (!level.hasChunk(chunk.x, chunk.z)) continue;
            int x = chunk.getMiddleBlockX();
            int z = chunk.getMiddleBlockZ();
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            pos.set(x, y, z);
            ResourceLocation actual = level.getBiome(pos).unwrapKey()
                .map(key -> key.location())
                .orElse(null);
            if (actual != null && actual.toString().equals(wanted)) return true;
        }
        return false;
    }

    private static void startCrisis(MinecraftServer server, Settlement settlement, CrisisDefinition def) {
        long now = server.overworld().getGameTime();
        PENDING_STARTS.remove(pendingKey(settlement));
        CrisisState state = new CrisisState(def.id(), UUID.randomUUID(), now);
        settlement.setActiveCrisis(state);
        updateJournal(server, settlement, def, state, false, now);
        SettlementManager.broadcastToSettlement(server, settlement,
            Component.literal("New Crisis: " + def.title()).withStyle(ChatFormatting.GOLD));
        applyCrisisMoment(server, settlement, def,
            "bannerbound.status.crisis_started", ThoughtKind.CRISIS_STARTED, 24_000);
        announceCrisisStarted(server, settlement, def);
        broadcastState(server, settlement);
        JournalManager.broadcastSettlement(server, settlement);
    }

    public static boolean debugStart(MinecraftServer server, Settlement settlement, String crisisId) {
        CrisisDefinition def = CrisisDefinitionLoader.get(crisisId);
        if (server == null || settlement == null || def == null) return false;
        PENDING_STARTS.remove(pendingKey(settlement));
        startCrisis(server, settlement, def);
        SettlementData.get(server.overworld()).setDirty();
        return true;
    }

    public static boolean debugClear(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return false;
        boolean hadPending = PENDING_STARTS.remove(pendingKey(settlement)) != null;
        if (settlement.activeCrisis() == null) {
            if (hadPending) SettlementData.get(server.overworld()).setDirty();
            return hadPending;
        }
        CrisisState state = settlement.activeCrisis();
        JournalEntry entry = settlement.findJournalEntry(SOURCE_TYPE, state.crisisId());
        if (entry != null) settlement.removeJournalEntry(entry.instanceId());
        settlement.setActiveCrisis(null);
        broadcastState(server, settlement);
        JournalManager.broadcastSettlement(server, settlement);
        SettlementData.get(server.overworld()).setDirty();
        return true;
    }

    /** Clears all crisis runtime state tied to a settlement that is being deleted, and pushes an
     *  empty client mirror to former members so the HUD/markers don't outlive the settlement. */
    public static void onSettlementRemoved(MinecraftServer server, Settlement settlement,
                                           Iterable<UUID> formerMembers) {
        if (settlement == null) return;
        String settlementId = String.valueOf(settlement.id());
        PENDING_STARTS.remove(settlementId);
        PASSIVE_TRIGGER_STREAKS.keySet().removeIf(key -> key.startsWith(settlementId + "|"));
        settlement.setActiveCrisis(null);
        if (server == null || formerMembers == null) return;
        for (UUID memberId : formerMembers) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) sendEmptyStateTo(member);
        }
    }

    /** Clears one client's crisis HUD/marker mirror, used when they leave a settlement. */
    public static void sendEmptyStateTo(ServerPlayer player) {
        if (player != null) {
            PacketDistributor.sendToPlayer(player, CrisisStatePayload.empty());
        }
    }

    public static void handleChoice(ServerPlayer player, String choiceId) {
        if (player == null || choiceId == null || choiceId.isBlank()) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null || !settlement.members().contains(player.getUUID())) return;
        CrisisState state = settlement.activeCrisis();
        if (state == null || !state.awaitingChoice()) return;
        CrisisDefinition def = CrisisDefinitionLoader.get(state.crisisId());
        if (def == null) return;
        CrisisChoice choice = def.choice(choiceId);
        if (choice == null) return;

        if (settlement.governmentType() == Settlement.Government.CHIEFDOM
                && !settlement.canActWeighty(player.getUUID())) {
            // Members can't decide a chiefdom crisis, but they can ADVISE: record their preferred
            // option as a non-binding tally the chief sees, then live-refresh open screens.
            state.vote(player.getUUID(), choiceId);
            data.setDirty();
            broadcastState(server, settlement);
            refreshOpenScreens(server, settlement);
            player.sendSystemMessage(Component.translatable("bannerbound.crisis.advice_sent", choice.label())
                .withStyle(ChatFormatting.GRAY));
            return;
        }

        if (settlement.governmentType() == Settlement.Government.COUNCIL) {
            state.vote(player.getUUID(), choiceId);
            int required = councilThreshold(server, settlement);
            if (state.voteCount(choiceId) < required) {
                data.setDirty();
                broadcastState(server, settlement);
                refreshOpenScreens(server, settlement);
                return;
            }
        }

        applyChoice(server, settlement, def, state, choice, player.getUUID());
        data.setDirty();
    }

    private static void applyChoice(MinecraftServer server, Settlement settlement, CrisisDefinition def,
                                    CrisisState state, CrisisChoice choice, UUID chooserId) {
        long now = server.overworld().getGameTime();
        state.choose(choice.id(), chooserId, now);
        // Baseline for "produce N from your <source>" objectives: count only production from here on,
        // not whatever the settlement already produced (e.g. anarchy fishers before the crisis).
        state.snapshotProducedBaseline(settlement.foodProducedTotals());
        updateJournal(server, settlement, def, state, false, now);
        SettlementManager.broadcastToSettlement(server, settlement,
            Component.literal(choice.outcome().isBlank()
                ? "Crisis response chosen: " + choice.label()
                : choice.outcome()).withStyle(ChatFormatting.AQUA));
        if (choice.objectives().isEmpty()) {
            completeCrisis(server, settlement, def, state, now);
            return;
        }
        broadcastState(server, settlement);
        JournalManager.broadcastSettlement(server, settlement);
    }

    private static void completeCrisis(MinecraftServer server, Settlement settlement, CrisisDefinition def,
                                       CrisisState state, long now) {
        updateJournal(server, settlement, def, state, true, now);
        JournalEntry entry = settlement.findJournalEntry(SOURCE_TYPE, def.id());
        if (entry != null && !entry.resolved()) entry.resolve(now, false);
        state.resolve(now, false);
        settlement.markCrisisResolved(def.id(), false);
        settlement.setActiveCrisis(null);
        applyCompletionEffects(server, settlement, def.completionEffects());
        SettlementManager.broadcastToSettlement(server, settlement,
            Component.literal("Crisis resolved: " + def.title()).withStyle(ChatFormatting.GREEN));
        applyCrisisMoment(server, settlement, def,
            "bannerbound.status.crisis_resolved", ThoughtKind.CRISIS_RESOLVED, 12_000);
        announceCrisisResolved(server, settlement, def);
        if (!def.chronicleEntry().isBlank()) {
            for (java.util.UUID memberId : settlement.members()) {
                ServerPlayer member = server.getPlayerList().getPlayer(memberId);
                if (member != null) {
                    com.bannerbound.core.codex.CodexManager.unlock(member, def.chronicleEntry(), true);
                }
            }
        }
        broadcastState(server, settlement);
        JournalManager.broadcastSettlement(server, settlement);
    }

    private static JournalUpdate updateJournal(MinecraftServer server, Settlement settlement,
                                               CrisisDefinition def, CrisisState state,
                                               boolean forceComplete, long now) {
        List<JournalObjective> objectives = buildObjectives(server, settlement, def, state, forceComplete);
        String title = state.hasChoice() ? def.title() : "New Crisis: " + def.title();
        String subtitle = state.hasChoice() ? chosenSubtitle(def, state) : def.headline();
        JournalEntry entry = settlement.findJournalEntry(SOURCE_TYPE, def.id());
        if (entry == null) {
            entry = new JournalEntry(state.instanceId(), def.id(), JournalEntryType.CRISIS,
                title, subtitle, def.priority(), now, 0L, SOURCE_TYPE, def.id(),
                def.chronicleEntry(), objectives);
            settlement.putJournalEntry(entry);
            return new JournalUpdate(true);
        }
        boolean changed = false;
        if (!entry.title().equals(title)) {
            entry.setTitle(title);
            changed = true;
        }
        if (!entry.subtitle().equals(subtitle)) {
            entry.setSubtitle(subtitle);
            changed = true;
        }
        if (!entry.objectives().equals(objectives)) {
            entry.setObjectives(objectives);
            changed = true;
        }
        return new JournalUpdate(changed);
    }

    private static String chosenSubtitle(CrisisDefinition def, CrisisState state) {
        CrisisChoice choice = def.choice(state.choiceId());
        return choice == null ? def.headline() : choice.label();
    }

    private static List<JournalObjective> buildObjectives(MinecraftServer server, Settlement settlement,
                                                          CrisisDefinition def, CrisisState state,
                                                          boolean forceComplete) {
        if (!state.hasChoice()) {
            return List.of(new JournalObjective("choose_response",
                "Choose a response at the Town Hall.", "", false));
        }
        CrisisChoice choice = def.choice(state.choiceId());
        if (choice == null) return List.of();
        List<JournalObjective> out = new ArrayList<>();
        for (CrisisObjectiveDefinition objective : choice.objectives()) {
            ObjectiveStatus status = evaluateObjective(server, settlement, objective, state);
            out.add(new JournalObjective(objective.id(), objective.label(), status.progressText(),
                forceComplete || status.complete(), objective.subSteps()));
        }
        return out;
    }

    private static boolean allObjectivesComplete(MinecraftServer server, Settlement settlement,
                                                 CrisisDefinition def, CrisisState state) {
        CrisisChoice choice = def.choice(state.choiceId());
        if (choice == null) return false;
        if (choice.objectives().isEmpty()) return true;
        for (CrisisObjectiveDefinition objective : choice.objectives()) {
            if (!evaluateObjective(server, settlement, objective, state).complete()) return false;
        }
        return true;
    }

    private static ObjectiveStatus evaluateObjective(MinecraftServer server, Settlement settlement,
                                                     CrisisObjectiveDefinition objective, CrisisState state) {
        String type = objective.type().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "food_produced", "produced_food" -> {
                // Cumulative food-stuff produced from a source SINCE the choice was made (lifetime total
                // minus the baseline snapshotted at choice time). The real "build your food source" gate.
                double made = settlement.foodProducedFrom(objective.source())
                    - state.producedBaselineFor(objective.source());
                double target = objective.targetCount() > 0 ? objective.targetCount() : objective.targetRate();
                yield new ObjectiveStatus(made >= target,
                    String.format(Locale.ROOT, "%.0f/%.0f produced", Math.max(0.0, made), target));
            }
            case "food_sustained", "income_covers_consumption" -> {
                // Production-flow gate against a FULL TRIBE's appetite. Two deliberate choices:
                //  • TOTAL production, not one source — totalFoodProductionRate() sums every source's EMA of
                //    food actually HARVESTED/CULLED/CAUGHT into storage (addFoodProduced), because no single
                //    source can reach a 7-mouth rate alone (2 spear fishers ≈ 0.27 vs 0.875 food/s). It is a
                //    FLOW (not the stored-food stock), so a one-time stockpile + a trickle still never passes;
                //    it takes real, ongoing fishing/farming/herding. Being an EMA, it only reaches the bar
                //    after steady production (a permanent catch, not a lucky day).
                //  • FULL-TRIBE target — targetFoodConsumptionPerSecond() anchors to the settlement's
                //    population high-water mark, NOT the live survivor count, so a die-off can't trivially
                //    "solve" hunger by shrinking the mouths to feed: a tribe that grew to 7 then starved to 1
                //    must still rebuild food for a full tribe, not for its lone survivor.
                double consumption = settlement.targetFoodConsumptionPerSecond();
                double sourceRate = settlement.totalFoodProductionRate();
                boolean covering = consumption > 0.0 && sourceRate + 1.0e-6 >= consumption;
                String status = consumption <= 0.0
                    ? "No appetite to cover yet"
                    : String.format(Locale.ROOT, "%s/%s food/s (full tribe)",
                        formatRate(sourceRate), formatRate(consumption));
                yield new ObjectiveStatus(covering, status);
            }
            case "research_completed", "research" -> {
                boolean done = settlement.hasCompletedResearchEitherTree(objective.researchId());
                yield new ObjectiveStatus(done, done ? "Completed" : "Not researched");
            }
            case "job_count", "appoint_workers" -> {
                int count = countReadyCitizensForJob(server, settlement, objective.jobType());
                int target = Math.max(1, objective.targetCount());
                yield new ObjectiveStatus(count >= target, count + "/" + target);
            }
            case "food_source_rate", "source_rate" -> {
                double rate = settlement.foodSourceRate(objective.source());
                double target = objective.targetRate();
                yield new ObjectiveStatus(rate >= target,
                    formatRate(rate) + "/" + formatRate(target) + " food/s");
            }
            case "food_surplus", "food_source_surplus" -> {
                double net = settlement.effectiveFoodPerSecond();
                double sourceRate = objective.source().isBlank() ? net : settlement.foodSourceRate(objective.source());
                double target = objective.targetRate();
                boolean done = net >= target && (objective.source().isBlank() || sourceRate > 0.0);
                yield new ObjectiveStatus(done,
                    "Net " + signedRate(net) + "/s, source " + formatRate(sourceRate) + "/s");
            }
            case "stored_food" -> {
                double target = objective.targetRate() > 0.0 ? objective.targetRate() : objective.targetCount();
                double stored = settlement.foodStored();
                yield new ObjectiveStatus(stored >= target,
                    String.format(Locale.ROOT, "%.1f/%.1f food", stored, target));
            }
            case "stored_food_rate", "storage_food" -> {
                double rate = settlement.storedFoodPerSecond();
                double target = objective.targetRate();
                yield new ObjectiveStatus(rate >= target,
                    formatRate(rate) + "/" + formatRate(target) + " food/s");
            }
            case "population" -> {
                int target = Math.max(1, objective.targetCount());
                int pop = settlement.population();
                yield new ObjectiveStatus(pop >= target, pop + "/" + target);
            }
            case "housed_all", "homeless_count", "house_citizens" -> {
                // Complete when no more than targetCount citizens remain homeless (default 0 →
                // everyone has a home). The "answer the housing demand" gate.
                int allowed = Math.max(0, objective.targetCount());
                int homeless = settlement.homelessCitizens().size();
                yield new ObjectiveStatus(homeless <= allowed,
                    homeless == 0 ? "Everyone housed" : homeless + " still homeless");
            }
            default -> new ObjectiveStatus(false, "");
        };
    }

    private static int countReadyCitizensForJob(MinecraftServer server, Settlement settlement, String jobType) {
        if (server == null || jobType == null || jobType.isBlank()) return 0;
        int count = 0;
        for (com.bannerbound.core.api.settlement.Citizen citizen : settlement.citizens()) {
            if (server.overworld().getEntity(citizen.entityId())
                    instanceof com.bannerbound.core.entity.CitizenEntity entity
                    && citizenReadyForObjective(entity, jobType)) {
                count++;
            }
        }
        return count;
    }

    private static boolean citizenReadyForObjective(CitizenEntity entity, String jobType) {
        if (entity == null || jobType == null || !jobType.equals(entity.getJobType())) return false;
        if (com.bannerbound.core.entity.FarmerWorkGoal.JOB_TYPE_ID.equals(jobType)) {
            return entity.isFarmerReady();
        }
        if (com.bannerbound.core.entity.FisherWorkGoal.JOB_TYPE_ID.equals(jobType)) {
            return entity.isFisherReady();
        }
        if (com.bannerbound.core.entity.ForagerWorkGoal.JOB_TYPE_ID.equals(jobType)) {
            return entity.isForagerReady();
        }
        if (com.bannerbound.core.entity.ForesterWorkGoal.JOB_TYPE_ID.equals(jobType)) {
            return entity.isForesterReady();
        }
        if (com.bannerbound.core.entity.HerderWorkGoal.JOB_TYPE_ID.equals(jobType)) {
            return entity.hasJobTool();
        }
        com.bannerbound.core.api.job.CitizenJobRegistry.JobDef def =
            com.bannerbound.core.api.job.CitizenJobRegistry.byId(jobType);
        if (def != null && def.gatherer()) {
            return entity.isGatherJobReady(jobType);
        }
        if (def != null) {
            return !def.toolRequired() || entity.hasJobTool();
        }
        return entity.isJobReady(jobType);
    }

    public static boolean shouldOpenCrisisScreen(Settlement settlement) {
        CrisisState state = settlement == null ? null : settlement.activeCrisis();
        return state != null && state.awaitingChoice();
    }

    public static void openCrisisScreen(ServerPlayer player) {
        if (player == null || player.getServer() == null) return;
        Settlement settlement = SettlementData.get(player.getServer().overworld()).getByPlayer(player.getUUID());
        if (settlement == null) return;
        PacketDistributor.sendToPlayer(player, buildScreenPayload(player.getServer(), settlement, player, true));
    }

    public static OpenCrisisScreenPayload buildScreenPayload(MinecraftServer server, Settlement settlement,
                                                             ServerPlayer player, boolean forceOpen) {
        CrisisState state = settlement.activeCrisis();
        CrisisDefinition def = state == null ? null : CrisisDefinitionLoader.get(state.crisisId());
        if (state == null || def == null) {
            return new OpenCrisisScreenPayload("", "", "", "", "", "", "", "",
                List.of(),
                false, false, false, false, 0, 0, "", "", List.of(), forceOpen);
        }
        List<OpenCrisisScreenPayload.Choice> choices = new ArrayList<>();
        for (CrisisChoice choice : def.choices()) {
            String warning = choiceViabilityWarning(server, settlement, choice);
            choices.add(new OpenCrisisScreenPayload.Choice(choice.id(), choice.label(),
                choice.description(), choice.outcome(), warning.isBlank(), warning,
                state.voteCount(choice.id())));
        }
        int online = onlineMembers(server, settlement);
        boolean councilVote = settlement.governmentType() == Settlement.Government.COUNCIL;
        boolean canChoose = state.awaitingChoice() && settlement.canActWeighty(player.getUUID());
        // In a chiefdom, members who can't decide may still ADVISE the chief (their pick is
        // tallied as a non-binding vote the chief sees before choosing).
        boolean canAdvise = state.awaitingChoice() && !canChoose
            && settlement.governmentType() == Settlement.Government.CHIEFDOM
            && settlement.members().contains(player.getUUID());
        return new OpenCrisisScreenPayload(
            settlement.name(), def.id(), def.category(), def.title(), def.headline(),
            def.body(), def.prompt(), def.background(), screenArtLayers(def), state.awaitingChoice(), canChoose,
            canAdvise, councilVote, online, councilVote ? councilThreshold(server, settlement) : 1,
            state.voteOf(player.getUUID()), state.choiceId(), choices, forceOpen
        );
    }

    /** Re-pushes the crisis screen payload to every member WITHOUT force-opening it — only clients
     *  that already have the crisis screen open refresh in place. Used for live vote/advice tallies. */
    public static void refreshOpenScreens(MinecraftServer server, Settlement settlement) {
        for (UUID memberId : settlement.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) {
                PacketDistributor.sendToPlayer(member, buildScreenPayload(server, settlement, member, false));
            }
        }
    }

    private static List<OpenCrisisScreenPayload.ArtLayer> screenArtLayers(CrisisDefinition def) {
        List<OpenCrisisScreenPayload.ArtLayer> out = new ArrayList<>();
        for (CrisisDefinition.ArtLayer layer : def.backgroundLayers()) {
            out.add(new OpenCrisisScreenPayload.ArtLayer(layer.texture(), layer.parallax(),
                layer.driftX(), layer.driftY(), layer.scale(), layer.opacity(),
                layer.revealDelayMs(), layer.revealDurationMs()));
        }
        return out;
    }

    public static void sendStateTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        if (settlement == null) {
            PacketDistributor.sendToPlayer(player, CrisisStatePayload.empty());
            return;
        }
        sendStateTo(server, settlement, player);
    }

    public static void sendStateTo(MinecraftServer server, Settlement settlement, ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, buildStatePayload(settlement));
    }

    public static void broadcastState(MinecraftServer server, Settlement settlement) {
        for (UUID memberId : settlement.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) sendStateTo(server, settlement, member);
        }
    }

    public static void broadcastOpenScreen(MinecraftServer server, Settlement settlement) {
        for (UUID memberId : settlement.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) {
                PacketDistributor.sendToPlayer(member, buildScreenPayload(server, settlement, member, true));
            }
        }
    }

    private static CrisisStatePayload buildStatePayload(Settlement settlement) {
        CrisisState state = settlement.activeCrisis();
        if (state == null) return CrisisStatePayload.empty();
        CrisisDefinition def = CrisisDefinitionLoader.get(state.crisisId());
        long townHallPos = settlement.townHallPos() == null ? 0L : settlement.townHallPos().asLong();
        return new CrisisStatePayload(true, state.crisisId(),
            def == null ? state.crisisId() : def.title(),
            def == null ? "" : def.headline(),
            state.awaitingChoice(), state.hasChoice(), state.resolved(), state.failed(),
            state.choiceId(), townHallPos);
    }

    private static int onlineMembers(MinecraftServer server, Settlement settlement) {
        int online = 0;
        for (UUID id : settlement.members()) {
            if (server.getPlayerList().getPlayer(id) != null) online++;
        }
        return Math.max(1, online);
    }

    private static int councilThreshold(MinecraftServer server, Settlement settlement) {
        return SettlementManager.councilExpandThreshold(onlineMembers(server, settlement));
    }

    private static String choiceViabilityWarning(MinecraftServer server, Settlement settlement, CrisisChoice choice) {
        if (server == null || settlement == null || choice == null || choice.viability().isEmpty()) return "";
        ServerLevel level = server.overworld();
        for (CrisisViabilityRequirement req : choice.viability()) {
            if (req == null || requirementPasses(level, settlement, req)) continue;
            return req.warning().isBlank() ? defaultViabilityWarning(req.type()) : req.warning();
        }
        return "";
    }

    private static boolean requirementPasses(ServerLevel level, Settlement settlement,
                                             CrisisViabilityRequirement requirement) {
        if (level == null || settlement == null || requirement == null) return true;
        String type = requirement.type().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "", "none" -> true;
            case "fishable_water", "fishing_water", "viable_fishing_pond" ->
                hasFishableWater(level, settlement);
            case "farmable_soil", "tillable_soil", "dirt_or_grass" ->
                hasFarmableSoil(level, settlement);
            case "grazing_land", "pasture_land", "livestock_land", "grassland" ->
                hasGrazingLand(level, settlement);
            default -> true;
        };
    }

    private static String defaultViabilityWarning(String type) {
        String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "fishable_water", "fishing_water", "viable_fishing_pond" ->
                "No viable fishing water is claimed.";
            case "farmable_soil", "tillable_soil", "dirt_or_grass" ->
                "No tillable soil is claimed.";
            case "grazing_land", "pasture_land", "livestock_land", "grassland" ->
                "No grazing ground is claimed.";
            default -> "This response may not be viable here.";
        };
    }

    private static boolean hasFarmableSoil(ServerLevel level, Settlement settlement) {
        return scanClaimSurfaces(level, settlement, pos -> {
            BlockState ground = level.getBlockState(pos);
            if (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) return false;
            return ground.is(Blocks.DIRT)
                || ground.is(Blocks.GRASS_BLOCK)
                || ground.is(Blocks.COARSE_DIRT)
                || ground.is(Blocks.ROOTED_DIRT)
                || ground.is(Blocks.DIRT_PATH)
                || ground.is(Blocks.FARMLAND);
        });
    }

    private static boolean hasGrazingLand(ServerLevel level, Settlement settlement) {
        return scanClaimSurfaces(level, settlement, pos ->
            level.getBlockState(pos).is(Blocks.GRASS_BLOCK)
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty());
    }

    private static boolean hasFishableWater(ServerLevel level, Settlement settlement) {
        // The early "feed through fish" path is SPEAR fishing — shore-only and effective in shallow
        // water — not the deep-cast rod fishery. So mirror the spear fisher's own spot test
        // (SpearFisherWorkGoal): claimed surface water with a small open pool around it and ANY
        // walkable shore beside it to stand on. No minimum depth and no wide-open-water demand, so a
        // shallow mesa pond reads as fishable instead of falsely warning "no spots".
        return scanClaimSurfaces(level, settlement, pos ->
            isSurfaceWater(level, pos)
                && openWaterCount(level, pos, 1) >= 3
                && hasWalkableShore(level, pos));
    }

    private static boolean scanClaimSurfaces(ServerLevel level, Settlement settlement,
                                             Predicate<BlockPos> predicate) {
        if (settlement.claimedChunks().isEmpty()) return false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long packed : settlement.claimedChunks()) {
            ChunkPos chunk = new ChunkPos(packed);
            if (!level.hasChunk(chunk.x, chunk.z)) continue;
            int minX = chunk.getMinBlockX();
            int minZ = chunk.getMinBlockZ();
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = minX + dx;
                    int z = minZ + dz;
                    int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                    if (y < level.getMinBuildHeight()) continue;
                    pos.set(x, y, z);
                    if (predicate.test(pos.immutable())) return true;
                }
            }
        }
        return false;
    }

    /** A walkable tile beside {@code water} to stand on and fish. Matches the spear fisher, which
     *  stands on any reachable shore (claimed or not) as long as the water itself is claimed — and the
     *  scan only ever reaches claimed water, so the stand needn't be re-checked for the claim. This is
     *  the part that fixes the false "no spots" when a claimed lake's banks spill into a neighbour chunk. */
    private static boolean hasWalkableShore(ServerLevel level, BlockPos water) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos shore = water.relative(dir);
            if (WorkerPathing.isWalkable(level, shore) || WorkerPathing.isWalkable(level, shore.above())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSurfaceWater(ServerLevel level, BlockPos pos) {
        if (!level.getFluidState(pos).is(FluidTags.WATER)) return false;
        BlockState above = level.getBlockState(pos.above());
        return above.isAir() || above.getCollisionShape(level, pos.above()).isEmpty();
    }

    private static int openWaterCount(ServerLevel level, BlockPos center, int radius) {
        int count = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                pos.set(center.getX() + dx, center.getY(), center.getZ() + dz);
                if (isSurfaceWater(level, pos)) count++;
            }
        }
        return count;
    }

    private static boolean matchesTarget(String triggerTarget, String eventTarget) {
        return triggerTarget == null || triggerTarget.isBlank()
            || triggerTarget.equalsIgnoreCase(eventTarget == null ? "" : eventTarget);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String formatRate(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String signedRate(double value) {
        return (value >= 0.0 ? "+" : "") + formatRate(value);
    }

    private static void applyCrisisMoment(MinecraftServer server, Settlement settlement,
                                          CrisisDefinition def, String statusKey,
                                          ThoughtKind thought, int durationTicks) {
        if (server == null || settlement == null || def == null) return;
        settlement.addOrRenewStatusEffect(new StatusEffect(
            crisisStatusId(def.id()), statusKey, List.of(def.title()),
            StatusEffectIcon.ALERT, 0.0, durationTicks));
        SettlementManager.broadcastStatusEffectsToMembers(server, settlement);

        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        boolean touched = false;
        for (com.bannerbound.core.api.settlement.Citizen citizen : settlement.citizens()) {
            if (level.getEntity(citizen.entityId()) instanceof CitizenEntity entity) {
                if (thought == ThoughtKind.CRISIS_STARTED) {
                    entity.getThoughts().remove(ThoughtKind.CRISIS_RESOLVED, null);
                } else if (thought == ThoughtKind.CRISIS_RESOLVED) {
                    entity.getThoughts().remove(ThoughtKind.CRISIS_STARTED, null);
                }
                entity.getThoughts().add(thought, null, now, level.random);
                entity.recomputeHappiness();
                touched = true;
            }
        }
        if (touched) SettlementData.get(level).setDirty();
    }

    private static void applyCompletionEffects(MinecraftServer server, Settlement settlement,
                                               CrisisDefinition.CompletionEffects effects) {
        if (server == null || settlement == null || effects == null || effects.isEmpty()) return;
        ServerLevel level = server.overworld();
        java.util.Set<UUID> leaders = settlement.leaderPlayerIds();
        boolean touched = false;
        for (com.bannerbound.core.api.settlement.Citizen citizen : settlement.citizens()) {
            if (!(level.getEntity(citizen.entityId()) instanceof CitizenEntity entity)) continue;
            if (effects.complianceDelta() != 0) {
                entity.setCompliance(entity.getCompliance() + effects.complianceDelta());
                touched = true;
            }
            if (effects.resentmentDelta() != 0 && !leaders.isEmpty()) {
                for (UUID leader : leaders) entity.addResentment(leader, effects.resentmentDelta());
                touched = true;
            }
        }
        if (touched) SettlementData.get(level).setDirty();
    }

    private static UUID crisisStatusId(String crisisId) {
        String key = "bannerbound:crisis_status:" + (crisisId == null ? "" : crisisId);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static void announceCrisisStarted(MinecraftServer server, Settlement settlement,
                                              CrisisDefinition def) {
        boolean customSound = def != null && !def.startSound().isBlank();
        announceTitle(server, settlement,
            Component.literal("New Crisis").withStyle(ChatFormatting.GOLD),
            Component.literal(def.title()).withStyle(ChatFormatting.RED),
            resolveSound(def.startSound(), SoundEvents.BELL_BLOCK), 1.25f, customSound ? 1.0f : 0.55f);
    }

    private static void announceCrisisResolved(MinecraftServer server, Settlement settlement,
                                               CrisisDefinition def) {
        announceTitle(server, settlement,
            Component.literal("Crisis Resolved").withStyle(ChatFormatting.GREEN),
            Component.literal(def.title()).withStyle(ChatFormatting.GOLD),
            SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
    }

    private static void announceTitle(MinecraftServer server, Settlement settlement,
                                      Component title, Component subtitle,
                                      SoundEvent sound, float volume, float pitch) {
        if (server == null || settlement == null) return;
        for (UUID memberId : settlement.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member == null) continue;
            member.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
            member.connection.send(new ClientboundSetTitleTextPacket(title));
            member.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            member.playNotifySound(sound, SoundSource.MASTER, volume, pitch);
        }
    }

    private static SoundEvent resolveSound(String soundId, SoundEvent fallback) {
        if (soundId == null || soundId.isBlank()) return fallback;
        ResourceLocation id = ResourceLocation.tryParse(soundId);
        if (id == null) return fallback;
        return BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(fallback);
    }

    private static String pendingKey(Settlement settlement) {
        return settlement == null ? "" : String.valueOf(settlement.id());
    }

    private record ObjectiveStatus(boolean complete, String progressText) {}
    private record JournalUpdate(boolean changed) {}
    private record PendingCrisisStart(String crisisId, long dueTick) {}
}
