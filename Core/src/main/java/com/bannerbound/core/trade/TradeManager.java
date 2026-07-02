package com.bannerbound.core.trade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.research.ItemKnowledge;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.ChatVoteManager;
import com.bannerbound.core.api.settlement.DiplomacyManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.barbarian.ItemValue;
import com.bannerbound.core.entity.DropOffContainers;
import com.bannerbound.core.entity.SettlementStorage;
import com.bannerbound.core.network.BarterEntry;
import com.bannerbound.core.network.OpenTradeScreenPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Settlement-to-settlement trade orchestrator (the "reliable trading system"): negotiation
 * (propose / counter / accept / reject / cancel with governance), the value-transparent P2P barter
 * (no accept bar — humans judge; totals via {@link ItemValue}), and the CLOCK delivery model —
 * escrow-when-loaded, distance-timed transit, deposit-with-retry. One active deal per settlement
 * pair. Gated by the Bartering research ({@link #FLAG}).
 *
 * <p>Escrow rules (the reliability core): nothing is withdrawn at accept. Each side's goods are
 * withdrawn from its show-for-trading stockpiles when those chunks are loaded (a dormant partner
 * simply waits); BOTH legs must escrow before either departs. A side that can't pay by the escrow
 * deadline fails the deal and the other side is refunded in full — after departure nothing can be
 * lost (no caravan entity yet; that's the physical-courier phase).
 */
@ApiStatus.Internal
public final class TradeManager {
    public static final String FLAG = "bannerbound.allow_trading";

    public static final int ACTION_PROPOSE = 0;
    public static final int ACTION_COUNTER = 1;
    public static final int ACTION_ACCEPT = 2;
    public static final int ACTION_REJECT = 3;
    public static final int ACTION_CANCEL = 4;

    private static final int SWEEP_INTERVAL_TICKS = 20;
    private static final long EXPIRE_TICKS = 3L * 24000L;          // unanswered offers lapse
    private static final long ESCROW_DEADLINE_TICKS = 2L * 24000L; // gather goods within 2 days
    private static final long TRAVEL_TICKS_PER_BLOCK = 4L;         // ~0.25 blocks/tick caravan pace
    private static final long MIN_TRAVEL_TICKS = 1200L;            // never instant, even next door
    private static final long ARRIVAL_DROP_AFTER_TICKS = 24000L;   // full storage → drop at town hall
    private static final long RESOLVED_RETENTION_TICKS = 5L * 24000L;
    private static final int MAX_POOL_ROWS = 40;                   // mirror BarbarianBarter's cap

    private TradeManager() {
    }

    // ─── Gates ──────────────────────────────────────────────────────────────────────────────────────

    /** Whether the Trade button lights up for {@code viewer} against {@code other}: both researched
     *  Bartering, the pair is at peace, and the viewer has a trading stockpile to deal from. */
    public static boolean canTrade(ServerLevel level, Settlement viewer, Settlement other) {
        if (viewer == null || other == null) return false;
        if (!ResearchManager.hasFlag(viewer, FLAG) || !ResearchManager.hasFlag(other, FLAG)) return false;
        if (!SettlementStorage.hasTradeStockpile(viewer)) return false;
        SettlementData.DiplomacyRelation rel =
            SettlementData.get(level).existingRelation(viewer.id(), other.id());
        return rel != null && rel.discovered && !rel.warActive && !rel.pending();
    }

    // ─── Screen open + live storage ─────────────────────────────────────────────────────────────────

    /** Builds the trade-screen snapshot for {@code player} against {@code target}, marking an
     *  awaiting deal as read (clears the Diplomacy badge). Null when the viewer has no settlement. */
    @Nullable
    public static OpenTradeScreenPayload buildOpen(ServerLevel level, ServerPlayer player, Settlement target) {
        Settlement mine = SettlementData.get(level).getByPlayer(player.getUUID());
        if (mine == null || target == null || mine.id().equals(target.id())) return null;
        TradeData trades = TradeData.get(level);
        TradeDeal deal = trades.activeBetween(mine.id(), target.id());

        if (deal != null && deal.unreadForAwaiting && mine.id().equals(deal.awaitingParty)) {
            deal.unreadForAwaiting = false;
            trades.setDirty();
            DiplomacyManager.broadcastDiplomacyState(level.getServer(), mine);
        }

        List<BarterEntry> myOffer = List.of();
        List<BarterEntry> theirOffer = List.of();
        int dealState = -1;
        boolean awaitingUs = false;
        if (deal != null) {
            myOffer = valued(deal.givesOf(mine.id()));
            theirOffer = valued(deal.givesOf(target.id()));
            dealState = deal.state.ordinal();
            awaitingUs = mine.id().equals(deal.awaitingParty)
                && (deal.state == TradeDeal.State.PROPOSED || deal.state == TradeDeal.State.COUNTERED);
        }
        return new OpenTradeScreenPayload(
            target.id().toString(), target.name(), target.color().ordinal(),
            mine.name(), mine.color().ordinal(),
            deal == null ? "" : deal.id.toString(), dealState, awaitingUs,
            canActOnTrades(mine, player),
            myOffer, theirOffer,
            poolEntries(level, mine, mine), poolEntries(level, target, mine));
    }

    /** Live storage snapshots for the poll while the screen is open. */
    public static List<BarterEntry> livePool(ServerLevel level, Settlement owner, Settlement viewer) {
        return poolEntries(level, owner, viewer);
    }

    // ─── Actions ────────────────────────────────────────────────────────────────────────────────────

    /** Routes a screen action. {@code give}/{@code get} are viewer-relative (my side / their side)
     *  and only meaningful for PROPOSE and COUNTER. */
    public static void handleAction(ServerPlayer player, Settlement target, @Nullable UUID dealId,
                                    int action, List<BarterEntry> give, List<BarterEntry> get) {
        MinecraftServer server = player.getServer();
        if (server == null || target == null) return;
        ServerLevel level = server.overworld();
        Settlement mine = SettlementData.get(level).getByPlayer(player.getUUID());
        if (mine == null || mine.id().equals(target.id())) return;

        // Governance: CHIEFDOM = only the chief negotiates; NONE/COUNCIL = any member may put terms
        // on the table (COUNCIL's check is on ACCEPT, where the vote happens).
        if (!canActOnTrades(mine, player)) {
            player.sendSystemMessage(Component.translatable("bannerbound.trade.error.not_chief")
                .withStyle(ChatFormatting.RED));
            return;
        }

        TradeData trades = TradeData.get(level);
        TradeDeal deal = dealId == null ? null : trades.getById(dealId);
        switch (action) {
            case ACTION_PROPOSE -> propose(level, player, mine, target, give, get);
            case ACTION_COUNTER -> counter(level, player, mine, target, deal, give, get);
            case ACTION_ACCEPT -> requestAccept(level, player, mine, deal);
            case ACTION_REJECT -> resolveNegotiation(level, mine, deal, TradeDeal.State.REJECTED,
                "bannerbound.trade.rejected_yours", "bannerbound.trade.rejected");
            case ACTION_CANCEL -> resolveNegotiation(level, mine, deal, TradeDeal.State.CANCELLED,
                "bannerbound.trade.cancelled", "bannerbound.trade.cancelled");
            default -> { }
        }
    }

    private static void propose(ServerLevel level, ServerPlayer player, Settlement mine,
                                Settlement target, List<BarterEntry> give, List<BarterEntry> get) {
        if (!canTrade(level, mine, target)) {
            player.sendSystemMessage(Component.translatable("bannerbound.trade.error.gate")
                .withStyle(ChatFormatting.RED));
            return;
        }
        TradeData trades = TradeData.get(level);
        if (trades.activeBetween(mine.id(), target.id()) != null) {
            player.sendSystemMessage(Component.translatable("bannerbound.trade.error.deal_exists")
                .withStyle(ChatFormatting.RED));
            return;
        }
        List<BarterEntry> gives = sanitize(give);
        List<BarterEntry> gets = sanitize(get);
        if (gives.isEmpty() && gets.isEmpty()) return;
        // Courtesy validation against live pools (escrow is the real enforcement later): my side must
        // exist in my trading stockpiles; their side is checked only when their chunks are loaded.
        if (!coveredByPool(level, mine, gives)) {
            player.sendSystemMessage(Component.translatable("bannerbound.trade.error.short_goods")
                .withStyle(ChatFormatting.RED));
            return;
        }
        Container theirPool = SettlementStorage.tradeAggregate(level, target);
        if (theirPool != null && !coveredBy(summarize(theirPool), gets)) {
            player.sendSystemMessage(Component.translatable("bannerbound.trade.error.they_lack")
                .withStyle(ChatFormatting.RED));
            return;
        }

        TradeDeal deal = new TradeDeal(UUID.randomUUID());
        deal.proposer = mine.id();
        deal.recipient = target.id();
        deal.proposerGives.addAll(gives);
        deal.recipientGives.addAll(gets);
        deal.state = TradeDeal.State.PROPOSED;
        deal.awaitingParty = target.id();
        deal.unreadForAwaiting = true;
        deal.proposedAt = level.getGameTime();
        deal.expiresAt = deal.proposedAt + EXPIRE_TICKS;
        trades.add(deal);

        MinecraftServer server = level.getServer();
        SettlementManager.broadcastToSettlement(server, mine, Component.translatable(
            "bannerbound.trade.proposed_sent", styledName(target)).withStyle(ChatFormatting.GOLD));
        SettlementManager.broadcastToSettlement(server, target, Component.translatable(
            "bannerbound.trade.proposed_incoming", styledName(mine)).withStyle(ChatFormatting.GOLD));
        refreshBadges(server, mine, target);
    }

    private static void counter(ServerLevel level, ServerPlayer player, Settlement mine,
                                Settlement target, @Nullable TradeDeal deal,
                                List<BarterEntry> give, List<BarterEntry> get) {
        if (deal == null || !deal.state.active() || !deal.involves(mine.id())
                || !mine.id().equals(deal.awaitingParty)
                || (deal.state != TradeDeal.State.PROPOSED && deal.state != TradeDeal.State.COUNTERED)) {
            return;
        }
        List<BarterEntry> gives = sanitize(give);
        List<BarterEntry> gets = sanitize(get);
        if (gives.isEmpty() && gets.isEmpty()) return;
        deal.givesOf(mine.id()).clear();
        deal.givesOf(mine.id()).addAll(gives);
        UUID other = deal.other(mine.id());
        deal.givesOf(other).clear();
        deal.givesOf(other).addAll(gets);
        deal.state = TradeDeal.State.COUNTERED;
        deal.awaitingParty = other;
        deal.unreadForAwaiting = true;
        deal.expiresAt = level.getGameTime() + EXPIRE_TICKS;
        TradeData.get(level).setDirty();

        MinecraftServer server = level.getServer();
        SettlementManager.broadcastToSettlement(server, target, Component.translatable(
            "bannerbound.trade.countered", styledName(mine)).withStyle(ChatFormatting.GOLD));
        refreshBadges(server, mine, target);
    }

    /** ACCEPT entry: NONE accepts directly, CHIEFDOM already chief-gated, COUNCIL runs a minor
     *  60-second chat vote before executing. */
    private static void requestAccept(ServerLevel level, ServerPlayer player, Settlement mine,
                                      @Nullable TradeDeal deal) {
        if (deal == null || !deal.involves(mine.id()) || !mine.id().equals(deal.awaitingParty)
                || (deal.state != TradeDeal.State.PROPOSED && deal.state != TradeDeal.State.COUNTERED)) {
            return;
        }
        if (mine.governmentType() == Settlement.Government.COUNCIL) {
            Settlement other = SettlementData.get(level).getById(deal.other(mine.id()));
            ChatVoteManager.start(level.getServer(), mine, ChatVoteManager.Kind.ACCEPT_TRADE,
                player, deal.id, other == null ? "?" : other.name());
            return;
        }
        executeAccept(level.getServer(), mine, deal.id);
    }

    /** Executes an agreed deal (direct, or from a passed council vote): re-checks the gates, then
     *  moves to ACCEPTED and lets the escrow sweep gather both sides' goods. */
    public static void executeAccept(MinecraftServer server, Settlement mine, UUID dealId) {
        ServerLevel level = server.overworld();
        TradeData trades = TradeData.get(level);
        TradeDeal deal = trades.getById(dealId);
        if (deal == null || !deal.involves(mine.id()) || !mine.id().equals(deal.awaitingParty)
                || (deal.state != TradeDeal.State.PROPOSED && deal.state != TradeDeal.State.COUNTERED)) {
            return;
        }
        Settlement other = SettlementData.get(level).getById(deal.other(mine.id()));
        if (other == null) return;
        SettlementData.DiplomacyRelation rel =
            SettlementData.get(level).existingRelation(mine.id(), other.id());
        if (rel == null || rel.warActive || rel.pending()) {
            fail(level, deal, "bannerbound.trade.war_cancelled");
            return;
        }
        deal.state = TradeDeal.State.ACCEPTED;
        deal.escrowDeadline = level.getGameTime() + ESCROW_DEADLINE_TICKS;
        deal.unreadForAwaiting = false;
        deal.proposerLeg.manifest.clear();
        deal.proposerLeg.manifest.addAll(deal.proposerGives);
        deal.recipientLeg.manifest.clear();
        deal.recipientLeg.manifest.addAll(deal.recipientGives);
        trades.setDirty();
        broadcastBoth(server, deal, "bannerbound.trade.accepted");
        refreshBadges(server, mine, other);
    }

    private static void resolveNegotiation(ServerLevel level, Settlement actor, @Nullable TradeDeal deal,
                                           TradeDeal.State state, String actorKey, String otherKey) {
        if (deal == null || !deal.involves(actor.id())
                || (deal.state != TradeDeal.State.PROPOSED && deal.state != TradeDeal.State.COUNTERED)) {
            return;
        }
        if (state == TradeDeal.State.REJECTED && !actor.id().equals(deal.awaitingParty)) return;
        deal.state = state;
        deal.resolvedAt = level.getGameTime();
        TradeData.get(level).setDirty();
        MinecraftServer server = level.getServer();
        Settlement other = SettlementData.get(level).getById(deal.other(actor.id()));
        SettlementManager.broadcastToSettlement(server, actor, Component.translatable(actorKey,
            other == null ? Component.literal("?") : styledName(other)).withStyle(ChatFormatting.GRAY));
        if (other != null) {
            SettlementManager.broadcastToSettlement(server, other, Component.translatable(otherKey,
                styledName(actor)).withStyle(ChatFormatting.GRAY));
            refreshBadges(server, actor, other);
        }
    }

    // ─── The sweep: expiry, war-cancel, escrow, transit, delivery, refunds ──────────────────────────

    public static void tickAll(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level.getGameTime() % SWEEP_INTERVAL_TICKS != 0) return;
        TradeData trades = TradeData.get(level);
        if (trades.all().isEmpty()) return;
        long now = level.getGameTime();
        SettlementData sd = SettlementData.get(level);
        boolean dirty = false;

        for (TradeDeal deal : new ArrayList<>(trades.all())) {
            Settlement proposer = sd.getById(deal.proposer);
            Settlement recipient = sd.getById(deal.recipient);
            if (deal.state.active() && (proposer == null || recipient == null)) {
                // A party disbanded: end any walking couriers, refund whatever escrowed, close it.
                TradeCourierManager.abortJourneysFor(level, deal);
                refundLegIfEscrowed(deal.proposerLeg);
                refundLegIfEscrowed(deal.recipientLeg);
                deal.state = TradeDeal.State.FAILED;
                deal.failReasonKey = "bannerbound.trade.partner_gone";
                deal.resolvedAt = now;
                dirty = true;
                continue;
            }
            switch (deal.state) {
                case PROPOSED, COUNTERED -> {
                    SettlementData.DiplomacyRelation rel =
                        sd.existingRelation(deal.proposer, deal.recipient);
                    if (rel != null && (rel.warActive || rel.pending())) {
                        deal.state = TradeDeal.State.CANCELLED;
                        deal.resolvedAt = now;
                        broadcastBoth(server, deal, "bannerbound.trade.war_cancelled");
                        dirty = true;
                    } else if (now >= deal.expiresAt) {
                        deal.state = TradeDeal.State.EXPIRED;
                        deal.resolvedAt = now;
                        broadcastBoth(server, deal, "bannerbound.trade.expired");
                        dirty = true;
                    }
                }
                case ACCEPTED -> dirty |= tickEscrow(level, deal, proposer, recipient, now);
                case IN_TRANSIT -> dirty |= tickTransit(level, deal, proposer, recipient, now);
                default -> {
                    if (deal.resolvedAt > 0 && now - deal.resolvedAt > RESOLVED_RETENTION_TICKS) {
                        trades.remove(deal.id);
                        dirty = true;
                    }
                }
            }
            // Refund legs keep draining regardless of overall state (FAILED deals finish refunding).
            dirty |= tickRefund(level, deal.proposerLeg, proposer);
            dirty |= tickRefund(level, deal.recipientLeg, recipient);
        }
        if (dirty) trades.setDirty();
    }

    /** ACCEPTED: withdraw each PENDING leg from its giver's trading stockpiles when loaded. Both
     *  escrowed → each leg departs: a Trading-toggled stocker physically walks it when one is
     *  available (retried for a while), else the abstract clock carries it. Past the escrow
     *  deadline with a side still unpaid → fail + refund the side that paid. */
    private static boolean tickEscrow(ServerLevel level, TradeDeal deal,
                                      Settlement proposer, Settlement recipient, long now) {
        boolean changed = tryEscrowLeg(level, deal.proposerLeg, proposer);
        changed |= tryEscrowLeg(level, deal.recipientLeg, recipient);

        if (deal.proposerLeg.state == TradeDeal.Leg.LegState.ESCROWED
                && deal.recipientLeg.state == TradeDeal.Leg.LegState.ESCROWED) {
            changed |= tickDeparture(level, deal, deal.proposerLeg, proposer, recipient, true, now);
            changed |= tickDeparture(level, deal, deal.recipientLeg, recipient, proposer, false, now);
            if (deal.proposerLeg.state == TradeDeal.Leg.LegState.IN_TRANSIT
                    && deal.recipientLeg.state == TradeDeal.Leg.LegState.IN_TRANSIT) {
                deal.state = TradeDeal.State.IN_TRANSIT;
                broadcastBoth(level.getServer(), deal, "bannerbound.trade.departed");
                return true;
            }
            return changed;
        }
        if (now >= deal.escrowDeadline) {
            // Whoever hasn't paid defaults; the paid side gets its goods back.
            Settlement defaulter = deal.proposerLeg.state == TradeDeal.Leg.LegState.PENDING
                ? proposer : recipient;
            refundLegIfEscrowed(deal.proposerLeg);
            refundLegIfEscrowed(deal.recipientLeg);
            deal.state = TradeDeal.State.FAILED;
            deal.failReasonKey = "bannerbound.trade.failed_short";
            deal.resolvedAt = now;
            MinecraftServer server = level.getServer();
            SettlementManager.broadcastToSettlement(server, defaulter, Component.translatable(
                "bannerbound.trade.failed_short_yours").withStyle(ChatFormatting.RED));
            Settlement paid = defaulter == proposer ? recipient : proposer;
            SettlementManager.broadcastToSettlement(server, paid, Component.translatable(
                "bannerbound.trade.failed_short", styledName(defaulter)).withStyle(ChatFormatting.RED));
            return true;
        }
        return changed;
    }

    /** One escrowed leg's departure: prefer a walking courier (kept hunting for a while — stockers
     *  may be busy or unloaded), then fall back to the abstract clock so trades never stall. */
    private static boolean tickDeparture(ServerLevel level, TradeDeal deal, TradeDeal.Leg leg,
                                         Settlement giver, Settlement receiver, boolean proposerLeg,
                                         long now) {
        if (leg.state != TradeDeal.Leg.LegState.ESCROWED) return false;
        if (leg.courierSearchUntil == 0) {
            leg.courierSearchUntil = now + TradeCourierManager.COURIER_WAIT_TICKS;
        }
        if (TradeCourierManager.tryDispatch(level, deal, leg, giver, receiver, proposerLeg)) {
            leg.state = TradeDeal.Leg.LegState.IN_TRANSIT;
            leg.arriveAt = 0; // arrival is event-driven while a courier carries it
            return true;
        }
        if (now >= leg.courierSearchUntil) {
            leg.state = TradeDeal.Leg.LegState.IN_TRANSIT;
            leg.arriveAt = now + travelTicks(giver, receiver);
            SettlementManager.broadcastToSettlement(level.getServer(), giver, Component.translatable(
                "bannerbound.trade.no_courier").withStyle(ChatFormatting.YELLOW));
            return true;
        }
        return false;
    }

    private static boolean tryEscrowLeg(ServerLevel level, TradeDeal.Leg leg, Settlement giver) {
        if (leg.state != TradeDeal.Leg.LegState.PENDING) return false;
        if (leg.manifest.isEmpty()) { // giving nothing is a valid side of a deal (a gift received)
            leg.state = TradeDeal.Leg.LegState.ESCROWED;
            return true;
        }
        Container pool = SettlementStorage.tradeAggregate(level, giver);
        if (pool == null) return false; // unloaded or nothing flagged — wait for the deadline
        Map<Item, Integer> have = summarize(pool);
        if (!coveredBy(have, leg.manifest)) return false; // short — they may restock before deadline
        // Withdraw all lines; a race with a worker rolls back and retries next sweep.
        List<ItemStack> withdrawn = new ArrayList<>();
        for (BarterEntry e : leg.manifest) {
            Item item = item(e.itemId());
            ItemStack got = DropOffContainers.extract(pool, item, e.count());
            withdrawn.add(got);
            if (got.getCount() < e.count()) {
                for (ItemStack st : withdrawn) {
                    if (!st.isEmpty()) DropOffContainers.insert(pool, st);
                }
                return false;
            }
        }
        leg.state = TradeDeal.Leg.LegState.ESCROWED;
        return true;
    }

    /** IN_TRANSIT: land arrivals and deposit (retrying until the receiver's storage is loaded and
     *  has room; after a day of no room, the remainder drops at the receiving town hall). */
    private static boolean tickTransit(ServerLevel level, TradeDeal deal,
                                       Settlement proposer, Settlement recipient, long now) {
        boolean changed = tickArrival(level, deal.proposerLeg, proposer, recipient, now);
        changed |= tickArrival(level, deal.recipientLeg, recipient, proposer, now);
        boolean pDone = deal.proposerLeg.state == TradeDeal.Leg.LegState.DELIVERED
            || deal.proposerLeg.state == TradeDeal.Leg.LegState.FAILED;
        boolean rDone = deal.recipientLeg.state == TradeDeal.Leg.LegState.DELIVERED
            || deal.recipientLeg.state == TradeDeal.Leg.LegState.FAILED;
        if (pDone && rDone) {
            boolean anyFailed = deal.proposerLeg.state == TradeDeal.Leg.LegState.FAILED
                || deal.recipientLeg.state == TradeDeal.Leg.LegState.FAILED;
            deal.state = anyFailed ? TradeDeal.State.PARTIAL : TradeDeal.State.DELIVERED;
            deal.resolvedAt = now;
            broadcastBoth(level.getServer(), deal, anyFailed
                ? "bannerbound.trade.partial" : "bannerbound.trade.delivered_all");
            return true;
        }
        return changed;
    }

    private static boolean tickArrival(ServerLevel level, TradeDeal.Leg leg, Settlement sender,
                                       Settlement receiver, long now) {
        if (leg.state == TradeDeal.Leg.LegState.IN_TRANSIT) {
            if (leg.journeyId != null) {
                if (TradeCourierManager.isJourneyLive(leg.journeyId)) {
                    return false; // a courier is walking it — arrival is event-driven
                }
                // The journey session vanished without a callback (server restart): the courier
                // will drop its stale flag when next seen; the goods continue by the clock.
                leg.journeyId = null;
                leg.courierId = null;
                leg.arriveAt = now + travelTicks(sender, receiver);
                return true;
            }
            if (now < leg.arriveAt) return false;
            leg.state = TradeDeal.Leg.LegState.ARRIVED_PENDING;
        }
        if (leg.state != TradeDeal.Leg.LegState.ARRIVED_PENDING) return false;
        if (leg.manifest.isEmpty()) {
            leg.state = TradeDeal.Leg.LegState.DELIVERED;
            return true;
        }
        boolean progressed = depositInto(level, receiver, leg.manifest);
        if (leg.manifest.isEmpty()) {
            leg.state = TradeDeal.Leg.LegState.DELIVERED;
            SettlementManager.broadcastToSettlement(level.getServer(), receiver, Component.translatable(
                "bannerbound.trade.delivered_in").withStyle(ChatFormatting.GREEN));
            return true;
        }
        // Storage full for a whole day → spill the remainder at the town hall rather than hold it
        // hostage (only when the chunk is loaded so the items are real).
        if (now - leg.arriveAt > ARRIVAL_DROP_AFTER_TICKS && dropAtTownHall(level, receiver, leg.manifest)) {
            leg.manifest.clear();
            leg.state = TradeDeal.Leg.LegState.DELIVERED;
            SettlementManager.broadcastToSettlement(level.getServer(), receiver, Component.translatable(
                "bannerbound.trade.overflow").withStyle(ChatFormatting.YELLOW));
            return true;
        }
        return progressed;
    }

    private static void refundLegIfEscrowed(TradeDeal.Leg leg) {
        if (leg.state == TradeDeal.Leg.LegState.ESCROWED
                || leg.state == TradeDeal.Leg.LegState.IN_TRANSIT
                || leg.state == TradeDeal.Leg.LegState.ARRIVED_PENDING) {
            leg.state = leg.manifest.isEmpty()
                ? TradeDeal.Leg.LegState.REFUNDED : TradeDeal.Leg.LegState.REFUNDING;
        } else if (leg.state == TradeDeal.Leg.LegState.PENDING) {
            leg.state = TradeDeal.Leg.LegState.FAILED;
        }
    }

    private static boolean tickRefund(ServerLevel level, TradeDeal.Leg leg, @Nullable Settlement owner) {
        if (leg.state != TradeDeal.Leg.LegState.REFUNDING) return false;
        if (owner == null) { // nobody left to refund
            leg.manifest.clear();
            leg.state = TradeDeal.Leg.LegState.FAILED;
            return true;
        }
        depositInto(level, owner, leg.manifest);
        if (leg.manifest.isEmpty()) {
            leg.state = TradeDeal.Leg.LegState.REFUNDED;
            return true;
        }
        return false;
    }

    /** Deposits manifest lines into the receiver's trading stockpiles (fallback: any deposit-open
     *  storage), draining the list in place. Returns true if anything moved. */
    private static boolean depositInto(ServerLevel level, Settlement receiver, List<BarterEntry> manifest) {
        BlockPos near = receiver.hasTownHall() ? receiver.townHallPos() : receiver.bannerPos();
        Container pool = SettlementStorage.tradeAggregate(level, receiver);
        if (pool == null) {
            pool = SettlementStorage.depotAggregate(level, receiver,
                near == null ? BlockPos.ZERO : near);
        }
        if (pool == null) return false;
        boolean moved = false;
        List<BarterEntry> remaining = new ArrayList<>();
        for (BarterEntry e : manifest) {
            Item item = item(e.itemId());
            if (item == Items.AIR || e.count() <= 0) continue;
            ItemStack leftover = DropOffContainers.insert(pool, new ItemStack(item, e.count()));
            if (leftover.getCount() < e.count()) moved = true;
            if (!leftover.isEmpty()) {
                remaining.add(new BarterEntry(e.itemId(), leftover.getCount(), 0));
            }
        }
        manifest.clear();
        manifest.addAll(remaining);
        return moved;
    }

    private static boolean dropAtTownHall(ServerLevel level, Settlement receiver, List<BarterEntry> manifest) {
        BlockPos pos = receiver.hasTownHall() ? receiver.townHallPos() : receiver.bannerPos();
        if (pos == null || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        for (BarterEntry e : manifest) {
            Item item = item(e.itemId());
            if (item == Items.AIR) continue;
            int left = e.count();
            while (left > 0) {
                int n = Math.min(left, item.getDefaultMaxStackSize());
                net.minecraft.world.Containers.dropItemStack(level,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, new ItemStack(item, n));
                left -= n;
            }
        }
        return true;
    }

    // ─── Failure + notification helpers ─────────────────────────────────────────────────────────────

    private static void fail(ServerLevel level, TradeDeal deal, String key) {
        TradeCourierManager.abortJourneysFor(level, deal);
        refundLegIfEscrowed(deal.proposerLeg);
        refundLegIfEscrowed(deal.recipientLeg);
        deal.state = TradeDeal.State.FAILED;
        deal.failReasonKey = key;
        deal.resolvedAt = level.getGameTime();
        TradeData.get(level).setDirty();
        broadcastBoth(level.getServer(), deal, key);
    }

    private static void broadcastBoth(MinecraftServer server, TradeDeal deal, String key) {
        SettlementData sd = SettlementData.get(server.overworld());
        Settlement a = sd.getById(deal.proposer);
        Settlement b = sd.getById(deal.recipient);
        if (a != null) {
            SettlementManager.broadcastToSettlement(server, a, Component.translatable(key,
                b == null ? Component.literal("?") : styledName(b)).withStyle(ChatFormatting.GOLD));
        }
        if (b != null) {
            SettlementManager.broadcastToSettlement(server, b, Component.translatable(key,
                a == null ? Component.literal("?") : styledName(a)).withStyle(ChatFormatting.GOLD));
        }
        if (a != null && b != null) refreshBadges(server, a, b);
    }

    private static void refreshBadges(MinecraftServer server, Settlement a, Settlement b) {
        DiplomacyManager.broadcastDiplomacyState(server, a);
        DiplomacyManager.broadcastDiplomacyState(server, b);
    }

    private static Component styledName(Settlement s) {
        return Component.literal(s.name()).withStyle(s.identityFormatting());
    }

    /** CHIEFDOM: only the chief (or regent) negotiates; other governments: any member. */
    private static boolean canActOnTrades(Settlement s, ServerPlayer player) {
        return s.governmentType() != Settlement.Government.CHIEFDOM
            || s.canActAsChief(player.getUUID());
    }

    static long travelTicks(Settlement a, Settlement b) {
        BlockPos pa = a.hasTownHall() ? a.townHallPos() : a.bannerPos();
        BlockPos pb = b.hasTownHall() ? b.townHallPos() : b.bannerPos();
        if (pa == null || pb == null) return MIN_TRAVEL_TICKS;
        double dx = pa.getX() - pb.getX();
        double dz = pa.getZ() - pb.getZ();
        long dist = Math.round(Math.sqrt(dx * dx + dz * dz));
        return Math.max(MIN_TRAVEL_TICKS, dist * TRAVEL_TICKS_PER_BLOCK);
    }

    // ─── Pool helpers (mirrors BarbarianBarter's shapes, over the TRADING stockpiles only) ─────────

    /** The show-for-trading pool of {@code owner}, knowledge-filtered to what {@code viewer}'s civ
     *  comprehends — no "?" items on the table (matches the barbarian barter display rule). */
    private static List<BarterEntry> poolEntries(ServerLevel level, Settlement owner, Settlement viewer) {
        Container pool = SettlementStorage.tradeAggregate(level, owner);
        if (pool == null) return List.of();
        List<BarterEntry> rows = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : summarize(pool).entrySet()) {
            if (!ItemKnowledge.isKnown(viewer, e.getKey())) continue;
            int unit = ItemValue.unitValue(e.getKey());
            if (unit <= 0 || e.getValue() <= 0) continue;
            rows.add(new BarterEntry(BuiltInRegistries.ITEM.getKey(e.getKey()).toString(),
                e.getValue(), unit));
        }
        rows.sort(java.util.Comparator.comparingInt(BarterEntry::count).reversed());
        return rows.size() > MAX_POOL_ROWS ? new ArrayList<>(rows.subList(0, MAX_POOL_ROWS)) : rows;
    }

    private static LinkedHashMap<Item, Integer> summarize(Container c) {
        LinkedHashMap<Item, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            if (!st.isEmpty()) out.merge(st.getItem(), st.getCount(), Integer::sum);
        }
        return out;
    }

    private static boolean coveredByPool(ServerLevel level, Settlement s, List<BarterEntry> lines) {
        if (lines.isEmpty()) return true;
        Container pool = SettlementStorage.tradeAggregate(level, s);
        return pool != null && coveredBy(summarize(pool), lines);
    }

    private static boolean coveredBy(Map<Item, Integer> have, List<BarterEntry> lines) {
        for (BarterEntry e : lines) {
            if (e.count() > have.getOrDefault(item(e.itemId()), 0)) return false;
        }
        return true;
    }

    /** Drops empty/invalid lines and re-prices with the authoritative server unit values. */
    private static List<BarterEntry> sanitize(List<BarterEntry> in) {
        List<BarterEntry> out = new ArrayList<>();
        if (in == null) return out;
        for (BarterEntry e : in) {
            if (e.count() <= 0) continue;
            Item item = item(e.itemId());
            if (item == Items.AIR) continue;
            out.add(new BarterEntry(e.itemId(), Math.min(e.count(), 64 * 64),
                ItemValue.unitValue(item)));
        }
        return out;
    }

    /** Re-prices persisted lines (NBT stores value 0) for client display. */
    private static List<BarterEntry> valued(List<BarterEntry> in) {
        List<BarterEntry> out = new ArrayList<>(in.size());
        for (BarterEntry e : in) {
            out.add(new BarterEntry(e.itemId(), e.count(), ItemValue.value(e.itemId(), 1)));
        }
        return out;
    }

    private static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
    }
}
