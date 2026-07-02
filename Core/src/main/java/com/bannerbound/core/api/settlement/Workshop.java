package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bannerbound.core.api.workshop.WorkBlockRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * A Workshop — a player-selected, enclosed crafting building worked by crafter citizens (see
 * {@code CRAFTER_PLAN.md}). The box geometry lives in {@code BlockSelectionRegistry} (kind
 * WORKSHOP, keyed by {@link #id()}), exactly like homes; this record holds everything else:
 * identity, custom name, derived type, validation status + caches, assigned workers and the
 * min-stock map. Stored in {@code Settlement.workshops}, persisted with the settlement NBT.
 *
 * <p>Workshops have NO anchor block — the Workshop Orders rod binds by id and validation runs on
 * commit / menu open / when a crafter looks for work (not from a block entity).
 */
public final class Workshop {

    /** Validation outcome, surfaced verbatim in the workshop menu (specific, never silent).
     *  Workshops deliberately do NOT require enclosure (unlike houses) — open-air smithy-porch
     *  builds are legitimate workplaces. What they require instead is REACHABILITY: citizens must
     *  be able to walk to a work block and to storage (doors/gates/non-colliding blocks pass;
     *  floating or walled-off fails). Persisted by ordinal — append new values, never reorder. */
    public enum Status {
        /** ≥1 reachable work block, ≥1 reachable storage block. */
        VALID,
        /** No boxes committed yet (freshly created, shouldn't normally persist). */
        UNMARKED,
        /** Marked solids are not one connected shell. */
        DISCONNECTED,
        /** Legacy (enclosure requirement dropped) — kept so saved ordinals stay stable. */
        NOT_ENCLOSED,
        /** No registered work block inside the union. */
        NO_WORK_BLOCK,
        /** No {@code #bannerbound:workshop_storage} block inside the union. */
        NO_STORAGE,
        /** Work block(s) exist but citizens can't path to any of them. */
        WORK_BLOCK_UNREACHABLE,
        /** Storage exists but citizens can't path to any of it. */
        STORAGE_UNREACHABLE,
        /** A work block's standing spots exist but lack headroom — the roof is too low. */
        NO_HEADROOM,
        /** A type-specific rule needs a furnace, kiln, or other heat source inside. */
        MISSING_HEAT_SOURCE,
        /** A type-specific rule needs a crafting surface inside. */
        MISSING_CRAFTING_SURFACE,
        /** A type-specific rule needs a required tool stored inside (e.g. a saw for carpentry). */
        MISSING_TOOL,
        /** A type-specific rule needs a clay tank inside to cure (the tannery). */
        MISSING_CURING_LIQUID;

        public static Status fromOrdinalOrDefault(int ord) {
            Status[] v = values();
            return (ord >= 0 && ord < v.length) ? v[ord] : UNMARKED;
        }
    }

    private final UUID id;
    /** Player-chosen name; empty = display the derived type instead. A rename sticks even when
     *  the derived type changes. */
    private String customName = "";
    /** Derived from the contained work blocks on every validation (a registered typeId,
     *  {@link WorkBlockRegistry#TYPE_MIXED} or {@link WorkBlockRegistry#TYPE_NONE}). */
    private String derivedTypeId = WorkBlockRegistry.TYPE_NONE;
    private Status status = Status.UNMARKED;
    /** Cached work-block positions from the last validation (capacity = size). */
    private final List<BlockPos> workBlocks = new ArrayList<>();
    /** Cached storage-block positions from the last validation. */
    private final List<BlockPos> storageBlocks = new ArrayList<>();
    /** Citizens assigned to work here (≤ capacity). */
    private final List<UUID> workers = new ArrayList<>();
    /** Min-stock rows: item registry id → settlement-wide minimum (Phase 3 consumes this). */
    private final Map<String, Integer> minStock = new LinkedHashMap<>();
    /** Explicit order queue: item registry id → remaining count, in queue order (insertion-ordered;
     *  queuing more of an already-queued item merges into its existing slot). Orders OUTRANK the
     *  min-stock governor and ignore it — a queued item crafts even when not configured / not in
     *  deficit. An order whose ingredients are missing is skipped, never blocking the queue. */
    private final Map<String, Integer> orders = new LinkedHashMap<>();
    /** DERIVED orders the production chain queued here (a fletchery needed plant string none
     *  existed of, so the general-crafts stone gets an auto order). Kept separate from the
     *  player's {@link #orders} so the chain can revoke its own orders when the need that
     *  created them disappears, without ever touching what the player queued. Crafted AFTER
     *  player orders. {@link #autoOrderSources} remembers WHY (the requesting workshop). */
    private final Map<String, Integer> autoOrders = new LinkedHashMap<>();
    /** item registry id → requesting workshop UUID string (the "why" of each auto order). */
    private final Map<String, String> autoOrderSources = new LinkedHashMap<>();
    /** Per-worker station position: citizen UUID → the workshop type id of the station family
     *  they work EXCLUSIVELY (self-assigned on first claim in a mixed workshop). One worker =
     *  one profession, so experience accumulates in a single bucket instead of smearing across
     *  whatever station happens to be free. */
    private final Map<UUID, String> positions = new LinkedHashMap<>();
    /** Game tick of the last full validation (transient — the reachability BFS is too heavy to
     *  run on every crafter think-tick; {@code Workshops.validateCached} throttles on this). */
    private transient long lastValidatedTick = Long.MIN_VALUE;
    /** Cached appeal score of the box union (workplace appeal: pretty workshops make happier,
     *  faster-learning workers). Refreshed on every validation — same blocks-walk cadence the
     *  status caches already pay for. */
    private double cachedAppealScore;
    /** Cached beauty tier matching {@link #cachedAppealScore}. Null until first scored. */
    private ChunkBeauty cachedAppealBeauty;
    /** Lifetime count of output ITEMS this workshop has crafted (persisted statistic for the Stats tab). */
    private long itemsProduced = 0L;
    /** Smoothed output RATE (items/sec) + last cumulative snapshot. Transient — rebuilt by {@link #tickStats}. */
    private transient double outputRate = 0.0;
    private transient long lastProducedSnapshot = 0L;
    private static final double OUTPUT_RATE_ALPHA = 0.1; // EMA smoothing (~10s at 1 Hz), matches Settlement

    public long lastValidatedTick() {
        return lastValidatedTick;
    }

    public void setLastValidatedTick(long tick) {
        this.lastValidatedTick = tick;
    }

    public Workshop(UUID id) {
        this.id = id;
    }

    public UUID id() {
        return id;
    }

    public String customName() {
        return customName;
    }

    /** Sets the player-chosen name ({@code ""} resets to the derived-type display). */
    public void setCustomName(String name) {
        this.customName = name == null ? "" : name;
    }

    public String derivedTypeId() {
        return derivedTypeId;
    }

    public Status status() {
        return status;
    }

    public List<BlockPos> workBlocks() {
        return workBlocks;
    }

    public List<BlockPos> storageBlocks() {
        return storageBlocks;
    }

    /** Max assigned workers — one per work block. */
    public int capacity() {
        return workBlocks.size();
    }

    public List<UUID> workers() {
        return workers;
    }

    public Map<String, Integer> minStock() {
        return minStock;
    }

    /** The explicit order queue (live view): item registry id → remaining, in queue order. */
    public Map<String, Integer> orders() {
        return orders;
    }

    /** The chain-derived order queue (live view): item registry id → remaining. */
    public Map<String, Integer> autoOrders() {
        return autoOrders;
    }

    /** The "why" of each auto order: item registry id → requesting workshop UUID string. */
    public Map<String, String> autoOrderSources() {
        return autoOrderSources;
    }

    /** Counts one finished craft of {@code itemId} against the queues — player orders first,
     *  then chain-derived auto orders; rows drop at zero. Returns true iff an order was consumed
     *  (caller marks data dirty). Deliberately matches ANY craft of the item — a min-stock
     *  fallback craft of the same output fulfils the order just as well. */
    public boolean fulfillOrder(String itemId) {
        return fulfillOrder(itemId, 1);
    }

    public boolean fulfillOrder(String itemId, int count) {
        int remainingCount = Math.max(1, count);
        boolean fulfilled = false;
        Integer remaining = orders.get(itemId);
        if (remaining != null) {
            if (remaining <= remainingCount) {
                orders.remove(itemId);
                remainingCount -= remaining;
            } else {
                orders.put(itemId, remaining - remainingCount);
                remainingCount = 0;
            }
            fulfilled = true;
        }
        if (remainingCount <= 0) return fulfilled;
        Integer auto = autoOrders.get(itemId);
        if (auto != null) {
            if (auto <= remainingCount) {
                autoOrders.remove(itemId);
                autoOrderSources.remove(itemId);
            } else {
                autoOrders.put(itemId, auto - remainingCount);
            }
            fulfilled = true;
        }
        return fulfilled;
    }

    /** Credit {@code count} crafted output items toward this workshop's lifetime throughput stat.
     *  Called at every craft completion (order or min-stock alike). Cumulative; never decreases. */
    public void recordCraftOutput(int count) {
        if (count > 0) itemsProduced += count;
    }

    /** Lifetime output items crafted here. */
    public long itemsProduced() { return itemsProduced; }

    /** Smoothed output rate (items/sec) right now (0 if idle). */
    public double outputRatePerSecond() { return outputRate; }

    /** Total queued work outstanding (player orders + chain auto-orders). */
    public int pendingOrders() {
        int total = 0;
        for (int n : orders.values()) total += n;
        for (int n : autoOrders.values()) total += n;
        return total;
    }

    /** Once-a-second EMA update of the output rate from the cumulative counter. Derived stat, not persisted. */
    public void tickStats() {
        double inst = Math.max(0.0, itemsProduced - lastProducedSnapshot);
        outputRate += OUTPUT_RATE_ALPHA * (inst - outputRate);
        lastProducedSnapshot = itemsProduced;
    }

    /** Per-worker station position (see {@link #positions}): the work-block type id this citizen
     *  is locked to, or null when they haven't self-assigned yet. */
    public String positionOf(UUID citizenId) {
        return positions.get(citizenId);
    }

    public void setPosition(UUID citizenId, String workshopTypeId) {
        positions.put(citizenId, workshopTypeId);
    }

    public void clearPosition(UUID citizenId) {
        positions.remove(citizenId);
    }

    /** Drops positions of citizens no longer on the worker roster (call after reconciling). */
    public void prunePositions() {
        positions.keySet().retainAll(workers);
    }

    public double cachedAppealScore() {
        return cachedAppealScore;
    }

    /** Beauty tier of the last appeal scoring, or null if never scored (e.g. unmarked). */
    public ChunkBeauty cachedAppealBeauty() {
        return cachedAppealBeauty;
    }

    public void setCachedAppeal(double score, ChunkBeauty beauty) {
        this.cachedAppealScore = score;
        this.cachedAppealBeauty = beauty;
    }

    /** Applies a validation result (status + fresh caches + re-derived type). */
    public void applyValidation(Status newStatus, List<BlockPos> newWorkBlocks,
                                List<BlockPos> newStorageBlocks, String newDerivedTypeId) {
        this.status = newStatus;
        this.workBlocks.clear();
        this.workBlocks.addAll(newWorkBlocks);
        this.storageBlocks.clear();
        this.storageBlocks.addAll(newStorageBlocks);
        this.derivedTypeId = newDerivedTypeId;
    }

    // ─── NBT ────────────────────────────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        if (!customName.isEmpty()) tag.putString("Name", customName);
        tag.putString("DerivedType", derivedTypeId);
        tag.putInt("Status", status.ordinal());
        tag.put("WorkBlocks", savePosList(workBlocks));
        tag.put("StorageBlocks", savePosList(storageBlocks));
        if (!workers.isEmpty()) {
            ListTag list = new ListTag();
            for (UUID w : workers) list.add(NbtUtils.createUUID(w));
            tag.put("Workers", list);
        }
        if (!minStock.isEmpty()) {
            CompoundTag ms = new CompoundTag();
            for (Map.Entry<String, Integer> e : minStock.entrySet()) ms.putInt(e.getKey(), e.getValue());
            tag.put("MinStock", ms);
        }
        // Appeal cache persisted (like Home's) so post-load reads don't wait for a revalidation.
        tag.putDouble("Appeal", cachedAppealScore);
        if (cachedAppealBeauty != null) tag.putInt("AppealTier", cachedAppealBeauty.ordinal());
        if (itemsProduced > 0L) tag.putLong("ItemsProduced", itemsProduced);
        if (!orders.isEmpty()) {
            // LinkedHashMap iteration order IS the queue order; a ListTag of compounds keeps it
            // (a CompoundTag like MinStock's would alphabetise on reload).
            ListTag orderList = new ListTag();
            for (Map.Entry<String, Integer> e : orders.entrySet()) {
                CompoundTag row = new CompoundTag();
                row.putString("Item", e.getKey());
                row.putInt("Count", e.getValue());
                orderList.add(row);
            }
            tag.put("Orders", orderList);
        }
        if (!autoOrders.isEmpty()) {
            ListTag autoList = new ListTag();
            for (Map.Entry<String, Integer> e : autoOrders.entrySet()) {
                CompoundTag row = new CompoundTag();
                row.putString("Item", e.getKey());
                row.putInt("Count", e.getValue());
                String src = autoOrderSources.get(e.getKey());
                if (src != null) row.putString("Source", src);
                autoList.add(row);
            }
            tag.put("AutoOrders", autoList);
        }
        if (!positions.isEmpty()) {
            ListTag posList = new ListTag();
            for (Map.Entry<UUID, String> e : positions.entrySet()) {
                CompoundTag row = new CompoundTag();
                row.putUUID("Worker", e.getKey());
                row.putString("Type", e.getValue());
                posList.add(row);
            }
            tag.put("Positions", posList);
        }
        return tag;
    }

    public static Workshop load(CompoundTag tag) {
        Workshop w = new Workshop(tag.getUUID("Id"));
        w.customName = tag.getString("Name");
        w.derivedTypeId = tag.contains("DerivedType")
            ? tag.getString("DerivedType") : WorkBlockRegistry.TYPE_NONE;
        w.status = Status.fromOrdinalOrDefault(tag.getInt("Status"));
        w.itemsProduced = tag.getLong("ItemsProduced");
        w.lastProducedSnapshot = w.itemsProduced; // avoid a false burst on first tick after load
        loadPosList(tag, "WorkBlocks", w.workBlocks);
        loadPosList(tag, "StorageBlocks", w.storageBlocks);
        if (tag.contains("Workers")) {
            for (Tag t : tag.getList("Workers", Tag.TAG_INT_ARRAY)) {
                w.workers.add(NbtUtils.loadUUID(t));
            }
        }
        if (tag.contains("MinStock")) {
            CompoundTag ms = tag.getCompound("MinStock");
            for (String key : ms.getAllKeys()) w.minStock.put(key, ms.getInt(key));
        }
        if (tag.contains("Appeal")) w.cachedAppealScore = tag.getDouble("Appeal");
        if (tag.contains("AppealTier")) {
            int ord = tag.getInt("AppealTier");
            ChunkBeauty[] v = ChunkBeauty.values();
            if (ord >= 0 && ord < v.length) w.cachedAppealBeauty = v[ord];
        }
        if (tag.contains("Orders")) {
            ListTag orderList = tag.getList("Orders", Tag.TAG_COMPOUND);
            for (int i = 0; i < orderList.size(); i++) {
                CompoundTag row = orderList.getCompound(i);
                int count = row.getInt("Count");
                if (count > 0) w.orders.put(row.getString("Item"), count);
            }
        }
        if (tag.contains("AutoOrders")) {
            ListTag autoList = tag.getList("AutoOrders", Tag.TAG_COMPOUND);
            for (int i = 0; i < autoList.size(); i++) {
                CompoundTag row = autoList.getCompound(i);
                int count = row.getInt("Count");
                if (count <= 0) continue;
                w.autoOrders.put(row.getString("Item"), count);
                if (row.contains("Source")) {
                    w.autoOrderSources.put(row.getString("Item"), row.getString("Source"));
                }
            }
        }
        if (tag.contains("Positions")) {
            ListTag posList = tag.getList("Positions", Tag.TAG_COMPOUND);
            for (int i = 0; i < posList.size(); i++) {
                CompoundTag row = posList.getCompound(i);
                if (row.hasUUID("Worker")) {
                    w.positions.put(row.getUUID("Worker"), row.getString("Type"));
                }
            }
        }
        return w;
    }

    // Positions stored as packed longs (BlockPos.asLong) — compact, and immune to the
    // writeBlockPos tag-format drift that silently loaded the cached lists back EMPTY (the
    // "capacity 1/0 after relog" bug: IntArrayTag elements read with TAG_COMPOUND = no rows).
    private static net.minecraft.nbt.LongArrayTag savePosList(List<BlockPos> list) {
        long[] packed = new long[list.size()];
        for (int i = 0; i < list.size(); i++) packed[i] = list.get(i).asLong();
        return new net.minecraft.nbt.LongArrayTag(packed);
    }

    private static void loadPosList(CompoundTag tag, String key, List<BlockPos> out) {
        for (long packed : tag.getLongArray(key)) {
            out.add(BlockPos.of(packed));
        }
    }
}
