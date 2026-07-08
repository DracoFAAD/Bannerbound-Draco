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
 * Central orchestrator for the whole settlement lifecycle: founding, joining, leaving,
 * disbanding, government/chief elections, policy + palette governance, coups, and every
 * era/research/culture/known-items sync to clients. Everything that mutates {@link SettlementData}
 * from outside the faction package routes through here so the side effects (scoreboard-team upkeep,
 * fireworks, chat, client sync payloads) stay in one place. Pure static, no instance state;
 * {@link #PENDING_SETTLEMENT_CENTER} is the only cross-packet scratch (the campfire the player
 * right-clicked to open SettleScreen, cleared on logout).
 *
 * <p>Identity is server-unique: one settlement per {@link SettlementColor}, so "all colors used"
 * == the hard faction cap ({@link #isAtMaxFactions}). Founding is spaced away from other
 * settlements AND AI city-states.
 *
 * <p>Voting model (government, chief, policy, palette, disband all share it): the denominator is
 * ONLINE members at resolution time -- offline members forfeit. Resolution waits until every
 * online member has voted (an online non-voter must never be cut out by an early majority), and
 * the tally counts only still-online votes (a logged-off pick can't carry a vote it can't attend).
 * Strict majority is {@code floor(n*ratio)+1} ({@link #votesNeeded}) -- NOT {@code ceil(n*0.5)},
 * which would let 1-of-2 pass; council expand-territory ({@link #councilExpandThreshold}) is a
 * deliberately lower bar. When every online member voted with no majority, the tie is handed to
 * the citizens via the animated tribe-vote reveal: the winner is decided server-side now, the
 * client just plays it back, and the real enactment is scheduled for after the animation
 * (schedule*Pending -> enactPending*), so the dissolving/mutating state doesn't close the reveal
 * screen the instant it opens. Reveal duration comes from {@link com.bannerbound.core.client.TribeVoteTiming}
 * (shared with the client).
 *
 * <p>Government: Council enacts immediately + celebrates; Chiefdom hands off to a chief election
 * ({@link #startChiefElection}). Enacting a government sets every citizen's compliance to 100
 * (anarchy citizens otherwise spawn at the low 5-13). The seated chief's crown is rendered purely
 * from a dedicated scoreboard TEAM whose prefix carries the glyph: vanilla syncs team data to all
 * clients, so one team makes the crown appear in nametag + TAB + chat with no custom payload. A
 * regent (grey crown) stands in while the chief is offline. Disband/collapse MUST tear down all
 * three teams (settlement, chief, regent) or orphan teams linger with stale prefixes. Disband is
 * hard-blocked while under raid/war; solo or chief disband is instant behind a double-press
 * confirm; multi-member needs a 100% vote (plus tribe backing under Opinionated Crowd).
 *
 * <p>{@link #computeWorldYear} is monotonic (world era only advances; escape hatch is
 * {@code /bannerbound reset_world_age}). Tick-driven client sends wrap in try/catch because a
 * connection may be mid-handshake; those payloads are registered clientbound in BOTH network dist
 * branches. When teardown happens, former members get their research/era/culture/HUD fully reset
 * and unknown gear unequipped.
 *
 * <p>Open: citizen tribe-votes (chief tiebreak, government tiebreak, coup, Opinionated Crowd) are
 * currently uniform-random; Step 9 will weight them by per-citizen inverse resentment.
 */
public final class SettlementManager {
    public static final int MIN_NAME_LENGTH = 3;
    public static final int MAX_NAME_LENGTH = 24;
    public static final int INITIAL_CLAIM_RADIUS = 1;
    public static final int MIN_DISTANCE_TO_OTHER_SETTLEMENT = 6;

    private static final Map<UUID, BlockPos> PENDING_SETTLEMENT_CENTER = new HashMap<>();

    private SettlementManager() {
    }

    public enum Result {
        OK,
        ALREADY_IN_SETTLEMENT,
        NAME_TAKEN,
        NAME_INVALID,
        TOO_CLOSE_TO_OTHER_SETTLEMENT,
        TOO_CLOSE_TO_CITY_STATE,
        MAX_FACTIONS,
        COLOR_TAKEN
    }

    public static boolean isAtMaxFactions(SettlementData data) {
        return data.all().size() >= SettlementColor.count();
    }

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

    private static final long DISBAND_VOTE_EXPIRY_MS = 3L * 60L * 1000L;
    public static final long VOTE_EXPIRY_MS = 5L * 60L * 1000L;
    @Deprecated
    private static final long GOVERNMENT_VOTE_EXPIRY_MS = VOTE_EXPIRY_MS;
    private static final double GOVERNMENT_VOTE_THRESHOLD_RATIO = 0.5;
    private static final double POLICY_CONFIRM_VOTE_THRESHOLD = 0.5;

    private static int votesNeeded(int n, double ratio) {
        if (n <= 0) return Integer.MAX_VALUE;
        // Strict majority: floor(n*ratio)+1, NOT ceil(n*0.5) which would let 1-of-2 pass.
        return (int) Math.floor(n * ratio) + 1;
    }

    public static int councilExpandThreshold(int onlineMembers) {
        if (onlineMembers <= 1) return 1;
        if (onlineMembers == 2) return 2;
        return (onlineMembers + 1) / 2;
    }

    private static boolean allOnlineMembersVoted(MinecraftServer server, Settlement settlement,
                                                  Set<UUID> voterIds) {
        if (server == null || settlement == null) return false;
        for (UUID id : settlement.members()) {
            if (server.getPlayerList().getPlayer(id) == null) continue;
            if (!voterIds.contains(id)) return false;
        }
        return true;
    }

    public static java.util.List<com.bannerbound.core.entity.CitizenEntity>
            allCitizensOf(ServerLevel level, Settlement settlement) {
        if (level == null || settlement == null) return java.util.Collections.emptyList();
        java.util.List<com.bannerbound.core.entity.CitizenEntity> out = new java.util.ArrayList<>();
        // +/-3.0e7 = vanilla's dimension-spanning entity-query bound; enumerates every loaded citizen.
        for (com.bannerbound.core.entity.CitizenEntity c
                : level.getEntitiesOfClass(com.bannerbound.core.entity.CitizenEntity.class,
                    new net.minecraft.world.phys.AABB(
                        -3.0e7, level.getMinBuildHeight(), -3.0e7,
                        3.0e7, level.getMaxBuildHeight(), 3.0e7))) {
            if (settlement.id().equals(c.getSettlementId())) out.add(c);
        }
        return out;
    }

    public static int countOnlineMembers(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return 0;
        int n = 0;
        for (UUID id : settlement.members()) {
            if (server.getPlayerList().getPlayer(id) != null) n++;
        }
        return n;
    }

    public static void refreshDormancy(MinecraftServer server) {
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        for (Settlement s : SettlementData.get(overworld).all()) {
            s.setDormant(countOnlineMembers(server, s) == 0);
        }
    }

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

        if (settlement.isGovernmentVoteActive()
                && (now - settlement.governmentVoteStartedMs()) > GOVERNMENT_VOTE_EXPIRY_MS) {
            settlement.clearGovernmentVote();
            broadcastToSettlement(server, settlement,
                Component.translatable("bannerbound.government.vote.expired")
                    .withStyle(ChatFormatting.GRAY));
        }

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

        int onlineNow = countOnlineMembers(server, settlement);
        if (onlineNow <= 0) return;
        if (!allOnlineMembersVoted(server, settlement, settlement.governmentVotes().keySet())) return;
        int needed = votesNeeded(onlineNow, GOVERNMENT_VOTE_THRESHOLD_RATIO);
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
            dispatchGovernmentTribeVoteReveal(server, settlement, data);
        }
    }

    private static void enactGovernment(MinecraftServer server, Settlement settlement,
                                         SettlementData data, Settlement.Government winner) {
        settlement.setGovernmentType(winner);
        settlement.clearGovernmentVote();
        if (server != null) {
            for (com.bannerbound.core.entity.CitizenEntity c
                    : allCitizensOf(server.overworld(), settlement)) {
                c.setCompliance(100);
            }
        }
        settlement.setCoupSuppressed(false);
        data.setDirty();
        com.bannerbound.core.codex.CodexManager.onCustom(server, settlement, "government_enacted",
            winner.name().toLowerCase(java.util.Locale.ROOT));
        Component label = Component.translatable(
            winner == Settlement.Government.COUNCIL
                ? "bannerbound.government.council"
                : "bannerbound.government.chiefdom");
        broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.government.enacted", label)
                .withStyle(ChatFormatting.GREEN));
        ImmigrationManager.broadcastState(server, settlement);
        if (winner == Settlement.Government.CHIEFDOM) {
            startChiefElection(server, settlement, data);
        } else {
            celebrateGovernmentEnacted(server, settlement);
        }
    }

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
            launchCelebrationFireworks(level, thp, settlement, 3,
                SoundEvents.FIREWORK_ROCKET_LAUNCH);
        }
        com.bannerbound.core.crisis.CrisisManager.onGovernmentEnacted(server, settlement);
    }

    public static void startChiefElection(MinecraftServer server, Settlement settlement,
                                           SettlementData data) {
        int onlineNow = countOnlineMembers(server, settlement);
        if (onlineNow <= 1) {
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
    }

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
        if (!settlement.members().contains(candidate)) {
            player.sendSystemMessage(Component.translatable("bannerbound.chief.election.bad_candidate")
                .withStyle(ChatFormatting.RED));
            return;
        }
        long now = System.currentTimeMillis();
        if (settlement.isChiefElectionActive()
                && (now - settlement.chiefElectionStartedMs()) > GOVERNMENT_VOTE_EXPIRY_MS) {
            resolveChiefElectionByTopVote(server, settlement, data);
            if (!settlement.chiefdomElectionWindowOpen()) return;
            settlement.clearChiefElection();
        }
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
        int onlineNow = countOnlineMembers(server, settlement);
        if (onlineNow <= 0) return;
        if (!allOnlineMembersVoted(server, settlement, settlement.chiefNominations().keySet())) return;
        int needed = votesNeeded(onlineNow, GOVERNMENT_VOTE_THRESHOLD_RATIO);
        int topCount = settlement.chiefNominationCountFor(candidate);
        if (topCount >= needed) {
            enactChief(server, settlement, data, candidate);
            return;
        }
        resolveChiefElectionByTopVote(server, settlement, data);
    }

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

    public static List<UUID> computeCitizenVotes(ServerLevel level, Settlement settlement,
                                                   List<UUID> tiedCandidates) {
        List<UUID> votes = new ArrayList<>();
        if (tiedCandidates.isEmpty()) return votes;
        java.util.Random rng = new java.util.Random();
        for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
            votes.add(tiedCandidates.get(rng.nextInt(tiedCandidates.size())));
        }
        return votes;
    }

    private static void dispatchTribeVoteReveal(MinecraftServer server, Settlement settlement,
                                                  SettlementData data, List<UUID> tiedCandidates) {
        ServerLevel overworld = server.overworld();
        List<UUID> citizenVotes = computeCitizenVotes(overworld, settlement, tiedCandidates);
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

        long revealMs = com.bannerbound.core.client.TribeVoteTiming.revealDurationMs(voterNames.size());
        long enactTick = overworld.getGameTime() + (revealMs / 50L);
        settlement.schedulePendingChief(winner, enactTick);
        settlement.clearChiefElection();
        data.setDirty();
    }

    public static final long CHIEF_STEP_DOWN_COOLDOWN_TICKS = 20L * 60L * 20L;

    private static void enactChief(MinecraftServer server, Settlement settlement,
                                    SettlementData data, UUID chiefId) {
        settlement.setChiefPlayerId(chiefId);
        settlement.setChiefSinceTick(server.overworld().getGameTime());
        settlement.clearChiefElection();
        data.setDirty();
        broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.chief.election.elected",
                playerName(server, chiefId))
                .withStyle(ChatFormatting.GREEN));
        ServerPlayer chiefPlayer = server.getPlayerList().getPlayer(chiefId);
        if (chiefPlayer != null) {
            applyChiefScoreboardTeam(server, chiefPlayer, settlement);
        }
        recomputeRegent(server, settlement);
        ImmigrationManager.broadcastState(server, settlement);
        celebrateGovernmentEnacted(server, settlement);
    }

    public static void enactPendingChief(MinecraftServer server, Settlement settlement,
                                          SettlementData data) {
        UUID winner = settlement.pendingChiefId();
        if (winner == null) return;
        settlement.clearPendingChief();
        enactChief(server, settlement, data, winner);
    }

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
        Settlement.Government winner;
        if (councilN == chiefdomN) {
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
        settlement.clearGovernmentVote();
        data.setDirty();
    }

    public static void enactPendingGovernment(MinecraftServer server, Settlement settlement,
                                                SettlementData data) {
        Settlement.Government winner = settlement.pendingGovernmentType();
        if (winner == null) return;
        settlement.clearPendingGovernment();
        enactGovernment(server, settlement, data, winner);
    }

    public static void enactPendingDisband(MinecraftServer server, Settlement settlement,
                                            SettlementData data) {
        if (!settlement.hasPendingDisband()) return;
        settlement.clearPendingDisband();
        broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.disband.vote.passed").withStyle(ChatFormatting.RED));
        performFullDisband(server, settlement, data);
    }

    private static final int COUP_CITIZEN_THRESHOLD = 80;
    private static final double COUP_TRIGGER_FRACTION = 0.45;
    private static final int COUP_MIN_ONLINE_PLAYERS = 2;

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
        int onlineNow = countOnlineMembers(server, settlement);
        if (onlineNow < COUP_MIN_ONLINE_PLAYERS) {
            settlement.setCoupSuppressed(true);
            return;
        }
        settlement.setCoupSuppressed(false);
        triggerCoupVote(server, settlement);
    }

    public static final int STRIKE_RISK_COMPLIANCE = 30;

    public static java.util.List<Component> settlementWarnings(MinecraftServer server, Settlement settlement) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        if (server == null || settlement == null) return out;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return out;
        java.util.List<com.bannerbound.core.entity.CitizenEntity> citizens =
            allCitizensOf(overworld, settlement);
        int total = citizens.size();
        if (total == 0) return out;

        int homeless = settlement.isTribe() ? settlement.homelessCitizens().size() : 0;
        if (homeless > 0) {
            out.add(Component.translatable("bannerbound.warning.homeless", homeless)
                .withStyle(ChatFormatting.YELLOW));
        }

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

    private static void triggerCoupVote(MinecraftServer server, Settlement settlement) {
        UUID oldChief = settlement.chiefPlayerId();
        if (oldChief == null) return;
        java.util.List<UUID> candidates = new java.util.ArrayList<>();
        for (UUID memberId : settlement.members()) {
            if (!memberId.equals(oldChief)) candidates.add(memberId);
        }
        if (candidates.isEmpty()) {
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

    private static Component playerName(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) return p.getName();
        net.minecraft.server.players.GameProfileCache cache = server.getProfileCache();
        String shortId = id.toString().substring(0, 8);
        if (cache == null) return Component.literal(shortId);
        return Component.literal(cache.get(id).map(profile -> profile.getName()).orElse(shortId));
    }

    private static final long DISBAND_CONFIRM_WINDOW_MS = 12_000L;
    private static final java.util.Map<UUID, Long> PENDING_DISBAND_CONFIRM = new java.util.HashMap<>();

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

        if (isUnderAttack(server, settlement)) {
            player.sendSystemMessage(Component.translatable("bannerbound.disband.error.under_attack")
                .withStyle(ChatFormatting.RED));
            return;
        }

        if (memberCount <= 1) {
            if (!confirmInstantDisband(player, settlement)) return;
            performFullDisband(server, settlement, data);
            return;
        }

        if (settlement.governmentType() == Settlement.Government.CHIEFDOM
                && settlement.canActWeighty(player.getUUID())) {
            if (!confirmInstantDisband(player, settlement)) return;
            performFullDisband(server, settlement, data);
            return;
        }

        if (settlement.isDisbandVoteActive()
                && (now - settlement.disbandVoteStartedMs()) > DISBAND_VOTE_EXPIRY_MS) {
            settlement.clearDisbandVote();
            broadcastToSettlement(server, settlement,
                Component.translatable("bannerbound.disband.vote.expired")
                    .withStyle(ChatFormatting.GRAY));
        }

        if (settlement.hasDisbandVoted(player.getUUID())) {
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
            if (!s.canActWeighty(player.getUUID())) {
                player.displayClientMessage(Component.translatable(
                    "bannerbound.policy.error.chief_only").withStyle(ChatFormatting.RED), true);
                return;
            }
            enactPolicyChange(server, s, data, change);
            return;
        }
        if (s.pendingPolicyChange() != null) {
            player.displayClientMessage(Component.translatable(
                "bannerbound.policy.error.vote_in_progress").withStyle(ChatFormatting.RED), true);
            return;
        }
        s.setPendingPolicyChange(change);
        s.castPolicyConfirmVote(player.getUUID(), true);
        data.setDirty();
        broadcastPolicyState(server, s);
        resolvePolicyVote(server, s, data);
    }

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

    private static void resolvePolicyVote(MinecraftServer server, Settlement s, SettlementData data) {
        if (s.pendingPolicyChange() == null) return;
        int onlineNow = countOnlineMembers(server, s);
        if (onlineNow <= 0) return;
        if (!allOnlineMembersVoted(server, s, s.policyConfirmVotes().keySet())) return;
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
        PolicyEffects.syncPolicyThoughts(server, s);
        s.clearPolicyChangeState();
        data.setDirty();
        broadcastPolicyState(server, s);
        broadcastToSettlement(server, s, Component.translatable(
            "bannerbound.policy.enacted").withStyle(ChatFormatting.GREEN));
        if (s.governmentType() == Settlement.Government.COUNCIL
                && s.hasPolicy(PolicyRegistry.OPINIONATED_CROWD)
                && dispatchOpinionatedReveal(server, s)) {
            grantOpinionatedBonus(server, s);
        }
    }

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
        ChunkBeautyManager.recomputeTrackedSet(server.overworld());
        for (UUID memberId : s.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(memberId);
            if (p != null) sendBlockAppealTo(p);
        }
    }

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

    public static void grantOpinionatedBonus(MinecraftServer server, Settlement settlement) {
        ServerLevel overworld = server == null ? null : server.overworld();
        if (overworld == null) return;
        settlement.setPolicyOpinionatedBonusExpiry(
            overworld.getGameTime() + PolicyEffects.OPINIONATED_BONUS_DURATION_TICKS);
    }

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

    public static void broadcastToSettlement(MinecraftServer server, Settlement settlement, Component msg) {
        for (UUID id : settlement.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) p.sendSystemMessage(msg);
        }
    }

    public static void razeSettlement(MinecraftServer server, Settlement settlement, SettlementData data) {
        performFullDisband(server, settlement, data);
    }

    private static void performFullDisband(MinecraftServer server, Settlement settlement, SettlementData data) {
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

        removeSettlementTeams(server, settlement);

        data.unclaimAllOf(settlement);
        data.removeSettlement(settlement);
        com.bannerbound.core.ruin.RuinManager.queue(server.overworld(), ruinArea);
        com.bannerbound.core.crisis.CrisisManager.onSettlementRemoved(server, settlement, formerMembers);
        com.bannerbound.core.api.farmer.FarmerFoodBonus.forget(settlement.id());
        com.bannerbound.core.entity.HerderFoodBonus.forget(settlement.id());
        com.bannerbound.core.barbarian.BarbarianCampManager.onSettlementRemoved(server.overworld(), settlement.id());
        com.bannerbound.core.citystate.CityStateWarManager.onSettlementRemoved(server.overworld(), settlement.id());
        broadcastClaims(server);

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
        if (com.bannerbound.core.citystate.CityStateData.get(overworld)
                .hasClaimWithin(center, INITIAL_CLAIM_RADIUS + MIN_DISTANCE_TO_OTHER_SETTLEMENT)) {
            return Result.TOO_CLOSE_TO_CITY_STATE;
        }

        SettlementColor color = SettlementColor.byIndex(colorIndex);
        if (isColorInUse(data, color)) {
            return Result.COLOR_TAKEN;
        }
        Settlement settlement = new Settlement(UUID.randomUUID(), name, color, player.getUUID());
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
        ChunkBeautyManager.recomputeTrackedSet(overworld);

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
        com.bannerbound.core.codex.CodexManager.onCustom(player, "settlement_founded", "");
        celebrateFounding(player, settlement);
        broadcastClaims(player.getServer());
        sendEraStateTo(player);
        com.bannerbound.core.language.CustomLanguageSync.sendTo(player);
        ImmigrationManager.broadcastState(player.getServer(), settlement);
        sendBlockAppealTo(player);
        sendFoodValuesTo(player);

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
        com.bannerbound.core.codex.CodexManager.onCustom(player, "settlement_joined", "");

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

        long remainingTicks = data.leaveCooldownUntil(player.getUUID()) - overworld.getGameTime();
        if (remainingTicks > 0) {
            long remainingSeconds = (remainingTicks + 19) / 20;
            player.sendSystemMessage(Component.translatable("bannerbound.leave.cooldown",
                String.format("%d:%02d", remainingSeconds / 60, remainingSeconds % 60))
                .withStyle(ChatFormatting.RED));
            return LeaveResult.COOLDOWN;
        }

        Scoreboard scoreboard = server.getScoreboard();
        // Remove from their ACTUAL team: a chief sits in bb_chief_<id>, not the settlement team.
        PlayerTeam currentTeam = scoreboard.getPlayersTeam(player.getScoreboardName());
        if (currentTeam != null) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), currentTeam);
        }

        if (player.getUUID().equals(settlement.chiefPlayerId())) {
            settlement.setChiefPlayerId(null);
        }

        data.removeMember(settlement, player.getUUID());

        player.sendSystemMessage(Component.translatable("bannerbound.leave.success", settlement.factionName())
            .withStyle(ChatFormatting.GRAY));

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
            caps.add(s.laborCap(j));
        }
        return new com.bannerbound.core.network.LaborStatePayload(jobs, enabled, current, caps,
            s.laborAutoAssign(), s.hasPolicy(PolicyRegistry.WORKLOAD_SHARE),
            s.preferredStoragePos() == null ? Long.MIN_VALUE : s.preferredStoragePos().asLong());
    }

    public static void broadcastLaborState(MinecraftServer server, Settlement s) {
        if (server == null || s == null) return;
        java.util.List<ServerPlayer> online = new java.util.ArrayList<>();
        for (UUID memberId : s.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) online.add(m);
        }
        if (online.isEmpty()) return;
        com.bannerbound.core.network.LaborStatePayload payload = buildLaborPayload(server.overworld(), s);
        for (ServerPlayer m : online) sendLaborSafely(m, payload);
    }

    public static void sendLaborStateTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Settlement s = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        if (s == null) return;
        sendLaborSafely(player, buildLaborPayload(server.overworld(), s));
    }

    private static void sendLaborSafely(ServerPlayer player, com.bannerbound.core.network.LaborStatePayload payload) {
        if (player.connection == null) return;
        try {
            PacketDistributor.sendToPlayer(player, payload);
        } catch (RuntimeException ignored) {
            // Channel may be mid-handshake; must not crash the 1/s settlement tick -- it retries.
        }
    }

    private static String memberName(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        if (server.getProfileCache() != null) {
            return server.getProfileCache().get(id)
                .map(com.mojang.authlib.GameProfile::getName).orElse("?");
        }
        return "?";
    }

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

    private static void sendSafely(ServerPlayer player,
            net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (player.connection == null) return;
        try {
            PacketDistributor.sendToPlayer(player, payload);
        } catch (RuntimeException ignored) {
            // Channel may not be negotiated yet; never crash the tick -- the next change retries.
        }
    }

    public static boolean canEditLabor(ServerPlayer player, Settlement s) {
        if (!s.members().contains(player.getUUID())) return false;
        return switch (s.governmentType()) {
            case NONE, COUNCIL -> true;
            case CHIEFDOM -> s.canActAsChief(player.getUUID())
                || s.hasPolicy(PolicyRegistry.WORKLOAD_SHARE);
        };
    }

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

    public static int computeWorldYear(SettlementData data) {
        Era era = data.getWorldAge();
        int start = com.bannerbound.core.api.settlement.data.EraTimelineLoader.getStartYear(era);
        Era next = era.next();
        if (next == era) {
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

    public static void sendCultureTreeTo(ServerPlayer player) {
        java.util.List<com.bannerbound.core.api.research.ResearchDefinition> defs =
            new java.util.ArrayList<>(
                com.bannerbound.core.api.research.data.CultureTreeLoader.getAll().values());
        PacketDistributor.sendToPlayer(player,
            new com.bannerbound.core.network.CultureTreeSyncPayload(defs));
    }

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
        java.util.Set<Long> ruinArea = new java.util.HashSet<>(settlement.claimedChunks());
        DiplomacyManager.onSettlementDisbanded(server, settlement, data);
        removeSettlementTeams(server, settlement);
        despawnAllCitizens(server, settlement);

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
        com.bannerbound.core.ruin.RuinManager.queue(server.overworld(), ruinArea);
        com.bannerbound.core.crisis.CrisisManager.onSettlementRemoved(server, settlement,
            java.util.List.of());
        com.bannerbound.core.api.farmer.FarmerFoodBonus.forget(settlement.id());
        com.bannerbound.core.entity.HerderFoodBonus.forget(settlement.id());
        com.bannerbound.core.barbarian.BarbarianCampManager.onSettlementRemoved(server.overworld(), settlement.id());
        com.bannerbound.core.citystate.CityStateWarManager.onSettlementRemoved(server.overworld(), settlement.id());

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

    private static String chiefTeamName(Settlement settlement) {
        return "bb_chief_" + settlement.id().toString().substring(0, 8);
    }
    private static String regentTeamName(Settlement settlement) {
        return "bb_regent_" + settlement.id().toString().substring(0, 8);
    }

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

    public static void recomputeRegent(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        if (settlement.governmentType() != Settlement.Government.CHIEFDOM) {
            clearRegent(server, settlement);
            return;
        }
        UUID chiefId = settlement.chiefPlayerId();
        if (chiefId == null) {
            clearRegent(server, settlement);
            return;
        }
        boolean chiefOnline = server.getPlayerList().getPlayer(chiefId) != null;
        if (chiefOnline) {
            clearRegent(server, settlement);
            return;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        java.util.Map<UUID, Integer> totals = new java.util.HashMap<>();
        for (UUID memberId : settlement.members()) {
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
        if (oldRegent != null && tied.contains(oldRegent)) return;
        UUID newRegent = tied.get(overworld.random.nextInt(tied.size()));
        if (newRegent.equals(oldRegent)) return;
        if (oldRegent != null) demoteFromRegentTeam(server, settlement, oldRegent);
        settlement.setRegentPlayerId(newRegent);
        ServerPlayer regentPlayer = server.getPlayerList().getPlayer(newRegent);
        if (regentPlayer != null) applyRegentScoreboardTeam(server, regentPlayer, settlement);
        broadcastToSettlement(server, settlement,
            Component.translatable("bannerbound.chief.regent_installed",
                playerName(server, newRegent))
                .withStyle(ChatFormatting.GRAY));
    }

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
        Component prefix = Component.empty()
            .append(Component.literal("[" + settlement.factionName() + "] ").withStyle(color))
            .append(com.bannerbound.core.api.Glyphs.crown())
            .append(Component.literal(" "));
        team.setPlayerPrefix(prefix);
        scoreboard.addPlayerToTeam(chief.getScoreboardName(), team);
    }

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

    private static void demoteFromRegentTeam(MinecraftServer server, Settlement settlement, UUID playerId) {
        if (server == null || settlement == null || playerId == null) return;
        ServerPlayer p = server.getPlayerList().getPlayer(playerId);
        if (p == null) return;
        if (settlement.members().contains(playerId)) {
            applyScoreboardTeam(server, p, settlement);
        }
    }
}
