package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.building.PenEnclosure;
import com.bannerbound.core.world.SelectionBroadcaster;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;

/**
 * Herder {@link OrderedWorkGoal}: tends a fenced pen on a livestock chunk, marked with one Foreman's-Rod
 * click ({@link PenEnclosure} auto-detects the enclosure). It doesn't haul; it keeps a herd alive and
 * growing and rests (walking OUT of the pen, leaving the space to the animals) when there's nothing to do.
 * A pen on a working-claim chunk turns this herder into an OUTPOST WORKER (roofed-bed sleep, pen idle-anchor,
 * greyed storage controls) exactly like the miner; setOutpostSite must run BEFORE the hasChunk gate.
 *
 * <p>State machine (Phase): COLLECT/LEAD is the leash-free corral - walk out to wild/escaped stock of the
 * chunk's kind, claim a batch (each is calmed and gets its own {@link HerdFollowGoal} so it WALKS behind the
 * herder; driving the animal's nav from outside got overridden and nothing moved), lead them to a cell JUST
 * inside the gate, and release each as it steps in. The herder posts one block inside the gate on purpose:
 * the follow is the animal's own navigation and only solves a short, direct crossing - leading to the deep
 * centre across the pen's water gave an unsolvable path. SPAWNING is the last-resort starter pair (only when
 * no wild stock exists, gated to once per half MC day so a wiped pen can't instantly refill). TO_BREED sets a
 * ready pair in love (feed is on hand like a farmer's seeds; the baby itself is BreedingEvents' chance roll),
 * gated on the settlement's Animal Husbandry flag ({@code VanillaGates.FLAG}). TO_CULL is a real melee kill
 * of surplus adults above the pen's keep threshold (drops route through LivingDropsEvent; credits the
 * "livestock" food source). COLLECT_MANURE mucks out droppings that foul fertility (see BreedingEvents).
 * LEAVE walks the herder back out of the pen.
 *
 * <p>Every animal brought in or spawned is domesticated ({@link #DOMESTICATED_TAG}, shared with Antiquity's
 * HuntingFear.isTamed) so it won't flee players or leave footprints; horses are additionally tamed because
 * vanilla only breeds / obeys setInLove on a tamed horse. The follow is a gentle server-side claim
 * ({@code HERDED_BY}, synced so the client draws the cosmetic rope), NOT a vanilla leash - nothing tangles.
 * Claims and follow goals do not survive a reload and are re-established when a valid herder re-claims.
 *
 * <p>Gate ownership: while tending a pen the herder is the SINGLE owner of its gate, reserving it via
 * {@link GateHolds} every tick so the shared {@link OpenFenceGateGoal} never also toggles it (two systems
 * toggling one gate each tick was the open/close flicker); the reservation lapses on its own so nothing leaks
 * if the herder dies or disengages, and {@link #stop()} releases it. Gates are opened/closed by tag + the
 * OPEN property so it works for vanilla AND the rope gate, never by instanceof.
 *
 * <p>Tool: a lead-rope in the job slot, recognised only by {@link #HERDER_ROPE_TAG} (Core ships it with
 * vanilla {@code minecraft:lead}; Antiquity merges in {@code fiber_rope}) - never a hard item ref, so the
 * herder works standalone and the expansion just upgrades the option. The pen marker packs
 * "&lt;animalId&gt;|&lt;kills&gt;|&lt;keep&gt;" into its seedItemId (keep 0 = Auto -> full capacity;
 * back-compat with old 2-field markers whose keep reads as 0).
 */
