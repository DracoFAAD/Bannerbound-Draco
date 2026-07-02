package com.bannerbound.core.territory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Stockpile;
import com.bannerbound.core.api.settlement.food.LarderService;
import com.bannerbound.core.api.settlement.ImmigrationManager;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.DropOffContainers;
import com.bannerbound.core.entity.MinerWorkGoal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The outpost supply line as a <b>ghost simulation</b> — the "believed, not counted" model the
 * trader proves ({@link com.bannerbound.core.sim.TraderSimManager}), applied to the stocker so a
 * remote outpost keeps producing AND being hauled home even when nobody is anywhere near it.
 *
 * <p>Two cheap, entity-free layers:
 * <ul>
 *   <li><b>Accrual</b> — while an outpost chunk is UNLOADED, ore dead-reckons into the settlement's
 *       persisted per-outpost {@link Settlement#outpostAccrued(long) balance} (paced by the vein's
 *       deterministic richness — no chunk read). Skipped while the chunk is loaded, where the real
 *       miner produces for real (no double count). Both outpost AND settlement unloaded → this is
 *       all that runs: a few numbers tick up, no entities, the cheapest case.</li>
 *   <li><b>Ghost haul</b> — when the settlement is loaded (a stocker is home to send) and an outpost
 *       has banked enough, a ghost stocker sets out on a round trip whose duration is the real
 *       travel distance. At the far point it COLLECTS the accrued ore; back home it DELIVERS into the
 *       stockpile. Pure data: position is a clock fraction along the route. The ore is removed from
 *       the bank only ON DELIVERY, so a haul interrupted by a restart simply re-dispatches — never a
 *       dupe, never a loss.</li>
 * </ul>
 *
 * <p>Layer 2 (TODO — see {@link #observePosition}) materializes a real, followable puppet stocker at
 * the ghost's position whenever a player is near, so the trip can be watched; until then the haul is
 * invisible but functionally complete (ore arrives in the stockpile after a realistic delay).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class GhostStockerManager {
    /** Driver cadence — haul progress + dispatch checks. 20t (1s) is smooth and cheap. */
    private static final int TICK_INTERVAL = 20;
    /** Accrual cadence — integer ore per this window, by richness. */
    private static final int ACCRUE_INTERVAL = 200;
    /** Ore accrued per {@link #ACCRUE_INTERVAL} per UNLOADED outpost (poor / normal / rich). Mirrors
     *  the loaded miner once {@link MinerVeinRegen}'s wave lands. */
    private static final int[] YIELD_BY_RICHNESS = {1, 2, 3};
    /** Per-outpost accrual ceiling — an endless absence can't bank an absurd hoard. */
    private static final int PENDING_CAP = 2048;
    /** Don't send a stocker on a long round trip for a trivial amount; let it build up first. */
    private static final int DISPATCH_THRESHOLD = 16;
    /** Ghost stocker round-trip pace (blocks/tick). ~0.2 ≈ the citizen walk speed. */
    private static final double STOCKER_SPEED = 0.2;
    /** Floor on a trip so a near outpost still takes a believable few seconds, not an instant. */
    private static final long MIN_TRIP_TICKS = 200;
    /** Materialize the visual puppet when a player is within this of the ghost; drop it past the
     *  despawn band. The gap is hysteresis so it doesn't flicker at the boundary. */
    private static final double PUPPET_SPAWN_RANGE = 56.0;
    private static final double PUPPET_DESPAWN_RANGE = 80.0;

    /** In-flight hauls, keyed by outpost chunk. NOT persisted — the accrued bank is the source of
     *  truth (decremented on delivery), so a haul lost to a restart just re-dispatches. */
    private static final Map<Long, Haul> ACTIVE = new HashMap<>();

    private GhostStockerManager() {
    }

    /** One ghost round trip: depart stockpile → reach outpost (collect) → return (deliver). */
    private static final class Haul {
        final UUID settlement;
        final long outpostChunk;
        final Vec3 outpostPos;   // chunk centre (XZ matters; Y nominal)
        final Vec3 stockpilePos;
        final Item item;
        final long startTick;
        final long tripTicks;
        int carried;             // captured at the half-way collect
        boolean collected;
        UUID puppet;             // live visual stocker while observed (null when ghosting), see Layer 2

        Haul(UUID settlement, long outpostChunk, Vec3 outpostPos, Vec3 stockpilePos,
             Item item, long startTick, long tripTicks) {
            this.settlement = settlement;
            this.outpostChunk = outpostChunk;
            this.outpostPos = outpostPos;
            this.stockpilePos = stockpilePos;
            this.item = item;
            this.startTick = startTick;
            this.tripTicks = tripTicks;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        if (ACTIVE.isEmpty() && server.getTickCount() % TICK_INTERVAL != 0) return;
        long now = overworld.getGameTime();
        SettlementData data = SettlementData.get(overworld);

        // EVERY tick: slide the visual puppets so following one looks like a real walk, not a
        // once-a-second teleport. Cheap — at most a settlement's outpost cap of hauls.
        syncPuppets(overworld, data, now);

        // Periodic: the heavy work (accrual, dispatch, collect/deliver) — 1 s is plenty responsive.
        if (server.getTickCount() % TICK_INTERVAL == 0) {
            boolean accrue = server.getTickCount() % ACCRUE_INTERVAL == 0;
            boolean dirty = false;
            for (Settlement s : data.all()) {
                if (accrue) dirty |= accrueUnloadedOutposts(overworld, s);
                dispatchHauls(overworld, s, now);
            }
            dirty |= advanceHauls(overworld, data, now);
            if (dirty) data.setDirty();
        }
    }

    /** Per-tick cosmetic pass: move/spawn/despawn each haul's puppet at its live ghost position. */
    private static void syncPuppets(ServerLevel sl, SettlementData data, long now) {
        if (ACTIVE.isEmpty()) return;
        for (Haul h : ACTIVE.values()) {
            Settlement s = data.getById(h.settlement);
            if (s == null) continue;   // advanceHauls discards its puppet + drops it on the 20t pass
            double progress = Math.max(0.0, Math.min(1.0, (double) (now - h.startTick) / h.tripTicks));
            observePosition(sl, s, h, progress);
        }
    }

    // ─── Layer 1a: accrual ───────────────────────────────────────────────────────────────────────

    private static boolean accrueUnloadedOutposts(ServerLevel sl, Settlement s) {
        if (s.workingClaims().isEmpty()) return false;
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(sl);
        boolean changed = false;
        for (long packed : s.workingClaims()) {
            ChunkPos cp = new ChunkPos(packed);
            if (sl.hasChunk(cp.x, cp.z)) continue;            // loaded → the real miner produces here
            ChunkResource type = ChunkResources.typeAt(sl, cp);
            if (!BoulderLayout.isOreChunk(type)) continue;
            if (BoulderLayout.dropFor(type).isEmpty()) continue;
            if (!hasAssignedMiner(registry, s, cp)) continue;  // unstaffed outpost makes nothing
            int rich = BoulderLayout.richness(sl.getSeed(), cp);
            s.addOutpostAccrued(packed, YIELD_BY_RICHNESS[rich], PENDING_CAP);
            changed = true;
        }
        return changed;
    }

    private static boolean hasAssignedMiner(BlockSelectionRegistry registry, Settlement s, ChunkPos cp) {
        for (BlockSelection sel : registry.getForSettlement(s.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!MinerWorkGoal.SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (sel.targetsAllWorkers()) continue;
            if (cp.equals(new ChunkPos(new BlockPos(sel.minX(), sel.minY(), sel.minZ())))) return true;
        }
        return false;
    }

    // ─── Layer 1b: dispatch ──────────────────────────────────────────────────────────────────────

    private static void dispatchHauls(ServerLevel sl, Settlement s, long now) {
        if (s.workingClaims().isEmpty()) return;
        // A stocker must be a thing here (researched), and a loaded stockpile must exist to deliver to.
        if (!ResearchManager.hasFlag(s, LarderService.STORAGE_RESEARCH_FLAG)) return;
        BlockPos stockpile = loadedStockpilePos(sl, s);
        if (stockpile == null) return;   // home storage not loaded → nobody home to send / nowhere to put it

        for (long packed : s.workingClaims()) {
            if (ACTIVE.containsKey(packed)) continue;
            if (s.outpostAccrued(packed) < DISPATCH_THRESHOLD) continue;
            ChunkPos cp = new ChunkPos(packed);
            ChunkResource type = ChunkResources.typeAt(sl, cp);
            Item drop = BoulderLayout.dropFor(type).orElse(null);
            if (drop == null) continue;
            Vec3 outpostPos = new Vec3(cp.getMinBlockX() + 8.5, stockpile.getY(), cp.getMinBlockZ() + 8.5);
            Vec3 stockVec = new Vec3(stockpile.getX() + 0.5, stockpile.getY(), stockpile.getZ() + 0.5);
            double oneWay = outpostPos.distanceTo(stockVec);
            long trip = Math.max(MIN_TRIP_TICKS, (long) (2.0 * oneWay / STOCKER_SPEED));
            ACTIVE.put(packed, new Haul(s.id(), packed, outpostPos, stockVec, drop, now, trip));
        }
    }

    // ─── Layer 1c: advance / collect / deliver ───────────────────────────────────────────────────

    private static boolean advanceHauls(ServerLevel sl, SettlementData data, long now) {
        boolean dirty = false;
        Iterator<Haul> it = ACTIVE.values().iterator();
        while (it.hasNext()) {
            Haul h = it.next();
            Settlement s = data.getById(h.settlement);
            if (s == null) { discardPuppet(sl, h); it.remove(); continue; }
            double progress = (double) (now - h.startTick) / h.tripTicks;

            // Half-way: the stocker reaches the outpost and loads up. Snapshot the bank as carried,
            // but DON'T debit it yet — only delivery debits, so a lost haul re-dispatches losslessly.
            if (!h.collected && progress >= 0.5) {
                h.carried = Math.min(s.outpostAccrued(h.outpostChunk), 64 * 9);   // a cart-load cap
                h.collected = true;
            }

            // Layer 2: show a real, followable puppet at the live ghost position when a player is near.
            observePosition(sl, s, h, progress);

            if (progress >= 1.0) {
                if (h.collected && h.carried > 0) {
                    int delivered = deliver(sl, s, h.item, h.carried);
                    if (delivered > 0) {
                        s.takeOutpostAccrued(h.outpostChunk, delivered);   // debit ONLY what landed
                        dirty = true;
                    }
                    // If home went unloaded right at the finish, leave the haul one more cycle to
                    // retry delivery rather than dropping the cart on the floor.
                    if (delivered < h.carried && loadedStockpilePos(sl, s) == null) {
                        continue;
                    }
                }
                discardPuppet(sl, h);
                it.remove();
            }
        }
        return dirty;
    }

    /** Insert up to {@code amount} of {@code item} into the settlement's loaded home storage; returns
     *  how much actually landed (≤ amount). Never dupes — only the inserted count is reported. */
    private static int deliver(ServerLevel sl, Settlement s, Item item, int amount) {
        List<Container> homes = loadedHomeContainers(sl, s);
        if (homes.isEmpty()) return 0;
        int remaining = amount;
        int maxStack = new ItemStack(item).getMaxStackSize();
        for (Container c : homes) {
            while (remaining > 0) {
                ItemStack stack = new ItemStack(item, Math.min(remaining, maxStack));
                ItemStack leftover = DropOffContainers.insert(c, stack);
                int inserted = stack.getCount() - leftover.getCount();
                if (inserted <= 0) break;
                remaining -= inserted;
            }
            if (remaining <= 0) break;
        }
        return amount - remaining;
    }

    /** Realize-on-observe: when a player is near the ghost's live route position, materialize a real
     *  (simulated, never-saved) stocker there and slide it along; when the player leaves, discard it.
     *  Hysteresis (spawn within {@link #PUPPET_SPAWN_RANGE}, drop past {@link #PUPPET_DESPAWN_RANGE})
     *  stops it flickering at the edge. Pure cosmetic — the ghost clock is authoritative. */
    private static void observePosition(ServerLevel sl, Settlement s, Haul h, double progress) {
        // Out to the outpost over [0,0.5], back to the stockpile over [0.5,1].
        boolean outbound = progress < 0.5;
        Vec3 pos = outbound
            ? h.stockpilePos.lerp(h.outpostPos, progress * 2.0)
            : h.outpostPos.lerp(h.stockpilePos, (progress - 0.5) * 2.0);

        CitizenEntity puppet = (h.puppet != null && sl.getEntity(h.puppet) instanceof CitizenEntity c
            && !c.isRemoved()) ? c : null;
        if (puppet == null) h.puppet = null;

        double range = puppet != null ? PUPPET_DESPAWN_RANGE : PUPPET_SPAWN_RANGE;
        boolean chunkLoaded = sl.hasChunk(((int) Math.floor(pos.x)) >> 4, ((int) Math.floor(pos.z)) >> 4);
        boolean observed = chunkLoaded
            && sl.getNearestPlayer(pos.x, pos.y, pos.z, range, false) != null;

        if (!observed) { discardPuppet(sl, h); return; }

        if (puppet == null) {
            puppet = ImmigrationManager.spawnSimCitizen(sl, s);   // spawns near town hall, then we place it
            if (puppet == null) return;                           // town hall unloaded this tick — retry later
            puppet.setNoAi(true);                                 // we drive its position; no goals, no wander
            h.puppet = puppet.getUUID();
        }
        int gx = (int) Math.floor(pos.x);
        int gz = (int) Math.floor(pos.z);
        int gy = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz);
        Vec3 target = outbound ? h.outpostPos : h.stockpilePos;
        float yaw = (float) (Math.toDegrees(Math.atan2(target.z - pos.z, target.x - pos.x)) - 90.0);
        puppet.moveTo(gx + 0.5, gy, gz + 0.5, yaw, 0.0f);
        puppet.setYBodyRot(yaw);
        puppet.setYHeadRot(yaw);
        // Carrying the load home shows in-hand after the half-way pickup; empty-handed on the way out.
        puppet.setItemSlot(EquipmentSlot.MAINHAND,
            h.collected && h.carried > 0 ? new ItemStack(h.item) : ItemStack.EMPTY);
    }

    /** Remove a haul's puppet entity if one is live. Idempotent. */
    private static void discardPuppet(ServerLevel sl, Haul h) {
        if (h.puppet == null) return;
        if (sl.getEntity(h.puppet) instanceof CitizenEntity c) c.discard();
        h.puppet = null;
    }

    // ─── Storage resolution ──────────────────────────────────────────────────────────────────────

    /** A loaded, valid stockpile's block pos (the trip's home anchor), or null. */
    private static BlockPos loadedStockpilePos(ServerLevel sl, Settlement s) {
        for (Stockpile sp : s.stockpiles().values()) {
            if (!sp.valid()) continue;
            ChunkPos cp = new ChunkPos(sp.pos());
            if (sl.hasChunk(cp.x, cp.z)) return sp.pos();
        }
        BlockPos pref = s.preferredStoragePos();
        if (pref != null && sl.hasChunk(pref.getX() >> 4, pref.getZ() >> 4)) return pref;
        return null;
    }

    /** Every loaded home container (stockpile aggregates + preferred drop-off) to deliver into. */
    private static List<Container> loadedHomeContainers(ServerLevel sl, Settlement s) {
        List<Container> out = new ArrayList<>();
        for (Stockpile sp : s.stockpiles().values()) {
            if (!sp.valid()) continue;
            ChunkPos cp = new ChunkPos(sp.pos());
            if (!sl.hasChunk(cp.x, cp.z)) continue;
            Container c = DropOffContainers.resolveDropOff(sl, sp.pos());
            if (c != null) out.add(c);
        }
        BlockPos pref = s.preferredStoragePos();
        if (pref != null && sl.hasChunk(pref.getX() >> 4, pref.getZ() >> 4)) {
            Container c = DropOffContainers.resolveDropOff(sl, pref);
            if (c != null && !out.contains(c)) out.add(c);
        }
        return out;
    }
}
