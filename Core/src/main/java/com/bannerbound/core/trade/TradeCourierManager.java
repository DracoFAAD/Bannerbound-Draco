package com.bannerbound.core.trade;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Citizen;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.api.settlement.Stockpile;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.StockerWorkGoal;
import com.bannerbound.core.sim.TraderSimManager;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The WALKING TRADER bridge between {@link TradeManager}'s deals and {@link TraderSimManager}'s
 * journeys: picks a Trading-toggled stocker, adopts it for the trip (its own AI suspends via
 * {@code CitizenEntity.isOnTradeJourney()}), musters it to the trading stockpile, walks it to the
 * partner settlement carrying the leg's escrowed manifest (a chest in hand for readability), lands
 * the delivery through TradeManager's normal deposit path, and walks it home to resume stocking.
 *
 * <p>Every failure degrades to the abstract CLOCK (never bricks a trade): no courier found, route
 * unplannable, courier stuck/lost/killed → the leg continues on the distance timer and the deal
 * proceeds. Journey→leg wiring is transient (rebuilt each dispatch); a server restart drops live
 * sessions, and {@code TradeManager}'s sweep detects the dead {@code journeyId} and clocks the leg.
 */
@ApiStatus.Internal
public final class TradeCourierManager {
    /** How long a leg keeps hunting for an available courier before falling back to the clock. */
    static final long COURIER_WAIT_TICKS = 6000L;

    /** Which deal leg a live journey is carrying ({@code returning} = the empty walk home). */
    private record LegRef(UUID dealId, boolean proposerLeg, boolean returning) {}

    private static final Map<UUID, LegRef> JOURNEYS = new HashMap<>();

    private TradeCourierManager() {
    }

    /** True while {@code journeyId} is a live courier journey (post-restart this is false, which is
     *  exactly how the sweep notices the courier is gone and clocks the leg). */
    public static boolean isJourneyLive(@Nullable UUID journeyId) {
        return journeyId != null && TraderSimManager.hasSession(journeyId);
    }

