package com.bannerbound.core.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Stockpile;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkBlockRegistry;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.entity.DropOffContainers;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The Stocker's settlement task board: an ENQUEUED, shared FIFO of haul orders that stockers
 * claim one at a time. Tasks are <b>derived state</b> — regenerated periodically from the live
 * inventory picture, never persisted — so the board self-heals around anything the player moves
 * by hand, and the queue order (not a stocker's whim) decides what gets hauled first. Claiming
 * one task at a time from the shared queue is what balances the load: a free stocker always
 * takes the oldest open order, so work splits across however many stockers are employed.
 *
 * <p>Two task flows (CRAFTER_PLAN.md's logistics tier):
 * <ul>
 *   <li><b>SUPPLY</b> — a staffed workshop's executors report {@code missingInputs}; the board
 *       finds those items in stockpiles / loose drop-off containers (chests, baskets — never
 *       another workshop's storage) and queues container → workshop hauls.</li>
 *   <li><b>CLEAR</b> — items in a staffed workshop's storage that no currently-wanted craft
 *       needs ({@code retainedItems}) are surplus (finished outputs, dead raws) and get queued
 *       workshop → stockpile. An ingredient of a wanted craft is NEVER hauled out, so a crafter
 *       making plant string for the fletchery's own bows keeps its string; once min-stock is
 *       satisfied and no orders remain, the same string becomes surplus and ships out.</li>
 * </ul>
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class StockerTasks {
    /** Board regen cadence (ticks). Census/scans are cheap and cached; 10 s keeps it responsive. */
    private static final int REGEN_INTERVAL = 200;
    /** A claimed task older than this is presumed orphaned (stocker died / job changed) and dropped. */
    private static final long CLAIM_TIMEOUT_TICKS = 1_200;
    /** Max OPEN (unclaimed) tasks per settlement — the board never floods. */
    private static final int MAX_OPEN_TASKS = 32;
    /** Per-task haul cap: one stack. */
    private static final int MAX_HAUL = 64;
    /** How much of a luxury a stocker delivers to a home pantry — a small stock, since the demand is
     *  satisfied by mere presence and nothing consumes it yet (a market replaces this in the medieval era). */
    private static final int HOME_PANTRY_AMOUNT = 8;

    /** One haul order. Exactly one of {@code sourceWorkshopId}/{@code sourcePos} is set, and
     *  exactly one of {@code destWorkshopId}/{@code destPos} — supply = container→workshop,
     *  clear = workshop→stockpile rack. */
    public static final class Task {
        public final UUID id = UUID.randomUUID();
        /** Dedup key for this haul "lane" (e.g. {@code supply:<ws>:<item>}, {@code home:<home>:<suffix>}).
         *  Stored so the regen's in-flight set recognises a claimed task regardless of its shape. */
        public final String lane;
        @Nullable public final UUID sourceWorkshopId;
        @Nullable public final BlockPos sourcePos;
        @Nullable public final UUID destWorkshopId;
        @Nullable public final BlockPos destPos;
        public final Item item;
        public final int count;
        @Nullable UUID claimedBy;
        long claimTick;

        Task(String lane, @Nullable UUID sourceWorkshopId, @Nullable BlockPos sourcePos,
             @Nullable UUID destWorkshopId, @Nullable BlockPos destPos, Item item, int count) {
            this.lane = lane;
            this.sourceWorkshopId = sourceWorkshopId;
            this.sourcePos = sourcePos;
            this.destWorkshopId = destWorkshopId;
            this.destPos = destPos;
            this.item = item;
            this.count = count;
        }
    }

    private static final Map<UUID, Deque<Task>> BOARDS = new HashMap<>();
    private static int tickCounter;

    private StockerTasks() {
    }

    // ─── Goal API ────────────────────────────────────────────────────────────────────────────

    /** Claims the oldest open task for {@code citizenId}, or null when the board is empty. */
    @Nullable
    public static Task claim(ServerLevel sl, Settlement settlement, UUID citizenId) {
        return claim(sl, settlement, citizenId, t -> true);
    }

    /** Claims the oldest open task that {@code acceptable} passes. Lets a stocker skip hauls whose
     *  endpoints it recently found unreachable WITHOUT hiding them from other stockers (a released
     *  task is regenerated and another stocker — standing somewhere else — may well reach it). */
    @Nullable
    public static synchronized Task claim(ServerLevel sl, Settlement settlement, UUID citizenId,
                                          java.util.function.Predicate<Task> acceptable) {
        Deque<Task> queue = BOARDS.get(settlement.id());
        if (queue == null) return null;
        for (Task t : queue) {
            if (t.claimedBy == null && acceptable.test(t)) {
                t.claimedBy = citizenId;
                t.claimTick = sl.getGameTime();
                return t;
            }
        }
        return null;
    }

    /** Removes a finished task from the board. */
    public static synchronized void complete(Settlement settlement, Task task) {
        Deque<Task> queue = BOARDS.get(settlement.id());
        if (queue != null) queue.remove(task);
    }

    /** Drops a failed/abandoned task — the next regen recreates it if the need still exists. */
    public static synchronized void release(Settlement settlement, Task task) {
        complete(settlement, task);
    }

    /** Who currently claimed this task (a stocker's UUID), or null while it's open. */
    @Nullable
    public static UUID claimedBy(Task task) {
        return task.claimedBy;
    }

    /** A snapshot of the settlement's current board, queue order preserved — drives the
     *  stocker Job tab's task list. Safe to iterate; mutations don't write back. */
    public static synchronized List<Task> snapshot(UUID settlementId) {
        Deque<Task> queue = BOARDS.get(settlementId);
        return queue == null ? List.of() : new ArrayList<>(queue);
    }

    // ─── Board regeneration ──────────────────────────────────────────────────────────────────

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % REGEN_INTERVAL != 0) return;
        ServerLevel sl = event.getServer().overworld();
        for (Settlement s : SettlementData.get(sl).all()) {
            regen(sl, s);
        }
    }

    private static synchronized void regen(ServerLevel sl, Settlement s) {
        Deque<Task> queue = BOARDS.computeIfAbsent(s.id(), k -> new ArrayDeque<>());
        long now = sl.getGameTime();
        // Keep only live claimed tasks; everything open is regenerated from the current state.
        queue.removeIf(t -> t.claimedBy == null || now - t.claimTick > CLAIM_TIMEOUT_TICKS);

        // What's already in flight — don't queue a duplicate haul for the same lane.
        Set<String> inFlight = new HashSet<>();
        for (Task t : queue) inFlight.add(laneKey(t));

        BlockPos stockpileRack = firstUsableStockpile(s);
        // Home pantry chests are destinations, not sources: excluded so the drain pass never empties
        // a home (and a delivered luxury stays put). Map = home → its deliverable container(s).
        Map<com.bannerbound.core.api.settlement.Home, List<BlockPos>> homePantries = collectHomePantries(sl, s);
        Set<BlockPos> homeContainers = new HashSet<>();
        for (List<BlockPos> v : homePantries.values()) homeContainers.addAll(v);
        List<SourceContainer> sources = collectSources(sl, s, stockpileRack, homeContainers);

        // The production chain's CURRENT needs: producer workshop → (item id → wanted count),
        // plus which workshop asked (the "why"). Rebuilt from scratch every regen, then
        // reconciled onto each producer's autoOrders below — an order whose need vanished is
        // revoked (already-completed crafts were decremented away by fulfillOrder and stay made).
        Map<UUID, Map<String, Integer>> chainNeeds = new HashMap<>();
        Map<UUID, Map<String, String>> chainSources = new HashMap<>();

        int open = 0;
        for (Workshop w : s.workshops().values()) {
            if (open >= MAX_OPEN_TASKS) break;
            if (w.status() != Workshop.Status.VALID || w.workers().isEmpty()) continue;

            // Per-workshop wanted/keep view, unioned across its work blocks. `missing` is the
            // BUFFERED haul demand (pre-stock raws); `trueDemand` is the un-buffered production
            // demand used when an input has to be CHAIN-CRAFTED rather than hauled (so a producer
            // makes only what's truly needed — a bow doesn't pull a buffer's worth of string).
            Map<Item, Integer> missing = new LinkedHashMap<>();
            Map<Item, Integer> trueDemand = new LinkedHashMap<>();
            Set<Item> retained = new HashSet<>();
            for (BlockPos p : w.workBlocks()) {
                WorkBlockRegistry.WorkBlockDef def = WorkBlockRegistry.of(sl.getBlockState(p));
                if (def == null || def.executor() == null) continue;
                retained.addAll(def.executor().retainedItems(sl, s, w, p));
                for (ItemStack m : def.executor().missingInputs(sl, s, w, p)) {
                    missing.merge(m.getItem(), m.getCount(), Math::max);
                }
                for (ItemStack m : def.executor().trueInputDemand(sl, s, w, p)) {
                    trueDemand.merge(m.getItem(), m.getCount(), Math::max);
                }
            }

            // SUPPLY: container → workshop for every needed input we can actually find. A needed
            // input NOBODY stocks instead asks the chain: a workshop that can PRODUCE it gets a
            // derived order (the fletcher needs plant string → the general-crafts stone queues
            // plant string). Hauls use the BUFFERED count (pre-stock raws in fewer trips); chain
            // PRODUCTION orders use the TRUE count, so the producer makes only what's needed and a
            // single wanted final doesn't pull a buffer's worth of intermediates.
            Set<Item> needs = new java.util.LinkedHashSet<>(missing.keySet());
            needs.addAll(trueDemand.keySet());
            for (Item item : needs) {
                if (open >= MAX_OPEN_TASKS) break;
                int haul = missing.getOrDefault(item, 0);
                int produce = trueDemand.getOrDefault(item, haul);
                String lane = "supply:" + w.id() + ":" + key(item);
                if (inFlight.contains(lane)) continue;
                SourceContainer src = haul > 0 ? findSourceWith(sl, sources, item) : null;
                if (src == null) {
                    int count = produce > 0 ? produce : haul;
                    if (count <= 0) continue;
                    Workshop producer = findProducer(sl, s, w, item);
                    if (producer != null) {
                        String itemId = key(item);
                        chainNeeds.computeIfAbsent(producer.id(), k -> new LinkedHashMap<>())
                            .merge(itemId, Math.min(count, MAX_HAUL), Integer::sum);
                        chainSources.computeIfAbsent(producer.id(), k -> new LinkedHashMap<>())
                            .putIfAbsent(itemId, w.id().toString());
                    }
                    continue;
                }
                queue.add(new Task(lane, null, src.pos, w.id(), null,
                    item, Math.min(haul, MAX_HAUL)));
                inFlight.add(lane);
                open++;
            }

            // CLEAR: workshop → stockpile (or the item's role tool depot) for storage items no
            // wanted craft retains — a crafted-to-order bone spear ships straight to the
            // weapons depot the hunter equips from.
            for (Map.Entry<Item, Integer> e : storageCounts(sl, w).entrySet()) {
                if (open >= MAX_OPEN_TASKS) break;
                if (retained.contains(e.getKey())) continue;
                BlockPos dest = stockpileRack;
                if (dest == null) continue;
                String lane = "clear:" + w.id() + ":" + key(e.getKey());
                if (inFlight.contains(lane)) continue;
                queue.add(new Task(lane, w.id(), null, null, dest,
                    e.getKey(), Math.min(e.getValue(), MAX_HAUL)));
                inFlight.add(lane);
                open++;
            }
        }

        // TOOL NEEDS: a worker with a tool-using job and no tool registers a need for its role.
        // Tools are pooled now — a toolless worker equips itself from the nearest take-open
        // container (JobTools.supplyPool), so if any allowed tool already sits in settlement stock
        // there's nothing to haul. Only when NONE exists anywhere does the chain order one crafted
        // (it lands in the stockpile via CLEAR, where the worker picks it up). Revoked like any auto
        // order once the worker is equipped. Government-only — anarchy has no stockers.
        if (s.governmentType() != Settlement.Government.NONE) {
            Map<String, Integer> roleNeeds = new LinkedHashMap<>();
            Map<String, UUID> roleNeedSource = new LinkedHashMap<>();
            for (com.bannerbound.core.api.settlement.Citizen c : s.citizens()) {
                if (!(sl.getEntity(c.entityId())
                        instanceof com.bannerbound.core.entity.CitizenEntity ce)) continue;
                if (ce.getJobType() == null || ce.hasJobTool()) continue;
                String role = com.bannerbound.core.social.JobIcons.roleForJob(ce.getJobType());
                if (role == null) continue;
                if (com.bannerbound.core.entity.JobTools.allowedToolsFor(s, role).isEmpty()) continue;
                roleNeeds.merge(role, 1, Integer::sum);
                roleNeedSource.putIfAbsent(role, ce.getUUID());
            }
            for (Map.Entry<String, Integer> need : roleNeeds.entrySet()) {
                if (open >= MAX_OPEN_TASKS) break;
                String role = need.getKey();
                List<Item> allowed = com.bannerbound.core.entity.JobTools.allowedToolsFor(s, role);
                // A valid tool already sits in pooled stock → the worker self-equips, nothing to do.
                boolean exists = false;
                for (Item t : allowed) {
                    if (findSourceWith(sl, sources, t) != null) { exists = true; break; }
                }
                if (exists) continue;
                // Nowhere at all → ask the chain to CRAFT one (first producible allowed tool).
                for (Item t : allowed) {
                    Workshop producer = findProducer(sl, s, null, t);
                    if (producer == null) continue;
                    String itemId = key(t);
                    chainNeeds.computeIfAbsent(producer.id(), k -> new LinkedHashMap<>())
                        .merge(itemId, need.getValue(), Integer::sum);
                    chainSources.computeIfAbsent(producer.id(), k -> new LinkedHashMap<>())
                        .putIfAbsent(itemId, roleNeedSource.get(role).toString());
                    break;
                }
            }
        }

        // HOME SUPPLY: stock each home's pantry with the LUXURIES it demands (cooked food, charcoal…).
        // Home chests are excluded from sources/drain above, so a delivered luxury stays put and keeps
        // the demand satisfied. Government-only — anarchy has no stockers, so don't clutter the board.
        if (s.governmentType() != Settlement.Government.NONE) {
            for (Map.Entry<com.bannerbound.core.api.settlement.Home, List<BlockPos>> e : homePantries.entrySet()) {
                if (open >= MAX_OPEN_TASKS) break;
                com.bannerbound.core.api.settlement.Home home = e.getKey();
                if (home.status() != com.bannerbound.core.api.settlement.Home.Status.VALID) continue;
                BlockPos dest = e.getValue().get(0);
                for (com.bannerbound.core.api.settlement.HomeDemand.DemandState d : home.cachedDemands()) {
                    if (open >= MAX_OPEN_TASKS) break;
                    if (!d.demand().isLuxury() || d.met()) continue;
                    String lane = "home:" + home.id() + ":" + d.demand().suffix();
                    if (inFlight.contains(lane)) continue;
                    TaggedSource ts = findSourceWithTag(sl, sources, d.demand().luxuryTag());
                    if (ts == null) continue; // nothing in settlement stock to deliver yet
                    queue.add(new Task(lane, null, ts.src().pos(), null, dest, ts.item(), HOME_PANTRY_AMOUNT));
                    inFlight.add(lane);
                    open++;
                }
            }
        }

        // DRAIN: empty EVERY loose drop-off container (gatherer baskets, chests, outpost chests)
        // into the stockpile — completely. One task per container per regen (its biggest stack),
        // repeating until the container is bare. No fill threshold: the stockpile IS the
        // settlement's inventory and baskets are buffers, not storage (user decision — earlier
        // "drain only when ⅔/half full" rules read as "stockers ignore baskets").
        // Queued after supply/clear, so feeding workshops wins.
        if (stockpileRack != null) {
            for (SourceContainer src : sources) {
                if (open >= MAX_OPEN_TASKS) break;
                if (src.stockpile()) continue;
                net.minecraft.world.Container c =
                    com.bannerbound.core.entity.DropOffContainers.resolveDropOff(sl, src.pos());
                if (c == null) continue;
                Item biggest = null;
                int biggestCount = 0;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack stack = c.getItem(i);
                    if (!stack.isEmpty() && stack.getCount() > biggestCount) {
                        biggest = stack.getItem();
                        biggestCount = stack.getCount();
                    }
                }
                if (biggest == null) continue;
                BlockPos dest = stockpileRack;
                if (dest.equals(src.pos())) continue; // already where it belongs
                String lane = "drain:" + src.pos().asLong() + ":" + key(biggest);
                if (inFlight.contains(lane)) continue;
                queue.add(new Task(lane, null, src.pos(), null, dest,
                    biggest, Math.min(biggestCount, MAX_HAUL)));
                inFlight.add(lane);
                open++;
            }
        }

        // Reconcile the chain's derived orders onto every producer: counts follow the live
        // deficit (shrinking as deliveries land), orders whose need disappeared are revoked.
        boolean dirty = false;
        for (Workshop p : s.workshops().values()) {
            Map<String, Integer> needs = chainNeeds.getOrDefault(p.id(), Map.of());
            Map<String, String> needSources = chainSources.getOrDefault(p.id(), Map.of());
            if (p.autoOrders().keySet().retainAll(needs.keySet())) dirty = true;
            p.autoOrderSources().keySet().retainAll(needs.keySet());
            for (Map.Entry<String, Integer> e : needs.entrySet()) {
                Integer cur = p.autoOrders().get(e.getKey());
                int want = Math.min(e.getValue(), MAX_HAUL);
                if (cur == null || cur != want) {
                    p.autoOrders().put(e.getKey(), want);
                    dirty = true;
                }
                p.autoOrderSources().put(e.getKey(), needSources.get(e.getKey()));
            }
        }
        if (dirty) SettlementData.get(sl).setDirty();
    }

    /** A staffed, valid workshop able to PRODUCE {@code item} (gating applied via
     *  possibleOutputs) — the requesting workshop itself first when given (a mixed workshop
     *  supplies its own fletching station from its own stone), then any other. Null when
     *  nobody can. */
    @Nullable
    private static Workshop findProducer(ServerLevel sl, Settlement s, @Nullable Workshop requester,
                                         Item item) {
        if (requester != null && producesItem(sl, requester, item)) return requester;
        for (Workshop p : s.workshops().values()) {
            if (p == requester) continue;
            if (p.status() != Workshop.Status.VALID || p.workers().isEmpty()) continue;
            if (producesItem(sl, p, item)) return p;
        }
        return null;
    }

    private static boolean producesItem(ServerLevel sl, Workshop w, Item item) {
        for (BlockPos p : w.workBlocks()) {
            WorkBlockRegistry.WorkBlockDef def = WorkBlockRegistry.of(sl.getBlockState(p));
            if (def == null || def.executor() == null) continue;
            for (ItemStack out : def.executor().possibleOutputs(sl, p)) {
                if (out.is(item)) return true;
            }
        }
        return false;
    }

    private static String laneKey(Task t) {
        return t.lane;
    }

    private static String key(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** Per-item counts across a workshop's storage blocks. */
    private static Map<Item, Integer> storageCounts(ServerLevel sl, Workshop w) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (ItemStack s : WorkshopStorage.contents(sl, w)) {
            if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return out;
    }

    /** The rack pos of the first stockpile that is valid and has containers, or null. */
    @Nullable
    private static BlockPos firstUsableStockpile(Settlement s) {
        for (Stockpile sp : s.stockpiles().values()) {
            if (sp.valid() && !sp.containers().isEmpty()) return sp.pos();
        }
        return null;
    }

    /** A haul source: a walkable container position the stocker can take items from.
     *  {@code stockpile} marks rack entries — supply may take from them, the drain pass skips
     *  them (a stockpile never drains into itself). */
    private record SourceContainer(BlockPos pos, boolean stockpile) {
    }

    /**
     * Every place a supply haul may TAKE from: stockpile racks (their aggregate spans the whole
     * enclosure) plus loose drop-off containers (chests/baskets) in claimed loaded chunks.
     * Workshop storages and the containers already inside a stockpile enclosure are excluded —
     * the former so workshops never raid each other, the latter so the rack isn't double-listed.
     */
    private static List<SourceContainer> collectSources(ServerLevel sl, Settlement s,
                                                        @Nullable BlockPos stockpileRack,
                                                        Set<BlockPos> homeContainers) {
        Set<BlockPos> excluded = new HashSet<>();
        for (Workshop w : s.workshops().values()) excluded.addAll(w.storageBlocks());
        excluded.addAll(homeContainers); // home pantries are deliver-only, never drained as a source
        List<SourceContainer> out = new ArrayList<>();
        for (Stockpile sp : s.stockpiles().values()) {
            if (sp.valid() && !sp.containers().isEmpty()) {
                out.add(new SourceContainer(sp.pos(), true));
                excluded.addAll(sp.containers());
            }
        }
        // Working-claimed outpost chunks haul like home territory — the stocker walking the road
        // out there IS the outpost's exposed supply line.
        List<Long> sourceChunks = new ArrayList<>(s.claimedChunks());
        sourceChunks.addAll(s.workingClaims());
        for (long packed : sourceChunks) {
            ChunkPos cp = new ChunkPos(packed);
            if (!sl.hasChunk(cp.x, cp.z)) continue;
            LevelChunk chunk = sl.getChunk(cp.x, cp.z);
            for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                BlockPos pos = e.getKey();
                if (excluded.contains(pos)) continue;
                if (!(e.getValue() instanceof Container)) continue;
                if (!DropOffContainers.isDropOffBlock(sl, pos)) continue; // chests + baskets only
                // Never loot generated structures: unopened loot chests and containers buried
                // deep below the surface (mineshafts under the claim) are not ours to empty.
                if (DropOffContainers.isWildStorage(sl, pos)) continue;
                out.add(new SourceContainer(pos, false));
            }
        }
        return out;
    }

    /** The first source actually holding {@code item} right now, or null. */
    @Nullable
    private static SourceContainer findSourceWith(ServerLevel sl, List<SourceContainer> sources,
                                                  Item item) {
        for (SourceContainer src : sources) {
            Container c = DropOffContainers.resolveDropOff(sl, src.pos());
            if (c == null) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).is(item)) return src;
            }
        }
        return null;
    }

    /** A source container plus the concrete tag-matching item found in it (the home-supply pass
     *  needs a specific {@link Item} to haul, the demand is specified as a tag). */
    private record TaggedSource(SourceContainer src, Item item) {
    }

    /** The first source holding ANY item in {@code tag} (a luxury group), with that item, or null. */
    @Nullable
    private static TaggedSource findSourceWithTag(ServerLevel sl, List<SourceContainer> sources,
                                                  net.minecraft.tags.TagKey<Item> tag) {
        for (SourceContainer src : sources) {
            Container c = DropOffContainers.resolveDropOff(sl, src.pos());
            if (c == null) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (!st.isEmpty() && st.is(tag)) return new TaggedSource(src, st.getItem());
            }
        }
        return null;
    }

    /** Every home with a deliverable pantry container (chest / basket inside its marked region),
     *  mapped to that home's container positions. Drives both the source exclusion (a home is never
     *  drained) and the home-supply deliveries. */
    private static Map<com.bannerbound.core.api.settlement.Home, List<BlockPos>> collectHomePantries(
            ServerLevel sl, Settlement s) {
        Map<com.bannerbound.core.api.settlement.Home, List<BlockPos>> out = new HashMap<>();
        for (com.bannerbound.core.api.settlement.Home h : s.homes().values()) {
            List<BlockPos> containers = com.bannerbound.core.api.settlement.Homes.deliverableContainers(sl, h);
            if (!containers.isEmpty()) out.put(h, containers);
        }
        return out;
    }
}
