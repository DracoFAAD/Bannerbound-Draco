package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.world.StockerTasks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * The Stocker — the settlement's first pure-logistics worker (see {@link LogisticsWorkGoal}).
 * No tool, no marked work area: its targets are auto-assigned by the settlement's shared
 * {@link StockerTasks} board. The cycle is claim → walk to the source → withdraw (the load rides
 * visibly in hand) → walk to the destination → deposit → claim the next order. Tasks execute in
 * enqueued order; with several stockers employed each claims the oldest open order, so the queue
 * splits across them automatically and no stocker overrides a decision already in flight.
 *
 * <p>Failure is always soft: a source that emptied, a workshop that invalidated or a path that
 * times out just releases the task (carried items are deposited back / dropped at the feet) —
 * the next board regen recreates the haul if the need still exists.
 */
@ApiStatus.Internal
public class StockerWorkGoal extends LogisticsWorkGoal {
    public static final String JOB_TYPE_ID = "stocker";

    private static final double USE_DIST_SQ = 2.6 * 2.6;
    /** Ticks allowed per leg (to source / to destination) before the task is released. */
    private static final int LEG_TIMEOUT_TICKS = 600;
    /** Outpost legs cross open wilderness: walk them in {@link LongHaulWalker} hops until within this
     *  many blocks of the container, then hand off to a precise direct {@code moveTo}. Comfortably
     *  inside the 64-block FOLLOW_RANGE so the final approach is a single clean pathfind. */
    private static final double OUTPOST_HANDOFF = 28.0;

    /** Carry capacity scales with the stocker's job skill: a green hand hauls a quarter-stack, a
     *  master a full stack. The board still caps a single order at one stack
     *  ({@link com.bannerbound.core.world.StockerTasks}); a load larger than the worker can carry is
     *  split — the unhauled remainder re-queues on the next board regen, so an unskilled stocker
     *  simply makes more trips for the same need. */
    private static final int CARRY_NOVICE = 16;
    private static final int CARRY_MASTER = 64;

    private enum Phase { TO_SOURCE, TO_DEST }

    /** How long a walk position this stocker failed to reach stays personally embargoed (2 min). */
    private static final long UNREACHABLE_COOLDOWN_TICKS = 2400;

    /** Walk positions this stocker recently could NOT path to → game time the embargo lifts.
     *  Without this, an unreachable container livelocks the stocker: the leg timeout releases the
     *  task, the board regen recreates the same lane (the need is still unmet), and the stocker
     *  re-claims it — a permanent walk-grind-repeat loop the player sees as "stuck at the fence".
     *  Per-CITIZEN on purpose: a different stocker standing elsewhere may legitimately reach it. */
    private final java.util.Map<Long, Long> unreachableUntil = new java.util.HashMap<>();

    private StockerTasks.Task task;
    private Phase phase = Phase.TO_SOURCE;
    private ItemStack carried = ItemStack.EMPTY;
    private BlockPos walkTarget;
    private int legAge;
    private int repathCooldown;
    /** This task touches an OUTPOST working-claim chunk: legs get extra time (the site can be
     *  ~8 chunks out), the stocker paves a trader-style road as it walks, and the road-preference
     *  pathing flag is on — so the first trip MAKES the road and later trips FOLLOW it. */
    private boolean outpostTrip;
    /** Last column the road pass processed — one pave decision per step, like the trader. */
    private BlockPos lastRoadPos;
    /** Hop-walker for the long outpost legs (shared with {@link OutpostCommuteGoal}). Keeps a ~8-chunk
     *  haul from truncating into stutter + a fresh meandering road every trip — a roughly straight
     *  hop route lets {@link #trampleRoad} reuse one road instead of laying parallel strips. */
    private final LongHaulWalker walker = new LongHaulWalker();

