package com.bannerbound.core.api.settlement;

import com.bannerbound.core.faction.ChunkForceLoader;
import com.bannerbound.core.api.research.ResearchDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.network.ClaimEntry;
import com.bannerbound.core.network.ClaimSyncPayload;
import com.bannerbound.core.network.EraStatePayload;
import com.bannerbound.core.network.StartingItemsSyncPayload;
import com.bannerbound.core.api.research.data.StartingItemsLoader;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.research.data.ResearchTreeLoader;
import com.bannerbound.core.network.ResearchTreeSyncPayload;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Orchestrates every settlement lifecycle event: founding, joining, leaving, disbanding, age
 * changes, claim broadcasts, era/research/known-items sync to clients. Everything that mutates
 * {@link SettlementData} from outside the faction package goes through here so the
 * accompanying side effects (scoreboard team upkeep, fireworks, chat messages, client sync)
 * stay in one place.
 * <p>
 * Pure static. No instance state â€” call directly. For pending-state that survives across packet
 * exchanges (e.g. the campfire position the player right-clicked to open SettleScreen), use
 * the {@link #PENDING_SETTLEMENT_CENTER} map.
 */
public final class SettlementManager {
    public static final int MIN_NAME_LENGTH = 3;
    public static final int MAX_NAME_LENGTH = 24;
    public static final int INITIAL_CLAIM_RADIUS = 1;
    public static final int MIN_DISTANCE_TO_OTHER_SETTLEMENT = 6;

    // Server-side: campfire center the player right-clicked to open the settle popup.
    // Consumed when the SettleRequest payload arrives. Cleared on logout.
    private static final Map<UUID, BlockPos> PENDING_SETTLEMENT_CENTER = new HashMap<>();

    private SettlementManager() {
    }

    public enum Result {
        OK,
        ALREADY_IN_SETTLEMENT,
        NAME_TAKEN,
        NAME_INVALID,
        TOO_CLOSE_TO_OTHER_SETTLEMENT,
        /** The founding site sits on or beside a discovered/undiscovered AI city-state (village). */
        TOO_CLOSE_TO_CITY_STATE,
        /** Every banner color is already claimed by a settlement â€” the server is full. */
        MAX_FACTIONS,
        /** The requested color is already used by another settlement (colors are unique). */
        COLOR_TAKEN
    }

    /**
     * True when every {@link SettlementColor} is spoken for, i.e. the server has hit its hard cap
     * of one settlement per color. Once this is true no new settlement can be founded: open
     * founding menus are dismissed, fresh campfires stay normal, and a pending founding campfire
     * reverts to a normal campfire on its next interaction. Colors are unique per settlement
     * (see {@link #isColorInUse}), so "all colors used" is exactly "settlement count == color count".
     */
    public static boolean isAtMaxFactions(SettlementData data) {
        return data.all().size() >= SettlementColor.count();
    }

    /** Whether {@code color} is already the banner color of some existing settlement. */
    public static boolean isColorInUse(SettlementData data, SettlementColor color) {
        for (Settlement s : data.all()) {
            if (s.color() == color) {
                return true;
            }
        }
        return false;
    }

    public enum LeaveResult {
        OK,
        OK_COLLAPSED,
        NOT_IN_SETTLEMENT,
        COOLDOWN
    }

    /** A member can't leave for this long after joining/founding — stops cheesing rapid
     *  join/leave cycles (e.g. to dodge consequences or hop civs). 5 minutes. */
    public static final long LEAVE_COOLDOWN_TICKS = 20L * 60L * 5L;

    public enum JoinResult {
        OK,
        ALREADY_IN_SETTLEMENT,
        NOT_FOUND
    }

    public static void setPendingTownHall(UUID playerId, BlockPos pos) {
        PENDING_SETTLEMENT_CENTER.put(playerId, pos);
    }

    public static BlockPos takePendingTownHall(UUID playerId) {
        return PENDING_SETTLEMENT_CENTER.remove(playerId);
    }

    public static void clearPendingTownHall(UUID playerId) {
        PENDING_SETTLEMENT_CENTER.remove(playerId);
    }

    /** Disband vote expiry â€” 3 minutes in milliseconds, per the user-defined design. */
    private static final long DISBAND_VOTE_EXPIRY_MS = 3L * 60L * 1000L;
    /** Shared 5-minute expiry for every "weighty" multi-member vote: Choose-Government,
     *  Chief nomination, Council expand-territory. Single constant so all three feel the
     *  same to players ("you have about 5 minutes to weigh in"); change here and everything
     *  moves together. */
    public static final long VOTE_EXPIRY_MS = 5L * 60L * 1000L;
    /** @deprecated Same value as {@link #VOTE_EXPIRY_MS}; kept only because internal call
     *  sites in this class still use this name. New code should reference {@code VOTE_EXPIRY_MS}. */
    @Deprecated
    private static final long GOVERNMENT_VOTE_EXPIRY_MS = VOTE_EXPIRY_MS;
    /** Fraction of <b>online</b> members that must vote for the same option for the vote to
     *  pass. Default 0.5 = strict majority (count > N * ratio, i.e. more than half).
     *  Offline members forfeit (not counted in denominator). */
    private static final double GOVERNMENT_VOTE_THRESHOLD_RATIO = 0.5;
    /** Council policy-confirm threshold â€” &gt;50% of online members must Agree (every online
     *  member must vote first; see {@link #resolvePolicyVote}). */
    private static final double POLICY_CONFIRM_VOTE_THRESHOLD = 0.5;

    /** Strict-majority threshold: "more than {@code ratio} of {@code n} online members."
     *  At ratio 0.5 this is the classic strict majority â€” 2 of 2, 2 of 3, 3 of 4, 3 of 5,
     *  etc. Avoids the Math.ceil(n*0.5) pitfall where a single vote of two would pass.
     *  Used by Choose-Government and Chief-nomination votes (both want everyone to weigh in
     *  before a decision is final). */
    private static int votesNeeded(int n, double ratio) {
        if (n <= 0) return Integer.MAX_VALUE;
        return (int) Math.floor(n * ratio) + 1;
    }

    /** Council expand-territory vote threshold: 1 / 2 / ceil(N/2). Distinct from
     *  {@link #votesNeeded} because expand is intentionally a LOWER bar than the strict-
     *  majority used elsewhere â€” players want chunks claimed quickly without waiting on
     *  every member to log on. The 1 + 2 special cases prevent a solo or two-player council
     *  from being trivially soloed. Both TerritoryService and any future "weighty Council
     *  action" should call this so the formula is in one place.
     *
     *  <p>Edge case: with 0 online members this returns 1, but the calling code in
     *  {@code TerritoryService.tryCouncilVote} checks {@code votes.isEmpty()} after the
     *  toggle and bails â€” so a 0-online settlement can never actually pass the threshold,
     *  it's just numerically reachable. Don't change the formula here without revisiting
     *  that check. */
    public static int councilExpandThreshold(int onlineMembers) {
        if (onlineMembers <= 1) return 1;
        if (onlineMembers == 2) return 2;
        return (onlineMembers + 1) / 2;
    }

    /** True iff every currently-online member of {@code settlement} appears in {@code voterIds}.
     *  Used to gate vote resolution: don't end the election while an online member still hasn't
     *  clicked. Going offline mid-vote is a separate case â€” those members are simply not counted
     *  here (and not in {@link #countOnlineMembers}), per "absent players forfeit" design. */
    private static boolean allOnlineMembersVoted(MinecraftServer server, Settlement settlement,
                                                  Set<UUID> voterIds) {
        if (server == null || settlement == null) return false;
        for (UUID id : settlement.members()) {
            if (server.getPlayerList().getPlayer(id) == null) continue; // offline = forfeit
            if (!voterIds.contains(id)) return false;
        }
        return true;
    }

    /** Iterate every loaded {@link com.bannerbound.core.entity.CitizenEntity} belonging to
     *  {@code settlement}. The dimension-spanning AABB ({@code Â±3.0e7}) is the cheapest way
     *  in vanilla 1.21 to enumerate every loaded entity of a class without depending on a
     *  separate chunk-scan registry â€” entity lookups go through the same packed-entity-list
     *  that vanilla's entity iterators use, so the cost is O(loaded entities) not O(world).
     *  Centralised here so callers don't repeat the magic AABB. */
    public static java.util.List<com.bannerbound.core.entity.CitizenEntity>
            allCitizensOf(ServerLevel level, Settlement settlement) {
        if (level == null || settlement == null) return java.util.Collections.emptyList();
        java.util.List<com.bannerbound.core.entity.CitizenEntity> out = new java.util.ArrayList<>();
        for (com.bannerbound.core.entity.CitizenEntity c
                : level.getEntitiesOfClass(com.bannerbound.core.entity.CitizenEntity.class,
                    new net.minecraft.world.phys.AABB(
                        -3.0e7, level.getMinBuildHeight(), -3.0e7,
                        3.0e7, level.getMaxBuildHeight(), 3.0e7))) {
            if (settlement.id().equals(c.getSettlementId())) out.add(c);
        }
        return out;
    }

    /** Count members of {@code settlement} who are currently online. The vote-resolution
     *  denominator â€” offline members forfeit their vote (per confirmed design: "absent players
     *  forfeit; resolve among players online at resolution time"). */
    public static int countOnlineMembers(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return 0;
        int n = 0;
        for (UUID id : settlement.members()) {
            if (server.getPlayerList().getPlayer(id) != null) n++;
        }
        return n;
    }

    /** Recomputes every settlement's transient {@link Settlement#isDormant()} flag — dormant when
     *  NO member is online. Runs FIRST in the server tick ({@code ResearchEvents.onServerTick}),
     *  before any per-settlement ticker, so every consumer in the same tick reads a fresh value.
     *  The flag is transient (defaults false on load), so without this pre-pass the first tick
     *  after a restart — and any ticker ordered before the recompute — would read a stale flag. */
    public static void refreshDormancy(MinecraftServer server) {
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        for (Settlement s : SettlementData.get(overworld).all()) {
            s.setDormant(countOnlineMembers(server, s) == 0);
        }
    }

    /**
     * Entry point for the Choose-Government button. Records {@code player}'s pick (or
     * overwrites their previous pick this round), broadcasts the tally update, and if a
     * majority of ONLINE members agrees on the same option, enacts that government and clears
     * the vote.
     *
     * <p>Council enacts immediately. Chiefdom flips the type now and lets Step 6's chief-
     * election sub-flow take over from there (added when that step lands; for now we just set
     * the type and broadcast that an election should follow).
     *
     * @param player the voter â€” must be a member of a settlement whose
     *               {@code governmentChoiceWindowOpen()} returns true
     * @param pick   the chosen government; {@link Settlement.Government#NONE} is invalid and
     *               silently dropped
     */
    public static void handleGovernmentVote(ServerPlayer player, Settlement.Government pick) {
        if (pick == null || pick == Settlement.Government.NONE) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) return;
        if (!settlement.governmentChoiceWindowOpen()) {
            player.sendSystemMessage(Component.translatable("bannerbound.government.vote.not_open")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        // A council of one is just a chief with extra steps, and council vote thresholds assume
        // multiple members â€” reject a COUNCIL pick while the voter is the only member online.
        // (The client greys the button; this stops spoofed packets.)
        if (pick == Settlement.Government.COUNCIL) {
            int online = 0;
            for (UUID m : settlement.members()) {
                if (server.getPlayerList().getPlayer(m) != null) online++;
            }
            if (online <= 1) {
                player.sendSystemMessage(Component.translatable(
                        "bannerbound.government.council.solo_tooltip")
                    .withStyle(ChatFormatting.RED));
                return;
            }
        }
        long now = System.currentTimeMillis();

        // Expire stale vote before accepting the new one â€” same shape as disband.
        if (settlement.isGovernmentVoteActive()
                && (now - settlement.governmentVoteStartedMs()) > GOVERNMENT_VOTE_EXPIRY_MS) {
            settlement.clearGovernmentVote();
            broadcastToSettlement(server, settlement,
                Component.translatable("bannerbound.government.vote.expired")
                    .withStyle(ChatFormatting.GRAY));
        }

        // Vote is final once cast. The client greys out the buttons after Cast Vote, but a
        // spoofed packet shouldn't be able to change a player's choice either â€” reject re-votes
        // server-side. (Expiry above already wipes the map, so this is genuinely a duplicate.)
        if (settlement.governmentVotes().containsKey(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("bannerbound.vote.already_cast")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }

        boolean firstVote = settlement.governmentVotes().isEmpty();
        Settlement.Government previous = settlement.governmentVotes().get(player.getUUID());
        settlement.castGovernmentVote(player.getUUID(), pick, now);
        data.setDirty();

        Component pickLabel = Component.translatable(
            pick == Settlement.Government.COUNCIL
                ? "bannerbound.government.council"
                : "bannerbound.government.chiefdom");

        if (firstVote) {
            broadcastToSettlement(server, settlement,
                Component.translatable("bannerbound.government.vote.started",
                    player.getName(), pickLabel)
                    .withStyle(ChatFormatting.GOLD));
        } else if (previous == null || previous != pick) {
            int councilN = settlement.governmentVoteCountFor(Settlement.Government.COUNCIL);
            int chiefdomN = settlement.governmentVoteCountFor(Settlement.Government.CHIEFDOM);
            broadcastToSettlement(server, settlement,
                Component.translatable("bannerbound.government.vote.progress",
                    player.getName(), pickLabel, councilN, chiefdomN)
                    .withStyle(ChatFormatting.GOLD));
        }

        // Resolve. Denominator = online members AT RESOLUTION TIME; if someone logs off mid-
        // window, the electorate shrinks rather than counting them as a no-vote.
        // Don't resolve until every online member has voted. Going offline mid-window forfeits
        // their vote (countOnlineMembers shrinks), but a still-online member who hasn't clicked
        // yet must not be cut out by an early majority.
        int onlineNow = countOnlineMembers(server, settlement);
        if (onlineNow <= 0) return; // nobody to resolve against; just record + wait
        if (!allOnlineMembersVoted(server, settlement, settlement.governmentVotes().keySet())) return;
        int needed = votesNeeded(onlineNow, GOVERNMENT_VOTE_THRESHOLD_RATIO);
        // Count only ONLINE votes so a logged-off pick doesn't carry a vote it can no longer
        // attend (mirrors ChatVoteManager.tryResolve).
        int councilN = 0;
        int chiefdomN = 0;
        for (java.util.Map.Entry<UUID, Settlement.Government> e
                : settlement.governmentVotes().entrySet()) {
            if (server.getPlayerList().getPlayer(e.getKey()) == null) continue;
            if (e.getValue() == Settlement.Government.COUNCIL) councilN++;
            else if (e.getValue() == Settlement.Government.CHIEFDOM) chiefdomN++;
        }
        Settlement.Government winner = null;
        if (councilN >= needed && councilN > chiefdomN) winner = Settlement.Government.COUNCIL;
        else if (chiefdomN >= needed && chiefdomN > councilN) winner = Settlement.Government.CHIEFDOM;
        if (winner != null) {
            enactGovernment(server, settlement, data, winner);
        } else {
            // All online voted, no strict majority â†’ hand to citizens. v1 stub picks per
            // citizen uniformly between Council and Chiefdom (Step 9's resentment weighting
            // will replace the uniform pick once shipped). The reveal screen plays the same
            // animation as the chief tiebreak; the actual enactment is scheduled for after
            // the reveal completes.
            dispatchGovernmentTribeVoteReveal(server, settlement, data);
        }
    }

    /** Flip {@code settlement} to {@code winner}, clear the vote, broadcast the result, push
     *  fresh state to every member's open town-hall screen. For Chiefdom, hands off to
     *  {@link #startChiefElection} so the second-stage chief-pick sub-flow begins immediately;
     *  for Council, no follow-up is needed (the whole council are the leaders). */
    private static void enactGovernment(MinecraftServer server, Settlement settlement,
                                         SettlementData data, Settlement.Government winner) {
        settlement.setGovernmentType(winner);
        settlement.clearGovernmentVote();
        // Enacting a government galvanises the populace: every existing citizen's compliance
        // jumps to the max. From here on new citizens also spawn at 100 (see
        // ImmigrationManager.spawnNewCitizen) â€” only the lawless anarchy stage rolls the low 5â€“13.
        if (server != null) {
            for (com.bannerbound.core.entity.CitizenEntity c
                    : allCitizensOf(server.overworld(), settlement)) {
                c.setCompliance(100);
            }
        }
        // Coup suppression is a Chiefdom-specific state â€” clear it on every government
        // transition so a flag raised pre-transition doesn't stick around erroneously
        // (especially important if future content allows switching governments mid-era).
        settlement.setCoupSuppressed(false);
        data.setDirty();
        Component label = Component.translatable(
            winner == Settlement.Government.COUNCIL
                ? "bannerbound.government.council"
                : "bannerbound.government.chiefdom");
        broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.government.enacted", label)
                .withStyle(ChatFormatting.GREEN));
        // Refresh the population payload so client-side gauges + buttons pick up the new
        // government type immediately (food consumption starts the next tick, etc.).
        ImmigrationManager.broadcastState(server, settlement);
        if (winner == Settlement.Government.CHIEFDOM) {
            // Chiefdom keeps the ordeal open â€” the chief still has to be elected. Celebrate
            // only after enactChief fires.
            startChiefElection(server, settlement, data);
        } else {
            // Council: the ordeal is fully done â€” fireworks + level-up.
            celebrateGovernmentEnacted(server, settlement);
        }
    }

    /** Fires off the "the ordeal is decided" feedback: the experience-level-up jingle to every
     *  online member individually (personal feedback that this was a real change), plus a
     *  fireworks burst at the town hall (communal celebration, same shape as the founding
     *  fireworks). Called from {@link #enactGovernment} when Council wins, and from
     *  {@link #enactChief} when the Chief is finalised. */
    public static void celebrateGovernmentEnacted(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        ServerLevel level = server.overworld();
        for (UUID memberId : settlement.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(memberId);
            if (p == null) continue;
            p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0f, 1.0f);
        }
        BlockPos thp = settlement.townHallPos();
        if (thp != null) {
            // Use the rocket-launch SFX as the "fanfare" so we don't double up on the level-up
            // â€” the level-up has already played in each member's ears above.
            launchCelebrationFireworks(level, thp, settlement, 3,
                SoundEvents.FIREWORK_ROCKET_LAUNCH);
        }
        com.bannerbound.core.crisis.CrisisManager.onGovernmentEnacted(server, settlement);
    }

    /** Open the chief-election sub-flow on a settlement that just declared Chiefdom. Solo
     *  settlements (1 online member) auto-elect that player as Chief. Multi-member settlements
     *  start a nomination round that resolves via {@link #handleChiefNomination} (majority of
     *  online votes) or via the timeout in {@code ImmigrationManager.tickAll} (highest-vote
     *  count wins; ties go to {@link #tiebreakWithCitizenVotes}). */
    public static void startChiefElection(MinecraftServer server, Settlement settlement,
                                           SettlementData data) {
        int onlineNow = countOnlineMembers(server, settlement);
        if (onlineNow <= 1) {
            // Solo Chiefdom â€” pick the single online member (or fall back to the owner).
            UUID solo = null;
            for (UUID id : settlement.members()) {
                if (server.getPlayerList().getPlayer(id) != null) { solo = id; break; }
            }
            if (solo == null) solo = settlement.owner();
            enactChief(server, settlement, data, solo);
            return;
        }
        broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.chief.election.started")
                .withStyle(ChatFormatting.GOLD));
        // No tally yet â€” players will nominate via NominateChiefScreen, which fires
        // CastChiefNominationPayload â†’ handleChiefNomination on the server.
    }

    /**
     * Records {@code player}'s nomination of {@code candidate} in the active chief election,
     * broadcasts the tally update, and â€” if a majority of ONLINE members agrees on one
     * candidate â€” finalises that player as Chief.
     */
    public static void handleChiefNomination(ServerPlayer player, UUID candidate) {
        if (candidate == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) return;
        if (!settlement.chiefdomElectionWindowOpen()) {
            player.sendSystemMessage(Component.translatable("bannerbound.chief.election.not_open")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        // Candidate must be a member of this settlement.
        if (!settlement.members().contains(candidate)) {
            player.sendSystemMessage(Component.translatable("bannerbound.chief.election.bad_candidate")
                .withStyle(ChatFormatting.RED));
            return;
        }
        long now = System.currentTimeMillis();
        // Expire stale vote first.
        if (settlement.isChiefElectionActive()
                && (now - settlement.chiefElectionStartedMs()) > GOVERNMENT_VOTE_EXPIRY_MS) {
            resolveChiefElectionByTopVote(server, settlement, data);
            if (!settlement.chiefdomElectionWindowOpen()) return; // resolved
            settlement.clearChiefElection();
        }
        // Nomination is final once cast â€” same rule as the government vote. Reject duplicates.
        if (settlement.chiefNominations().containsKey(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("bannerbound.vote.already_cast")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        UUID previous = settlement.chiefNominations().get(player.getUUID());
        settlement.castChiefNomination(player.getUUID(), candidate, now);
        data.setDirty();
        Component candidateName = playerName(server, candidate);
        if (previous == null) {
            broadcastToSettlement(server, settlement,
                Component.translatable("bannerbound.chief.election.nominated",
                    player.getName(), candidateName)
                    .withStyle(ChatFormatting.GOLD));
        } else if (!previous.equals(candidate)) {
            broadcastToSettlement(server, settlement,
                Component.translatable("bannerbound.chief.election.changed",
                    player.getName(), candidateName)
                    .withStyle(ChatFormatting.GOLD));
        }
        // Wait for every online member to nominate before resolving. An online member who
        // hasn't picked yet must not be cut out by an early majority. Offline members forfeit.
        int onlineNow = countOnlineMembers(server, settlement);
        if (onlineNow <= 0) return;
        if (!allOnlineMembersVoted(server, settlement, settlement.chiefNominations().keySet())) return;
        int needed = votesNeeded(onlineNow, GOVERNMENT_VOTE_THRESHOLD_RATIO);
        int topCount = settlement.chiefNominationCountFor(candidate);
        if (topCount >= needed) {
            enactChief(server, settlement, data, candidate);
            return;
        }
        // All online voted but no strict majority for any one candidate â†’ tiebreak (top-vote or
        // citizen-vote reveal if multiple tied).
        resolveChiefElectionByTopVote(server, settlement, data);
    }

    /** Tally-time resolver â€” pick the candidate with the most player-votes. Single-winner
     *  case enacts immediately; ties hand off to {@link #dispatchTribeVoteReveal} which
     *  shows the citizen-vote animation on all members' screens and schedules the actual
     *  enactment for ~5 s out. If nobody got any votes at all, the election quietly retracts
     *  (settlement stays in the chief-election window â€” next nomination can restart). */
    public static void resolveChiefElectionByTopVote(MinecraftServer server, Settlement settlement,
                                                       SettlementData data) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (UUID candidate : settlement.chiefNominations().values()) {
            counts.merge(candidate, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            settlement.clearChiefElection();
            return;
        }
        int topCount = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<UUID> tied = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : counts.entrySet()) {
            if (e.getValue() == topCount) tied.add(e.getKey());
        }
        if (tied.size() == 1) {
            enactChief(server, settlement, data, tied.get(0));
        } else {
            dispatchTribeVoteReveal(server, settlement, data, tied);
        }
    }

    /**
     * Citizen-vote tiebreaker for the chief election. Each living citizen casts one vote
     * weighted by inverse resentment â€” formula {@code weight(p) = 1 / (1 + resentment(p))},
     * normalised across the tied candidates. Returns each citizen's pick in random reveal
     * order (paired with the candidate UUIDs in the parallel arrays).
     *
     * <p>v1 stub: per-citizen resentment isn't shipped yet (Step 9). For now the weights
     * are uniform, so each citizen picks a tied candidate uniformly at random. When Step 9
     * ships, plug the resentment map in here without touching the caller.
     */
    public static List<UUID> computeCitizenVotes(ServerLevel level, Settlement settlement,
                                                   List<UUID> tiedCandidates) {
        List<UUID> votes = new ArrayList<>();
        if (tiedCandidates.isEmpty()) return votes;
        java.util.Random rng = new java.util.Random();
        for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
            // TODO Step 9: weight by inverse resentment per (citizen, candidate). Uniform now.
            votes.add(tiedCandidates.get(rng.nextInt(tiedCandidates.size())));
        }
        return votes;
    }

    // Tribe-vote reveal duration is computed per-vote-count via
    // TribeVoteTiming.revealDurationMs â€” single source of truth shared with the client
    // animation. No hardcoded constant here; see the callers below.

    /** Tied chief election â†’ kick off the dramatic citizen-vote reveal on every online
     *  member's screen, then schedule the actual enactment for after the animation. The
     *  winner is decided server-side now (highest citizen-vote count across the reveal
     *  sequence); the client just plays it back. */
    private static void dispatchTribeVoteReveal(MinecraftServer server, Settlement settlement,
                                                  SettlementData data, List<UUID> tiedCandidates) {
        ServerLevel overworld = server.overworld();
        List<UUID> citizenVotes = computeCitizenVotes(overworld, settlement, tiedCandidates);
        // Pick winner: candidate with most citizen votes; if citizens themselves tie, RNG.
        Map<UUID, Integer> tally = new HashMap<>();
        for (UUID v : citizenVotes) tally.merge(v, 1, Integer::sum);
        int topCitizenCount = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<UUID> topByCitizen = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : tally.entrySet()) {
            if (e.getValue() == topCitizenCount) topByCitizen.add(e.getKey());
        }
        UUID winner = topByCitizen.isEmpty()
            ? tiedCandidates.get((int) (Math.random() * tiedCandidates.size()))
            : topByCitizen.get((int) (Math.random() * topByCitizen.size()));

        // Build the reveal payload â€” voter names (citizens) + candidate names (the player
        // each citizen voted for), parallel arrays in reveal order.
        java.util.ArrayList<String> voterNames = new java.util.ArrayList<>();
        java.util.ArrayList<String> candidateNames = new java.util.ArrayList<>();
        int i = 0;
        for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
            if (i >= citizenVotes.size()) break;
            voterNames.add(c.name());
            candidateNames.add(playerName(server, citizenVotes.get(i)).getString());
            i++;
        }
        com.bannerbound.core.network.OpenTribeVoteScreenPayload reveal =
            new com.bannerbound.core.network.OpenTribeVoteScreenPayload(voterNames, candidateNames);
        for (UUID memberId : settlement.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(memberId);
            if (p != null) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, reveal);
            }
        }

        // Schedule the actual chief enactment for after the animation completes â€” the
        // reveal duration is computed from the same per-vote-count formula the screen uses.
        long revealMs = com.bannerbound.core.client.TribeVoteTiming.revealDurationMs(voterNames.size());
        long enactTick = overworld.getGameTime() + (revealMs / 50L);
        settlement.schedulePendingChief(winner, enactTick);
        // Clear the player-vote tally so the chief-election window doesn't try to resolve
        // again before the pending enact fires.
        settlement.clearChiefElection();
        data.setDirty();
    }

    /** Final step of either election path (solo auto-elect, majority pass, or timeout
     *  tiebreaker). Sets the chief, clears election state, broadcasts the winner, refreshes
     *  the chief's display name so the crown glyph kicks in immediately, and fires the
     *  "ordeal complete" celebration (level-up + fireworks). */
    /** Minimum time a player must serve as chief before they're allowed to Step Down. Anti-cheese
     *  so a chief can't immediately resign to dodge a coup / reset state. 20 minutes real time
     *  (1200 ticks/min). The Step-Down button greys out with a live countdown until it elapses. */
    public static final long CHIEF_STEP_DOWN_COOLDOWN_TICKS = 20L * 60L * 20L;

    private static void enactChief(MinecraftServer server, Settlement settlement,
                                    SettlementData data, UUID chiefId) {
        settlement.setChiefPlayerId(chiefId);
        // Anchor the step-down cooldown at the moment of seating.
        settlement.setChiefSinceTick(server.overworld().getGameTime());
        settlement.clearChiefElection();
        // Step 15: a freshly-installed chief takes the chair from any sitting regent. The
        // recompute below will null the regent out if the new chief is online (typical
        // case â€” they just won an election), or re-pick a regent if they're somehow already
        // offline at install time (rare; admin command path).
        data.setDirty();
        broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.chief.election.elected",
                playerName(server, chiefId))
                .withStyle(ChatFormatting.GREEN));
        // Move the chief into a dedicated chief scoreboard team. The team prefix is vanilla-
        // synced to every connected client, so this is what makes the crown show in the
        // nametag above the chief's head + in the TAB list + in chat â€” one source of truth
        // for all three rendering paths, no NameFormat hook needed.
        ServerPlayer chiefPlayer = server.getPlayerList().getPlayer(chiefId);
        if (chiefPlayer != null) {
            applyChiefScoreboardTeam(server, chiefPlayer, settlement);
        }
        recomputeRegent(server, settlement);
        ImmigrationManager.broadcastState(server, settlement);
        celebrateGovernmentEnacted(server, settlement);
    }

    /** Tick-driven entry point: install the chief whose enactment was scheduled by the
     *  tribe-vote reveal flow. Called from {@code ImmigrationManager.tickAll} when
     *  {@code Settlement.pendingChiefEnactTick} arrives. Clears the pending fields so the
     *  install fires exactly once. */
    public static void enactPendingChief(MinecraftServer server, Settlement settlement,
                                          SettlementData data) {
        UUID winner = settlement.pendingChiefId();
        if (winner == null) return;
        settlement.clearPendingChief();
        enactChief(server, settlement, data, winner);
    }

    /** Choose-Government tiebreak: every online member voted, no strict majority. Citizens
     *  break it â€” each citizen picks Council or Chiefdom uniformly at random (Step 9 will
     *  replace this with resentment-weighted picks). The reveal animation plays on every
     *  member's screen; the winner is decided server-side now and enacted after the
     *  animation completes. */
    private static void dispatchGovernmentTribeVoteReveal(MinecraftServer server,
                                                           Settlement settlement,
                                                           SettlementData data) {
        ServerLevel overworld = server.overworld();
        Settlement.Government[] options = {
            Settlement.Government.COUNCIL,
            Settlement.Government.CHIEFDOM
        };
        java.util.Random rng = new java.util.Random();
        java.util.ArrayList<String> voterNames = new java.util.ArrayList<>();
        java.util.ArrayList<String> candidateNames = new java.util.ArrayList<>();
        int councilN = 0;
        int chiefdomN = 0;
        for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
            Settlement.Government pick = options[rng.nextInt(options.length)];
            if (pick == Settlement.Government.COUNCIL) councilN++; else chiefdomN++;
            voterNames.add(c.name());
            candidateNames.add(Component.translatable(
                pick == Settlement.Government.COUNCIL
                    ? "bannerbound.government.council"
                    : "bannerbound.government.chiefdom").getString());
        }
        // No citizens at all (edge case): plain RNG between the two options.
        Settlement.Government winner;
        if (councilN == chiefdomN) {
            // Citizens themselves tied (including the no-citizens case) â†’ RNG breaks it.
            winner = options[rng.nextInt(options.length)];
        } else {
            winner = councilN > chiefdomN
                ? Settlement.Government.COUNCIL
                : Settlement.Government.CHIEFDOM;
        }

        com.bannerbound.core.network.OpenTribeVoteScreenPayload reveal =
            new com.bannerbound.core.network.OpenTribeVoteScreenPayload(voterNames, candidateNames);
        for (UUID memberId : settlement.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(memberId);
            if (p != null) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, reveal);
            }
        }

        long revealMs = com.bannerbound.core.client.TribeVoteTiming.revealDurationMs(voterNames.size());
        long enactTick = overworld.getGameTime() + (revealMs / 50L);
        settlement.schedulePendingGovernment(winner, enactTick);
        // Clear the player-vote tally so handleGovernmentVote doesn't try to resolve again
        // before the pending enact fires.
        settlement.clearGovernmentVote();
        data.setDirty();
    }

    /** Tick-driven entry point: enact the government type whose installation was scheduled
     *  by the Choose-Government tribe-vote reveal. Mirrors {@link #enactPendingChief}. */
    public static void enactPendingGovernment(MinecraftServer server, Settlement settlement,
                                                SettlementData data) {
        Settlement.Government winner = settlement.pendingGovernmentType();
        if (winner == null) return;
        settlement.clearPendingGovernment();
        enactGovernment(server, settlement, data, winner);
    }

    /** Tick-driven: dissolve a tribe-backed (Opinionated Crowd) disband once the citizens'
     *  confirming reveal has played for the scheduled duration. Mirrors {@link
     *  #enactPendingGovernment}; drained by {@link ImmigrationManager#tickAll}. */
    public static void enactPendingDisband(MinecraftServer server, Settlement settlement,
                                            SettlementData data) {
        if (!settlement.hasPendingDisband()) return;
        settlement.clearPendingDisband();
        broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.disband.vote.passed").withStyle(ChatFormatting.RED));
        performFullDisband(server, settlement, data);
    }

    // â”€â”€â”€ Step 13 v2 tunables â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** Citizens at OR above this resentment toward the chief count as "in revolt." */
    private static final int COUP_CITIZEN_THRESHOLD = 80;
    /** Fraction of citizens-in-revolt that triggers the dawn coup vote (or, in singleplayer,
     *  the compliance-doubler suppression flag). */
    private static final double COUP_TRIGGER_FRACTION = 0.45;
    /** Below this many online players the tribe vote can't elect anyone â€” singleplayer
     *  Chiefdom falls back to the {@link Settlement#isCoupSuppressed} compliance doubler. */
    private static final int COUP_MIN_ONLINE_PLAYERS = 2;

    /** Step 13 v2: settlement-level dawn evaluator. Called once per day boundary from
     *  {@link ImmigrationManager#tickAll}.
     *
     *  <p>Condition: in CHIEFDOM with a seated chief, more than {@link #COUP_TRIGGER_FRACTION}
     *  of the population is at or above {@link #COUP_CITIZEN_THRESHOLD} resentment toward
     *  that chief.
     *
     *  <p>Outcome:
     *  <ul>
     *    <li>â‰¥ {@link #COUP_MIN_ONLINE_PLAYERS} online â†’ fire the tribe-vote reveal with
     *        every other member as a candidate; citizens pick, winner is enacted via the
     *        existing post-reveal install path.</li>
     *    <li>&lt; threshold â†’ set {@link Settlement#setCoupSuppressed} so the per-citizen
     *        compliance tick doubles its erosion until the condition clears. The "no NPCs
     *        to depose you, but you still feel the heat" fallback for singleplayer.</li>
     *  </ul>
     *
     *  When the condition is NOT met, clears the suppression flag. */
    public static void dawnCoupCheck(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        if (settlement.governmentType() != Settlement.Government.CHIEFDOM) {
            if (settlement.isCoupSuppressed()) settlement.setCoupSuppressed(false);
            return;
        }
        UUID chiefId = settlement.chiefPlayerId();
        if (chiefId == null) {
            if (settlement.isCoupSuppressed()) settlement.setCoupSuppressed(false);
            return;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;

        // Count citizens whose resentment toward the chief crosses the threshold.
        java.util.List<com.bannerbound.core.entity.CitizenEntity> citizens =
            allCitizensOf(overworld, settlement);
        int total = citizens.size();
        int inRevolt = 0;
        for (com.bannerbound.core.entity.CitizenEntity c : citizens) {
            if (c.getResentment(chiefId) >= COUP_CITIZEN_THRESHOLD) inRevolt++;
        }
        if (total == 0) {
            if (settlement.isCoupSuppressed()) settlement.setCoupSuppressed(false);
            return;
        }
        double fraction = inRevolt / (double) total;
        if (fraction <= COUP_TRIGGER_FRACTION) {
            if (settlement.isCoupSuppressed()) settlement.setCoupSuppressed(false);
            return;
        }
        // Condition met. Branch on online member count.
        int onlineNow = countOnlineMembers(server, settlement);
        if (onlineNow < COUP_MIN_ONLINE_PLAYERS) {
            // Singleplayer-ish â€” nobody to elect. Raise the suppression flag and let the
            // per-citizen hourly tick double compliance erosion.
            settlement.setCoupSuppressed(true);
            return;
        }
        // Enough players online to hold a vote. Drop the suppression (about to be moot
        // anyway since the chief will change), then fire the tribe vote.
        settlement.setCoupSuppressed(false);
        triggerCoupVote(server, settlement);
    }

    /** Compliance at/below which a citizen is at real risk of refusing work — the dawn
     *  full-day-strike table ({@link ComplianceTables#refuseFullDay}) only fires here. */
    public static final int STRIKE_RISK_COMPLIANCE = 30;

    /** A bracket of current settlement warnings — homelessness, looming strikes, brewing coups
     *  — derived fresh from loaded citizens. Single source of truth shared by the dusk chat
     *  pre-warning ({@link #duskWarningCheck}) and the Town Hall warnings panel. Empty list =
     *  all clear. Server-side only (scans entities). */
    public static java.util.List<Component> settlementWarnings(MinecraftServer server, Settlement settlement) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        if (server == null || settlement == null) return out;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return out;
        java.util.List<com.bannerbound.core.entity.CitizenEntity> citizens =
            allCitizensOf(overworld, settlement);
        int total = citizens.size();
        if (total == 0) return out;

        // Homelessness — count citizens with no assigned home (settlement-level truth). Only a
        // real Tribe frets about housing: a Hearth-stage campfire band has no homes yet and the
        // NO_HOME resentment thought is itself gated by isTribe() (see CitizenEntity), so warning
        // that "resentment deepens" before then would be a lie about pressure that isn't accruing.
        int homeless = settlement.isTribe() ? settlement.homelessCitizens().size() : 0;
        if (homeless > 0) {
            out.add(Component.translatable("bannerbound.warning.homeless", homeless)
                .withStyle(ChatFormatting.YELLOW));
        }

        // Looming strikes — citizens whose compliance has fallen into the refusal band. Only
        // under a government: anarchy citizens self-organize and work willingly (the dawn
        // full-day-strike roll in CitizenEntity is gated by !isAnarchy(), and low anarchy
        // compliance governs job-switch consent, not work refusal), so the warning must not
        // threaten a strike that can't happen — anarchy spawns citizens at compliance 5–13,
        // which would otherwise trip this band every time.
        int strikeRisk = 0;
        if (settlement.governmentType() != Settlement.Government.NONE) {
            for (com.bannerbound.core.entity.CitizenEntity c : citizens) {
                if (c.getCompliance() <= STRIKE_RISK_COMPLIANCE) strikeRisk++;
            }
        }
        if (strikeRisk > 0) {
            out.add(Component.translatable("bannerbound.warning.strike_risk", strikeRisk)
                .withStyle(ChatFormatting.GOLD));
        }

        // Brewing coup — same condition the dawn check uses, surfaced a day early.
        if (settlement.governmentType() == Settlement.Government.CHIEFDOM) {
            UUID chiefId = settlement.chiefPlayerId();
            if (chiefId != null) {
                int inRevolt = 0;
                for (com.bannerbound.core.entity.CitizenEntity c : citizens) {
                    if (c.getResentment(chiefId) >= COUP_CITIZEN_THRESHOLD) inRevolt++;
                }
                if (inRevolt / (double) total > COUP_TRIGGER_FRACTION) {
                    out.add(Component.translatable("bannerbound.warning.coup_imminent")
                        .withStyle(ChatFormatting.RED));
                }
            }
        }
        return out;
    }

    /** Dusk pre-warning pass — called once per in-game day from {@link ImmigrationManager#tickAll}
     *  when the day rolls into evening. Broadcasts the current {@link #settlementWarnings} to
     *  members so the dawn consequences (strikes, coup) are never a surprise. No-op when all
     *  clear. */
    public static void duskWarningCheck(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        java.util.List<Component> warnings = settlementWarnings(server, settlement);
        if (warnings.isEmpty()) return;
        broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.warning.dusk_header").withStyle(ChatFormatting.RED));
        for (Component w : warnings) {
            broadcastToSettlement(server, settlement, Component.literal("  ").append(w));
        }
    }

    /** Fire the tribe-vote reveal flow with every non-chief member as a candidate. The
     *  citizens "vote" via the existing uniform-RNG citizen pick (Step 9 weighting TODO);
     *  the install fires after the animation, replacing the chief. Same plumbing as the
     *  founding chief-election tiebreak â€” see {@link #dispatchTribeVoteReveal}. */
    private static void triggerCoupVote(MinecraftServer server, Settlement settlement) {
        UUID oldChief = settlement.chiefPlayerId();
        if (oldChief == null) return;
        java.util.List<UUID> candidates = new java.util.ArrayList<>();
        for (UUID memberId : settlement.members()) {
            if (!memberId.equals(oldChief)) candidates.add(memberId);
        }
        if (candidates.isEmpty()) {
            // No eligible successor (chief is the only settlement member). Caller's online-
            // count check should prevent this, but guard defensively: fall back to the
            // singleplayer-equivalent compliance penalty so the citizens still get the
            // "we tried to depose you and nobody was there" pressure.
            settlement.setCoupSuppressed(true);
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.chief.coup.vote_called",
                playerName(server, oldChief))
                .withStyle(ChatFormatting.RED));
        dispatchTribeVoteReveal(server, settlement, data, candidates);
    }

    // Legacy depositionAndInstall removed (cleanup pass) â€” the organic coup flow goes
    // through triggerCoupVote â†’ tribe-vote reveal â†’ enactPendingChief, and no admin command
    // calls the old NPC-install path. Re-add a thin wrapper here if a future admin command
    // wants instant install.

    /** Pretty player name for chat â€” looks up the online ServerPlayer first, falls back to
     *  the profile cache, then to a short UUID-prefix if both fail. */
    private static Component playerName(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) return p.getName();
        net.minecraft.server.players.GameProfileCache cache = server.getProfileCache();
        String shortId = id.toString().substring(0, 8);
        if (cache == null) return Component.literal(shortId);
        return Component.literal(cache.get(id).map(profile -> profile.getName()).orElse(shortId));
    }

    /** Instant-disband confirmation window: a first press only warns, a second press within this
     *  long actually disbands. Keyed per player (transient â€” a server restart just re-arms it). */
    private static final long DISBAND_CONFIRM_WINDOW_MS = 12_000L;
    private static final java.util.Map<UUID, Long> PENDING_DISBAND_CONFIRM = new java.util.HashMap<>();

    /** True if {@code settlement} is currently under a barbarian raid, in a city-state war, or in
     *  a live settlement-vs-settlement war (active or a pending declaration). Disband is refused
     *  while this holds. */
    private static boolean isUnderAttack(MinecraftServer server, Settlement settlement) {
        ServerLevel level = server.overworld();
        UUID id = settlement.id();
        if (com.bannerbound.core.barbarian.BarbarianCampManager.isSettlementRaided(id)) return true;
        if (com.bannerbound.core.citystate.CityStateWarManager.isSettlementAtWar(level, id)) return true;
        for (SettlementData.DiplomacyRelation rel : SettlementData.get(level).diplomacyRelations()) {
            if (rel.involves(id) && (rel.warActive || rel.pendingTicksRemaining > 0)) return true;
        }
        return false;
    }

    /** First press of an instant (solo / chief) disband only warns; a second press within
     *  {@link #DISBAND_CONFIRM_WINDOW_MS} actually disbands. Returns true once confirmed (and
     *  clears the pending flag), false on the first press after sending the warning. */
    private static boolean confirmInstantDisband(ServerPlayer player, Settlement settlement) {
        long nowMs = System.currentTimeMillis();
        Long pending = PENDING_DISBAND_CONFIRM.get(player.getUUID());
        if (pending != null && (nowMs - pending) <= DISBAND_CONFIRM_WINDOW_MS) {
            PENDING_DISBAND_CONFIRM.remove(player.getUUID());
            return true;
        }
        PENDING_DISBAND_CONFIRM.put(player.getUUID(), nowMs);
        player.sendSystemMessage(Component.translatable("bannerbound.disband.confirm",
                settlement.factionName()).withStyle(ChatFormatting.YELLOW));
        return false;
    }

    /**
     * Entry point for the Disband Settlement button. Routes into either an instant disband
     * (solo settlement) or the voting flow (multi-player settlement, 100% required). Vote
     * progress is broadcast to all settlement members; the vote auto-expires after 3 minutes.
     */
    public static void disband(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) {
            player.sendSystemMessage(Component.translatable("bannerbound.disband.error.not_in_settlement")
                .withStyle(ChatFormatting.RED));
            return;
        }

        int memberCount = settlement.members().size();
        long now = System.currentTimeMillis();

        // Hard block: a settlement can't disband while it's being raided or is at war â€” survive the
        // raid or make peace first. Stops a mid-siege rage-quit AND avoids tearing the settlement
        // down with an active raid/war still referencing it.
        if (isUnderAttack(server, settlement)) {
            player.sendSystemMessage(Component.translatable("bannerbound.disband.error.under_attack")
                .withStyle(ChatFormatting.RED));
            return;
        }

        // Solo settlement: bypass voting, disband immediately â€” but require a confirming second
        // press first, so a single misclick can't wipe a settlement, its citizens and claims.
        if (memberCount <= 1) {
            if (!confirmInstantDisband(player, settlement)) return;
            performFullDisband(server, settlement, data);
            return;
        }

        // Chiefdom: the seated Chief has unilateral disband authority â€” no vote required.
        // (Without this, the 100%-of-members vote stalls forever because only the Chief is
        // permitted to press the button at all, so we'd never reach memberCount votes.)
        if (settlement.governmentType() == Settlement.Government.CHIEFDOM
                && settlement.canActWeighty(player.getUUID())) {
            if (!confirmInstantDisband(player, settlement)) return;
            performFullDisband(server, settlement, data);
            return;
        }

        // Multi-player: expire stale vote before accepting new one.
        if (settlement.isDisbandVoteActive()
                && (now - settlement.disbandVoteStartedMs()) > DISBAND_VOTE_EXPIRY_MS) {
            settlement.clearDisbandVote();
            broadcastToSettlement(server, settlement,
                Component.translatable("bannerbound.disband.vote.expired")
                    .withStyle(ChatFormatting.GRAY));
        }

        if (settlement.hasDisbandVoted(player.getUUID())) {
            // Already voted â€” don't double-count, just remind them where we stand.
            player.sendSystemMessage(Component.translatable(
                    "bannerbound.disband.already_voted",
                    settlement.disbandVoteCount(), memberCount)
                .withStyle(ChatFormatting.YELLOW));
            return;
        }

        boolean firstVote = settlement.disbandVotes().isEmpty();
        settlement.addDisbandVote(player.getUUID(), now);
        data.setDirty();

        if (firstVote) {
            broadcastToSettlement(server, settlement,
                Component.translatable("bannerbound.disband.vote.started", player.getName())
                    .withStyle(ChatFormatting.GOLD));
        } else {
            broadcastToSettlement(server, settlement,
                Component.translatable("bannerbound.disband.vote.progress",
                    player.getName(), settlement.disbandVoteCount(), memberCount)
                    .withStyle(ChatFormatting.GOLD));
        }

        if (settlement.disbandVoteCount() >= memberCount) {
            // Opinionated Crowd: a major vote also needs the tribe's backing, put to the citizens
            // via the tribe-vote reveal (not a chat line). Refuse â†’ blocked + vote cleared.
            // Approve â†’ the disband is scheduled to fire when the reveal finishes animating, so
            // the dissolving settlement doesn't close the reveal screen the moment it opens.
            if (settlement.governmentType() == Settlement.Government.COUNCIL
                    && settlement.hasPolicy(PolicyRegistry.OPINIONATED_CROWD)) {
                int citizenCount = allCitizensOf(server.overworld(), settlement).size();
                if (citizenCount > 0) {
                    boolean approved = dispatchOpinionatedReveal(server, settlement);
                    settlement.clearDisbandVote();
                    if (approved) {
                        long revealMs = com.bannerbound.core.client.TribeVoteTiming
                            .revealDurationMs(citizenCount);
                        settlement.schedulePendingDisband(
                            server.overworld().getGameTime() + revealMs / 50L);
                    }
                    data.setDirty();
                    return;
                }
            }
            broadcastToSettlement(server, settlement,
                Component.translatable("bannerbound.disband.vote.passed")
                    .withStyle(ChatFormatting.RED));
            performFullDisband(server, settlement, data);
        }
    }

    // â”€â”€â”€ Policies â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Builds + sends the policy-state snapshot to every online member so their Policies tab
     *  stays live (available list, active slots, pending change, confirm-vote tally,
     *  Chiefdom suggestions). Call after every policy mutation. */
    public static void broadcastPolicyState(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        java.util.List<String> available = PolicyRegistry.availableFor(settlement);
        java.util.List<String> active = new java.util.ArrayList<>(settlement.activePolicies());
        Settlement.PolicyChange pending = settlement.pendingPolicyChange();
        int pendingSlot = pending == null ? -1 : pending.slotIndex();
        String addId = pending == null || pending.addPolicyId() == null ? "" : pending.addPolicyId();
        String removeId = pending == null || pending.removePolicyId() == null ? "" : pending.removePolicyId();
        java.util.List<UUID> voterIds = new java.util.ArrayList<>();
        java.util.List<Boolean> voteAgrees = new java.util.ArrayList<>();
        for (java.util.Map.Entry<UUID, Boolean> e : settlement.policyConfirmVotes().entrySet()) {
            voterIds.add(e.getKey());
            voteAgrees.add(e.getValue());
        }
        java.util.List<String> sugIds = new java.util.ArrayList<>();
        java.util.List<java.util.List<UUID>> sugVoters = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.LinkedHashSet<UUID>> e
                : settlement.allPolicySuggestions().entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            sugIds.add(e.getKey());
            sugVoters.add(new java.util.ArrayList<>(e.getValue()));
        }
        com.bannerbound.core.network.PolicyStateSyncPayload payload =
            new com.bannerbound.core.network.PolicyStateSyncPayload(
                available, active, settlement.policySlotTypeNames(), pendingSlot, addId, removeId,
                countOnlineMembers(server, settlement), voterIds, voteAgrees, sugIds, sugVoters);
        for (UUID memberId : settlement.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(memberId);
            if (p != null) PacketDistributor.sendToPlayer(p, payload);
        }
    }

    /** A member proposed a policy change (drag-into-slot + Confirm). Council opens a confirm
     *  vote; a Chiefdom chief enacts immediately; a Chiefdom non-chief is rejected (they must
     *  use {@link #suggestPolicy}). Returns silently on any validation failure (client mirrors
     *  the same gates, so a rejection here means a tampered or stale packet). */
    public static void proposePolicyChange(ServerPlayer player, int slotIndex,
                                            String addId, String removeId) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null || s.governmentType() == Settlement.Government.NONE) return;
        String add = (addId == null || addId.isBlank()) ? null : addId;
        String remove = (removeId == null || removeId.isBlank()) ? null : removeId;
        if (add == null && remove == null) return;
        // Validate the add: available + not already active + a free slot of the matching type
        // (or the government's signature slot) once the remove + any exclusive eviction is applied.
        if (add != null) {
            if (!PolicyRegistry.isAvailable(s, add)) return;
            if (s.hasPolicy(add)) return;
            if (!s.canEnactProposal(add, remove)) {
                player.displayClientMessage(Component.translatable(
                    "bannerbound.policy.error.no_slot").withStyle(ChatFormatting.RED), true);
                return;
            }
        }
        if (remove != null && !s.hasPolicy(remove)) return;

        Settlement.PolicyChange change = new Settlement.PolicyChange(slotIndex, add, remove);

        if (s.governmentType() == Settlement.Government.CHIEFDOM) {
            // Only the seated chief confirms policies (weighty); a regent cannot. Non-chiefs
            // route to suggest on the client â€” a propose here is a spoof.
            if (!s.canActWeighty(player.getUUID())) {
                player.displayClientMessage(Component.translatable(
                    "bannerbound.policy.error.chief_only").withStyle(ChatFormatting.RED), true);
                return;
            }
            enactPolicyChange(server, s, data, change);
            return;
        }
        // Council: open a confirm vote. Reject if one's already running.
        if (s.pendingPolicyChange() != null) {
            player.displayClientMessage(Component.translatable(
                "bannerbound.policy.error.vote_in_progress").withStyle(ChatFormatting.RED), true);
            return;
        }
        s.setPendingPolicyChange(change);
        // Proposer implicitly agrees â€” saves them a second click.
        s.castPolicyConfirmVote(player.getUUID(), true);
        data.setDirty();
        broadcastPolicyState(server, s);
        resolvePolicyVote(server, s, data);
    }

    /** A Council member cast an Agree/Disagree on the pending change. */
    public static void castPolicyVote(ServerPlayer player, boolean agree) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null || s.pendingPolicyChange() == null) return;
        if (s.governmentType() != Settlement.Government.COUNCIL) return;
        if (!s.members().contains(player.getUUID())) return;
        s.castPolicyConfirmVote(player.getUUID(), agree);
        data.setDirty();
        broadcastPolicyState(server, s);
        resolvePolicyVote(server, s, data);
    }

    /** Council resolution: once every online member has voted, enact if &gt;50% Agree, else
     *  discard the proposal. */
    private static void resolvePolicyVote(MinecraftServer server, Settlement s, SettlementData data) {
        if (s.pendingPolicyChange() == null) return;
        int onlineNow = countOnlineMembers(server, s);
        if (onlineNow <= 0) return;
        if (!allOnlineMembersVoted(server, s, s.policyConfirmVotes().keySet())) return;
        // Count only ONLINE votes so a logged-off Agree doesn't carry a vote it can no longer
        // attend (mirrors ChatVoteManager.tryResolve).
        int agrees = 0;
        for (java.util.Map.Entry<UUID, Boolean> e : s.policyConfirmVotes().entrySet()) {
            if (server.getPlayerList().getPlayer(e.getKey()) == null) continue;
            if (Boolean.TRUE.equals(e.getValue())) agrees++;
        }
        int needed = votesNeeded(onlineNow, POLICY_CONFIRM_VOTE_THRESHOLD);
        Settlement.PolicyChange change = s.pendingPolicyChange();
        if (agrees >= needed) {
            enactPolicyChange(server, s, data, change);
        } else {
            s.clearPolicyChangeState();
            broadcastToSettlement(server, s, Component.translatable(
                "bannerbound.policy.vote.rejected").withStyle(ChatFormatting.GRAY));
            data.setDirty();
            broadcastPolicyState(server, s);
        }
    }

    /** Chiefdom non-chief toggles a suggestion on {@code policyId}. No chat â€” the marker is the
     *  feedback. Re-broadcasts so the chief's tab updates. */
    public static void suggestPolicy(ServerPlayer player, String policyId) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null || s.governmentType() != Settlement.Government.CHIEFDOM) return;
        if (!PolicyRegistry.isAvailable(s, policyId)) return;
        s.togglePolicySuggestion(policyId, player.getUUID());
        broadcastPolicyState(server, s);
    }

    /** Cancel the pending policy change (proposer or chief clicked Cancel). */
    public static void retractPolicyChange(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null || s.pendingPolicyChange() == null) return;
        if (!s.members().contains(player.getUUID())) return;
        s.clearPolicyChangeState();
        data.setDirty();
        broadcastPolicyState(server, s);
    }

    /** Apply a confirmed policy change: remove then add, clear pending + the policy's
     *  suggestion markers, run the on/off side-effects, broadcast. */
    private static void enactPolicyChange(MinecraftServer server, Settlement s,
                                           SettlementData data, Settlement.PolicyChange change) {
        if (change.removePolicyId() != null && s.removeActivePolicy(change.removePolicyId())) {
            PolicyEffects.onDeactivated(server, s, change.removePolicyId());
        }
        String exclusive = PolicyRegistry.exclusiveWith(change.addPolicyId());
        if (exclusive != null && s.removeActivePolicy(exclusive)) {
            PolicyEffects.onDeactivated(server, s, exclusive);
            s.clearPolicySuggestions(exclusive);
        }
        if (change.addPolicyId() != null && s.addActivePolicy(change.addPolicyId())) {
            PolicyEffects.onActivated(server, s, change.addPolicyId());
            s.clearPolicySuggestions(change.addPolicyId());
        }
        // Reconcile policy-driven thoughts NOW (don't wait for the hourly upkeep, which is gated on
        // having an active policy — removing the last policy would otherwise strand its thought,
        // e.g. Nightshift fatigue).
        PolicyEffects.syncPolicyThoughts(server, s);
        s.clearPolicyChangeState();
        data.setDirty();
        broadcastPolicyState(server, s);
        broadcastToSettlement(server, s, Component.translatable(
            "bannerbound.policy.enacted").withStyle(ChatFormatting.GREEN));
        // Opinionated Crowd: a confirmed Council decision is put to the tribe via the tribe-vote
        // reveal; if the citizens back it (majority Agree) it grants the morale bonus. The reveal
        // is the feedback â€” no chat. (Only meaningful in Council, where the policy can be active.)
        if (s.governmentType() == Settlement.Government.COUNCIL
                && s.hasPolicy(PolicyRegistry.OPINIONATED_CROWD)
                && dispatchOpinionatedReveal(server, s)) {
            grantOpinionatedBonus(server, s);
        }
    }

    // â”€â”€â”€ Palettes â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // The palette governance flow is a near-exact mirror of the Policies flow above (same
    // Council-vote / Chiefdom-chief / non-chief-suggest gates, same >50% threshold). The only
    // difference: a palette's "effect" is an additive block-appeal layer, so enacting one
    // recomputes chunk appeal and re-syncs the per-block appeal table instead of running a
    // PolicyEffects hook.

    /** Builds + sends the palette-state snapshot (incl. the definitions of every available/active
     *  palette, so the client can render block icons + bonuses) to every online member. */
    public static void broadcastPaletteState(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        java.util.List<String> available = com.bannerbound.core.api.settlement.data.PaletteLoader
            .availableFor(settlement);
        java.util.List<String> active = new java.util.ArrayList<>(settlement.activePalettes());
        Settlement.PaletteChange pending = settlement.pendingPaletteChange();
        int pendingSlot = pending == null ? -1 : pending.slotIndex();
        String addId = pending == null || pending.addPaletteId() == null ? "" : pending.addPaletteId();
        String removeId = pending == null || pending.removePaletteId() == null ? "" : pending.removePaletteId();
        java.util.List<UUID> voterIds = new java.util.ArrayList<>();
        java.util.List<Boolean> voteAgrees = new java.util.ArrayList<>();
        for (java.util.Map.Entry<UUID, Boolean> e : settlement.paletteConfirmVotes().entrySet()) {
            voterIds.add(e.getKey());
            voteAgrees.add(e.getValue());
        }
        java.util.List<String> sugIds = new java.util.ArrayList<>();
        java.util.List<java.util.List<UUID>> sugVoters = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.LinkedHashSet<UUID>> e
                : settlement.allPaletteSuggestions().entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            sugIds.add(e.getKey());
            sugVoters.add(new java.util.ArrayList<>(e.getValue()));
        }
        // Definitions for every palette the client might render (available âˆª active).
        java.util.LinkedHashSet<String> defSet = new java.util.LinkedHashSet<>(available);
        defSet.addAll(active);
        java.util.List<String> defIds = new java.util.ArrayList<>();
        java.util.List<String> defNames = new java.util.ArrayList<>();
        java.util.List<java.util.List<String>> defBlockIds = new java.util.ArrayList<>();
        java.util.List<java.util.List<Float>> defBonuses = new java.util.ArrayList<>();
        for (String id : defSet) {
            com.bannerbound.core.api.settlement.Palette palette =
                com.bannerbound.core.api.settlement.data.PaletteLoader.get(id);
            if (palette == null) continue;
            java.util.List<String> blockIds = new java.util.ArrayList<>();
            java.util.List<Float> bonuses = new java.util.ArrayList<>();
            for (java.util.Map.Entry<net.minecraft.world.level.block.Block, Float> e
                    : palette.bonuses().entrySet()) {
                blockIds.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(e.getKey()).toString());
                bonuses.add(e.getValue());
            }
            defIds.add(id);
            defNames.add(palette.name());
            defBlockIds.add(blockIds);
            defBonuses.add(bonuses);
        }
        com.bannerbound.core.network.PaletteStateSyncPayload payload =
            new com.bannerbound.core.network.PaletteStateSyncPayload(
                available, active, settlement.paletteSlotCapacity(), pendingSlot, addId, removeId,
                countOnlineMembers(server, settlement), voterIds, voteAgrees, sugIds, sugVoters,
                defIds, defNames, defBlockIds, defBonuses);
        for (UUID memberId : settlement.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(memberId);
            if (p != null) PacketDistributor.sendToPlayer(p, payload);
        }
    }

    /** A member proposed a palette change â€” twin of {@link #proposePolicyChange}. */
    public static void proposePaletteChange(ServerPlayer player, int slotIndex,
                                             String addId, String removeId) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null || s.governmentType() == Settlement.Government.NONE) return;
        String add = (addId == null || addId.isBlank()) ? null : addId;
        String remove = (removeId == null || removeId.isBlank()) ? null : removeId;
        if (add == null && remove == null) return;
        if (add != null) {
            if (!com.bannerbound.core.api.settlement.data.PaletteLoader.availableFor(s).contains(add)) return;
            if (s.hasPalette(add)) return;
            int effectiveSize = s.activePalettes().size()
                - (remove != null && s.hasPalette(remove) ? 1 : 0);
            if (effectiveSize >= s.paletteSlotCapacity()) {
                player.displayClientMessage(Component.translatable(
                    "bannerbound.palette.error.no_slot").withStyle(ChatFormatting.RED), true);
                return;
            }
        }
        if (remove != null && !s.hasPalette(remove)) return;

        Settlement.PaletteChange change = new Settlement.PaletteChange(slotIndex, add, remove);

        if (s.governmentType() == Settlement.Government.CHIEFDOM) {
            if (!s.canActWeighty(player.getUUID())) {
                player.displayClientMessage(Component.translatable(
                    "bannerbound.palette.error.chief_only").withStyle(ChatFormatting.RED), true);
                return;
            }
            enactPaletteChange(server, s, data, change);
            return;
        }
        if (s.pendingPaletteChange() != null) {
            player.displayClientMessage(Component.translatable(
                "bannerbound.palette.error.vote_in_progress").withStyle(ChatFormatting.RED), true);
            return;
        }
        s.setPendingPaletteChange(change);
        s.castPaletteConfirmVote(player.getUUID(), true);
        data.setDirty();
        broadcastPaletteState(server, s);
        resolvePaletteVote(server, s, data);
    }

    /** A Council member cast an Agree/Disagree on the pending palette change. */
    public static void castPaletteVote(ServerPlayer player, boolean agree) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null || s.pendingPaletteChange() == null) return;
        if (s.governmentType() != Settlement.Government.COUNCIL) return;
        if (!s.members().contains(player.getUUID())) return;
        s.castPaletteConfirmVote(player.getUUID(), agree);
        data.setDirty();
        broadcastPaletteState(server, s);
        resolvePaletteVote(server, s, data);
    }

    private static void resolvePaletteVote(MinecraftServer server, Settlement s, SettlementData data) {
        if (s.pendingPaletteChange() == null) return;
        int onlineNow = countOnlineMembers(server, s);
        if (onlineNow <= 0) return;
        if (!allOnlineMembersVoted(server, s, s.paletteConfirmVotes().keySet())) return;
        // Count only ONLINE votes so a logged-off Agree doesn't carry a vote it can no longer
        // attend (mirrors ChatVoteManager.tryResolve).
        int agrees = 0;
        for (java.util.Map.Entry<UUID, Boolean> e : s.paletteConfirmVotes().entrySet()) {
            if (server.getPlayerList().getPlayer(e.getKey()) == null) continue;
            if (Boolean.TRUE.equals(e.getValue())) agrees++;
        }
        int needed = votesNeeded(onlineNow, POLICY_CONFIRM_VOTE_THRESHOLD);
        Settlement.PaletteChange change = s.pendingPaletteChange();
        if (agrees >= needed) {
            enactPaletteChange(server, s, data, change);
        } else {
            s.clearPaletteChangeState();
            broadcastToSettlement(server, s, Component.translatable(
                "bannerbound.palette.vote.rejected").withStyle(ChatFormatting.GRAY));
            data.setDirty();
            broadcastPaletteState(server, s);
        }
    }

    /** Chiefdom non-chief toggles a suggestion on {@code paletteId}. */
    public static void suggestPalette(ServerPlayer player, String paletteId) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null || s.governmentType() != Settlement.Government.CHIEFDOM) return;
        if (!com.bannerbound.core.api.settlement.data.PaletteLoader.availableFor(s).contains(paletteId)) return;
        s.togglePaletteSuggestion(paletteId, player.getUUID());
        broadcastPaletteState(server, s);
    }

    /** Cancel the pending palette change. */
    public static void retractPaletteChange(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null || s.pendingPaletteChange() == null) return;
        if (!s.members().contains(player.getUUID())) return;
        s.clearPaletteChangeState();
        data.setDirty();
        broadcastPaletteState(server, s);
    }

    /** Apply a confirmed palette change, then recompute appeal: a palette adds per-block appeal,
     *  so chunk beauty must be re-evaluated and the per-block appeal table re-synced (so block
     *  tooltips + the beauty HUD show the new values immediately). */
    private static void enactPaletteChange(MinecraftServer server, Settlement s,
                                            SettlementData data, Settlement.PaletteChange change) {
        if (change.removePaletteId() != null) {
            s.removeActivePalette(change.removePaletteId());
        }
        if (change.addPaletteId() != null && s.addActivePalette(change.addPaletteId())) {
            s.clearPaletteSuggestions(change.addPaletteId());
        }
        s.clearPaletteChangeState();
        data.setDirty();
        broadcastPaletteState(server, s);
        broadcastToSettlement(server, s, Component.translatable(
            "bannerbound.palette.enacted").withStyle(ChatFormatting.GREEN));
        // Recompute appeal under the new palette set + push the resolved per-block table to members.
        ChunkBeautyManager.recomputeTrackedSet(server.overworld());
        for (UUID memberId : s.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(memberId);
            if (p != null) sendBlockAppealTo(p);
        }
    }

    /**
     * Opinionated Crowd as the tribe-vote reveal (the same animated screen the government
     * tiebreaker uses) instead of a silent check + chat line. Each living citizen "votes" Agree
     * with probability equal to its compliance/100, else Disagree; the parallel-list reveal is
     * pushed to every member's screen, and the method returns whether a strict majority agreed.
     * With no citizens loaded nothing is shown and it defaults to approve (no crowd to object).
     */
    public static boolean dispatchOpinionatedReveal(MinecraftServer server, Settlement settlement) {
        ServerLevel overworld = server == null ? null : server.overworld();
        if (overworld == null) return true;
        java.util.List<com.bannerbound.core.entity.CitizenEntity> citizens =
            allCitizensOf(overworld, settlement);
        if (citizens.isEmpty()) return true;
        java.util.ArrayList<String> voterNames = new java.util.ArrayList<>();
        java.util.ArrayList<String> candidateNames = new java.util.ArrayList<>();
        String agree = Component.translatable("bannerbound.policy.agree").getString();
        String disagree = Component.translatable("bannerbound.policy.disagree").getString();
        int approve = 0;
        for (com.bannerbound.core.entity.CitizenEntity c : citizens) {
            double p = Math.max(0.0, Math.min(1.0, c.getCompliance() / 100.0));
            boolean yes = overworld.random.nextDouble() < p;
            if (yes) approve++;
            voterNames.add(c.getCitizenName());
            candidateNames.add(yes ? agree : disagree);
        }
        com.bannerbound.core.network.OpenTribeVoteScreenPayload reveal =
            new com.bannerbound.core.network.OpenTribeVoteScreenPayload(voterNames, candidateNames);
        for (UUID memberId : settlement.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(memberId);
            if (p != null) PacketDistributor.sendToPlayer(p, reveal);
        }
        return approve * 2 > citizens.size();
    }

    /** Stamp the Opinionated-Crowd bonus window (+25 compliance / -10 resentment for 10 min,
     *  read in the hourly tick). No chat â€” the {@link #dispatchOpinionatedReveal} tribe-vote
     *  reveal that gates this call is the player-facing feedback. */
    public static void grantOpinionatedBonus(MinecraftServer server, Settlement settlement) {
        ServerLevel overworld = server == null ? null : server.overworld();
        if (overworld == null) return;
        settlement.setPolicyOpinionatedBonusExpiry(
            overworld.getGameTime() + PolicyEffects.OPINIONATED_BONUS_DURATION_TICKS);
    }

    /** Push the current research-suggestion snapshot to every online settlement member so
     *  the research-screen markers update live. Called whenever a non-chief toggles a
     *  suggestion (add or retract) and whenever the chief enacts a research (which clears
     *  the marker). Cheap â€” the wire format is just the non-empty entries. */
    public static void broadcastSuggestionState(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        com.bannerbound.core.network.SuggestionStateSyncPayload payload =
            new com.bannerbound.core.network.SuggestionStateSyncPayload(
                com.bannerbound.core.network.SuggestionStateSyncPayload.flatten(
                    settlement.allScienceSuggestions()),
                com.bannerbound.core.network.SuggestionStateSyncPayload.flatten(
                    settlement.allCultureSuggestions()));
        for (UUID memberId : settlement.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(memberId);
            if (p != null) PacketDistributor.sendToPlayer(p, payload);
        }
    }

    /** Broadcasts a chat message to every ONLINE member of {@code settlement}. Public so
     *  other managers (ImmigrationManager's Code-of-Laws trigger, government vote flow,
     *  etc.) can reuse it without re-implementing the iterate-and-send pattern. */
    public static void broadcastToSettlement(MinecraftServer server, Settlement settlement, Component msg) {
        for (UUID id : settlement.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) p.sendSystemMessage(msg);
        }
    }

    /**
     * The actual destruction step: despawn citizens, unlight campfire, unclaim, remove team,
     * notify members, scrub rod selections, strip research unlocks. Refactored out of
     * {@link #disband} so the vote handler can call it once the vote passes.
     */
    public static void razeSettlement(MinecraftServer server, Settlement settlement, SettlementData data) {
        performFullDisband(server, settlement, data);
    }

    private static void performFullDisband(MinecraftServer server, Settlement settlement, SettlementData data) {
        // Capture members + claimed territory BEFORE removing the settlement (claims are cleared below).
        List<UUID> formerMembers = new ArrayList<>(settlement.members());
        java.util.Set<Long> ruinArea = new java.util.HashSet<>(settlement.claimedChunks());
        DiplomacyManager.onSettlementDisbanded(server, settlement, data);

        despawnAllCitizens(server, settlement);
        ChunkForceLoader.unforceAll(server.overworld(), settlement);

        for (UUID memberId : formerMembers) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) {
                member.sendSystemMessage(Component.translatable("bannerbound.disband.success", settlement.factionName())
                    .withStyle(settlement.identityFormatting()));
            }
        }

        BlockPos thp = settlement.townHallPos();
        if (thp != null) {
            ServerLevel level = server.overworld();
            BlockState state = level.getBlockState(thp);
            if (state.getBlock() instanceof CampfireBlock && state.hasProperty(CampfireBlock.LIT)
                    && state.getValue(CampfireBlock.LIT)) {
                level.setBlock(thp, state.setValue(CampfireBlock.LIT, false), 3);
            }
        }

        // Tear down BOTH scoreboard teams (regular settlement team + the chief team if one
        // exists). Pre-cleanup-pass code only removed the settlement team, leaving the chief
        // team as an orphan with a stale name prefix referencing a deleted settlement.
        removeSettlementTeams(server, settlement);

        data.unclaimAllOf(settlement);
        data.removeSettlement(settlement);
        // The abandoned/conquered town slowly crumbles to ruins (palette-agnostic, see RuinManager).
        com.bannerbound.core.ruin.RuinManager.queue(server.overworld(), ruinArea);
        com.bannerbound.core.crisis.CrisisManager.onSettlementRemoved(server, settlement, formerMembers);
        com.bannerbound.core.api.farmer.FarmerFoodBonus.forget(settlement.id());
        com.bannerbound.core.entity.HerderFoodBonus.forget(settlement.id());
        // End any raid/war still referencing this settlement and scrub its id from barbarian +
        // city-state memory, so an in-progress siege doesn't outlive its target.
        com.bannerbound.core.barbarian.BarbarianCampManager.onSettlementRemoved(server.overworld(), settlement.id());
        com.bannerbound.core.citystate.CityStateWarManager.onSettlementRemoved(server.overworld(), settlement.id());
        broadcastClaims(server);

        // Drop any Foreman's Rod selections this settlement still owned (otherwise they'd
        // linger in the registry as orphans referencing a deleted settlement id).
        com.bannerbound.core.api.world.BlockSelectionRegistry rodRegistry =
            com.bannerbound.core.api.world.BlockSelectionRegistry.get(server.overworld());
        boolean removedAnySelection = false;
        for (com.bannerbound.core.api.world.BlockSelection sel
                : rodRegistry.getForSettlement(settlement.id())) {
            rodRegistry.unregister(sel.rodId());
            removedAnySelection = true;
        }
        if (removedAnySelection) {
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(server);
        }

        // Former members no longer belong to anything â€” strip their research unlocks immediately,
        // unequip any armor/offhand item that's now unknown to them, and dismiss any open
        // settlement screen so they're not left staring at stale data for a dead settlement.
        for (UUID memberId : formerMembers) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) {
                PacketDistributor.sendToPlayer(member,
                    com.bannerbound.core.network.CloseSettlementScreensPayload.INSTANCE);
                sendEraStateTo(member);
                ResearchManager.sendStateTo(member);
                com.bannerbound.core.api.research.CultureManager.sendStateTo(member);
                com.bannerbound.core.crisis.CrisisManager.sendEmptyStateTo(member);
                com.bannerbound.core.event.UnknownItemBlocker.unequipUnknownGear(member);
                clearLeftoverSettlementHud(member);
            }
        }
    }

    /** Resets the standalone per-player HUD banners that ride on their own broadcast payloads
     *  (food warning, raid warning) and re-syncs the journal, so a player who just left or lost
     *  their settlement doesn't keep a stale "Food running low"/"RAIDED" banner or a dead
     *  settlement's crisis-objective checklist. Must run AFTER the player is detached from the
     *  settlement (so the journal re-sync drops the settlement-scoped entries). The crisis marker,
     *  era/research/culture sync and open screens are reset by their own calls at the call site. */
    private static void clearLeftoverSettlementHud(ServerPlayer member) {
        PacketDistributor.sendToPlayer(member,
            new com.bannerbound.core.network.SettlementFoodWarningPayload(
                com.bannerbound.core.network.SettlementFoodWarningPayload.LEVEL_OK));
        PacketDistributor.sendToPlayer(member,
            new com.bannerbound.core.network.RaidWarningPayload(false));
        com.bannerbound.core.journal.JournalManager.sendTo(member);
    }

    public static Result trySettle(ServerPlayer player, String requestedName, int colorIndex,
                                   String cultureStyle, BlockPos townHallPos) {
        ServerLevel overworld = player.getServer().overworld();
        SettlementData data = SettlementData.get(overworld);

        if (data.getByPlayer(player.getUUID()) != null) {
            return Result.ALREADY_IN_SETTLEMENT;
        }

        // Hard server cap: one settlement per banner color. Checked before anything else so the
        // founding is refused regardless of name/color/placement once the server is full.
        if (isAtMaxFactions(data)) {
            return Result.MAX_FACTIONS;
        }

        String name = requestedName == null ? "" : requestedName.trim();
        if (!isNameValid(name)) {
            return Result.NAME_INVALID;
        }
        if (data.nameTaken(name)) {
            return Result.NAME_TAKEN;
        }

        BlockPos foundingPos = townHallPos != null ? townHallPos : player.blockPosition();
        ChunkPos center = new ChunkPos(foundingPos);
        if (data.hasClaimsWithin(center, INITIAL_CLAIM_RADIUS, MIN_DISTANCE_TO_OTHER_SETTLEMENT)) {
            return Result.TOO_CLOSE_TO_OTHER_SETTLEMENT;
        }
        // Keep new settlements off (and clear of) AI city-state territory â€” same spacing as between
        // settlements. City-states claim a chunk territory around their village (see CityStateData).
        if (com.bannerbound.core.citystate.CityStateData.get(overworld)
                .hasClaimWithin(center, INITIAL_CLAIM_RADIUS + MIN_DISTANCE_TO_OTHER_SETTLEMENT)) {
            return Result.TOO_CLOSE_TO_CITY_STATE;
        }

        SettlementColor color = SettlementColor.byIndex(colorIndex);
        // Colors are unique per server â€” reject a color another settlement already flies. The
        // founding screen greys taken colors out, but a stale screen or a hand-built packet could
        // still request one, so the server is the authority.
        if (isColorInUse(data, color)) {
            return Result.COLOR_TAKEN;
        }
        Settlement settlement = new Settlement(UUID.randomUUID(), name, color, player.getUUID());
        // Culture style: use the chosen one if it's a real style, otherwise the first defined.
        String style = (cultureStyle == null || cultureStyle.isBlank())
            ? com.bannerbound.core.api.settlement.data.CultureStyleLoader.ids().stream()
                .findFirst().orElse("")
            : cultureStyle;
        if (com.bannerbound.core.api.settlement.data.CultureStyleLoader.get(style) != null) {
            settlement.setCultureStyle(style);
        }
        if (townHallPos != null) {
            settlement.setTownHallPos(townHallPos);
            ServerLevel level = player.serverLevel();
            BlockState thpState = level.getBlockState(townHallPos);
            if (thpState.getBlock() instanceof CampfireBlock && thpState.hasProperty(CampfireBlock.LIT)
                    && !thpState.getValue(CampfireBlock.LIT)) {
                level.setBlock(townHallPos, thpState.setValue(CampfireBlock.LIT, true), 3);
            }
            terraformAroundTownHall(level, townHallPos);
        }
        data.addSettlement(settlement);
        data.setLeaveCooldownUntil(player.getUUID(),
            overworld.getGameTime() + LEAVE_COOLDOWN_TICKS);

        ResearchManager.initializeAutoUnlocks(player.getServer(), settlement);
        com.bannerbound.core.api.research.CultureManager.initializeAutoUnlocks(player.getServer(), settlement);

        claimInitialChunks(overworld, data, settlement, center);
        // Track the new claim's chunks (+ ring) for beauty scoring right away.
        ChunkBeautyManager.recomputeTrackedSet(overworld);

        // Raise the FACTION BANNER beside the campfire, front turned toward it â€” the block the
        // settlement is bound to. Placed after terraform + claims so it stands on final ground
        // inside the new territory. If no spot took, the founder plants one by hand (starting
        // items include banners; any member-placed banner in territory registers).
        if (townHallPos != null) {
            BlockPos bannerSpot = FactionBanner.placeFoundingBanner(
                player.serverLevel(), settlement, townHallPos);
            if (bannerSpot == null) {
                player.sendSystemMessage(Component.translatable("bannerbound.banner.no_spot")
                    .withStyle(ChatFormatting.YELLOW));
            }
            data.setDirty();
        }

        applyScoreboardTeam(player.getServer(), player, settlement);

        player.sendSystemMessage(Component.translatable("bannerbound.settle.success", name)
            .withStyle(color.formatting()));
        celebrateFounding(player, settlement);
        broadcastClaims(player.getServer());
        sendEraStateTo(player);
        com.bannerbound.core.language.CustomLanguageSync.sendTo(player);
        ImmigrationManager.broadcastState(player.getServer(), settlement);
        // The chosen style now governs appeal â€” refresh the founder's tooltip/debug data.
        sendBlockAppealTo(player);
        sendFoodValuesTo(player);

        // If that founding claimed the last color, the server is now full: dismiss any founding
        // menu still open on any client so nobody can submit a doomed request against a full
        // server. Broadcast is cheap and the client no-ops if it isn't showing the SettleScreen.
        if (isAtMaxFactions(data)) {
            PacketDistributor.sendToAllPlayers(
                com.bannerbound.core.network.CloseSettleScreenPayload.INSTANCE);
        }
        return Result.OK;
    }

    private static void celebrateFounding(ServerPlayer player, Settlement settlement) {
        launchCelebrationFireworks(player.serverLevel(), player.blockPosition(), settlement, 3,
            BannerboundCore.FOUND_SETTLEMENT_SOUND.get());
    }

    /**
     * Shoots {@code count} settlement-colored fireworks upward from {@code pos}, plus the
     * provided fanfare sound + rocket launch SFX. Reused for founding (player-position +
     * found_settlement) and age advancement (town-hall-position + era-specific jingle).
     */
    public static void launchCelebrationFireworks(ServerLevel level, net.minecraft.core.BlockPos pos,
                                                  Settlement settlement, int count,
                                                  net.minecraft.sounds.SoundEvent fanfare) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;

        level.playSound(null, x, y, z, fanfare, SoundSource.PLAYERS, 1.0f, 1.0f);
        level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0f, 1.0f);

        int colorRgb = settlement.identityRgb();
        ItemStack firework = createSettlementFirework(colorRgb);
        for (int i = 0; i < count; i++) {
            double dx = (level.random.nextDouble() - 0.5) * 2.0;
            double dz = (level.random.nextDouble() - 0.5) * 2.0;
            FireworkRocketEntity rocket = new FireworkRocketEntity(level, firework.copy(), x + dx, y + 1.0, z + dz, true);
            rocket.shoot(0.0, 1.0, 0.0, 0.5f, 0.2f);
            level.addFreshEntity(rocket);
        }
    }

    private static ItemStack createSettlementFirework(int rgb) {
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        FireworkExplosion explosion = new FireworkExplosion(
            FireworkExplosion.Shape.LARGE_BALL,
            IntList.of(rgb),
            IntList.of(rgb),
            true,
            true
        );
        Fireworks fireworks = new Fireworks((byte) 1, List.of(explosion));
        stack.set(DataComponents.FIREWORKS, fireworks);
        return stack;
    }

    private static void claimInitialChunks(ServerLevel overworld, SettlementData data, Settlement settlement, ChunkPos center) {
        for (int dx = -INITIAL_CLAIM_RADIUS; dx <= INITIAL_CLAIM_RADIUS; dx++) {
            for (int dz = -INITIAL_CLAIM_RADIUS; dz <= INITIAL_CLAIM_RADIUS; dz++) {
                ChunkPos cp = new ChunkPos(center.x + dx, center.z + dz);
                if (data.claimChunk(settlement, cp)) {
                    ChunkForceLoader.force(overworld, cp.toLong());
                }
            }
        }
    }

    public static JoinResult tryJoin(ServerPlayer player, String settlementName) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return JoinResult.NOT_FOUND;
        }
        SettlementData data = SettlementData.get(server.overworld());

        if (data.getByPlayer(player.getUUID()) != null) {
            return JoinResult.ALREADY_IN_SETTLEMENT;
        }

        Settlement target = null;
        for (Settlement s : data.all()) {
            if (s.matchesName(settlementName)) {
                target = s;
                break;
            }
        }
        if (target == null) {
            return JoinResult.NOT_FOUND;
        }

        data.addMember(target, player.getUUID());
        data.setLeaveCooldownUntil(player.getUUID(),
            server.overworld().getGameTime() + LEAVE_COOLDOWN_TICKS);
        applyScoreboardTeam(server, player, target);

        player.sendSystemMessage(Component.translatable("bannerbound.join.success", target.factionName())
            .withStyle(target.identityFormatting()));

        // Player just inherited a new civ's age + research unlocks â€” push fresh state.
        sendEraStateTo(player);
        com.bannerbound.core.language.CustomLanguageSync.sendTo(player);
        ResearchManager.sendStateTo(player);
        com.bannerbound.core.api.research.CultureManager.sendStateTo(player);
        return JoinResult.OK;
    }

    public static boolean isNameValid(String name) {
        if (name == null) {
            return false;
        }
        if (name.length() < MIN_NAME_LENGTH || name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == ' ' || c == '-' || c == '_' || c == '\'';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public static LeaveResult tryLeave(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return LeaveResult.NOT_IN_SETTLEMENT;
        }
        ServerLevel overworld = server.overworld();
        SettlementData data = SettlementData.get(overworld);

        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) {
            return LeaveResult.NOT_IN_SETTLEMENT;
        }

        // Anti-cheese: a member can't leave for a few minutes after joining/founding (see
        // LEAVE_COOLDOWN_TICKS). Sends its own message so every caller (Town Hall button, /command)
        // surfaces the wait without each needing to handle the result.
        long remainingTicks = data.leaveCooldownUntil(player.getUUID()) - overworld.getGameTime();
        if (remainingTicks > 0) {
            long remainingSeconds = (remainingTicks + 19) / 20; // round up
            player.sendSystemMessage(Component.translatable("bannerbound.leave.cooldown",
                String.format("%d:%02d", remainingSeconds / 60, remainingSeconds % 60))
                .withStyle(ChatFormatting.RED));
            return LeaveResult.COOLDOWN;
        }

        // Remove from whichever team the player is actually on (regular settlement team OR
        // the dedicated chief team if they're the seated Chief). Previously this only tried
        // the settlement team, which threw "Player is on another team" when the leaver was
        // the Chief and lived in bb_chief_<id>.
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam currentTeam = scoreboard.getPlayersTeam(player.getScoreboardName());
        if (currentTeam != null) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), currentTeam);
        }

        // If the leaver IS the chief, vacate the seat â€” the chief role doesn't follow them
        // out, and the settlement falls back to "no chief" until the next coup check or a
        // future re-election system fills it.
        if (player.getUUID().equals(settlement.chiefPlayerId())) {
            settlement.setChiefPlayerId(null);
        }

        data.removeMember(settlement, player.getUUID());

        player.sendSystemMessage(Component.translatable("bannerbound.leave.success", settlement.factionName())
            .withStyle(ChatFormatting.GRAY));

        // Leaving player loses access to the settlement's research unlocks. Close any open
        // settlement screen too so they're not left holding stale UI.
        PacketDistributor.sendToPlayer(player,
            com.bannerbound.core.network.CloseSettlementScreensPayload.INSTANCE);
        sendEraStateTo(player);
        com.bannerbound.core.language.CustomLanguageSync.sendTo(player);
        ResearchManager.sendStateTo(player);
        com.bannerbound.core.api.research.CultureManager.sendStateTo(player);
        com.bannerbound.core.crisis.CrisisManager.sendEmptyStateTo(player);
        com.bannerbound.core.event.UnknownItemBlocker.unequipUnknownGear(player);
        clearLeftoverSettlementHud(player);

        if (settlement.isEmpty()) {
            collapseSettlement(server, data, settlement);
            broadcastClaims(server);
            return LeaveResult.OK_COLLAPSED;
        }
        broadcastClaims(server);
        return LeaveResult.OK;
    }

    public static ClaimSyncPayload buildClaimSyncPayload(MinecraftServer server) {
        SettlementData data = SettlementData.get(server.overworld());
        List<ClaimEntry> entries = new ArrayList<>();
        for (Settlement s : data.all()) {
            int colorIdx = s.color().ordinal();
            String name = s.name();
            for (long chunk : s.claimedChunks()) {
                entries.add(new ClaimEntry(chunk, colorIdx, name));
            }
        }
        return new ClaimSyncPayload(entries);
    }

    public static void broadcastClaims(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ClaimSyncPayload payload = buildClaimSyncPayload(server);
        // Identity colors ride along with every claims broadcast â€” anywhere claims change,
        // the color table may have too (founding, collapse, design saves).
        com.bannerbound.core.network.IdentitySyncPayload identity = buildIdentitySyncPayload(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(p, payload);
            PacketDistributor.sendToPlayer(p, identity);
        }
    }

    public static void sendClaimsTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, buildClaimSyncPayload(server));
        PacketDistributor.sendToPlayer(player, buildIdentitySyncPayload(server));
    }

    /** The settlement identity color table â€” founding-color ordinal (the server-unique key
     *  every claim/selection payload already carries) â†’ banner-derived primary/secondary/
     *  tertiary 0xRRGGBB. Client renderers resolve colors through this, so a re-dyed banner
     *  recolors the territory overlay, wireframes, and HUD everywhere at once. */
    public static com.bannerbound.core.network.IdentitySyncPayload buildIdentitySyncPayload(
            MinecraftServer server) {
        SettlementData data = SettlementData.get(server.overworld());
        List<Integer> ordinals = new ArrayList<>();
        List<List<Integer>> rgbLists = new ArrayList<>();
        for (Settlement s : data.all()) {
            ordinals.add(s.color().ordinal());
            rgbLists.add(s.identityRgbList());
        }
        return new com.bannerbound.core.network.IdentitySyncPayload(ordinals, rgbLists);
    }

    public static void broadcastIdentity(MinecraftServer server) {
        if (server == null) return;
        com.bannerbound.core.network.IdentitySyncPayload payload = buildIdentitySyncPayload(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(p, payload);
        }
    }

    public static boolean setSettlementAge(ServerPlayer player, Era era) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getByPlayer(player.getUUID());
        if (settlement == null) {
            return false;
        }
        settlement.setAge(era);
        if (era.ordinal() > data.getWorldAge().ordinal()) {
            data.setWorldAge(era);
        }
        data.setDirty();
        // Drop any completed/active research whose min_age now exceeds the settlement age.
        ResearchManager.regressResearchAfterAgeChange(server, settlement);
        com.bannerbound.core.api.research.CultureManager.regressResearchAfterAgeChange(server, settlement);
        broadcastEraState(server);
        for (UUID memberId : settlement.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) {
                member.sendSystemMessage(Component.translatable("bannerbound.settlement.set_age.success",
                        settlement.name(), era.displayName())
                    .withStyle(settlement.identityFormatting()));
            }
        }
        return true;
    }

    public static void setWorldAge(MinecraftServer server, Era era) {
        if (server == null) {
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        data.setWorldAge(era);
        broadcastEraState(server);
    }

    public static void broadcastEraState(MinecraftServer server) {
        if (server == null) {
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        int worldOrd = data.getWorldAge().ordinal();
        int worldYear = computeWorldYear(data);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Settlement s = data.getByPlayer(p.getUUID());
            int playerOrd = s == null ? Era.ANCIENT.ordinal() : s.age().ordinal();
            PacketDistributor.sendToPlayer(p, new EraStatePayload(playerOrd, worldOrd, worldYear));
        }
    }

    public static void sendEraStateTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        int playerOrd = s == null ? Era.ANCIENT.ordinal() : s.age().ordinal();
        int worldOrd = data.getWorldAge().ordinal();
        int worldYear = computeWorldYear(data);
        PacketDistributor.sendToPlayer(player, new EraStatePayload(playerOrd, worldOrd, worldYear));
    }

    // â”€â”€â”€ Status effects â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Pushes the current {@link StatusEffect} list for {@code s} to every member. Called on
     *  add/remove transitions â€” between transitions the client decrements its mirror locally. */
    public static void broadcastStatusEffectsToMembers(MinecraftServer server, Settlement s) {
        if (server == null || s == null) return;
        com.bannerbound.core.network.StatusEffectListPayload payload = buildStatusPayload(s);
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) {
                PacketDistributor.sendToPlayer(m, payload);
            }
        }
    }

    // â”€â”€â”€ Labor priorities (Town Hall "Labor" tab) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** Builds the labor-state snapshot for the Labor tab: unlocked gatherer jobs in priority order
     *  with per-job enabled flag + current/target worker counts + the auto-assign flag. */
    public static com.bannerbound.core.network.LaborStatePayload buildLaborPayload(ServerLevel level, Settlement s) {
        java.util.List<String> jobs = com.bannerbound.core.entity.AnarchyJobs.orderedUnlockedGatherers(s);
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (com.bannerbound.core.entity.CitizenEntity c
                : allCitizensOf(level, s)) {
            if (c.isChild() || c.isPregnant() || c.usesAmbientBrain()) continue;
            if (com.bannerbound.core.entity.AnarchyJobs.isGathererJob(c.getJobType())) {
                counts.put(c.getJobType(), counts.getOrDefault(c.getJobType(), 0) + 1);
            }
        }
        java.util.List<Boolean> enabled = new java.util.ArrayList<>(jobs.size());
        java.util.List<Integer> current = new java.util.ArrayList<>(jobs.size());
        java.util.List<Integer> caps = new java.util.ArrayList<>(jobs.size());
        for (String j : jobs) {
            enabled.add(!s.isLaborJobDisabled(j));
            current.add(counts.getOrDefault(j, 0));
            caps.add(s.laborCap(j));   // -1 = no limit
        }
        return new com.bannerbound.core.network.LaborStatePayload(jobs, enabled, current, caps,
            s.laborAutoAssign(), s.hasPolicy(PolicyRegistry.WORKLOAD_SHARE),
            s.preferredStoragePos() == null ? Long.MIN_VALUE : s.preferredStoragePos().asLong());
    }

    public static void broadcastLaborState(MinecraftServer server, Settlement s) {
        if (server == null || s == null) return;
        // Skip the citizen-count scan entirely when no member is online to see it.
        java.util.List<ServerPlayer> online = new java.util.ArrayList<>();
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) online.add(m);
        }
        if (online.isEmpty()) return;
        com.bannerbound.core.network.LaborStatePayload payload = buildLaborPayload(server.overworld(), s);
        for (ServerPlayer m : online) sendLaborSafely(m, payload);
    }

    /** Send a single player their settlement's labor state (used when the town hall opens). */
    public static void sendLaborStateTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Settlement s = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        if (s == null) return;
        sendLaborSafely(player, buildLaborPayload(server.overworld(), s));
    }

    /** Send a player their settlement's labor state. Runs from the once-a-second settlement tick
     *  ({@link com.bannerbound.core.api.settlement.ImmigrationManager}), so the try/catch keeps a
     *  connection that genuinely can't take the payload yet (e.g. mid-handshake) from ever
     *  hard-crashing the server tick loop. The payload is registered clientbound in both dist
     *  branches of {@link com.bannerbound.core.network.BannerboundNetwork}, so this normally sends. */
    private static void sendLaborSafely(ServerPlayer player, com.bannerbound.core.network.LaborStatePayload payload) {
        if (player.connection == null) return;
        try {
            PacketDistributor.sendToPlayer(player, payload);
        } catch (RuntimeException ignored) {
            // Connection not ready for this channel yet â€” skip; the 1/s broadcast will retry.
        }
    }

    // â”€â”€â”€ Chat votes + extra suggestions sync (Votes / Suggestions tabs) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** A member's name for vote/suggestion rows: online player first, then the profile cache. */
    private static String memberName(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        if (server.getProfileCache() != null) {
            return server.getProfileCache().get(id)
                .map(com.mojang.authlib.GameProfile::getName).orElse("?");
        }
        return "?";
    }

    /** Push every online member their personal snapshot of the settlement's in-flight chat votes
     *  (per-player because {@code myVote} differs). Safe to call often â€” tiny payload. */
    public static void broadcastChatVotesState(MinecraftServer server, Settlement s) {
        if (server == null || s == null) return;
        java.util.List<com.bannerbound.core.api.settlement.ChatVoteManager.ChatVote> votes =
            com.bannerbound.core.api.settlement.ChatVoteManager.activeVotesFor(s.id());
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m == null) continue;
            sendChatVotesStateTo(server, m, votes);
        }
    }

    /** Send one player their chat-votes snapshot (used on town-hall open + by the broadcast). */
    public static void sendChatVotesStateTo(MinecraftServer server, ServerPlayer player,
            java.util.List<com.bannerbound.core.api.settlement.ChatVoteManager.ChatVote> votes) {
        java.util.List<com.bannerbound.core.network.ChatVotesStatePayload.Entry> entries =
            new java.util.ArrayList<>(votes.size());
        for (com.bannerbound.core.api.settlement.ChatVoteManager.ChatVote v : votes) {
            entries.add(new com.bannerbound.core.network.ChatVotesStatePayload.Entry(
                v.id, v.kind.ordinal(), memberName(server, v.initiator), v.targetName,
                v.yes.size(), v.no.size(), (int) v.secondsLeft(), v.voteOf(player.getUUID())));
        }
        sendSafely(player, new com.bannerbound.core.network.ChatVotesStatePayload(entries));
    }

    /** Push the exile/tablet suggestion snapshot to every online member (the other four suggestion
     *  kinds have their own sync â€” see {@code ExtraSuggestionsPayload}). */
    public static void broadcastExtraSuggestions(MinecraftServer server, Settlement s) {
        if (server == null || s == null) return;
        java.util.List<com.bannerbound.core.network.ExtraSuggestionsPayload.ExileEntry> exiles =
            new java.util.ArrayList<>();
        for (java.util.Map.Entry<UUID, java.util.LinkedHashSet<UUID>> e
                : s.allExileSuggestions().entrySet()) {
            String name = "Citizen";
            for (Citizen c : s.citizens()) {
                if (c.entityId().equals(e.getKey())) { name = c.name(); break; }
            }
            exiles.add(new com.bannerbound.core.network.ExtraSuggestionsPayload.ExileEntry(
                e.getKey(), name, new java.util.ArrayList<>(e.getValue())));
        }
        com.bannerbound.core.network.ExtraSuggestionsPayload payload =
            new com.bannerbound.core.network.ExtraSuggestionsPayload(
                exiles, new java.util.ArrayList<>(s.tabletSuggesters()));
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) sendSafely(m, payload);
        }
    }

    /** Never let a tick-driven sync crash the server if a connection can't take the payload yet. */
    private static void sendSafely(ServerPlayer player,
            net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (player.connection == null) return;
        try {
            PacketDistributor.sendToPlayer(player, payload);
        } catch (RuntimeException ignored) {
            // Channel not negotiated yet â€” skip; the next state change will retry.
        }
    }

    /** Who may edit the labor list: any member in anarchy or a council; the seated chief/regent in a
     *  chiefdom. */
    public static boolean canEditLabor(ServerPlayer player, Settlement s) {
        if (!s.members().contains(player.getUUID())) return false;
        return switch (s.governmentType()) {
            case NONE, COUNCIL -> true;
            // Chief/regent, OR any member while Workload Share is active â€” same delegation the Job tab
            // (canManageJobs) honors, so the per-citizen and settlement-wide labor controls agree.
            case CHIEFDOM -> s.canActAsChief(player.getUUID())
                || s.hasPolicy(PolicyRegistry.WORKLOAD_SHARE);
        };
    }

    /** Apply a Town Hall labor edit: validate the job ids (gatherers only), set the order, and â€” only
     *  under a government â€” the disabled set, per-job worker caps, and auto-assign flag. In anarchy the
     *  player can ONLY reorder priority; the disabled set / caps / auto-assign are locked (auto forced
     *  on, no caps), so those parts of the payload are ignored. Re-broadcasts the new state. */
    public static void proposeLaborPriorityChange(ServerPlayer player,
            com.bannerbound.core.network.ProposeLaborPriorityChangePayload payload) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null || !canEditLabor(player, s)) return;
        boolean anarchy = s.governmentType() == Settlement.Government.NONE;
        java.util.List<String> order = new java.util.ArrayList<>();
        for (String j : payload.orderedJobIds()) {
            if (com.bannerbound.core.entity.AnarchyJobs.isGathererJob(j) && !order.contains(j)) order.add(j);
        }
        // In anarchy the on/off toggles are greyed, so preserve the existing disabled set rather than
        // trusting the payload (a copy â€” setLaborConfig clears its own backing set before re-adding).
        java.util.List<String> disabled = new java.util.ArrayList<>();
        if (anarchy) {
            disabled.addAll(s.laborDisabled());
        } else {
            for (String j : payload.disabledJobIds()) {
                if (com.bannerbound.core.entity.AnarchyJobs.isGathererJob(j) && !disabled.contains(j)) disabled.add(j);
            }
        }
        s.setLaborConfig(order, disabled);
        if (!anarchy) {
            // Caps are parallel to orderedJobIds; -1 means "no limit" (dropped by setLaborCaps).
            java.util.Map<String, Integer> caps = new java.util.HashMap<>();
            java.util.List<String> ids = payload.orderedJobIds();
            java.util.List<Integer> capVals = payload.caps();
            for (int i = 0; i < ids.size() && i < capVals.size(); i++) {
                String j = ids.get(i);
                if (com.bannerbound.core.entity.AnarchyJobs.isGathererJob(j) && capVals.get(i) >= 0) {
                    caps.put(j, capVals.get(i));
                }
            }
            s.setLaborCaps(caps);
        }
        boolean auto = anarchy || payload.autoAssign();
        s.setLaborAutoAssign(auto);
        // "No means no": turning a job OFF immediately unemploys everyone currently working it â€” even
        // pinned workers (setJobType(null) also clears the pin) â€” instead of waiting on the once-a-second
        // distributor, which strands them whenever every job is off (no enabled target to re-skill toward)
        // or auto-assign is disabled. With a government + auto-assign on, the distributor flows them into
        // other ENABLED jobs next tick (the disabled one is never a target); otherwise they stay idle.
        if (!disabled.isEmpty()) {
            ServerLevel overworld = server.overworld();
            for (com.bannerbound.core.entity.CitizenEntity c : allCitizensOf(overworld, s)) {
                String j = c.getJobType();
                if (j != null && disabled.contains(j)) c.setJobType(null);
            }
        }
        data.setDirty();
        broadcastLaborState(server, s);
    }

    /** Send a single player their settlement's current status effects (used on join + datapack
     *  resync). No-op if the player isn't in a settlement. */
    public static void sendStatusEffectsTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) {
            PacketDistributor.sendToPlayer(player,
                new com.bannerbound.core.network.StatusEffectListPayload(java.util.List.of()));
            return;
        }
        PacketDistributor.sendToPlayer(player, buildStatusPayload(s));
    }

    private static com.bannerbound.core.network.StatusEffectListPayload buildStatusPayload(Settlement s) {
        java.util.List<com.bannerbound.core.network.StatusEffectListPayload.Entry> entries =
            new java.util.ArrayList<>(s.statusEffects().size());
        for (StatusEffect e : s.statusEffects()) {
            entries.add(new com.bannerbound.core.network.StatusEffectListPayload.Entry(
                e.instanceId(),
                e.translationKey(),
                e.args(),
                e.icon().ordinal(),
                e.iconValue(),
                e.totalDurationTicks(),
                e.remainingTicks()
            ));
        }
        return new com.bannerbound.core.network.StatusEffectListPayload(entries);
    }

    /**
     * World year for HUD display â€” monotonic. Sources its era band from {@link SettlementData#getWorldAge}
     * (already monotonic â€” only advances upward) and its progress fraction from
     * {@link SettlementData#getGlobalResearchedIds}, filtered to researches whose {@code minAge}
     * matches the world era.
     * <p>
     * Consequence: once the world reaches Medieval, completing an antiquity-tier research for
     * the first time anywhere doesn't move the year â€” its {@code minAge} is below the world era
     * and so it doesn't enter the count. Conversely, once a settlement has fallen but its prior
     * discoveries are recorded in the global set, the year stays where it was â€” no time travel
     * backward from conquest, regression, or disband. The escape hatch is
     * {@code /bannerbound reset_world_age}, which clears the set + drops the world era.
     */
    public static int computeWorldYear(SettlementData data) {
        Era era = data.getWorldAge();
        int start = com.bannerbound.core.api.settlement.data.EraTimelineLoader.getStartYear(era);
        Era next = era.next();
        if (next == era) {
            // Last era â€” no interpolation target. Year sits at the era's start.
            return start;
        }
        int nextStart = com.bannerbound.core.api.settlement.data.EraTimelineLoader.getStartYear(next);
        int total = countResearchesInEra(era);
        if (total <= 0) {
            return start;
        }
        int discovered = countDiscoveredInEra(data, era);
        double frac = Math.min(1.0, Math.max(0.0, (double) discovered / total));
        return start + (int) Math.round(frac * (nextStart - start));
    }

    private static int countResearchesInEra(Era era) {
        int n = 0;
        for (ResearchDefinition def : ResearchTreeLoader.getAll().values()) {
            if (def.minAge() == era) n++;
        }
        return n;
    }

    private static int countDiscoveredInEra(SettlementData data, Era era) {
        int n = 0;
        for (String id : data.getGlobalResearchedIds()) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def != null && def.minAge() == era) n++;
        }
        return n;
    }

    public static void sendOreDisguisesTo(ServerPlayer player) {
        java.util.List<com.bannerbound.core.api.research.OreDisguise> all =
            new java.util.ArrayList<>(com.bannerbound.core.api.research.data.OreDisguiseLoader.getAll().values());
        PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.OreDisguisesSyncPayload(all));
    }

    public static void sendResearchTreeTo(ServerPlayer player) {
        java.util.List<com.bannerbound.core.api.research.ResearchDefinition> defs =
            new java.util.ArrayList<>(ResearchTreeLoader.getAll().values());
        PacketDistributor.sendToPlayer(player, new ResearchTreeSyncPayload(defs));
    }

    public static void broadcastResearchTree(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendResearchTreeTo(p);
        }
    }

    /** Step 8: ships the Culture tree definitions to {@code player} once. The state payload
     *  (active research / progress / queue) goes through {@link com.bannerbound.core.api.research.CultureManager#sendStateTo}. */
    public static void sendCultureTreeTo(ServerPlayer player) {
        java.util.List<com.bannerbound.core.api.research.ResearchDefinition> defs =
            new java.util.ArrayList<>(
                com.bannerbound.core.api.research.data.CultureTreeLoader.getAll().values());
        PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.CultureTreeSyncPayload(defs));
    }

    /** Ships the FAITH tree definitions (third tree â€” FAITH_PLAN Part 2.5). State goes
     *  through {@link com.bannerbound.core.api.faith.FaithManager#sendTreeStateTo}. */
    public static void sendFaithTreeTo(ServerPlayer player) {
        java.util.List<com.bannerbound.core.api.research.ResearchDefinition> defs =
            new java.util.ArrayList<>(
                com.bannerbound.core.api.research.data.FaithTreeLoader.getAll().values());
        PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.FaithTreeSyncPayload(defs));
    }

    public static void sendStartingItemsTo(ServerPlayer player) {
        Set<String> all = StartingItemsLoader.getAll();
        PacketDistributor.sendToPlayer(player, new StartingItemsSyncPayload(new ArrayList<>(all)));
    }

    /** Pushes the resolved per-block appeal (base + the player's settlement's style overrides)
     *  so the appeal tooltip and beauty-debug overlay show correct, style-aware values. */
    public static void sendBlockAppealTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Settlement s = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        List<String> styles = s != null ? s.cultureStyles() : List.of();
        List<String> palettes = s != null ? s.activePalettes() : List.of();

        java.util.Set<net.minecraft.world.level.block.Block> blocks = new java.util.HashSet<>(
            com.bannerbound.core.api.settlement.data.BlockAppealLoader.all().keySet());
        for (CultureStyle style :
                com.bannerbound.core.api.settlement.data.CultureStyleLoader.all().values()) {
            blocks.addAll(style.overrides().keySet());
        }
        // Include every palette's blocks too, so a block that only gets appeal from an active
        // palette still appears in the synced table (and its tooltip shows the boosted value).
        for (com.bannerbound.core.api.settlement.Palette palette :
                com.bannerbound.core.api.settlement.data.PaletteLoader.all().values()) {
            blocks.addAll(palette.bonuses().keySet());
        }
        List<String> ids = new ArrayList<>();
        List<Float> appeals = new ArrayList<>();
        for (net.minecraft.world.level.block.Block b : blocks) {
            ids.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString());
            appeals.add(AppealResolver.appealOf(b, styles, palettes));
        }
        PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.BlockAppealSyncPayload(ids, appeals));
    }

    /** Pushes the base per-item food values so the green "Food value" tooltip line renders.
     *  v1 sends the raw FoodValueLoader table â€” culture-style food overrides aren't applied
     *  here yet (they belong on a future per-player resolved sync; the deposit math on the
     *  town hall will pick them up first). */
    public static void sendFoodValuesTo(ServerPlayer player) {
        java.util.Map<net.minecraft.world.item.Item, Float> base =
            com.bannerbound.core.api.settlement.data.FoodValueLoader.all();
        List<String> ids = new ArrayList<>(base.size());
        List<Float> values = new ArrayList<>(base.size());
        for (java.util.Map.Entry<net.minecraft.world.item.Item, Float> e : base.entrySet()) {
            ids.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
            values.add(e.getValue());
        }
        PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.FoodValueSyncPayload(ids, values));
    }

    /** Pushes the available culture styles to {@code player} so the founding screen can list them. */
    public static void sendCultureStylesTo(ServerPlayer player) {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> images = new ArrayList<>();
        for (String id : com.bannerbound.core.api.settlement.data.CultureStyleLoader.ids()) {
            com.bannerbound.core.api.settlement.CultureStyle style =
                com.bannerbound.core.api.settlement.data.CultureStyleLoader.get(id);
            ids.add(id);
            names.add(style != null ? style.nameKey() : "bannerbound.culture_style." + id);
            images.add(style != null ? style.imageKey()
                : "bannerbound:textures/gui/culture/" + id + ".png");
        }
        PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.CultureStyleSyncPayload(ids, names, images));
    }

    /**
     * Removes every citizen entity belonging to {@code settlement} from the world. The roster is
     * cleared too â€” used when the settlement is disbanded (members + bystanders shouldn't keep
     * stray ghost citizens hanging around).
     */
    public static void despawnAllCitizens(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            for (com.bannerbound.core.entity.CitizenEntity citizen : allCitizensOf(level, settlement)) {
                citizen.discard();
            }
        }
        settlement.citizens().clear();
    }

    private static void collapseSettlement(MinecraftServer server, SettlementData data, Settlement settlement) {
        // Capture claimed territory BEFORE removing the settlement (claims are cleared below).
        java.util.Set<Long> ruinArea = new java.util.HashSet<>(settlement.claimedChunks());
        DiplomacyManager.onSettlementDisbanded(server, settlement, data);
        // Same team-cleanup as performFullDisband â€” drop both the settlement team and the
        // chief team so neither lingers as an orphan after the settlement is removed.
        removeSettlementTeams(server, settlement);
        despawnAllCitizens(server, settlement);

        // Un-light the town-hall campfire so the abandoned anchor reads as dead (mirrors disband).
        BlockPos thp = settlement.townHallPos();
        if (thp != null) {
            ServerLevel overworld = server.overworld();
            BlockState thState = overworld.getBlockState(thp);
            if (thState.getBlock() instanceof CampfireBlock && thState.hasProperty(CampfireBlock.LIT)
                    && thState.getValue(CampfireBlock.LIT)) {
                overworld.setBlock(thp, thState.setValue(CampfireBlock.LIT, false), 3);
            }
        }

        ChunkForceLoader.unforceAll(server.overworld(), settlement);
        data.unclaimAllOf(settlement);
        data.removeSettlement(settlement);
        // The abandoned town slowly crumbles to ruins, same as a disbanded one (see RuinManager).
        com.bannerbound.core.ruin.RuinManager.queue(server.overworld(), ruinArea);
        com.bannerbound.core.crisis.CrisisManager.onSettlementRemoved(server, settlement,
            java.util.List.of());
        com.bannerbound.core.api.farmer.FarmerFoodBonus.forget(settlement.id());
        com.bannerbound.core.entity.HerderFoodBonus.forget(settlement.id());
        com.bannerbound.core.barbarian.BarbarianCampManager.onSettlementRemoved(server.overworld(), settlement.id());
        com.bannerbound.core.citystate.CityStateWarManager.onSettlementRemoved(server.overworld(), settlement.id());

        // Drop any Foreman's Rod selections this settlement still owned (otherwise they'd
        // linger in the registry as orphans referencing a deleted settlement id).
        com.bannerbound.core.api.world.BlockSelectionRegistry rodRegistry =
            com.bannerbound.core.api.world.BlockSelectionRegistry.get(server.overworld());
        boolean removedAnySelection = false;
        for (com.bannerbound.core.api.world.BlockSelection sel
                : rodRegistry.getForSettlement(settlement.id())) {
            rodRegistry.unregister(sel.rodId());
            removedAnySelection = true;
        }
        if (removedAnySelection) {
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(server);
        }

        Component announcement = Component.translatable("bannerbound.settle.collapsed", settlement.factionName())
            .withStyle(settlement.identityFormatting());
        server.getPlayerList().broadcastSystemMessage(announcement, false);
    }

    private static final Block[] TERRAFORM_PALETTE = new Block[] {
        Blocks.DIRT_PATH, Blocks.GRAVEL, Blocks.COARSE_DIRT, Blocks.DIRT, Blocks.GRASS_BLOCK
    };

    private static void terraformAroundTownHall(ServerLevel level, BlockPos center) {
        final int radius = 6;
        final int radiusSq = radius * radius;
        RandomSource random = level.random;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSq) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (cursor.equals(center)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (!isReplaceableSurface(state)) {
                        continue;
                    }
                    Block pick = TERRAFORM_PALETTE[random.nextInt(TERRAFORM_PALETTE.length)];
                    level.setBlock(cursor, pick.defaultBlockState(), 3);
                }
            }
        }
    }

    private static boolean isReplaceableSurface(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.GRASS_BLOCK
            || b == Blocks.DIRT
            || b == Blocks.COARSE_DIRT
            || b == Blocks.PODZOL
            || b == Blocks.MYCELIUM
            || b == Blocks.ROOTED_DIRT
            || b == Blocks.DIRT_PATH
            || b == Blocks.GRAVEL
            || b == Blocks.SAND
            || b == Blocks.RED_SAND
            || b == Blocks.MUD
            || b == Blocks.MUDDY_MANGROVE_ROOTS;
    }

    /** Add {@code player} to the settlement's regular scoreboard team (creating the team
     *  if needed). Public because the quit-chief flow + future role-change paths need to
     *  move a player out of the chief / regent team back into the baseline. */
    public static void applyScoreboardTeam(MinecraftServer server, ServerPlayer player, Settlement settlement) {
        Scoreboard scoreboard = server.getScoreboard();
        String teamName = settlement.teamName();

        PlayerTeam team = scoreboard.getPlayerTeam(teamName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName);
        }

        ChatFormatting color = settlement.identityFormatting();
        team.setColor(color);
        team.setDisplayName(Component.literal(settlement.factionName()));
        team.setPlayerPrefix(Component.literal("[" + settlement.factionName() + "] ").withStyle(color));

        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
    }

    // â”€â”€â”€ Chief crown sync (Step 7 cross-client visibility) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // The PlayerEvent.NameFormat hook handles chat names, but the in-world nametag and TAB
    // list render from team prefix + ClientPlayerInfo on each client. Vanilla scoreboard team
    // data is fully synced, so giving the chief their OWN team (with the crown baked into the
    // prefix Component) makes the glyph appear in nametag, TAB, and chat across all clients
    // without any custom payload.
    //
    // Chief team name: bb_chief_<settlementId> (one per settlement at most one player in it).
    // Color matches the settlement; prefix is "[Settlement] <crown> ".

    /** Scoreboard team-name helper for the chief seat. */
    private static String chiefTeamName(Settlement settlement) {
        return "bb_chief_" + settlement.id().toString().substring(0, 8);
    }
    /** Scoreboard team-name helper for the (transient) regent seat. */
    private static String regentTeamName(Settlement settlement) {
        return "bb_regent_" + settlement.id().toString().substring(0, 8);
    }

    /** Removes ALL scoreboard teams associated with {@code settlement} â€” the regular
     *  settlement team, the chief team, and the regent team. Called from the disband and
     *  collapse paths; without it those teams would linger as orphans with stale prefixes
     *  referencing a deleted settlement. */
    private static void removeSettlementTeams(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam settlementTeam = scoreboard.getPlayerTeam(settlement.teamName());
        if (settlementTeam != null) scoreboard.removePlayerTeam(settlementTeam);
        PlayerTeam chiefTeam = scoreboard.getPlayerTeam(chiefTeamName(settlement));
        if (chiefTeam != null) scoreboard.removePlayerTeam(chiefTeam);
        PlayerTeam regentTeam = scoreboard.getPlayerTeam(regentTeamName(settlement));
        if (regentTeam != null) scoreboard.removePlayerTeam(regentTeam);
    }

    // â”€â”€â”€ Step 15: regent selection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** Recompute the regent seat for {@code settlement}.
     *
     *  <p>If the seated chief is ONLINE, the regent is cleared instantly (real chief
     *  reclaims authority). If the chief is OFFLINE and at least one other online member
     *  exists, the regent becomes the online member with the lowest summed citizen-
     *  resentment â€” the same "least-resented" metric the coup install used. Ties resolve
     *  randomly. If no online member is eligible, the regent stays null (the settlement
     *  idles on routine until someone logs in).
     *
     *  <p>Called from {@link com.bannerbound.core.event.RegencyEvents} on player login /
     *  logout and from {@code ImmigrationManager.tickAll} as a fallback heartbeat in case
     *  an event slipped through (server crash mid-tick, etc.). Idempotent â€” calling it
     *  with the same state is a no-op. */
    public static void recomputeRegent(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        if (settlement.governmentType() != Settlement.Government.CHIEFDOM) {
            clearRegent(server, settlement);
            return;
        }
        UUID chiefId = settlement.chiefPlayerId();
        // Seat vacant â€” chief just stepped down (or never was elected). No regent during the
        // election window: per design, NO leader exists in that gap. The election picks a new
        // chief directly; a regent would muddy the "lent, not vacated" contract since the seat
        // is now actually vacated. Clears any pre-quit regent that may have been seated when
        // the (now ex-)chief was offline.
        if (chiefId == null) {
            clearRegent(server, settlement);
            return;
        }
        boolean chiefOnline = server.getPlayerList().getPlayer(chiefId) != null;
        if (chiefOnline) {
            clearRegent(server, settlement);
            return;
        }
        // Chief is offline â€” pick the least-resented online member as regent.
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        java.util.Map<UUID, Integer> totals = new java.util.HashMap<>();
        for (UUID memberId : settlement.members()) {
            // Exclude the offline chief themselves â€” regency is for stand-ins.
            if (memberId.equals(chiefId)) continue;
            if (server.getPlayerList().getPlayer(memberId) == null) continue;
            totals.put(memberId, 0);
        }
        if (totals.isEmpty()) {
            clearRegent(server, settlement);
            return;
        }
        for (com.bannerbound.core.entity.CitizenEntity citizen : allCitizensOf(overworld, settlement)) {
            for (UUID candidateId : totals.keySet()) {
                int v = citizen.getResentment(candidateId);
                if (v > 0) totals.merge(candidateId, v, Integer::sum);
            }
        }
        int minSummed = Integer.MAX_VALUE;
        for (int v : totals.values()) if (v < minSummed) minSummed = v;
        java.util.List<UUID> tied = new java.util.ArrayList<>();
        for (java.util.Map.Entry<UUID, Integer> e : totals.entrySet()) {
            if (e.getValue() == minSummed) tied.add(e.getKey());
        }
        if (tied.isEmpty()) {
            clearRegent(server, settlement);
            return;
        }
        UUID oldRegent = settlement.regentPlayerId();
        // Incumbent preference: while the seated regent remains among the best candidates,
        // keep them â€” only re-roll when they drop out of the tied set (logout, resentment).
        if (oldRegent != null && tied.contains(oldRegent)) return;
        UUID newRegent = tied.get(overworld.random.nextInt(tied.size()));
        if (newRegent.equals(oldRegent)) return; // already this person â€” nothing to do
        // Demote the previous regent (if any) back into the regular settlement team.
        if (oldRegent != null) demoteFromRegentTeam(server, settlement, oldRegent);
        settlement.setRegentPlayerId(newRegent);
        ServerPlayer regentPlayer = server.getPlayerList().getPlayer(newRegent);
        if (regentPlayer != null) applyRegentScoreboardTeam(server, regentPlayer, settlement);
        broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.chief.regent_installed",
                playerName(server, newRegent))
                .withStyle(ChatFormatting.GRAY));
    }

    /** Drop any active regent and clean up the regent scoreboard team. */
    private static void clearRegent(MinecraftServer server, Settlement settlement) {
        UUID oldRegent = settlement.regentPlayerId();
        if (oldRegent == null) return;
        settlement.setRegentPlayerId(null);
        demoteFromRegentTeam(server, settlement, oldRegent);
        broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.chief.regent_stepped_down",
                playerName(server, oldRegent))
                .withStyle(ChatFormatting.GRAY));
    }

    /** Move {@code chief} into the chief team â€” created if it doesn't exist. Vanilla
     *  scoreboard semantics auto-remove them from the regular settlement team first (a
     *  player can only be on one team at a time). Called from {@link #enactChief}. */
    private static void applyChiefScoreboardTeam(MinecraftServer server, ServerPlayer chief, Settlement settlement) {
        if (server == null || chief == null || settlement == null) return;
        Scoreboard scoreboard = server.getScoreboard();
        String teamName = chiefTeamName(settlement);
        PlayerTeam team = scoreboard.getPlayerTeam(teamName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName);
        }
        ChatFormatting color = settlement.identityFormatting();
        team.setColor(color);
        team.setDisplayName(Component.literal(settlement.factionName() + " Chief"));
        // Crown component routes through the shared Glyphs.crown() so the codepoint +
        // style live in one place â€” the client's Icons.crown() and this server-side call
        // are guaranteed to produce identical components.
        Component prefix = Component.empty()
            .append(Component.literal("[" + settlement.factionName() + "] ").withStyle(color))
            .append(com.bannerbound.core.api.Glyphs.crown())
            .append(Component.literal(" "));
        team.setPlayerPrefix(prefix);
        scoreboard.addPlayerToTeam(chief.getScoreboardName(), team);
    }

    /** Step 15: move {@code regent} into a dedicated regent team. Same shape as the chief
     *  team but the prefix uses GRAY (not the settlement color) for the crown â€” visually
     *  distinguishes "stand-in" from "real chief" at a glance without needing a separate
     *  glyph asset. Old chief team membership is preserved for the real chief on the
     *  server side; the regent inherits the regular settlement team affiliation. */
    private static void applyRegentScoreboardTeam(MinecraftServer server, ServerPlayer regent,
                                                    Settlement settlement) {
        if (server == null || regent == null || settlement == null) return;
        Scoreboard scoreboard = server.getScoreboard();
        String teamName = regentTeamName(settlement);
        PlayerTeam team = scoreboard.getPlayerTeam(teamName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName);
        }
        ChatFormatting color = settlement.identityFormatting();
        team.setColor(color);
        team.setDisplayName(Component.literal(settlement.factionName() + " Regent"));
        // Same crown glyph but tinted dim grey so members can tell at a glance that the
        // person is filling in for the real chief rather than wearing the actual crown.
        net.minecraft.network.chat.Component crown = Component.literal(
            String.valueOf(com.bannerbound.core.api.Glyphs.CROWN))
            .withStyle(com.bannerbound.core.api.Glyphs.ICONS_STYLE
                .withColor(net.minecraft.network.chat.TextColor.fromRgb(0xA0A0A0)));
        Component prefix = Component.empty()
            .append(Component.literal("[" + settlement.factionName() + "] ").withStyle(color))
            .append(crown)
            .append(Component.literal(" "));
        team.setPlayerPrefix(prefix);
        scoreboard.addPlayerToTeam(regent.getScoreboardName(), team);
    }

    /** Pull {@code player} OUT of the regent scoreboard team and back into the regular
     *  settlement team. Called when the regent stops being regent (real chief returned,
     *  regent went offline, or settlement changed government). */
    private static void demoteFromRegentTeam(MinecraftServer server, Settlement settlement, UUID playerId) {
        if (server == null || settlement == null || playerId == null) return;
        ServerPlayer p = server.getPlayerList().getPlayer(playerId);
        if (p == null) return;
        // Only re-add to the regular settlement team if they're still actually a member.
        if (settlement.members().contains(playerId)) {
            applyScoreboardTeam(server, p, settlement);
        }
    }
}
