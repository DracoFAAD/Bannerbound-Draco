package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Home;
import com.bannerbound.core.api.settlement.Homes;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Stockpile;
import com.bannerbound.core.api.settlement.Workshop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The settlement's worker-usable storage POOL — the replacement for per-worker marked drop-offs.
 * Every stockpile the player left open to workers plus every loose basket/chest in claimed (and
 * working-claimed) chunks. A worker deposits its yield into the nearest pooled container with room
 * and takes inputs/seeds/tools from the nearest pooled container that holds them; nobody marks a
 * container per worker anymore.
 *
 * <p>Access is governed PER STOCKPILE by two toggles ({@link Stockpile#allowWorkerDeposit()} /
 * {@link Stockpile#allowWorkerTake()}, both default open, flipped from the rack terminal). Loose
 * baskets/chests are always open for both — they're overflow buffers, not managed storage. Workshop
 * storage (crafters own it), home pantries, and {@link DropOffContainers#isWildStorage wild}
 * storage are never pooled.
 *
 * <p>Works in anarchy too: a tribe's open baskets/stockpiles are pooled the same way, and an anarchy
 * gatherer falls back to the town-hall carry pack (see {@link CitizenEntity#getAnarchyHaul()}) only
 * when the pool is empty. The member list is cached per settlement for {@link #TTL} ticks so the
 * per-deposit/per-take calls don't rescan chunk block entities; the live container behind each member
 * is still resolved fresh every call, so inventory contents are never stale.
 */
@ApiStatus.Internal
public final class SettlementStorage {
    /** A pooled storage container: its rack/container position and the worker actions it permits. */
    public record Member(BlockPos pos, boolean allowDeposit, boolean allowTake) {
    }

    private record Pool(long stamp, List<Member> members) {
    }

    /** Member-list cache lifetime (ticks). The list (which containers exist + their toggles) is cheap
     *  to reuse for a few seconds; the inventories behind it are resolved live on each query. */
    private static final long TTL = 100;

    private static final Map<UUID, Pool> CACHE = new HashMap<>();

    private SettlementStorage() {
    }

    /** The cached pool members for {@code s}, rescanned when older than {@link #TTL}. */
    public static List<Member> members(ServerLevel sl, Settlement s) {
        Pool p = CACHE.get(s.id());
        long now = sl.getGameTime();
        if (p == null || now - p.stamp() > TTL) {
            p = new Pool(now, scan(sl, s));
            CACHE.put(s.id(), p);
        }
        return p.members();
    }

    /** Drops the cached scan for a settlement — call after a stockpile toggle/placement so the next
     *  worker query sees the change without waiting out the {@link #TTL}. */
    public static void invalidate(UUID settlementId) {
        CACHE.remove(settlementId);
    }

    /** True when at least one pooled container accepts worker deposits. */
    public static boolean hasDeposit(ServerLevel sl, Settlement s) {
        for (Member m : members(sl, s)) {
            if (m.allowDeposit()) return true;
        }
        return false;
    }

    /** True when at least one pooled container allows workers to take from it. */
    public static boolean hasTake(ServerLevel sl, Settlement s) {
        for (Member m : members(sl, s)) {
            if (m.allowTake()) return true;
        }
        return false;
    }

    /** An aggregate of every DEPOSIT-open container, ordered nearest-to-{@code near} first so an
     *  {@link DropOffContainers#insert} fills the closest container with room. Null when the
     *  settlement has no deposit-open storage. */
    @Nullable
    public static Container depotAggregate(ServerLevel sl, Settlement s, BlockPos near) {
        return aggregate(sl, s, near, true);
    }

    /** An aggregate of every TAKE-open container, nearest first so an {@link DropOffContainers#extract}
     *  pulls from the closest container that holds the item. Null when none. */
    @Nullable
    public static Container supplyAggregate(ServerLevel sl, Settlement s, BlockPos near) {
        return aggregate(sl, s, near, false);
    }

    /** An aggregate over the SHOW-FOR-TRADING stockpiles only — the pool a trade partner sees and
     *  settlement-to-settlement deals draw from / deliver into. Loose containers are never traded.
     *  Null when nothing is flagged (or nothing flagged is currently loaded). */
    @Nullable
    public static Container tradeAggregate(ServerLevel sl, Settlement s) {
        List<Container> parts = new ArrayList<>();
        for (Stockpile sp : s.stockpiles().values()) {
            if (!sp.valid() || !sp.showForTrading() || sp.containers().isEmpty()) continue;
            ChunkPos cp = new ChunkPos(sp.pos());
            if (!sl.hasChunk(cp.x, cp.z)) continue;
            Container c = DropOffContainers.resolveDropOff(sl, sp.pos());
            if (c != null) parts.add(c);
        }
        return parts.isEmpty() ? null : new AggregateContainer(parts);
    }

    /** True when the settlement has flagged at least one valid stockpile for trading — the static
     *  gate for offering a trade at all (independent of whether its chunks are loaded right now). */
    public static boolean hasTradeStockpile(Settlement s) {
        for (Stockpile sp : s.stockpiles().values()) {
            if (sp.valid() && sp.showForTrading() && !sp.containers().isEmpty()) return true;
        }
        return false;
    }

    @Nullable
    private static Container aggregate(ServerLevel sl, Settlement s, BlockPos near, boolean deposit) {
        List<Member> picked = new ArrayList<>();
        for (Member m : members(sl, s)) {
            if (deposit ? m.allowDeposit() : m.allowTake()) picked.add(m);
        }
        if (picked.isEmpty()) return null;
        picked.sort(Comparator.comparingDouble(m -> m.pos().distSqr(near)));
        List<Container> parts = new ArrayList<>();
        for (Member m : picked) {
            Container c = DropOffContainers.resolveDropOff(sl, m.pos());
            if (c != null) parts.add(c);
        }
        return parts.isEmpty() ? null : new AggregateContainer(parts);
    }

    /**
     * Walks the settlement's claimed (+ working-claimed) loaded chunks for pooled storage. Mirrors
     * {@code StockerTasks.collectSources} but tags each member with its worker toggles: valid
     * stockpiles carry their two flags; loose chests/baskets are always open. The containers already
     * enclosed by a stockpile, workshop storage, and home pantries are excluded.
     */
    private static List<Member> scan(ServerLevel sl, Settlement s) {
        Set<BlockPos> excluded = new HashSet<>();
        for (Workshop w : s.workshops().values()) excluded.addAll(w.storageBlocks());
        for (Home h : s.homes().values()) excluded.addAll(Homes.deliverableContainers(sl, h));

        List<Member> out = new ArrayList<>();
        for (Stockpile sp : s.stockpiles().values()) {
            if (!sp.valid() || sp.containers().isEmpty()) continue;
            // The rack pos resolves (via resolveDropOff) to the whole enclosure aggregate; the toggle
            // is stockpile-wide. Exclude the enclosed containers so they aren't also listed loose.
            out.add(new Member(sp.pos(), sp.allowWorkerDeposit(), sp.allowWorkerTake()));
            excluded.addAll(sp.containers());
        }

        List<Long> chunks = new ArrayList<>(s.claimedChunks());
        chunks.addAll(s.workingClaims());
        for (long packed : chunks) {
            ChunkPos cp = new ChunkPos(packed);
            if (!sl.hasChunk(cp.x, cp.z)) continue;
            LevelChunk chunk = sl.getChunk(cp.x, cp.z);
            for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                BlockPos pos = e.getKey();
                if (excluded.contains(pos)) continue;
                if (!(e.getValue() instanceof Container)) continue;
                if (!DropOffContainers.isDropOffBlock(sl, pos)) continue;   // chests + baskets only
                if (DropOffContainers.isWildStorage(sl, pos)) continue;     // not generated-structure loot
                if (DropOffContainers.isSecondaryChestHalf(sl, pos)) continue; // one member per double chest
                out.add(new Member(pos.immutable(), true, true));            // loose = always open
            }
        }
        return out;
    }
}