    public StockerWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    @Override
    protected boolean canStartWork() {
        if (!JOB_TYPE_ID.equals(citizen.getJobType())) return false;
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Settlement s = citizen.getSettlement();
        if (s == null) return false;
        StockerTasks.Task t = StockerTasks.claim(sl, s, citizen.getUUID(), c -> !isEmbargoed(sl, c));
        if (t == null) return false;
        BlockPos source = sourceWalkPos(sl, t);
        if (source == null) {
            StockerTasks.release(s, t);
            return false;
        }
        boolean outpost = touchesOutpost(s, t.sourcePos) || touchesOutpost(s, t.destPos);
        // One A* probe at CLAIM time (outpost legs excluded — they hop-walk, so a truncated path is
        // normal there): an unreachable source is rejected in one tick instead of a 30-second
        // walk-to-the-fence-and-grind loop. The embargo keeps this stocker off the recreated lane.
        if (!outpost && !probeReachable(source)) {
            markUnreachable(sl, source);
            citizen.broadcastCannotReach(source);
            StockerTasks.release(s, t);
            return false;
        }
        this.task = t;
        this.walkTarget = source;
        this.phase = Phase.TO_SOURCE;
        this.outpostTrip = outpost;
        citizen.setRoadBuilding(outpostTrip);
        return true;
    }

    /** True when either endpoint's walk position is on this stocker's personal unreachable embargo. */
    private boolean isEmbargoed(ServerLevel sl, StockerTasks.Task t) {
        if (unreachableUntil.isEmpty()) return false;
        long now = sl.getGameTime();
        unreachableUntil.values().removeIf(until -> until <= now);
        if (unreachableUntil.isEmpty()) return false;
        BlockPos src = sourceWalkPos(sl, t);
        BlockPos dst = destWalkPos(sl, t);
        return (src != null && unreachableUntil.containsKey(src.asLong()))
            || (dst != null && unreachableUntil.containsKey(dst.asLong()));
    }

    private void markUnreachable(ServerLevel sl, BlockPos p) {
        if (p != null) unreachableUntil.put(p.asLong(), sl.getGameTime() + UNREACHABLE_COOLDOWN_TICKS);
    }

