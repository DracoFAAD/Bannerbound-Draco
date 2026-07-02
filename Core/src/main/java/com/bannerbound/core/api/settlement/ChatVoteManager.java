package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Council clickable chat-votes: lightweight, transient yes/no votes announced in chat with
 * clickable <b>[Yes] [No]</b> (each runs {@code /bannerbound vote <id> yes|no}), mirrored live in
 * the Town Hall "Votes" tab. Used for the council actions that don't warrant a full screen flow —
 * exiling a citizen and issuing a registration tablet.
 *
 * <p>Resolution: the initiator auto-votes Yes; the vote passes the moment Yes reaches a strict
 * majority of the settlement's <b>online</b> members ({@code floor(n/2)+1}), fails early the moment
 * that majority becomes unreachable (enough No votes), and expires (fails) after 90 seconds.
 * Members may change their vote until it resolves. All state is transient — a restart drops
 * in-flight votes and players simply re-initiate.
 */
@ApiStatus.Internal
public final class ChatVoteManager {
    public enum Kind { EXILE, TABLET, DECLARE_WAR, OFFER_PEACE, TOGGLE_RALLY, RAZE_CAPTURED, ACCEPT_TRADE }

    private static final long VOTE_DURATION_MS = 90_000L;
    /** Trade acceptance is a MINOR vote — big enough to ask the council, not war-grade. */
    private static final long MINOR_VOTE_DURATION_MS = 60_000L;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Map<Integer, ChatVote> ACTIVE = new ConcurrentHashMap<>();

    /** One in-flight chat vote. Yes/No sets are mutually exclusive (casting moves the voter). */
    public static final class ChatVote {
        public final int id;
        public final UUID settlementId;
        public final Kind kind;
        public final UUID initiator;
        /** EXILE: citizen UUID; diplomacy votes: target settlement UUID; TABLET/RALLY: null. */
        @Nullable public final UUID targetCitizen;
        /** Display name baked at start (citizen name, or "" for TABLET) so resolution/expiry
         *  messages don't need the entity loaded. */
        public final String targetName;
        /** True when {@link #targetCitizen} is a CITY-STATE id (war/peace resolves via CityStateWarManager). */
        public final boolean cityState;
        public final long expiresAtMs;
        public final Set<UUID> yes = ConcurrentHashMap.newKeySet();
        public final Set<UUID> no = ConcurrentHashMap.newKeySet();

        ChatVote(int id, UUID settlementId, Kind kind, UUID initiator,
                 @Nullable UUID targetCitizen, String targetName, boolean cityState, long expiresAtMs) {
            this.id = id;
            this.settlementId = settlementId;
            this.kind = kind;
            this.initiator = initiator;
            this.targetCitizen = targetCitizen;
            this.targetName = targetName;
            this.cityState = cityState;
            this.expiresAtMs = expiresAtMs;
        }

        public long secondsLeft() {
            return Math.max(0L, (expiresAtMs - System.currentTimeMillis()) / 1000L);
        }

        /** 1 = yes, -1 = no, 0 = hasn't voted. */
        public int voteOf(UUID player) {
            if (yes.contains(player)) return 1;
            if (no.contains(player)) return -1;
            return 0;
        }
    }

    private ChatVoteManager() {
    }

    /** True if a vote of this kind (and, for EXILE, this citizen) is already running here. */
    public static boolean hasActive(UUID settlementId, Kind kind, @Nullable UUID targetCitizen) {
        for (ChatVote v : ACTIVE.values()) {
            if (v.settlementId.equals(settlementId) && v.kind == kind
                && java.util.Objects.equals(v.targetCitizen, targetCitizen)) {
                return true;
            }
        }
        return false;
    }

    /** All in-flight votes for a settlement (Votes-tab snapshot), oldest first. */
    public static List<ChatVote> activeVotesFor(UUID settlementId) {
        List<ChatVote> out = new ArrayList<>();
        for (ChatVote v : ACTIVE.values()) {
            if (v.settlementId.equals(settlementId)) out.add(v);
        }
        out.sort(java.util.Comparator.comparingInt(v -> v.id));
        return out;
    }

    /**
     * Starts a vote and announces it to every online member with clickable [Yes]/[No]. The
     * initiator auto-votes Yes (a solo "council" therefore resolves instantly). Refuses (with an
     * action-bar message) when an identical vote is already running.
     */
    public static void start(MinecraftServer server, Settlement s, Kind kind,
            ServerPlayer initiator, @Nullable UUID targetCitizen, String targetName) {
        start(server, s, kind, initiator, targetCitizen, targetName, false);
    }

