package com.bannerbound.core.barbarian;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ItemKnowledge;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.entity.DropOffContainers;
import com.bannerbound.core.entity.SettlementStorage;
import com.bannerbound.core.network.BarterEntry;
import com.bannerbound.core.network.OpenBarterPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The barter brain: builds a camp's opening offer + the two draw-from pools, and judges/executes a
 * player's submitted offer. Acceptance is purely value-based (so a counter-offer can pay the same worth
 * with different goods): {@code value(youGive) ≥ floor(value(youGet) × margin/100) + tributeFloor}.
 *
 * <p>"Your storage" is the settlement STOCKPILE (not the player's pockets) — gives are withdrawn from it
 * and gets are handed to the player. Margin + tribute floor are derived deterministically from the
 * settlement's wealth and its standing with the camp, so the bar the player saw at open still holds at
 * submit even though the suggested items are only a suggestion.
 */
@ApiStatus.Internal
public final class BarbarianBarter {
    private static final int ACCEPT_BASE = 8;
    private static final int GEN_CAP = 17;       // max bonus relation for a very generous deal
    private static final int GEN_DIV = 3;        // value-over-the-bar per +1 relation
    private static final int REJECT_DELTA = -12; // a lowball the camp refuses
    private static final int DECLINE_TRADE_DELTA = -5;
    private static final int REFUSE_DEMAND_DELTA = -30; // walking away from tribute
    private static final int MAX_STORAGE_ROWS = 40;
    private static final double DEMAND_CAP_FRACTION = 0.5; // never demand/seed more than half a holding

    private BarbarianBarter() {}

    public static boolean isDemand(BarbarianCamp camp) {
        return camp.type != CampType.NOMAD;
    }

    /** How much give-value the camp wants per unit of get-value — friendlier camps cut you a deal. */
    public static int marginPercent(CampRelationState st) {
        return switch (st) {
            case FRIENDLY -> 90;
            case HOSTILE -> 140;
            default -> 110;
        };
    }

    /** The abstract worth of tribute the camp expects (0 for nomads, who trade instead of demand). */
    public static int tributeValue(BarbarianCamp camp, Settlement s) {
        if (!isDemand(camp)) return 0;
        double base = switch (camp.type) {
            case TRIBE -> 18;
            case RAIDER -> 28;
            case MARAUDER -> 40;
            default -> 0;
        };
        double prosperity = Math.min(2.0, 1.0 + s.population() / 24.0 + s.claimedChunks().size() / 40.0);
        double relMult = switch (camp.relationToward(s.id())) {
            case HOSTILE -> 1.3;
            case FRIENDLY -> 0.75;
            default -> 1.0;
        };
        return Math.max(1, (int) Math.round(base * prosperity * relMult));
    }

    public static boolean canDefer(BarbarianCamp camp, Settlement s) {
        return isDemand(camp)
            && camp.type.relationCeiling() != CampRelationState.HOSTILE
            && camp.relationToward(s.id()) != CampRelationState.HOSTILE;
    }

    /** What the camp wants on the table for a deal to clear, given the get-side value. */
    public static int neededGiveValue(BarbarianCamp camp, Settlement s, int getValue) {
        return Math.floorDiv(getValue * marginPercent(camp.relationToward(s.id())), 100)
            + tributeValue(camp, s);
    }

    // ─── Opening offer ──────────────────────────────────────────────────────────────────────────────

    public static OpenBarterPayload open(ServerLevel level, BarbarianCamp camp, Settlement s,
                                         int messengerEntityId, String greetingKey, String flavorItemId) {
        CampRelationState rel = camp.relationToward(s.id());
        boolean demand = isDemand(camp);
        int margin = marginPercent(rel);
        int tribute = tributeValue(camp, s);

        LinkedHashMap<Item, Integer> storage = poolSummary(level, s);
        List<BarterEntry> yourStorage = storageEntries(storage);
        List<BarterEntry> theirGoods = goodsEntries(CampGoods.available(camp, s.id()), s);

        List<BarterEntry> youGive;
        List<BarterEntry> youGet;
        if (demand) {
            youGive = seedDemand(storage, tribute, camp, s, flavorItemId);
            youGet = List.of();
        } else {
            youGet = featuredTrade(theirGoods);
            youGive = fillValue(storage, Math.floorDiv(totalValue(youGet) * margin, 100));
        }

        Integer color = camp.type.nameColor().getColor();
        return new OpenBarterPayload(messengerEntityId, camp.name, color == null ? 0xFFFFFF : color,
            camp.type.englishName(), greetingKey, rel.ordinal(), demand, canDefer(camp, s),
            tribute, margin, youGive, youGet, yourStorage, theirGoods);
    }

    /** A fresh storage snapshot (+ the camp's goods) for the live-update poll while a screen is open. */
    public static List<BarterEntry> liveStorage(ServerLevel level, Settlement s) {
        return storageEntries(poolSummary(level, s));
    }

    public static List<BarterEntry> liveGoods(BarbarianCamp camp, Settlement s) {
        return goodsEntries(CampGoods.available(camp, s.id()), s);
    }

    // ─── Evaluation ───────────────────────────────────────────────────────────────────────────────

    public enum Outcome { ACCEPTED, REJECTED, CANT_PAY, INVALID }

    public static final class Result {
        public final Outcome outcome;
        public final int relationDelta;
        public Result(Outcome outcome, int relationDelta) {
            this.outcome = outcome;
            this.relationDelta = relationDelta;
        }
    }

    /**
     * Validate + (if it clears) execute a submitted offer against the live world. Withdraws gives from
     * the stockpile and hands gets to the player only on {@link Outcome#ACCEPTED}. Returns the outcome +
     * the relationship delta to apply (caller persists it so this stays free of manager coupling).
     */
    public static Result propose(ServerLevel level, ServerPlayer player, BarbarianCamp camp, Settlement s,
                                 List<BarterEntry> youGive, List<BarterEntry> youGet) {
        // Gets must be things the camp has on offer AND the civ comprehends (mirrors the display filter).
        Map<String, Integer> offered = new java.util.HashMap<>();
        for (CampGoods.Stock st : CampGoods.available(camp, s.id())) {
            if (!ItemKnowledge.isKnown(s, item(st.itemId()))) continue;
            offered.merge(st.itemId(), st.count(), Integer::sum);
        }
        int getValue = 0;
        for (BarterEntry e : youGet) {
            if (e.count() <= 0) continue;
            if (e.count() > offered.getOrDefault(e.itemId(), 0)) return new Result(Outcome.INVALID, 0);
            getValue += ItemValue.value(e.itemId(), e.count());
        }
        // Gives must physically exist in the stockpile.
        int giveValue = 0;
        for (BarterEntry e : youGive) {
            if (e.count() <= 0) continue;
            Item item = item(e.itemId());
            if (item == Items.AIR) return new Result(Outcome.INVALID, 0);
            if (poolCount(level, s, item) < e.count()) return new Result(Outcome.CANT_PAY, 0);
            giveValue += ItemValue.value(e.itemId(), e.count());
        }

        if (giveValue == 0 && getValue == 0) return new Result(Outcome.INVALID, 0); // empty "deal"

        int needed = neededGiveValue(camp, s, getValue);
        if (giveValue < needed) {
            return new Result(Outcome.REJECTED, REJECT_DELTA);
        }

        // Clears — move the goods. Withdraw ALL gives first; if the pool comes up short (the
        // feasibility count raced a worker, or storage changed under the screen), put back what
        // was taken and fail as CANT_PAY rather than granting the reward for a partial payment.
        List<ItemStack> withdrawn = new ArrayList<>();
        for (BarterEntry e : youGive) {
            if (e.count() <= 0) continue;
            Item item = item(e.itemId());
            int removed = poolWithdraw(level, s, item, e.count());
            if (removed > 0) withdrawn.add(new ItemStack(item, removed));
            if (removed < e.count()) {
                Container c = pool(level, s);
                for (ItemStack st : withdrawn) {
                    ItemStack leftover = c == null ? st : DropOffContainers.insert(c, st);
                    if (!leftover.isEmpty()) player.drop(leftover, false);
                }
                return new Result(Outcome.CANT_PAY, 0);
            }
        }
        for (BarterEntry e : youGet) {
            if (e.count() <= 0) continue;
            ItemStack reward = new ItemStack(item(e.itemId()), e.count());
            if (!player.addItem(reward)) player.drop(reward, false);
        }
        int generosity = giveValue - needed;
        int delta = ACCEPT_BASE + Math.min(GEN_CAP, generosity / GEN_DIV);
        return new Result(Outcome.ACCEPTED, delta);
    }

    /** Relation hit for walking away — refusing tribute is serious; leaving a trade is minor. */
    public static int declineDelta(BarbarianCamp camp) {
        return isDemand(camp) ? REFUSE_DEMAND_DELTA : DECLINE_TRADE_DELTA;
    }

    // ─── Builders ───────────────────────────────────────────────────────────────────────────────────

    private static List<BarterEntry> storageEntries(LinkedHashMap<Item, Integer> storage) {
        List<BarterEntry> rows = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : storage.entrySet()) {
            int unit = ItemValue.unitValue(e.getKey());
            if (unit <= 0 || e.getValue() <= 0) continue;
            rows.add(new BarterEntry(id(e.getKey()), e.getValue(), unit));
        }
        rows.sort(Comparator.comparingInt(BarterEntry::count).reversed());
        return rows.size() > MAX_STORAGE_ROWS ? new ArrayList<>(rows.subList(0, MAX_STORAGE_ROWS)) : rows;
    }

    private static List<BarterEntry> goodsEntries(List<CampGoods.Stock> goods, Settlement s) {
        List<BarterEntry> rows = new ArrayList<>();
        for (CampGoods.Stock st : goods) {
            Item item = item(st.itemId());
            // Only deal in goods the civ actually comprehends — no "?" unknown items on the table.
            if (item == Items.AIR || st.count() <= 0 || !ItemKnowledge.isKnown(s, item)) continue;
            int unit = ItemValue.unitValue(item);
            if (unit <= 0) continue;
            rows.add(new BarterEntry(st.itemId(), st.count(), unit));
        }
        return rows;
    }

    /** The camp's headline trade good: its single most valuable stock, a few units of it. */
    private static List<BarterEntry> featuredTrade(List<BarterEntry> theirGoods) {
        BarterEntry best = null;
        for (BarterEntry e : theirGoods) {
            if (best == null || e.unitValue() > best.unitValue()) best = e;
        }
        if (best == null) return List.of();
        return List.of(new BarterEntry(best.itemId(), Math.min(best.count(), 4), best.unitValue()));
    }

    /** Seed a tribute demand from what the settlement actually holds (fair), topping up with a flavour
     *  "fetch" item if it can't be met from storage and the camp will grant grace. */
    private static List<BarterEntry> seedDemand(LinkedHashMap<Item, Integer> storage, int targetValue,
                                                BarbarianCamp camp, Settlement s, String flavorItemId) {
        List<BarterEntry> out = new ArrayList<>();
        int got = fillInto(out, storage, targetValue);
        if (got < targetValue && canDefer(camp, s)) {
            Item flavor = item(flavorItemId);
            if (flavor == Items.AIR || !ItemKnowledge.isKnown(s, flavor)) flavor = Items.WHEAT;
            // Only ask them to fetch something the civ understands; otherwise just take what's in storage.
            if (ItemKnowledge.isKnown(s, flavor)) {
                int unit = Math.max(1, ItemValue.unitValue(flavor));
                int need = (int) Math.ceil((targetValue - got) / (double) unit);
                if (need > 0) out.add(new BarterEntry(id(flavor), need, unit));
            }
        }
        return out.isEmpty() ? List.of() : out;
    }

    /** Greedily pick items worth up to {@code targetValue}, biggest holdings first (bulk staples), each
     *  capped to a fraction of the stack so the town isn't stripped bare. Returns a fresh list. */
    private static List<BarterEntry> fillValue(LinkedHashMap<Item, Integer> storage, int targetValue) {
        List<BarterEntry> out = new ArrayList<>();
        fillInto(out, storage, targetValue);
        return out;
    }

    private static int fillInto(List<BarterEntry> out, LinkedHashMap<Item, Integer> storage, int target) {
        if (target <= 0) return 0;
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(storage.entrySet());
        sorted.sort(Comparator.<Map.Entry<Item, Integer>>comparingInt(Map.Entry::getValue).reversed());
        int acc = 0;
        for (Map.Entry<Item, Integer> e : sorted) {
            if (acc >= target) break;
            int unit = ItemValue.unitValue(e.getKey());
            if (unit <= 0) continue;
            int cap = Math.max(1, (int) (e.getValue() * DEMAND_CAP_FRACTION));
            int want = Math.min(cap, (int) Math.ceil((target - acc) / (double) unit));
            if (want <= 0) continue;
            out.add(new BarterEntry(id(e.getKey()), want, unit));
            acc += unit * want;
        }
        return acc;
    }

    private static int totalValue(List<BarterEntry> list) {
        int v = 0;
        for (BarterEntry e : list) v += e.totalValue();
        return v;
    }

    // ─── Settlement storage POOL (open stockpiles + loose baskets/chests, not just enclosures) ────────

    private static Container pool(ServerLevel level, Settlement s) {
        BlockPos near = s.hasTownHall() ? s.townHallPos() : s.bannerPos();
        return SettlementStorage.supplyAggregate(level, s, near == null ? BlockPos.ZERO : near);
    }

    private static LinkedHashMap<Item, Integer> poolSummary(ServerLevel level, Settlement s) {
        LinkedHashMap<Item, Integer> out = new LinkedHashMap<>();
        Container c = pool(level, s);
        if (c == null) return out;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            if (!st.isEmpty()) out.merge(st.getItem(), st.getCount(), Integer::sum);
        }
        return out;
    }

    private static int poolCount(ServerLevel level, Settlement s, Item item) {
        Container c = pool(level, s);
        if (c == null) return 0;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            if (st.is(item)) n += st.getCount();
        }
        return n;
    }

    /** Withdraws up to {@code count} of {@code item} from the pool, returning the amount actually
     *  removed so the caller can detect (and roll back) a short payment. */
    private static int poolWithdraw(ServerLevel level, Settlement s, Item item, int count) {
        Container c = pool(level, s);
        if (c == null) return 0;
        return DropOffContainers.extract(c, item, count).getCount();
    }

    private static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