@ApiStatus.Internal
public class HerderWorkGoal extends OrderedWorkGoal {
    public static final String JOB_TYPE_ID = "herders_pen";
    public static final String SELECTION_TYPE = "herder";
    public static final ResourceLocation FIBER_ROPE_ID =
        ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "fiber_rope");
    public static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> HERDER_ROPE_TAG =
        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("bannerbound", "herder_rope"));

    public static boolean isRope(net.minecraft.world.item.ItemStack stack) {
        return stack.is(HERDER_ROPE_TAG);
    }
    public static final String DOMESTICATED_TAG = "BannerboundDomesticated";
    // Same literal lives in RopeFenceEvents (Core can't import there) - keep the two in sync.
    public static final String TELEPORT_AT = CitizenEntity.TELEPORT_AT_KEY;

    private static final int START_PAIR = 2;
    private static final int MAX_BATCH = 6;
    private static final double CAPTURE_RADIUS = 24.0;
    private static final double ESCAPE_RADIUS = 32.0;
    private static final double REACH = 2.5;
    private static final int SEEK_TIMEOUT = 200;
    private static final int LEAD_TIMEOUT = 600;
    private static final int HERDER_STUCK_LIMIT = 40;
    private static final int LEAD_STALL_LIMIT = 80;
    private static final int FEED_WORK_TICKS = 24;
    private static final int DEFAULT_BUTCHER_DAMAGE = 4;
    private static final double DEFAULT_BUTCHER_ATTACK_SPEED = 1.6;
    private static final int SPAWN_WORK_TICKS = 40;
    private static final long SPAWN_COOLDOWN = 12_000L;
    private static final String SPAWN_AT_KEY = "BannerboundHerdSpawnAt";

    private enum Phase { IDLE, COLLECT, LEAD, SPAWNING, TO_BREED, TO_CULL, COLLECT_MANURE, LEAVE }

    private BlockPos anchor;
    private PenEnclosure.Result pen;
    private EntityType<? extends Animal> herdType;
    private Phase phase = Phase.IDLE;
    private final java.util.Map<Long, Long> penCooldown = new java.util.HashMap<>();
    private static final long PEN_IDLE_COOLDOWN = 120L;
    private final List<Animal> batch = new ArrayList<>();
    private Animal seeking;
    private int seekTicks;
    private BlockPos dropCell;
    private BlockPos gatePos;
    private int leadTicks;
    private int herderStuck;
    private int leadStall;
    private double prevX, prevZ;
    private BlockPos restSpot;
    private Animal breedA, breedB;
    private Animal cullTarget;
    private BlockPos manurePos;
    private int workTicks;

    public HerderWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    private boolean hasRope() {
        return JOB_TYPE_ID.equals(citizen.getJobType()) && isRope(citizen.getJobTool());
    }

    @Override
    protected boolean canStartWork() {
        if (!hasRope() || !(citizen.level() instanceof ServerLevel sl)) return false;
        PenClaims.releaseAll(citizen.getId());
        BlockSelection sel = findPenMarker(sl);
        if (sel == null) return false;
        anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
        Settlement os = citizen.getSettlement();
        citizen.setOutpostSite(os != null && os.workingClaims().contains(
            new net.minecraft.world.level.ChunkPos(anchor).toLong()) ? anchor.immutable() : null);
        if (!sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) return false;
        pen = PenEnclosure.scan(sl, anchor);
        if (!pen.valid()) {
            removeMarker(sl, sel);
            return false;
        }
        gatePos = findGate(sl, pen);
        herdType = animalFromMarker(sel);
        if (herdType == null) return false;
        assess(sl);
        if (phase == Phase.IDLE && gatePos != null && !GateHolds.isHeld(gatePos, sl.getGameTime())) {
            setOwnGate(sl, false);
        }
        return phase != Phase.IDLE;
    }

    @Override
    protected boolean canKeepWorking() {
        if (!hasRope() || anchor == null || herdType == null || phase == Phase.IDLE) return false;
        return citizen.level() instanceof ServerLevel sl && findPenMarker(sl) != null;
    }

    @Override
    public void start() {
        citizen.setWorking(true);
        holdRope();
    }

    @Override
    public void stop() {
        citizen.setWorking(false);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        releaseBatch();
        PenClaims.releaseAll(citizen.getId());
        if (gatePos != null && citizen.level() instanceof ServerLevel sl) {
            GateHolds.release(gatePos, citizen.getId());
            setOwnGate(sl, false);
        }
        phase = Phase.IDLE;
        seeking = null;
        breedA = breedB = null;
        cullTarget = null;
        manurePos = null;
        workTicks = 0;
        herderStuck = 0;
    }

    @Override
    public void tick() {
        if (!(citizen.level() instanceof ServerLevel sl) || pen == null || herdType == null) return;
        switch (phase) {
            case COLLECT -> tickCollect(sl);
            case LEAD -> tickLead(sl);
            case SPAWNING -> tickSpawning(sl);
            case TO_BREED -> tickToBreed(sl);
            case TO_CULL -> tickToCull(sl);
            case COLLECT_MANURE -> tickCollectManure(sl);
            case LEAVE -> tickLeave(sl);
            case IDLE -> { }
        }
        manageOwnGate(sl);
        equipForPhase();
    }

    private void manageOwnGate(ServerLevel sl) {
        if (gatePos == null) return;
        // Reserve the gate every tick so the shared OpenFenceGateGoal never also toggles it (flicker).
        GateHolds.hold(gatePos, citizen.getId(), sl.getGameTime());
        // Hold open from the start, not only by proximity: a closed gate blocks the path to itself, so proximity-open deadlocks.
        boolean flockIncoming = (phase == Phase.LEAD) && (!batch.isEmpty() || herdedAnimalNear(sl, gatePos, 7.5));

        boolean wantOpen = flockIncoming
                || herderCrossingGate()
                || (phase == Phase.LEAVE && insideHerder());

        setOwnGate(sl, wantOpen);
    }
    private boolean herderCrossingGate() {
        return !citizen.getNavigation().isDone()
            && citizen.distanceToSqr(gatePos.getX() + 0.5, gatePos.getY() + 0.5, gatePos.getZ() + 0.5) <= 6.25;
    }

    private void setOwnGate(ServerLevel sl, boolean open) {
        BlockState state = sl.getBlockState(gatePos);
        if (!state.is(BlockTags.FENCE_GATES) || !state.hasProperty(BlockStateProperties.OPEN)) return;
        if (state.getValue(BlockStateProperties.OPEN) == open) return;
        sl.setBlock(gatePos, state.setValue(BlockStateProperties.OPEN, open), 10);
        sl.playSound(null, gatePos, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE,
            SoundSource.BLOCKS, 1.0f, 1.0f);
        sl.gameEvent(citizen, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, gatePos);
    }

    private boolean herdedAnimalNear(ServerLevel sl, BlockPos pos, double r) {
        AABB box = new AABB(pos).inflate(r);
        for (Animal a : sl.getEntitiesOfClass(Animal.class, box)) {
            Integer h = a.getExistingDataOrNull(BannerboundCore.HERDED_BY.get());
            if (h != null && h != 0) return true;
        }
        return false;
    }

    private void assess(ServerLevel sl) {
        PenEnclosure.Result r = PenEnclosure.scan(sl, anchor);
        if (!r.valid()) {
            if (sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) removeMarker(sl, findPenMarker(sl));
            phase = Phase.IDLE;
            return;
        }
        pen = r;
        gatePos = findGate(sl, r);
        List<Animal> herd = penHerd(sl, r);
        for (Animal a : herd) if (!isDomesticated(a)) domesticate(a);
        int cap = capacity(sl);
        int keepAdults = adultKeepTarget(sl, cap);
        Animal surplus = selectCullTarget(herd, keepAdults);
        if (surplus != null && DropOffContainers.resolveOrPreferred(citizen, citizen.getDropOff()) != null) {
            cullTarget = surplus;
            workTicks = 0;
            phase = Phase.TO_CULL;
            return;
        }
        int inside = herd.size();

        if (!batch.isEmpty()) { beginLead(sl, r); return; }

        BlockPos manure = findManure(sl, r);
        if (manure != null) { manurePos = manure; phase = Phase.COLLECT_MANURE; return; }

        if (inside < cap && (findEscaped(sl, r) != null || findWild(sl, r) != null)) {
            seeking = null; seekTicks = 0;
            phase = Phase.COLLECT;
            return;
        }
        if (inside < START_PAIR) {
            if (spawnReady(sl)) {
                dropCell = findDropCell(sl, r);
                workTicks = 0;
                phase = Phase.SPAWNING;
            } else {
                goIdle(sl);
            }
            return;
        }
        if (inside < cap && hasBreedingResearch()) {
            List<Animal> ready = herd.stream().filter(HerderWorkGoal::breedReady).toList();
            if (ready.size() >= 2) {
                breedA = ready.get(0);
                breedB = ready.get(1);
                workTicks = 0;
                phase = Phase.TO_BREED;
                return;
            }
        }
        goIdle(sl);
    }

    private void goIdle(ServerLevel sl) {
        if (anchor != null) penCooldown.put(anchor.asLong(), sl.getGameTime() + PEN_IDLE_COOLDOWN);
        if (insideHerder()) {
            restSpot = exteriorRestSpot();
            herderStuck = 0;
            prevX = citizen.getX();
            prevZ = citizen.getZ();
            phase = Phase.LEAVE;
        } else {
            phase = Phase.IDLE;
        }
    }

    private void tickLeave(ServerLevel sl) {
        if (restSpot == null || !insideHerder()) { phase = Phase.IDLE; return; }
        lookAndApproach(restSpot);
        if (citizen.blockPosition().distSqr(restSpot) <= 4) { phase = Phase.IDLE; return; }
        double moved = (citizen.getX() - prevX) * (citizen.getX() - prevX)
            + (citizen.getZ() - prevZ) * (citizen.getZ() - prevZ);
        if (moved < 0.0025) {
            if (++herderStuck > HERDER_STUCK_LIMIT) {
                teleport(citizen, restSpot.getX() + 0.5, citizen.getY(), restSpot.getZ() + 0.5);
                herderStuck = 0;
                phase = Phase.IDLE;
            }
        } else {
            herderStuck = 0;
        }
        prevX = citizen.getX();
        prevZ = citizen.getZ();
    }

    private BlockPos exteriorRestSpot() {
        BlockPos c = penCenter();
        BlockPos g = gatePos != null ? gatePos : c;
        int ox = Integer.signum(g.getX() - c.getX());
        int oz = Integer.signum(g.getZ() - c.getZ());
        if (ox == 0 && oz == 0) ox = 1;
        return g.offset(ox * 2, 0, oz * 2);
    }

    private void tickCollect(ServerLevel sl) {
        pruneBatch();

        int room = capacity(sl) - penHerd(sl, pen).size() - batch.size();
        if (batch.size() >= MAX_BATCH || room <= 0) { beginLead(sl, pen); return; }

        if (seeking == null || !seeking.isAlive() || insidePen(seeking) || isClaimed(sl, seeking)
                || citizen.distanceToSqr(seeking) > (CAPTURE_RADIUS + 12) * (CAPTURE_RADIUS + 12)) {
            seeking = pickNext(sl);
            seekTicks = 0;
            if (seeking != null) domesticate(seeking);
        }
        if (seeking == null) { beginLead(sl, pen); return; }

        lookAndApproach(seeking.blockPosition());
        if (citizen.distanceToSqr(seeking) <= REACH * REACH) {
            claim(seeking);
            seeking = null;
        } else if (++seekTicks > SEEK_TIMEOUT) {
            seeking = null;
        }
    }

    private Animal pickNext(ServerLevel sl) {
        Animal escaped = findEscaped(sl, pen);
        return escaped != null ? escaped : findWild(sl, pen);
    }

    private void beginLead(ServerLevel sl, PenEnclosure.Result r) {
        seeking = null;
        if (batch.isEmpty()) { assess(sl); return; }
        dropCell = findDropCell(sl, r);
        leadTicks = 0;
        herderStuck = 0;
        leadStall = 0;
        prevX = citizen.getX();
        prevZ = citizen.getZ();
        phase = Phase.LEAD;
    }

    private void tickLead(ServerLevel sl) {
        pruneBatch();
        if (batch.isEmpty()) { assess(sl); return; }
        if (dropCell == null) dropCell = findDropCell(sl, pen);
        lookAndApproach(dropCell);

        if (insideHerder()) {
            herderStuck = 0;
        } else {
            double moved = (citizen.getX() - prevX) * (citizen.getX() - prevX)
                + (citizen.getZ() - prevZ) * (citizen.getZ() - prevZ);
            if (moved < 0.0025 && ++herderStuck > HERDER_STUCK_LIMIT) {
                teleport(citizen, dropCell.getX() + 0.5, dropCell.getY() + 1, dropCell.getZ() + 0.5);
                herderStuck = 0;
            } else if (moved >= 0.0025) {
                herderStuck = 0;
            }
        }
        prevX = citizen.getX();
        prevZ = citizen.getZ();

        boolean posted = citizen.distanceToSqr(
            dropCell.getX() + 0.5, dropCell.getY(), dropCell.getZ() + 0.5) <= 2.25;

        // Teleport the remaining animals when the herder reaches the goal path. Becuz sometimes the herder is stuck for too long allowing other animals to get out.
        if (posted) {
            placeBatch(sl);
            return;
        }

        List<BlockPos> finishSpots = null;
        int finished = 0;
        java.util.Iterator<Animal> it = batch.iterator();
        while (it.hasNext()) {
            Animal a = it.next();
            boolean done = arrivedInside(a);
            if (!done && a.getNavigation().isDone() && a.distanceToSqr(citizen) <= 9.0) {
                if (finishSpots == null) finishSpots = centerCells(sl, pen, Math.max(1, batch.size()));
                BlockPos c = finishSpots.get(Math.min(finished++, finishSpots.size() - 1));
                teleport(a, c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5);
                done = true;
            }
            if (done) {
                a.setPersistenceRequired();
                a.removeData(BannerboundCore.HERDED_BY.get());
                it.remove();
                leadStall = 0;
                citizen.grantJobXp(JOB_TYPE_ID, 0.5F, "herd");
            }
        }
        if (batch.isEmpty()) { assess(sl); return; }
        if (posted && ++leadStall > LEAD_STALL_LIMIT) { placeBatch(sl); return; }
        if (++leadTicks > LEAD_TIMEOUT) placeBatch(sl);
    }

    private void placeBatch(ServerLevel sl) {
        List<BlockPos> spots = centerCells(sl, pen, batch.size());
        for (int i = 0; i < batch.size(); i++) {
            Animal a = batch.get(i);
            if (!strictInside(a)) {
                BlockPos c = spots.get(Math.min(i, spots.size() - 1));
                teleport(a, c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5);
            }
            a.setPersistenceRequired();
            a.removeData(BannerboundCore.HERDED_BY.get());
        }
        batch.clear();
        dropCell = null;
        leadTicks = 0;
        leadStall = 0;
        assess(sl);
    }

    private void claim(Animal a) {
        a.setData(BannerboundCore.HERDED_BY.get(), citizen.getId());
        domesticate(a);
        a.setPersistenceRequired();
        ensureFollowGoal(a);
        if (!batch.contains(a)) batch.add(a);
    }

    private static void ensureFollowGoal(Animal a) {
        boolean present = a.goalSelector.getAvailableGoals().stream()
            .anyMatch(g -> g.getGoal() instanceof HerdFollowGoal);
        if (!present) a.goalSelector.addGoal(2, new HerdFollowGoal(a));
    }

    private void releaseBatch() {
        for (Animal a : batch) if (a.isAlive()) a.removeData(BannerboundCore.HERDED_BY.get());
        batch.clear();
    }

    private void pruneBatch() {
        batch.removeIf(a -> {
            Integer h = a.getExistingDataOrNull(BannerboundCore.HERDED_BY.get());
            return !a.isAlive() || h == null || h != citizen.getId();
        });
    }

    private boolean isClaimed(ServerLevel sl, Animal a) {
        Integer h = a.getExistingDataOrNull(BannerboundCore.HERDED_BY.get());
        return h != null && h != 0 && sl.getEntity(h) instanceof CitizenEntity c && c.isAlive();
    }

    private int capacity(ServerLevel sl) {
        return PenEnclosure.stats(sl, pen).capacity(animalSize(herdType));
    }

    public static int animalSize(EntityType<?> type) {
        return (type == EntityType.COW || type == EntityType.HORSE)
            ? com.bannerbound.core.Config.HERDER_PEN_LARGE_FOOTPRINT.get() : 1;
    }

    public static int foodSize(EntityType<?> type) {
        if (type == EntityType.HORSE) return 3;
        if (type == EntityType.COW || type == EntityType.SHEEP || type == EntityType.PIG) return 2;
        return 1;
    }

    private boolean insidePen(Animal a) {
        return nearInterior(a.getX(), a.blockPosition().getY(), a.getZ(), 1.0);
    }

    private boolean strictInside(Animal a) {
        return nearInterior(a.getX(), a.blockPosition().getY(), a.getZ(), 0.0);
    }

    private boolean arrivedInside(Animal a) {
        return nearInterior(a.getX(), a.blockPosition().getY(), a.getZ(), 0.5);
    }

    private boolean insideHerder() {
        return nearInterior(citizen.getX(), citizen.blockPosition().getY(), citizen.getZ(), 0.5);
    }

    private boolean nearInterior(double x, int feetY, double z, double margin) {
        int cx0 = (int) Math.floor(x - margin), cx1 = (int) Math.floor(x + margin);
        int cz0 = (int) Math.floor(z - margin), cz1 = (int) Math.floor(z + margin);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (pen.interior().contains(new BlockPos(cx, feetY, cz))
                    || pen.interior().contains(new BlockPos(cx, feetY - 1, cz))) {
                    return true;
                }
            }
        }
        return false;
    }

    private BlockPos findGate(ServerLevel sl, PenEnclosure.Result r) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = r.min().getX() - 1; x <= r.max().getX() + 1; x++) {
            for (int z = r.min().getZ() - 1; z <= r.max().getZ() + 1; z++) {
                for (int dy = -2; dy <= 2; dy++) {
                    m.set(x, r.min().getY() + dy, z);
                    if (sl.getBlockState(m).is(BlockTags.FENCE_GATES)) {
                        double d = citizen.blockPosition().distSqr(m);
                        if (d < bestD) { bestD = d; best = m.immutable(); }
                    }
                }
            }
        }
        return best;
    }

    private BlockPos gateApproach(ServerLevel sl, boolean inside) {
        if (gatePos == null) return null;
        BlockState gs = sl.getBlockState(gatePos);
        if (!gs.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return gatePos;
        net.minecraft.core.Direction facing = gs.getValue(BlockStateProperties.HORIZONTAL_FACING);
        BlockPos c = penCenter();
        boolean facingInward = gatePos.relative(facing).distSqr(c) < gatePos.relative(facing.getOpposite()).distSqr(c);
        net.minecraft.core.Direction inward = facingInward ? facing : facing.getOpposite();
        return gatePos.relative(inside ? inward : inward.getOpposite());
    }

    private void tickSpawning(ServerLevel sl) {
        int have = penHerd(sl, pen).size();
        if (have >= START_PAIR) { assess(sl); return; }
        if (dropCell == null) dropCell = findDropCell(sl, pen);
        lookAndApproach(dropCell);
        double d = citizen.distanceToSqr(
            dropCell.getX() + 0.5, dropCell.getY() + 0.5, dropCell.getZ() + 0.5);
        if (d > REACH * REACH) { workTicks = 0; return; }
        citizen.getNavigation().stop();
        swingEvery(sl, 8);
        if (++workTicks < skilledWorkTicks(SPAWN_WORK_TICKS)) return;
        spawnAdult(sl, pen);
        citizen.getPersistentData().putLong(SPAWN_AT_KEY, sl.getGameTime());
        citizen.grantJobXp(JOB_TYPE_ID, 0.5F, "herd");
        citizen.consumeStamina(1);
        workTicks = 0;
        assess(sl);
    }

    private boolean spawnReady(ServerLevel sl) {
        if (!citizen.getPersistentData().contains(SPAWN_AT_KEY)) return true;
        return sl.getGameTime() - citizen.getPersistentData().getLong(SPAWN_AT_KEY) >= SPAWN_COOLDOWN;
    }

    private void spawnAdult(ServerLevel sl, PenEnclosure.Result r) {
        List<BlockPos> cells = List.copyOf(r.interior());
        for (int attempt = 0; attempt < 12 && !cells.isEmpty(); attempt++) {
            BlockPos c = cells.get(citizen.getRandom().nextInt(cells.size()));
            BlockState floor = sl.getBlockState(c);
            if (!floor.blocksMotion() || !floor.getFluidState().isEmpty()) continue;
            if (sl.getBlockState(c.above()).blocksMotion()) continue;
            Animal mob = herdType.create(sl);
            if (mob == null) return;
            mob.moveTo(c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5, citizen.getRandom().nextFloat() * 360f, 0f);
            mob.finalizeSpawn(sl, sl.getCurrentDifficultyAt(c), MobSpawnType.MOB_SUMMONED, null);
            mob.setPersistenceRequired();
            domesticate(mob);
            sl.addFreshEntity(mob);
            return;
        }
    }

    private Animal selectCullTarget(List<Animal> herd, int keepAdults) {
        int adults = 0;
        for (Animal a : herd) if (a.isAlive() && !a.isBaby()) adults++;
        if (adults <= keepAdults) return null;
        Animal best = null;
        double bestD = Double.MAX_VALUE;
        for (Animal victim : herd) {
            if (!victim.isAlive() || victim.isBaby()) continue;
            double d = citizen.distanceToSqr(victim);
            if (d < bestD) { bestD = d; best = victim; }
        }
        return best;
    }

    private void tickToCull(ServerLevel sl) {
        Container harvest = DropOffContainers.resolveOrPreferred(citizen, citizen.getDropOff());
        if (harvest == null) { cullTarget = null; assess(sl); return; }
        int keepAdults = adultKeepTarget(sl, capacity(sl));
        if (cullTarget == null || !cullTarget.isAlive() || cullTarget.isBaby() || !insidePen(cullTarget)) {
            cullTarget = selectCullTarget(penHerd(sl, pen), keepAdults);
            workTicks = 0;
            if (cullTarget == null) { assess(sl); return; }
        }
        lookAndApproach(cullTarget.blockPosition());
        if (citizen.distanceToSqr(cullTarget) > REACH * REACH) { workTicks = 0; return; }
        citizen.getNavigation().stop();
        citizen.getLookControl().setLookAt(cullTarget);
        if (workTicks > 0) { workTicks--; return; }

        citizen.swing(InteractionHand.MAIN_HAND);
        cullTarget.hurt(citizen.damageSources().mobAttack(citizen), (float) butcherDamage());
        workTicks = butcherCooldownTicks();
        if (!cullTarget.isAlive()) {
            citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "herd");
            citizen.consumeStamina(1);
            bumpKills(sl, 1);
            if (citizen.getSettlement() != null) {
                citizen.getSettlement().addFoodProduced("livestock", foodSize(cullTarget.getType()));
            }
            cullTarget = null;
            workTicks = 0;
            assess(sl);
        }
    }

    private void bumpKills(ServerLevel sl, int add) {
        BlockSelection sel = penMarkerAt(sl);
        if (sel == null) return;
        String packed = sel.seedItemId();
        BlockSelectionRegistry.get(sl).register(
            sel.withSeed(packPen(penAnimalId(packed), penKills(packed) + add, penKeep(packed))));
        SelectionBroadcaster.broadcast(sl.getServer());
    }

    private void tickToBreed(ServerLevel sl) {
        if (breedA == null || breedB == null || !breedA.isAlive() || !breedB.isAlive()
            || !breedReady(breedA) || !breedReady(breedB)) { assess(sl); return; }
        lookAndApproach(breedA.blockPosition());
        if (citizen.distanceToSqr(breedA) > REACH * REACH) { workTicks = 0; return; }
        citizen.getNavigation().stop();
        citizen.getLookControl().setLookAt(breedA);
        swingEvery(sl, 8);
        if (++workTicks < skilledWorkTicks(FEED_WORK_TICKS)) return;
        breedA.setInLove(null);
        breedB.setInLove(null);
        citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "herd");
        citizen.consumeStamina(1);
        breedA = breedB = null;
        workTicks = 0;
        assess(sl);
    }

    private void tickCollectManure(ServerLevel sl) {
        if (manurePos == null || !sl.getBlockState(manurePos).is(BreedingEvents.MANURE)) {
            manurePos = findManure(sl, pen);
            if (manurePos == null) { assess(sl); return; }
        }
        lookAndApproach(manurePos);
        double d = citizen.distanceToSqr(manurePos.getX() + 0.5, manurePos.getY() + 0.5, manurePos.getZ() + 0.5);
        if (d <= REACH * REACH) {
            collectManure(sl, manurePos);
            manurePos = null;
            assess(sl);
        }
    }

    private BlockPos findManure(ServerLevel sl, PenEnclosure.Result r) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos c : r.interior()) {
            BlockPos air = c.above();
            if (!sl.getBlockState(air).is(BreedingEvents.MANURE)) continue;
            double d = citizen.blockPosition().distSqr(air);
            if (d < bestD) { bestD = d; best = air.immutable(); }
        }
        return best;
    }

    private void collectManure(ServerLevel sl, BlockPos pos) {
        BlockState st = sl.getBlockState(pos);
        if (!st.is(BreedingEvents.MANURE)) return;
        List<ItemStack> drops = Block.getDrops(st, sl, pos, null);
        sl.removeBlock(pos, false);
        sl.levelEvent(2001, pos, Block.getId(st));
        Container harvest = DropOffContainers.resolveOrPreferred(citizen, citizen.getDropOff());
        for (ItemStack drop : drops) {
            ItemStack rem = harvest != null ? DropOffContainers.insert(harvest, drop) : drop;
            if (!rem.isEmpty()) Block.popResource(sl, pos, rem);
        }
        citizen.grantJobXp(JOB_TYPE_ID, 0.5F, "herd");
        citizen.consumeStamina(1);
    }

    private void holdRope() {
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, citizen.getJobTool().copy());
    }

    private void equipForPhase() {
        net.minecraft.world.item.ItemStack want = switch (phase) {
            case COLLECT, LEAD, TO_BREED -> catalystFor(herdType);
            case TO_CULL -> butcherWeapon();
            default -> citizen.getJobTool().copy();
        };
        if (!citizen.getMainHandItem().is(want.getItem())) {
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, want);
        }
    }

    private ItemStack butcherWeapon() {
        Settlement s = citizen.getSettlement();
        if (s != null) {
            Item tool = s.getToolForRole("knife");
            if (tool == Items.AIR) tool = s.getToolForRole("sword");
            if (tool == Items.AIR) tool = s.getToolForRole("hunt");
            if (tool != Items.AIR) return new ItemStack(tool);
        }
        return citizen.getJobTool().copy();
    }

    private double butcherDamage() {
        Settlement s = citizen.getSettlement();
        return s == null ? DEFAULT_BUTCHER_DAMAGE : s.getWeaponDamageOrDefault(DEFAULT_BUTCHER_DAMAGE);
    }

    private int butcherCooldownTicks() {
        Settlement s = citizen.getSettlement();
        double speed = s == null ? DEFAULT_BUTCHER_ATTACK_SPEED
            : s.getWeaponAttackSpeedOrDefault(DEFAULT_BUTCHER_ATTACK_SPEED);
        return speed <= 0.0 ? 20 : Math.max(5, (int) Math.round(20.0 / speed));
    }

    private void swingEvery(ServerLevel sl, int interval) {
        if (workTicks % interval == 0) {
            sl.getChunkSource().broadcastAndSend(citizen,
                new net.minecraft.network.protocol.game.ClientboundAnimatePacket(citizen, 0));
        }
    }

    private static net.minecraft.world.item.ItemStack catalystFor(EntityType<?> type) {
        net.minecraft.world.item.Item food;
        if (type == EntityType.PIG) food = net.minecraft.world.item.Items.CARROT;
        else if (type == EntityType.CHICKEN) food = net.minecraft.world.item.Items.WHEAT_SEEDS;
        else food = net.minecraft.world.item.Items.WHEAT;
        return new net.minecraft.world.item.ItemStack(food);
    }

    private void lookAndApproach(BlockPos pos) {
        citizen.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        // Re-path on a cadence, not just when nav is done: a route computed before the gate opened stalls at it.
        if (citizen.getNavigation().isDone() || citizen.tickCount % 15 == 0) {
            citizen.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, skilledSpeed());
        }
    }

    private static void teleport(net.minecraft.world.entity.Entity e, double x, double y, double z) {
        e.teleportTo(x, y, z);
        // Tag the entity so rope-fence collision accepts this cross-rope jump instead of bouncing it back.
        e.getPersistentData().putLong(TELEPORT_AT, e.level().getGameTime());
    }

    private BlockPos penCenter() {
        return pen.min().offset((pen.max().getX() - pen.min().getX()) / 2, 0,
            (pen.max().getZ() - pen.min().getZ()) / 2);
    }

    private List<Animal> penHerd(ServerLevel sl, PenEnclosure.Result r) {
        return sl.getEntitiesOfClass(Animal.class, r.bounds().inflate(1.0, 2.0, 1.0),
            a -> a.isAlive() && a.getType() == herdType && insidePen(a));
    }

    private Animal findWild(ServerLevel sl, PenEnclosure.Result r) {
        AABB search = r.bounds().inflate(CAPTURE_RADIUS, 8.0, CAPTURE_RADIUS);
        Animal best = null;
        double bestD = Double.MAX_VALUE;
        for (Animal a : sl.getEntitiesOfClass(Animal.class, search,
                a -> a.isAlive() && a.getType() == herdType && !a.isLeashed() && !isDomesticated(a))) {
            if (strictInside(a)) continue;
            double d = citizen.distanceToSqr(a);
            if (d < bestD) { bestD = d; best = a; }
        }
        return best;
    }

    private Animal findEscaped(ServerLevel sl, PenEnclosure.Result r) {
        AABB search = r.bounds().inflate(ESCAPE_RADIUS, 12.0, ESCAPE_RADIUS);
        Animal best = null;
        double bestD = Double.MAX_VALUE;
        for (Animal a : sl.getEntitiesOfClass(Animal.class, search,
                a -> a.isAlive() && a.getType() == herdType && !a.isLeashed()
                    && isDomesticated(a) && !isClaimed(sl, a))) {
            if (insidePen(a)) continue;
            double d = citizen.distanceToSqr(a);
            if (d < bestD) { bestD = d; best = a; }
        }
        return best;
    }

    private BlockPos findDropCell(ServerLevel sl, PenEnclosure.Result r) {
        BlockPos in = gateApproach(sl, true);
        if (in != null && r.interior().contains(in)
                && sl.getBlockState(in.below()).blocksMotion()
                && !sl.getBlockState(in).blocksMotion()
                && !sl.getBlockState(in.above()).blocksMotion()) {
            return in;
        }
        List<BlockPos> spots = centerCells(sl, r, 1);
        return spots.isEmpty() ? penCenter() : spots.get(0);
    }

    private List<BlockPos> centerCells(ServerLevel sl, PenEnclosure.Result r, int n) {
        BlockPos focus = penCenter();
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos c : r.interior()) {
            BlockState floor = sl.getBlockState(c);
            if (!floor.blocksMotion() || !floor.getFluidState().isEmpty()) continue;
            if (sl.getBlockState(c.above()).blocksMotion()) continue;
            cells.add(c.immutable());
        }
        if (cells.isEmpty()) { cells.add(penCenter()); return cells; }
        cells.sort((p, q) -> Double.compare(p.distSqr(focus), q.distSqr(focus)));
        return cells.subList(0, Math.min(Math.max(1, n), cells.size()));
    }

    private static boolean breedReady(Animal a) {
        return a.isAlive() && !a.isBaby() && a.getAge() == 0 && a.canFallInLove() && !a.isInLove();
    }

    private boolean hasBreedingResearch() {
        return com.bannerbound.core.api.research.ResearchManager.hasFlag(
            citizen.getSettlement(), com.bannerbound.core.event.VanillaGates.FLAG);
    }

    private static boolean isDomesticated(Animal a) {
        return a.getPersistentData().getBoolean(DOMESTICATED_TAG);
    }

    private static void domesticate(Animal a) {
        a.getPersistentData().putBoolean(DOMESTICATED_TAG, true);
        if (a instanceof net.minecraft.world.entity.animal.horse.AbstractHorse h && !h.isTamed()) {
            h.setTamed(true);
        }
    }

    private void removeMarker(ServerLevel sl, BlockSelection sel) {
        if (sel == null) return;
        BlockSelectionRegistry.get(sl).unregister(sel.rodId());
        SelectionBroadcaster.broadcast(sl.getServer());
    }

    private BlockSelection findPenMarker(ServerLevel sl) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
        long now = sl.getGameTime();
        penCooldown.values().removeIf(t -> now >= t);
        BlockSelection best = null;
        int bestScore = Integer.MAX_VALUE;
        double bestD = Double.MAX_VALUE;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            BlockPos a = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            boolean open = sel.targetsAllWorkers();
            int score;
            if (!open) {
                score = 0;
            } else {
                if (PenClaims.isClaimedByOther(sl, a, citizen.getId())) continue;
                score = PenClaims.ownedBy(a, citizen.getId()) ? 1 : 2;
                Long until = penCooldown.get(a.asLong());
                if (until != null && now < until) score += 10;
            }
            double d = citizen.distanceToSqr(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
            if (score < bestScore || (score == bestScore && d < bestD)) {
                best = sel; bestScore = score; bestD = d;
            }
        }
        if (best != null && best.targetsAllWorkers()) {
            PenClaims.claim(new BlockPos(best.minX(), best.minY(), best.minZ()), citizen.getId());
        }
        return best;
    }

    private BlockSelection penMarkerAt(ServerLevel sl) {
        if (anchor == null) return null;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.kind() == BlockSelection.Kind.WORKSTATION && SELECTION_TYPE.equals(sel.workstationType())
                    && sel.minX() == anchor.getX() && sel.minY() == anchor.getY() && sel.minZ() == anchor.getZ()) {
                return sel;
            }
        }
        return null;
    }

    private int adultKeepTarget(ServerLevel sl, int cap) {
        BlockSelection sel = penMarkerAt(sl);
        int keep = sel == null ? 0 : penKeep(sel.seedItemId());
        return keep <= 0 ? cap : Math.max(2, Math.min(keep, cap));
    }

    public static String packPen(String animalId, int kills) {
        return packPen(animalId, kills, 0);
    }

    public static String packPen(String animalId, int kills, int keep) {
        return animalId + "|" + kills + "|" + keep;
    }

    public static String penAnimalId(String packed) {
        if (packed == null || packed.isEmpty()) return "";
        int i = packed.indexOf('|');
        return i < 0 ? packed : packed.substring(0, i);
    }

    public static int penKills(String packed) {
        return packedInt(packed, 1);
    }

    public static int penKeep(String packed) {
        return packedInt(packed, 2);
    }

    private static int packedInt(String packed, int index) {
        if (packed == null) return 0;
        String[] parts = packed.split("\\|");
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    public static EntityType<? extends Animal> animalFromMarker(BlockSelection sel) {
        if (sel == null) return null;
        ResourceLocation id = ResourceLocation.tryParse(penAnimalId(sel.seedItemId()));
        EntityType<?> t = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        return t == null ? null : (EntityType<? extends Animal>) t;
    }
}