    /**
     * Tries to put a walking courier on {@code leg} (giver → receiver). On success the courier is
     * adopted and the leg carries its ids; arrival/failure flow back through the journey listener.
     * Returns false when no courier is available right now (caller keeps waiting or clocks the leg).
     */
    static boolean tryDispatch(ServerLevel level, TradeDeal deal, TradeDeal.Leg leg,
                               Settlement giver, Settlement receiver, boolean proposerLeg) {
        BlockPos loadPos = tradingStockpilePos(giver);
        if (loadPos == null) return false;
        CitizenEntity courier = findCourier(level, giver, loadPos);
        if (courier == null) return false;
        BlockPos dest = receiver.hasTownHall() ? receiver.townHallPos() : receiver.bannerPos();
        if (dest == null) return false;

        UUID journeyId = TraderSimManager.startAdopted(level.getServer(), courier, loadPos, dest,
            true, LISTENER);
        courier.setTradeJourneyId(journeyId);
        courier.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CHEST)); // visible cargo
        leg.courierId = courier.getUUID();
        leg.journeyId = journeyId;
        JOURNEYS.put(journeyId, new LegRef(deal.id, proposerLeg, false));
        SettlementManager.broadcastToSettlement(level.getServer(), giver, Component.translatable(
            "bannerbound.trade.courier_departed", courier.getDisplayName(),
            Component.literal(receiver.name()).withStyle(receiver.identityFormatting()))
            .withStyle(ChatFormatting.GOLD));
        return true;
    }

    /** The rack position of the giver's best show-for-trading stockpile (any valid one). */
    @Nullable
    private static BlockPos tradingStockpilePos(Settlement s) {
        for (Stockpile sp : s.stockpiles().values()) {
            if (sp.valid() && sp.showForTrading() && !sp.containers().isEmpty()) return sp.pos();
        }
        return null;
    }

    /** The giver's nearest available Trading-toggled stocker: loaded, alive, adult, idle of any
     *  other journey. Interrupting a live haul is safe — StockerWorkGoal.stop() returns the load. */
    @Nullable
    private static CitizenEntity findCourier(ServerLevel level, Settlement giver, BlockPos near) {
        CitizenEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Citizen c : giver.citizens()) {
            if (!(level.getEntity(c.entityId()) instanceof CitizenEntity ce)) continue;
            if (!ce.isAlive() || ce.isRemoved()) continue;
            if (!StockerWorkGoal.JOB_TYPE_ID.equals(ce.getJobType())) continue;
            if (!ce.isTradingCourier() || ce.isOnTradeJourney()) continue;
            if (ce.isChild() || ce.isPregnant()) continue;
            double d = ce.distanceToSqr(near.getX() + 0.5, near.getY() + 0.5, near.getZ() + 0.5);
            if (d < bestDist) {
                bestDist = d;
                best = ce;
            }
        }
        return best;
    }

    // ─── Journey completion (main thread, fired by TraderSimManager.finish) ────────────────────────

    private static final TraderSimManager.JourneyListener LISTENER = new TraderSimManager.JourneyListener() {
        @Override
        public void onArrived(UUID journeyId) {
            handleCompletion(journeyId, true, null);
        }

        @Override
        public void onFailed(UUID journeyId, String reason) {
            handleCompletion(journeyId, false, reason);
        }
    };

    private static void handleCompletion(UUID journeyId, boolean arrived, @Nullable String reason) {
        LegRef ref = JOURNEYS.remove(journeyId);
        if (ref == null) return;
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        TradeData trades = TradeData.get(level);
        TradeDeal deal = trades.getById(ref.dealId());
        TradeDeal.Leg leg = deal == null ? null
            : (ref.proposerLeg() ? deal.proposerLeg : deal.recipientLeg);
        CitizenEntity courier = leg != null && leg.courierId != null
            && level.getEntity(leg.courierId) instanceof CitizenEntity ce ? ce
            : resolveCourierByJourney(level, journeyId);

        if (ref.returning()) {
            // The empty walk home — however it ended, hand the citizen back to its own AI.
            if (courier != null) endAdoption(courier);
            return;
        }

        SettlementData sd = SettlementData.get(level);
        Settlement giver = deal == null ? null
            : sd.getById(ref.proposerLeg() ? deal.proposer : deal.recipient);
        Settlement receiver = deal == null ? null
            : sd.getById(ref.proposerLeg() ? deal.recipient : deal.proposer);

        if (arrived && leg != null) {
            // Goods land through the normal deposit path (retry + town-hall overflow all reused).
            leg.state = TradeDeal.Leg.LegState.ARRIVED_PENDING;
            leg.arriveAt = level.getGameTime();
            leg.journeyId = null;
            trades.setDirty();
        } else if (leg != null && deal != null && deal.state.active()) {
            // Journey failed (stuck / lost / no route) — the goods continue by the abstract clock
            // from here so the trade never bricks; the courier is released to walk itself home.
            leg.journeyId = null;
            leg.courierId = null;
            if (leg.state == TradeDeal.Leg.LegState.IN_TRANSIT && giver != null && receiver != null) {
                leg.arriveAt = level.getGameTime() + TradeManager.travelTicks(giver, receiver);
                SettlementManager.broadcastToSettlement(server, giver, Component.translatable(
                    "bannerbound.trade.courier_fallback").withStyle(ChatFormatting.YELLOW));
            }
            trades.setDirty();
        }

        if (courier != null) {
            if (arrived && giver != null) {
                // Walk home empty; failures here just end adoption wherever the courier stands.
                BlockPos home = giver.hasTownHall() ? giver.townHallPos() : giver.bannerPos();
                if (home != null) {
                    courier.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); // cargo delivered
                    UUID back = TraderSimManager.startAdopted(server, courier,
                        courier.blockPosition(), home, true, LISTENER);
                    courier.setTradeJourneyId(back);
                    JOURNEYS.put(back, new LegRef(ref.dealId(), ref.proposerLeg(), true));
                    return;
                }
            }
            endAdoption(courier);
        }
    }

    @Nullable
    private static CitizenEntity resolveCourierByJourney(ServerLevel level, UUID journeyId) {
        // Fallback lookup when the leg is already gone: scan nothing — the courier keeps its
        // journey id and is cleaned up by the stale-flag sweep when next seen (P2). Null here.
        return null;
    }

    /** Returns the citizen to its own life: journey flag off (the blocker goal stops), cargo prop
     *  cleared, stocker AI resumes on the next goal poll. */
    static void endAdoption(CitizenEntity courier) {
        courier.setTradeJourneyId(null);
        ItemStack hand = courier.getItemBySlot(EquipmentSlot.MAINHAND);
        if (hand.is(Items.CHEST)) {
            courier.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    // ─── Death, aborts, and stale-flag recovery ─────────────────────────────────────────────────────

    /** A courier died mid-journey: the cargo it carried SPILLS as loot at the death site (no refund —
     *  the goods are on the ground for whoever killed it), the leg FAILS, and the other leg still
     *  delivers (the deal resolves PARTIAL). A death on the empty walk home costs nothing. */
    public static void onCourierDied(ServerLevel level, CitizenEntity courier) {
        UUID journeyId = courier.getTradeJourneyId();
        if (journeyId == null) return;
        LegRef ref = JOURNEYS.remove(journeyId);
        // Detach the listener path FIRST so the session teardown below can't double-handle it.
        TraderSimManager.stopSession(level.getServer(), journeyId, "lost");
        courier.setTradeJourneyId(null);
        if (ref == null || ref.returning()) return; // empty walk home — nothing carried

        TradeData trades = TradeData.get(level);
        TradeDeal deal = trades.getById(ref.dealId());
        if (deal == null) return;
        TradeDeal.Leg leg = ref.proposerLeg() ? deal.proposerLeg : deal.recipientLeg;
        if (leg.state != TradeDeal.Leg.LegState.IN_TRANSIT) return;

        for (com.bannerbound.core.network.BarterEntry e : leg.manifest) {
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.tryParse(e.itemId()));
            int left = e.count();
            while (left > 0 && item != Items.AIR) {
                int n = Math.min(left, item.getDefaultMaxStackSize());
                net.minecraft.world.Containers.dropItemStack(level,
                    courier.getX(), courier.getY() + 0.5, courier.getZ(), new ItemStack(item, n));
                left -= n;
            }
        }
        leg.manifest.clear();
        leg.state = TradeDeal.Leg.LegState.FAILED;
        leg.courierId = null;
        leg.journeyId = null;
        trades.setDirty();

        SettlementData sd = SettlementData.get(level);
        Settlement giver = sd.getById(ref.proposerLeg() ? deal.proposer : deal.recipient);
        Settlement other = sd.getById(ref.proposerLeg() ? deal.recipient : deal.proposer);
        MinecraftServer server = level.getServer();
        if (giver != null) {
            SettlementManager.broadcastToSettlement(server, giver, Component.translatable(
                "bannerbound.trade.courier_killed_yours", courier.getDisplayName())
                .withStyle(ChatFormatting.RED));
        }
        if (other != null && giver != null) {
            SettlementManager.broadcastToSettlement(server, other, Component.translatable(
                "bannerbound.trade.courier_killed",
                Component.literal(giver.name()).withStyle(giver.identityFormatting()))
                .withStyle(ChatFormatting.RED));
        }
    }

    /** Deal died mid-journey (cancel / war / a settlement disbanded): end every live courier journey
     *  it owns. The escrowed goods refund through the deal's abstract refund sweep; the courier is
     *  simply released where it stands and walks itself home under its own AI. */
    public static void abortJourneysFor(ServerLevel level, TradeDeal deal) {
        abortLeg(level, deal.proposerLeg);
        abortLeg(level, deal.recipientLeg);
    }

    private static void abortLeg(ServerLevel level, TradeDeal.Leg leg) {
        if (leg.journeyId == null) return;
        JOURNEYS.remove(leg.journeyId);
        TraderSimManager.stopSession(level.getServer(), leg.journeyId, "stopped");
        if (leg.courierId != null && level.getEntity(leg.courierId) instanceof CitizenEntity courier) {
            endAdoption(courier);
        }
        leg.journeyId = null;
        leg.courierId = null;
    }

    /** Recovery for a courier whose journey no longer exists (server restart, or its deal resolved
     *  while it was unloaded): drop the stale flag and hand it back to its own AI. Called cheaply
     *  from {@code TradeCourierGoal.canUse} — a map lookup per AI poll. */
    public static boolean isStaleJourney(CitizenEntity courier) {
        UUID id = courier.getTradeJourneyId();
        return id != null && !TraderSimManager.hasSession(id);
    }

    public static void clearStale(CitizenEntity courier) {
        endAdoption(courier);
    }
}