    /** {@code cityState} = true when {@code targetCitizen} is a city-state id (war/peace on a
     *  city-state resolves through {@code CityStateWarManager} instead of {@link DiplomacyManager}). */
    public static void start(MinecraftServer server, Settlement s, Kind kind,
            ServerPlayer initiator, @Nullable UUID targetCitizen, String targetName, boolean cityState) {
        if (hasActive(s.id(), kind, targetCitizen)) {
            initiator.displayClientMessage(Component.translatable("bannerbound.vote.already_running")
                .withStyle(ChatFormatting.RED), true);
            return;
        }
        long duration = kind == Kind.ACCEPT_TRADE ? MINOR_VOTE_DURATION_MS : VOTE_DURATION_MS;
        ChatVote vote = new ChatVote(NEXT_ID.getAndIncrement(), s.id(), kind,
            initiator.getUUID(), targetCitizen, targetName, cityState,
            System.currentTimeMillis() + duration);
        vote.yes.add(initiator.getUUID());
        ACTIVE.put(vote.id, vote);

        MutableComponent header = startMessage(s, vote, initiator.getGameProfile().getName());
        MutableComponent yesBtn = Component.translatable("bannerbound.vote.yes")
            .withStyle(style -> style
                .withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/bannerbound vote " + vote.id + " yes"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.translatable("bannerbound.vote.yes_hover"))));
        MutableComponent noBtn = Component.translatable("bannerbound.vote.no")
            .withStyle(style -> style
                .withColor(ChatFormatting.RED).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/bannerbound vote " + vote.id + " no"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.translatable("bannerbound.vote.no_hover"))));
        Component msg = header.append(" ").append(yesBtn).append(" ").append(noBtn);
        broadcastToMembers(server, s, msg);
        // A 1-member council passes immediately off the initiator's auto-Yes.
        tryResolve(server, vote);
        broadcastVotesState(server, s.id());
    }

    private static MutableComponent startMessage(Settlement s, ChatVote vote, String initiatorName) {
        return switch (vote.kind) {
            case EXILE -> Component.translatable("bannerbound.vote.exile.started",
                initiatorName, vote.targetName, s.name()).withStyle(ChatFormatting.GOLD);
            case TABLET -> {
                boolean paper = s.age().ordinal() >= Era.MEDIEVAL.ordinal();
                yield Component.translatable(
                    paper ? "bannerbound.vote.paper.started" : "bannerbound.vote.tablet.started",
                    initiatorName).withStyle(ChatFormatting.GOLD);
            }
            case DECLARE_WAR -> Component.translatable("bannerbound.vote.diplomacy.war.started",
                initiatorName, vote.targetName).withStyle(ChatFormatting.GOLD);
            case OFFER_PEACE -> Component.translatable("bannerbound.vote.diplomacy.peace.started",
                initiatorName, vote.targetName).withStyle(ChatFormatting.GOLD);
            case TOGGLE_RALLY -> Component.translatable("bannerbound.vote.diplomacy.rally.started",
                initiatorName).withStyle(ChatFormatting.GOLD);
            case RAZE_CAPTURED -> Component.translatable("bannerbound.vote.diplomacy.raze.started",
                initiatorName, vote.targetName).withStyle(ChatFormatting.GOLD);
            case ACCEPT_TRADE -> Component.translatable("bannerbound.vote.trade.started",
                initiatorName, vote.targetName).withStyle(ChatFormatting.GOLD);
        };
    }

    /** Records a vote (clicked in chat or the Votes tab) and resolves if decided. Voters may
     *  switch sides until resolution. Non-members and stale ids are ignored quietly. */
    public static void castVote(MinecraftServer server, ServerPlayer voter, int voteId, boolean yesVote) {
        ChatVote vote = ACTIVE.get(voteId);
        if (vote == null) return;
        Settlement s = SettlementData.get(server.overworld()).getById(vote.settlementId);
        if (s == null) { ACTIVE.remove(voteId); return; }
        if (!s.members().contains(voter.getUUID())) return;
        if (System.currentTimeMillis() >= vote.expiresAtMs) {
            expire(server, vote, s);
            return;
        }
        if (yesVote) { vote.no.remove(voter.getUUID()); vote.yes.add(voter.getUUID()); }
        else { vote.yes.remove(voter.getUUID()); vote.no.add(voter.getUUID()); }
        voter.displayClientMessage(Component.translatable("bannerbound.vote.recorded")
            .withStyle(ChatFormatting.GRAY), true);
        tryResolve(server, vote);
        broadcastVotesState(server, vote.settlementId);
    }

    /** Once-a-second sweep (driven from {@link ImmigrationManager#tickAll}): expire overdue votes. */
    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (ChatVote vote : new ArrayList<>(ACTIVE.values())) {
            if (now < vote.expiresAtMs) continue;
            Settlement s = SettlementData.get(server.overworld()).getById(vote.settlementId);
            if (s == null) { ACTIVE.remove(vote.id); continue; }
            expire(server, vote, s);
        }
    }

    /** Majority math over the CURRENT online member count: pass at {@code floor(n/2)+1} Yes; fail
     *  early once that many Yes is unreachable. */
    private static void tryResolve(MinecraftServer server, ChatVote vote) {
        if (!ACTIVE.containsKey(vote.id)) return;
        Settlement s = SettlementData.get(server.overworld()).getById(vote.settlementId);
        if (s == null) { ACTIVE.remove(vote.id); return; }
        int online = 0;
        for (UUID m : s.members()) {
            if (server.getPlayerList().getPlayer(m) != null) online++;
        }
        if (online <= 0) return;   // nobody online to judge — leave it to the expiry sweep
        int needed = online / 2 + 1;
        // Count only ONLINE votes so a logged-off Yes doesn't carry a vote it can no longer attend.
        int yesOnline = 0;
        int noOnline = 0;
        for (UUID u : vote.yes) if (server.getPlayerList().getPlayer(u) != null) yesOnline++;
        for (UUID u : vote.no) if (server.getPlayerList().getPlayer(u) != null) noOnline++;
        if (yesOnline >= needed) {
            finish(server, s, vote, true, "bannerbound.vote.passed");
        } else if (online - noOnline < needed) {
            finish(server, s, vote, false, "bannerbound.vote.rejected");
        }
    }

    private static void expire(MinecraftServer server, ChatVote vote, Settlement s) {
        finish(server, s, vote, false, "bannerbound.vote.expired");
    }

    private static void finish(MinecraftServer server, Settlement s, ChatVote vote,
            boolean passed, String resultKey) {
        if (ACTIVE.remove(vote.id) == null) return;   // already resolved on another path
        broadcastToMembers(server, s, Component.translatable(resultKey,
                describe(s, vote))
            .withStyle(passed ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        if (passed) {
            perform(server, s, vote);
        }
        broadcastVotesState(server, vote.settlementId);
    }

    /** Short description of what the vote was about, for the result line. */
    private static Component describe(Settlement s, ChatVote vote) {
        return switch (vote.kind) {
            case EXILE -> Component.translatable("bannerbound.vote.describe.exile", vote.targetName);
            case TABLET -> {
                boolean paper = s.age().ordinal() >= Era.MEDIEVAL.ordinal();
                yield Component.translatable(paper
                    ? "bannerbound.vote.describe.paper" : "bannerbound.vote.describe.tablet");
            }
            case DECLARE_WAR -> Component.translatable("bannerbound.vote.describe.diplomacy.war", vote.targetName);
            case OFFER_PEACE -> Component.translatable("bannerbound.vote.describe.diplomacy.peace", vote.targetName);
            case TOGGLE_RALLY -> Component.translatable("bannerbound.vote.describe.diplomacy.rally");
            case RAZE_CAPTURED -> Component.translatable("bannerbound.vote.describe.diplomacy.raze", vote.targetName);
            case ACCEPT_TRADE -> Component.translatable("bannerbound.vote.describe.trade", vote.targetName);
        };
    }

    private static void perform(MinecraftServer server, Settlement s, ChatVote vote) {
        switch (vote.kind) {
            case EXILE -> com.bannerbound.core.network.ServerPayloadHandler
                .performExile(server, s, vote.targetCitizen);
            case TABLET -> {
                ServerPlayer recipient = server.getPlayerList().getPlayer(vote.initiator);
                if (recipient != null) {
                    com.bannerbound.core.network.ServerPayloadHandler.issueTablet(recipient, s);
                } else {
                    broadcastToMembers(server, s,
                        Component.translatable("bannerbound.vote.tablet.initiator_offline")
                            .withStyle(ChatFormatting.RED));
                }
            }
            case DECLARE_WAR, OFFER_PEACE, TOGGLE_RALLY, RAZE_CAPTURED -> {
                if (vote.cityState) {
                    com.bannerbound.core.citystate.CityStateWarManager.performCouncilAction(server, s, vote);
                } else {
                    DiplomacyManager.performCouncilAction(server, s, vote);
                }
            }
            case ACCEPT_TRADE -> com.bannerbound.core.trade.TradeManager
                .executeAccept(server, s, vote.targetCitizen);
        }
    }

    private static void broadcastToMembers(MinecraftServer server, Settlement s, Component msg) {
        for (UUID memberId : new LinkedHashSet<>(s.members())) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) m.sendSystemMessage(msg);
        }
    }

    /** Push the Votes-tab snapshot to every online member (open town halls update live). */
    private static void broadcastVotesState(MinecraftServer server, UUID settlementId) {
        Settlement s = SettlementData.get(server.overworld()).getById(settlementId);
        if (s != null) SettlementManager.broadcastChatVotesState(server, s);
    }
}
