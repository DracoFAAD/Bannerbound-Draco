package com.bannerbound.core.trade;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Top-level {@link SavedData} for settlement-to-settlement trade deals. A deal spans TWO parties, so
 * it lives here (one home, one owner) rather than on either {@code Settlement}. Attached to the
 * <b>overworld</b>; call {@link #get(ServerLevel)} server-side. Mutators call {@link #setDirty()}.
 *
 * <p>Resolved deals are kept briefly for the record, then swept by {@code TradeManager} once stale
 * (no history UI yet — the map stays small).
 */
public class TradeData extends SavedData {
    private static final String DATA_NAME = "bannerbound_trades";

    private final Map<UUID, TradeDeal> deals = new HashMap<>();

    public TradeData() {
    }

    public static TradeData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<TradeData> factory() {
        return new Factory<>(TradeData::new, TradeData::load);
    }

    public Collection<TradeDeal> all() {
        return Collections.unmodifiableCollection(deals.values());
    }

    @Nullable
    public TradeDeal getById(UUID id) {
        return id == null ? null : deals.get(id);
    }

    public void add(TradeDeal deal) {
        deals.put(deal.id, deal);
        setDirty();
    }

    public void remove(UUID id) {
        deals.remove(id);
        setDirty();
    }

    /** The single ACTIVE deal between this pair (order-independent), or null. */
    @Nullable
    public TradeDeal activeBetween(UUID a, UUID b) {
        for (TradeDeal d : deals.values()) {
            if (d.state.active() && d.involves(a) && d.involves(b)) return d;
        }
        return null;
    }

    /** Every active deal this settlement participates in. */
    public List<TradeDeal> activeFor(UUID settlementId) {
        List<TradeDeal> out = new ArrayList<>();
        for (TradeDeal d : deals.values()) {
            if (d.state.active() && d.involves(settlementId)) out.add(d);
        }
        return out;
    }

    /** Active deals where it's {@code settlementId}'s turn to respond and the terms are unread —
     *  drives the Diplomacy-tab badge. */
    public int unreadCountFor(UUID settlementId, UUID withPartner) {
        TradeDeal d = activeBetween(settlementId, withPartner);
        return d != null && d.unreadForAwaiting
            && settlementId.equals(d.awaitingParty)
            && (d.state == TradeDeal.State.PROPOSED || d.state == TradeDeal.State.COUNTERED) ? 1 : 0;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (TradeDeal d : deals.values()) list.add(d.save());
        tag.put("Deals", list);
        return tag;
    }

    public static TradeData load(CompoundTag tag, HolderLookup.Provider provider) {
        TradeData data = new TradeData();
        ListTag list = tag.getList("Deals", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            TradeDeal d = TradeDeal.load(list.getCompound(i));
            if (d != null) data.deals.put(d.id, d);
        }
        return data;
    }
}