    /** One A* probe: can this stocker get within WORKING range of {@code p}? Mirrors the arrival
     *  check's {@link #USE_DIST_SQ} tolerance, so a basket one block inside a fence — serviceable by
     *  reaching OVER it — still counts as reachable even though the path itself stops short. */
    private boolean probeReachable(BlockPos p) {
        net.minecraft.world.level.pathfinder.Path path = citizen.getNavigation().createPath(p, 1);
        if (path == null) return false;
        if (path.canReach()) return true;
        net.minecraft.world.level.pathfinder.Node end = path.getEndNode();
        if (end == null) return false;
        double dx = end.x + 0.5 - (p.getX() + 0.5);
        double dy = end.y - (p.getY() + 0.5);
        double dz = end.z + 0.5 - (p.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz <= USE_DIST_SQ;
    }

    private static boolean touchesOutpost(Settlement s, BlockPos pos) {
        return pos != null && s.workingClaims().contains(
            new net.minecraft.world.level.ChunkPos(pos).toLong());
    }

    @Override
    protected boolean canKeepWorking() {
        return task != null;
    }

    @Override
    public void start() {
        citizen.setWorking(true);
        bankRecoveredLoad();
        legAge = 0;
        repathCooldown = 0;
        walker.reset(citizen);
        if (walkTarget != null) moveTo(walkTarget);
    }

    /** A chunk unload mid-haul skips {@link #stop()}, so the load survives the save IN MAINHAND while
     *  {@link #carried} (transient) is lost. On the next start a fresh goal would overwrite that slot in
     *  {@link #withdraw} and void the stack — bank it into the settlement pool first (feet-drop only
     *  when no deposit-open container exists), mirroring {@link #fail}'s never-void rule. */
    private void bankRecoveredLoad() {
        ItemStack held = citizen.getMainHandItem();
        if (held.isEmpty() || !carried.isEmpty()) return;
        // MAINHAND can also hold a render/conjured copy (job tool, or the combat goal's tool-age
        // sword) left behind by an unload mid-goal — those must be CLEARED, never banked, or the
        // pool gains a duplicate (the real tool lives in jobTool; the sword was conjured).
        if (isPhantomHandCopy(held)) {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            return;
        }
        if (citizen.level() instanceof ServerLevel sl) {
            Settlement s = citizen.getSettlement();
            if (s != null) {
                Container depot = SettlementStorage.depotAggregate(sl, s, citizen.blockPosition());
                if (depot != null) held = DropOffContainers.insert(depot, held);
            }
        }
        if (!held.isEmpty()) citizen.spawnAtLocation(held);
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    /** True when {@code held} is a render/conjured hand copy rather than a hauled load: the job
     *  tool/pickaxe render copy, or the settlement's current tool-age sword the combat goal
     *  conjures. A genuinely hauled sword of the same age is voided too — same as the pre-recovery
     *  behavior, and strictly better than minting one. */
    private boolean isPhantomHandCopy(ItemStack held) {
        if (ItemStack.isSameItemSameComponents(held, citizen.getJobTool())) return true;
        if (ItemStack.isSameItemSameComponents(held, citizen.getJobPickaxe())) return true;
        // The trade courier's carried-cargo prop (a bare chest) is cosmetic — never bank it.
        if (held.getItem() == net.minecraft.world.item.Items.CHEST) return true;
        Settlement s = citizen.getSettlement();
        return s != null && held.getItem() == s.getToolForRole("sword");
    }

    @Override
    public void tick() {
        if (task == null || !(citizen.level() instanceof ServerLevel sl)) return;
        if (walkTarget == null) { fail(sl); return; }
        citizen.getLookControl().setLookAt(
            walkTarget.getX() + 0.5, walkTarget.getY() + 0.5, walkTarget.getZ() + 0.5);
        // Outpost legs span up to ~8 chunks of wilderness — give them real travel time.
        int legTimeout = outpostTrip ? LEG_TIMEOUT_TICKS * 4 : LEG_TIMEOUT_TICKS;
        if (++legAge > legTimeout) {
            // Couldn't get there in a whole leg's time: embargo the spot for this stocker so the
            // recreated lane isn't re-claimed straight into the same wall, and tell the players.
            markUnreachable(sl, walkTarget);
            citizen.broadcastCannotReach(walkTarget);
            fail(sl);
            return;
        }

        double distSq = citizen.distanceToSqr(
            walkTarget.getX() + 0.5, walkTarget.getY() + 0.5, walkTarget.getZ() + 0.5);
        if (distSq > USE_DIST_SQ) {
            if (outpostTrip) {
                trampleRoad(sl);
                // Far outpost legs travel in hops (one moveTo to a ~128-block target truncates at
                // FOLLOW_RANGE and stutters, finding a different — freshly trampled — route each
                // trip). The walker drives navigation until within OUTPOST_HANDOFF of the container;
                // only then do we fall through to the precise direct approach below.
                LongHaulWalker.Status st = walker.stepToward(
                    citizen, walkTarget, skilledSpeed(), OUTPOST_HANDOFF, true);
                if (st != LongHaulWalker.Status.ARRIVED) return;   // still hopping / waiting on chunks
            }
            if (--repathCooldown <= 0) {
                repathCooldown = 20;
                moveTo(walkTarget);
            }
            return;
        }
        citizen.getNavigation().stop();

        if (phase == Phase.TO_SOURCE) {
            withdraw(sl);
        } else {
            deliver(sl);
        }
    }

    /** At the source: take the items (as much of the order as is actually there). */
    private void withdraw(ServerLevel sl) {
        Settlement s = citizen.getSettlement();
        if (s == null) { fail(sl); return; }
        int want = Math.min(task.count, carryCapacity());
        if (task.sourceWorkshopId != null) {
            Workshops.Hit hit = Workshops.findById(sl, task.sourceWorkshopId);
            if (hit != null) {
                int have = WorkshopStorage.count(sl, hit.workshop(), task.item);
                int take = Math.min(want, have);
                if (take > 0) {
                    carried = WorkshopStorage.extract(sl, hit.workshop(), task.item, take);
                }
            }
        } else if (task.sourcePos != null) {
            Container src = DropOffContainers.resolveDropOff(sl, task.sourcePos);
            if (src != null) {
                carried = DropOffContainers.extract(src, task.item, want);
            }
        }
        if (carried.isEmpty()) { fail(sl); return; }
        citizen.setItemSlot(EquipmentSlot.MAINHAND, carried.copy());
        BlockPos dest = destWalkPos(sl, task);
        if (dest == null) { fail(sl); return; }
        // Probe the delivery leg from the source before hauling off (outposts hop-walk — skip):
        // an unreachable destination fails HERE, returning the load to this container, instead of
        // after a 30-second grind at whatever fence is in the way.
        if (!outpostTrip && !probeReachable(dest)) {
            markUnreachable(sl, dest);
            citizen.broadcastCannotReach(dest);
            fail(sl);
            return;
        }
        walkTarget = dest;
        phase = Phase.TO_DEST;
        legAge = 0;
        walker.reset(citizen);   // fresh hop sequence for the new leg, from wherever we ended up
        moveTo(walkTarget);
    }

    /** At the destination: deposit, finish the task, spend stamina. */
    private void deliver(ServerLevel sl) {
        Settlement s = citizen.getSettlement();
        ItemStack leftover = carried;
        if (task.destWorkshopId != null) {
            Workshops.Hit hit = Workshops.findById(sl, task.destWorkshopId);
            if (hit != null) leftover = WorkshopStorage.insert(sl, hit.workshop(), carried);
        } else if (task.destPos != null) {
            Container dest = DropOffContainers.resolveDropOff(sl, task.destPos);
            if (dest != null) leftover = DropOffContainers.insert(dest, carried);
        }
        if (!leftover.isEmpty()) {
            citizen.spawnAtLocation(leftover); // destination vanished/full — never void the load
        }
        carried = ItemStack.EMPTY;
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        if (s != null) StockerTasks.complete(s, task);
        citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "haul");
        citizen.consumeStamina(1);
        task = null; // canKeepWorking → false; the think-tick poll claims the next order
    }

    /** Releases the task and, if mid-haul, returns the load to its SOURCE container (remote
     *  insert) rather than dumping it at the citizen's feet — an interrupted outpost run used to
     *  strand ore on the ground in the wilderness. Ground-spill only when the source vanished. */
    private void fail(ServerLevel sl) {
        if (!carried.isEmpty()) {
            ItemStack leftover = carried;
            if (task != null) {
                if (task.sourceWorkshopId != null) {
                    Workshops.Hit hit = Workshops.findById(sl, task.sourceWorkshopId);
                    if (hit != null) leftover = WorkshopStorage.insert(sl, hit.workshop(), leftover);
                } else if (task.sourcePos != null) {
                    Container src = DropOffContainers.resolveDropOff(sl, task.sourcePos);
                    if (src != null) leftover = DropOffContainers.insert(src, leftover);
                }
            }
            if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
            carried = ItemStack.EMPTY;
            citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        Settlement s = citizen.getSettlement();
        if (s != null && task != null) StockerTasks.release(s, task);
        task = null;
    }

    @Override
    public void stop() {
        citizen.setWorking(false);
        if (citizen.level() instanceof ServerLevel sl && task != null) {
            if (!citizen.isAlive() && !carried.isEmpty()) {
                // KILLED mid-haul: the load drops AT THE BODY, lootable by the killer. This is
                // the interdiction thesis made real — raiding the supply line yields the cargo.
                // Only peaceful interrupts (night, stamina, timeout) bank the load back home.
                citizen.spawnAtLocation(carried);
                carried = ItemStack.EMPTY;
                Settlement s = citizen.getSettlement();
                if (s != null) StockerTasks.release(s, task);
                task = null;
            } else {
                fail(sl); // interrupted (stamina, night, job change) — return the load + free the order
            }
        }
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.getNavigation().stop();
        citizen.setRoadBuilding(false);
        walker.reset(citizen);
        outpostTrip = false;
        walkTarget = null;
        phase = Phase.TO_SOURCE;
    }

    /** Outpost trips lay a road in THE TRADER'S style (3-wide cross, 70% dirt path / 15% gravel /
     *  15% coarse dirt — {@link com.bannerbound.core.sim.TraderSimManager#pave3Wide}) — but ONLY
     *  through true wilderness. Claimed territory is off-limits (paving carved through village
     *  lawns in playtest: that griefs player designs), and so is the outpost's own working-claim
     *  chunk (the camp is player-built too) — checked per COLUMN so the 3-wide cross never leaks
     *  across a border. And a stocker already standing ON a road follows it instead of widening
     *  it or laying a parallel strip — re-walking an established route changes nothing. */
    private void trampleRoad(ServerLevel sl) {
        if (!citizen.onGround()) return;
        Settlement s = citizen.getSettlement();
        if (s == null) return;
        BlockPos feet = citizen.blockPosition();
        if (feet.equals(lastRoadPos)) return;   // one decision per step, like the trader
        lastRoadPos = feet;
        if (com.bannerbound.core.sim.TraderSimManager.isRoad(sl.getBlockState(feet.below()))) {
            return;   // already on a road — use it, don't build more
        }
        paveIfWild(sl, s, feet.getX(), feet.getZ());
        paveIfWild(sl, s, feet.getX() + 1, feet.getZ());
        paveIfWild(sl, s, feet.getX() - 1, feet.getZ());
        paveIfWild(sl, s, feet.getX(), feet.getZ() + 1);
        paveIfWild(sl, s, feet.getX(), feet.getZ() - 1);
    }

    /** One trader-style road column, gated to wilderness: never in a claimed chunk, never in a
     *  working-claim (outpost camp) chunk. */
    private static void paveIfWild(ServerLevel sl, Settlement s, int x, int z) {
        long packed = net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4);
        if (s.claimedChunks().contains(packed) || s.workingClaims().contains(packed)) return;
        com.bannerbound.core.sim.TraderSimManager.paveColumn(sl, x, z);
    }

    /** Where to stand to take the load: the source container / the source workshop's storage. */
    private static BlockPos sourceWalkPos(ServerLevel sl, StockerTasks.Task t) {
        if (t.sourcePos != null) return t.sourcePos;
        if (t.sourceWorkshopId != null) {
            Workshops.Hit hit = Workshops.findById(sl, t.sourceWorkshopId);
            if (hit != null && !hit.workshop().storageBlocks().isEmpty()) {
                return hit.workshop().storageBlocks().get(0);
            }
        }
        return null;
    }

    /** Where to stand to deposit: the destination workshop's storage / the stockpile rack. */
    private static BlockPos destWalkPos(ServerLevel sl, StockerTasks.Task t) {
        if (t.destPos != null) return t.destPos;
        if (t.destWorkshopId != null) {
            Workshops.Hit hit = Workshops.findById(sl, t.destWorkshopId);
            if (hit != null && !hit.workshop().storageBlocks().isEmpty()) {
                return hit.workshop().storageBlocks().get(0);
            }
        }
        return null;
    }

    private void moveTo(BlockPos p) {
        // A skilled (and happy) stocker covers ground faster — see WorkGoal.skilledSpeed().
        citizen.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, skilledSpeed());
    }

    /** How much this stocker can carry in one haul, scaled from {@link #CARRY_NOVICE} to
     *  {@link #CARRY_MASTER} by {@link #jobSkill()}. */
    private int carryCapacity() {
        return Math.round(CARRY_NOVICE + (CARRY_MASTER - CARRY_NOVICE) * jobSkill());
    }
}
