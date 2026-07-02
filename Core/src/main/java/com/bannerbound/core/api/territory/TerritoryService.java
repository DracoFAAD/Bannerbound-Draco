package com.bannerbound.core.api.territory;

import com.bannerbound.core.territory.ChunkClaimCostFile;
import com.bannerbound.core.territory.InventoryItemHelper;
import com.bannerbound.core.territory.TerritoryBiomeResolver;

import com.bannerbound.core.api.territory.data.ChunkClaimCostLoader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.bannerbound.core.faction.ChunkForceLoader;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.network.OpenExpandTerritoryScreenPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Server-side logic for the chunk-claim expansion flow. Two entry points:
 * <ul>
 *   <li>{@link #buildScreenPayload} — assembles the snapshot the
 *       {@code ExpandTerritoryScreen} renders from. Includes own + foreign chunks in a 9×9
 *       window around the town hall, the resolved current tier cost, and a
 *       server-evaluated {@code canAfford} flag covering items + population + cap.</li>
 *   <li>{@link #tryClaim} — re-validates everything when the client clicks a chunk, consumes
 *       items, performs the claim, force-loads the chunk, increments the per-era expansion
 *       counter, broadcasts the global claim sync, and announces in chat.</li>
 * </ul>
 * Keep all validation here, NOT in the client: the screen is allowed to lie about what it
 * thinks is purchasable. Server is authoritative.
 */
public final class TerritoryService {
    /** Half-width of the chunk window we ship to the screen. 4 → 9×9 area centered on town hall. */
    private static final int WINDOW_RADIUS = 4;

    private TerritoryService() {}

    /** A resolved expansion slot: which era's ladder it belongs to and the tier index within it. */
    private record EraTier(Era era, int tier) {}

    /**
     * Cumulative expansion cap — the sum of {@code maxExpansions} for every era the settlement has
     * reached. A settlement only ever advances one era at a time, so "reached" means every era
     * from the first up to (and including) its current age. Unused expansions therefore carry
     * forward: advancing simply adds the new era's allowance on top.
     */
    private static int totalExpansionCap(Settlement s) {
        int cap = 0;
        for (Era era : Era.values()) {
            ChunkClaimCostFile f = ChunkClaimCostLoader.get(era.key());
            if (f != null) cap += f.maxExpansions();
            if (era == s.age()) break;
        }
        return cap;
    }

    /**
     * Maps a 0-based global expansion index onto the era whose cost ladder it falls in (earlier
     * eras are consumed first, so leftover antiquity expansions still cost antiquity prices) plus
     * the tier index within that era. Null when the index is at or past the cumulative cap.
     */
    private static EraTier resolveEraTier(Settlement s, int globalIdx) {
        int acc = 0;
        for (Era era : Era.values()) {
            ChunkClaimCostFile f = ChunkClaimCostLoader.get(era.key());
            int cap = f == null ? 0 : f.maxExpansions();
            if (globalIdx < acc + cap) {
                return new EraTier(era, globalIdx - acc);
            }
            acc += cap;
            if (era == s.age()) break;
        }
        return null;
    }

    /** The cost-ladder entry for the {@code globalIdx}-th expansion, or null if that index is
     *  past the cap or the resolved era's ladder has no such tier. */
    private static ChunkClaimCost resolveCost(Settlement s, int globalIdx, ResourceLocation biome) {
        EraTier et = resolveEraTier(s, globalIdx);
        if (et == null) return null;
        ChunkClaimCostFile f = ChunkClaimCostLoader.get(et.era().key());
        if (f == null) return null;
        List<ChunkClaimCost> tiers = f.tiersFor(biome);
        return et.tier() < tiers.size() ? tiers.get(et.tier()) : null;
    }

    public static OpenExpandTerritoryScreenPayload buildScreenPayload(ServerLevel overworld,
                                                                       Settlement settlement,
                                                                       ServerPlayer requester) {
        ChunkPos thChunk = new ChunkPos(settlement.townHallPos());
        long thPacked = thChunk.toLong();

        List<Long> own = new ArrayList<>(settlement.claimedChunks());
        List<Long> foreign = new ArrayList<>();
        SettlementData data = SettlementData.get(overworld);
        for (int dx = -WINDOW_RADIUS; dx <= WINDOW_RADIUS; dx++) {
            for (int dz = -WINDOW_RADIUS; dz <= WINDOW_RADIUS; dz++) {
                long packed = new ChunkPos(thChunk.x + dx, thChunk.z + dz).toLong();
                if (settlement.claimedChunks().contains(packed)) continue;
                Settlement owner = data.getByChunk(packed);
                if (owner != null) foreign.add(packed);
            }
        }

        ResourceLocation biome = TerritoryBiomeResolver.majorityBiome(overworld, settlement);
        // Progress is global now: expansions used vs. the cumulative cap across every era reached.
        int used = settlement.expansionsUsed();
        int totalCap = totalExpansionCap(settlement);
        ChunkClaimCost cur = resolveCost(settlement, used, biome);

        List<String> ids = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        int reqPop = 0;
        boolean canAfford = false;
        if (cur != null) {
            reqPop = cur.populationRequired();
            for (ChunkClaimCost.ItemCost ic : cur.items()) {
                ResourceLocation itemRl = BuiltInRegistries.ITEM.getKey(ic.item());
                ids.add(itemRl.toString());
                counts.add(ic.count());
            }
            canAfford = settlement.population() >= reqPop
                && InventoryItemHelper.hasAll(requester, cur.items());
        }

        // Base beauty + adjacency bonus + effective beauty for every tracked (scored) chunk in
        // the view window — claimed chunks and the claimable ring.
        List<Long> beautyChunks = new ArrayList<>();
        List<Integer> beautyTagIds = new ArrayList<>();
        List<Integer> beautyAdjacency = new ArrayList<>();
        List<Integer> beautyEffective = new ArrayList<>();
        for (int dx = -WINDOW_RADIUS; dx <= WINDOW_RADIUS; dx++) {
            for (int dz = -WINDOW_RADIUS; dz <= WINDOW_RADIUS; dz++) {
                long packed = new ChunkPos(thChunk.x + dx, thChunk.z + dz).toLong();
                com.bannerbound.core.api.settlement.ChunkBeauty beauty =
                    com.bannerbound.core.api.settlement.ChunkBeautyManager.beautyOf(overworld, packed);
                if (beauty != null) {
                    com.bannerbound.core.api.settlement.ChunkBeauty effective =
                        com.bannerbound.core.api.settlement.ChunkBeautyManager
                            .effectiveBeautyOf(overworld, packed);
                    beautyChunks.add(packed);
                    beautyTagIds.add((int) beauty.networkId());
                    beautyAdjacency.add(com.bannerbound.core.api.settlement.ChunkBeautyManager
                        .adjacencyBonus(overworld, packed));
                    beautyEffective.add((int) (effective != null ? effective : beauty).networkId());
                }
            }
        }

        // Chunk markers — Council votes (with the live threshold) AND Chiefdom suggestions.
        // Empty for the inactive flavour; the client renders only the populated list.
        java.util.List<OpenExpandTerritoryScreenPayload.ChunkMarker> votes = new java.util.ArrayList<>();
        java.util.List<OpenExpandTerritoryScreenPayload.ChunkMarker> suggestions = new java.util.ArrayList<>();
        int threshold = 0;
        if (settlement.governmentType() == Settlement.Government.COUNCIL) {
            int onlineNow = SettlementManager.countOnlineMembers(
                overworld.getServer(), settlement);
            threshold = onlineNow <= 1 ? 1 : (onlineNow == 2 ? 2 : (onlineNow + 1) / 2);
            for (java.util.Map.Entry<Long, java.util.LinkedHashMap<java.util.UUID, Long>> e
                    : settlement.allExpansionVotes().entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                votes.add(new OpenExpandTerritoryScreenPayload.ChunkMarker(
                    e.getKey(), new java.util.ArrayList<>(e.getValue().keySet())));
            }
        } else if (settlement.governmentType() == Settlement.Government.CHIEFDOM) {
            for (java.util.Map.Entry<Long, java.util.LinkedHashSet<java.util.UUID>> e
                    : settlement.allExpansionSuggestions().entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                suggestions.add(new OpenExpandTerritoryScreenPayload.ChunkMarker(
                    e.getKey(), new java.util.ArrayList<>(e.getValue())));
            }
        }

        return new OpenExpandTerritoryScreenPayload(
            own, foreign, thPacked,
            settlement.color().ordinal(),
            used,
            totalCap,
            reqPop,
            settlement.population(),
            ids, counts,
            biome == null ? "" : biome.toString(),
            canAfford,
            beautyChunks, beautyTagIds, beautyAdjacency, beautyEffective,
            votes, suggestions, threshold);
    }

    /** Dispatcher: route the click through the right path for the settlement's government.
     *  Chiefdom + non-chief → suggestion toggle (chat to chief); Chiefdom + chief → direct
     *  claim using the chief's inventory + settlement chests; Council → vote toggle, claim
     *  fires when threshold is met (resources sourced from voters + settlement); NONE →
     *  direct claim (anarchy / pre-government). */
    public static void tryClaim(ServerPlayer player, long packedTarget) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        SettlementData data = SettlementData.get(overworld);
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) {
            err(player, "bannerbound.territory.error.no_settlement");
            return;
        }
        ChunkPos target = new ChunkPos(packedTarget);

        // Pre-claim gates that always apply (regardless of gov type). Filtering here keeps
        // the dispatch paths below short.
        if (settlement.claimedChunks().contains(packedTarget)) {
            err(player, "bannerbound.chunkclaim.error.already_yours");
            return;
        }
        Settlement otherOwner = data.getByChunk(packedTarget);
        if (otherOwner != null) {
            player.sendSystemMessage(Component.translatable(
                "bannerbound.chunkclaim.error.owned_by_other", otherOwner.name())
                .withStyle(ChatFormatting.RED));
            return;
        }
        if (!isAdjacentToClaim(settlement, target)) {
            err(player, "bannerbound.chunkclaim.error.too_far");
            return;
        }

        // Dispatch by government.
        switch (settlement.governmentType()) {
            case CHIEFDOM -> {
                if (settlement.canActWeighty(player.getUUID())) {
                    // Chief: claim directly using their inventory + settlement chests.
                    executeClaim(server, overworld, data, settlement, player, target, packedTarget,
                        com.bannerbound.core.territory.SettlementInventoryHelper.singletonVoters(player));
                } else {
                    // Non-chief: toggle the suggestion marker for this chunk.
                    toggleChunkSuggestion(server, settlement, player, packedTarget);
                }
            }
            case COUNCIL -> tryCouncilVote(server, overworld, data, settlement, player, target, packedTarget);
            case NONE -> executeClaim(server, overworld, data, settlement, player, target, packedTarget,
                com.bannerbound.core.territory.SettlementInventoryHelper.singletonVoters(player));
        }
    }

    /** Chiefdom non-chief: toggle a suggestion marker on this chunk. Per design: no chat
     *  broadcasts — the visual marker update IS the feedback. Pushes a screen refresh to
     *  every member so the marker shows up on the chief's screen too. */
    private static void toggleChunkSuggestion(MinecraftServer server, Settlement settlement,
                                                ServerPlayer player, long packedTarget) {
        settlement.toggleExpansionSuggestion(packedTarget, player.getUUID());
        broadcastTerritoryRefresh(server, settlement);
    }

    /** Council member: toggle vote, sweep expired votes, check threshold, fire claim if met.
     *  Per design: no chat broadcasts on vote cast/withdraw — the screen marker (N/X) is the
     *  feedback. The "vote retracted because we lack resources" broadcast (further down)
     *  IS kept because it's actionable info, not vote-tally spam. */
    private static void tryCouncilVote(MinecraftServer server, ServerLevel overworld,
                                        SettlementData data, Settlement settlement,
                                        ServerPlayer player, ChunkPos target, long packedTarget) {
        long now = System.currentTimeMillis();
        settlement.expireExpansionVotes(now, COUNCIL_VOTE_EXPIRY_MS);
        settlement.toggleExpansionVote(packedTarget, player.getUUID(), now);

        java.util.LinkedHashMap<java.util.UUID, Long> votes = settlement.expansionVotesFor(packedTarget);
        int onlineMembers = SettlementManager.countOnlineMembers(server, settlement);
        int needed = SettlementManager.councilExpandThreshold(onlineMembers);

        if (votes.size() < needed) {
            // Still gathering. Refresh every member's screen so the vote tally updates live.
            broadcastTerritoryRefresh(server, settlement);
            return;
        }
        // Threshold hit — assemble voter player list (online voters only) and execute.
        java.util.List<ServerPlayer> voters = new java.util.ArrayList<>(votes.size());
        for (java.util.UUID id : votes.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) voters.add(p);
        }
        if (voters.isEmpty()) {
            settlement.clearExpansionVotes(packedTarget);
            broadcastTerritoryRefresh(server, settlement);
            return;
        }
        boolean claimed = executeClaim(server, overworld, data, settlement, player, target, packedTarget, voters);
        if (!claimed) {
            // Resource sourcing failed — clear the vote so the council can re-vote after
            // gathering, and broadcast why.
            settlement.clearExpansionVotes(packedTarget);
            com.bannerbound.core.api.settlement.SettlementManager.broadcastToSettlement(
                server, settlement,
                Component.translatable("bannerbound.territory.vote.no_resources",
                    target.x, target.z).withStyle(ChatFormatting.RED));
            broadcastTerritoryRefresh(server, settlement);
        }
    }

    /** Shared claim-execution path: validates population + cost feasibility, consumes
     *  resources (settlement → voter fallback), claims the chunk, broadcasts. Returns true
     *  on success; false means the council vote should retract (resources missing). */
    private static boolean executeClaim(MinecraftServer server, ServerLevel overworld,
                                         SettlementData data, Settlement settlement,
                                         ServerPlayer triggeringPlayer, ChunkPos target,
                                         long packedTarget, java.util.List<ServerPlayer> voters) {
        int used = settlement.expansionsUsed();
        if (used >= totalExpansionCap(settlement)) {
            err(triggeringPlayer, "bannerbound.territory.error.cap_reached");
            return false;
        }
        EraTier et = resolveEraTier(settlement, used);
        if (et == null) {
            err(triggeringPlayer, "bannerbound.territory.error.cap_reached");
            return false;
        }
        ChunkClaimCostFile costFile = ChunkClaimCostLoader.get(et.era().key());
        if (costFile == null) {
            err(triggeringPlayer, "bannerbound.territory.error.no_cost_data");
            return false;
        }
        ResourceLocation biome = TerritoryBiomeResolver.majorityBiome(overworld, settlement);
        java.util.List<ChunkClaimCost> tiers = costFile.tiersFor(biome);
        if (et.tier() >= tiers.size()) {
            err(triggeringPlayer, "bannerbound.territory.error.cap_reached");
            return false;
        }
        ChunkClaimCost cost = tiers.get(et.tier());
        if (settlement.population() < cost.populationRequired()) {
            triggeringPlayer.sendSystemMessage(Component.translatable(
                "bannerbound.territory.error.not_enough_population",
                settlement.population(), cost.populationRequired())
                .withStyle(ChatFormatting.RED));
            return false;
        }
        // Source check + consume go through the settlement-aware helper: stockpile →
        // workstation inventories → voter player inventories.
        if (!com.bannerbound.core.territory.SettlementInventoryHelper.hasAll(
                overworld, settlement, voters, cost.items())) {
            return false;
        }
        if (!com.bannerbound.core.territory.SettlementInventoryHelper.consume(
                overworld, settlement, voters, cost.items())) {
            err(triggeringPlayer, "bannerbound.territory.error.consume_failed");
            return false;
        }
        if (data.claimChunk(settlement, target)) {
            ChunkForceLoader.force(overworld, packedTarget);
            String chunkType = com.bannerbound.core.territory.ChunkResources.typeAt(overworld, target)
                .name().toLowerCase(java.util.Locale.ROOT);
            com.bannerbound.core.api.research.InsightManager.recordEvent(
                server, settlement, "claim_chunk",
                authored -> authored.isEmpty() || authored.equals(chunkType), 1);
        }
        settlement.incrementExpansionsUsed();
        // Clear any leftover vote / suggestion state on the claimed chunk — those markers
        // have served their purpose now that the chunk is owned.
        settlement.clearExpansionVotes(packedTarget);
        settlement.clearExpansionSuggestions(packedTarget);
        data.setDirty();
        SettlementManager.broadcastClaims(server);

        // Audio confirmation for the triggering player (works for both chief direct-claim and
        // the council member whose vote landed the threshold).
        overworld.playSound(null,
            triggeringPlayer.getX(), triggeringPlayer.getY(), triggeringPlayer.getZ(),
            net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(),
            net.minecraft.sounds.SoundSource.PLAYERS,
            0.8f, 1.0f);

        // Map-wide claim announcement (unchanged from pre-Step-7 behavior).
        Component announcement = Component.translatable(
            "bannerbound.territory.announce",
            triggeringPlayer.getName(), target.x, target.z, settlement.name())
            .withStyle(settlement.identityFormatting());
        server.getPlayerList().broadcastSystemMessage(announcement, false);

        // Refresh every settlement member's territory screen — both for the chunk markers
        // and for the new tier of costs.
        broadcastTerritoryRefresh(server, settlement);
        return true;
    }

    /** Push a fresh OpenExpandTerritoryScreenPayload to every online settlement member who
     *  might have the screen open. The screen tolerates re-receiving the payload via its
     *  refreshData() handler (no flicker). */
    private static void broadcastTerritoryRefresh(MinecraftServer server, Settlement settlement) {
        ServerLevel overworld = server.overworld();
        for (java.util.UUID memberId : settlement.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m == null) continue;
            OpenExpandTerritoryScreenPayload refreshed = buildScreenPayload(overworld, settlement, m);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(m, refreshed);
        }
    }

    /** 5-min auto-expiry on Council expansion votes — matches the Choose-Government and
     *  Chief-nomination expiries in {@link SettlementManager}. */
    private static final long COUNCIL_VOTE_EXPIRY_MS = SettlementManager.VOTE_EXPIRY_MS;

    /** Same neighbourhood test the /bannerbound chunkclaim command uses. Inlined here so we don't
     *  drag in command-package imports. */
    private static boolean isAdjacentToClaim(Settlement settlement, ChunkPos target) {
        int cx = target.x;
        int cz = target.z;
        Set<Long> claims = settlement.claimedChunks();
        return claims.contains(new ChunkPos(cx - 1, cz).toLong())
            || claims.contains(new ChunkPos(cx + 1, cz).toLong())
            || claims.contains(new ChunkPos(cx, cz - 1).toLong())
            || claims.contains(new ChunkPos(cx, cz + 1).toLong());
    }

    private static void err(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.translatable(key).withStyle(ChatFormatting.RED));
    }

    // Silence unused-import warning on HashSet (kept available for future expansion of this file).
    @SuppressWarnings("unused")
    private static void _keepImports() { new HashSet<Long>(); }
}
