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
 * Herder {@link OrderedWorkGoal} — tends a fenced <b>pen</b> on a livestock chunk. Marked with a single
 * Foreman's-Rod click ({@link PenEnclosure} auto-detects the enclosure). It doesn't haul; it keeps a herd
 * alive and growing, and rests when there's nothing to do. Behaviour (a small state machine):
 *
 * <ul>
 *   <li><b>Corral</b> (leash-free): it walks out to wild animals of the chunk's kind, <i>claims</i> a
 *       batch of them (calming each so it joins a loose herd that follows the herder — no real leash, so
 *       nothing tangles), walks the group back, and drops them inside the pen near the centre. Only if
 *       none are around does it fall back to spawning a starter pair.</li>
 *   <li><b>Breed</b>: if there are ≥2 ready adults (and room), it fetches the right breeding food from a
 *       marked food storage, carries it to the pair, feeds them, and goes back to rest.</li>
 * </ul>
 *
 * <p>Every animal it brings in/spawns is <b>domesticated</b> (shared {@code BannerboundDomesticated} tag,
 * honoured by Antiquity's {@code HuntingFear.isTamed}) so it doesn't flee players or leave footprints. The
 * follow is a gentle server-side pull, not a vanilla leash — the visible "rope" is cosmetic polish layered
 * on top of the herder's claim ({@code BannerboundCore.HERDED_BY}). Tool: a lead-rope held in the job
 * slot — a vanilla {@code minecraft:lead} standalone, or Antiquity's {@code fiber_rope} when installed
 * (both in the {@link #HERDER_ROPE_TAG}); breeding food is held while breeding.
 */
@ApiStatus.Internal
public class HerderWorkGoal extends OrderedWorkGoal {
    public static final String JOB_TYPE_ID = "herders_pen";
    public static final String SELECTION_TYPE = "herder";
    public static final ResourceLocation FIBER_ROPE_ID =
        ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "fiber_rope");
    /** Items that count as a herder's lead-rope tool. Core ships this tag with {@code minecraft:lead};
     *  Antiquity merges in its {@code fiber_rope}. So the herder works standalone (vanilla lead) and the
     *  expansion simply upgrades the era-appropriate option — recognised by tag, never by a hard item ref. */
    public static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> HERDER_ROPE_TAG =
        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("bannerbound", "herder_rope"));

    /** Is this stack a valid herder rope (vanilla lead, Antiquity fiber rope, or any addon's)? */
    public static boolean isRope(net.minecraft.world.item.ItemStack stack) {
        return stack.is(HERDER_ROPE_TAG);
    }
    /** Shared with Antiquity's {@code HuntingFear.DOMESTICATED_TAG}. */
    public static final String DOMESTICATED_TAG = "BannerboundDomesticated";
    /** Transient tag (gametime) set on an entity we deliberately teleport, so Antiquity's rope-fence
     *  collision accepts the cross-rope jump instead of shoving the entity back to its old side. Mirrored
     *  by the same literal in {@code RopeFenceCollision} (Core can't be imported there). */
    public static final String TELEPORT_AT = CitizenEntity.TELEPORT_AT_KEY;

    private static final int START_PAIR = 2;
    private static final int MAX_BATCH = 6;            // animals gathered into one trip before leading them in
    private static final double CAPTURE_RADIUS = 24.0; // how far out the herder will look for wild stock
    private static final double ESCAPE_RADIUS = 32.0;  // how far out it will chase down its own escapees
    private static final double REACH = 2.5;           // close enough to claim / feed
    private static final int SEEK_TIMEOUT = 200;       // give up walking to one wild animal after ~10s
    private static final int LEAD_TIMEOUT = 600;       // give up walking the batch in after ~30s → just place
    private static final int HERDER_STUCK_LIMIT = 40;  // herder not moving while leading for ~2s → teleport to centre
    private static final int FEED_WORK_TICKS = 24;     // short, visible feeding action before love mode
    private static final int DEFAULT_BUTCHER_DAMAGE = 4; // wood-sword baseline; actual weapon age may override
    private static final double DEFAULT_BUTCHER_ATTACK_SPEED = 1.6;
    private static final int SPAWN_WORK_TICKS = 40;    // starter-pair fallback still happens from the pen floor
    private static final long SPAWN_COOLDOWN = 12_000L; // half an MC day between spawn-pair fallbacks
    /** Persistent key on the citizen: gametime of the last spawn-pair fallback (for the cooldown). */
    private static final String SPAWN_AT_KEY = "BannerboundHerdSpawnAt";

    private enum Phase { IDLE, COLLECT, LEAD, SPAWNING, TO_BREED, TO_CULL, COLLECT_MANURE, LEAVE }

    private BlockPos anchor;
    private PenEnclosure.Result pen;
    private EntityType<? extends Animal> herdType;
    private Phase phase = Phase.IDLE;
    /** OPEN pens this herder recently found no work at → avoid re-picking for a bit (posLong → gameTime until),
     *  so a lone herder rotates across open pens instead of looping on one. */
    private final java.util.Map<Long, Long> penCooldown = new java.util.HashMap<>();
    private static final long PEN_IDLE_COOLDOWN = 120L;   // ~6s before re-considering an idled open pen
    private final List<Animal> batch = new ArrayList<>(); // claimed animals following the herder right now
    private Animal seeking;          // the next wild animal the herder is walking over to claim
    private int seekTicks;           // watchdog so an uncatchable animal is eventually skipped
    private BlockPos dropCell;       // validated interior cell the herder walks to while leading
    private BlockPos gatePos;        // the pen's gate (cached when the pen is scanned), auto opened/closed
    private int leadTicks;           // watchdog so a stuck lead eventually just places the batch
    private int herderStuck;         // herder-not-moving counter → teleport the herder to its gate-side post
    private double prevX, prevZ;     // herder's last position, to detect it being stuck while leading
    private BlockPos restSpot;       // a spot OUTSIDE the pen the herder walks to when idle (leaves it to the herd)
    private Animal breedA, breedB;   // the pair to tend
    private Animal cullTarget;       // the surplus adult currently being butchered
    private BlockPos manurePos;      // the manure block (air cell) the herder is walking over to muck out
    private int workTicks;           // visible timed work for feeding/spawning, attack cooldown for culling

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
        PenClaims.releaseAll(citizen.getId());   // fresh each evaluation; findPenMarker re-claims if it commits
        BlockSelection sel = findPenMarker(sl);
        if (sel == null) return false;
        anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
        // Outpost pens (working-claim chunks) make this herder an OUTPOST WORKER: they sleep in
        // the site's roofed bed, idle-anchor at the pen, and the Job tab greys storage controls —
        // identical to the miner's arrangement (see CitizenEntity#setOutpostSite).
        Settlement os = citizen.getSettlement();
        citizen.setOutpostSite(os != null && os.workingClaims().contains(
            new net.minecraft.world.level.ChunkPos(anchor).toLong()) ? anchor.immutable() : null);
        if (!sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) return false; // pen not loaded → can't judge
        pen = PenEnclosure.scan(sl, anchor);
        if (!pen.valid()) {                  // chunk loaded but no valid pen → it was destroyed → drop the marker
            removeMarker(sl, sel);
            return false;
        }
        gatePos = findGate(sl, pen);
        herdType = animalFromMarker(sel);   // the pen's chosen species (stored on the marker)
        if (herdType == null) return false;
        assess(sl);                 // is there actually work to do?
        // No work → the goal won't start, so manageOwnGate won't run. Close a gate a chunk unload
        // left hanging open (unload skips stop()) so the pen never leaks its flock while idle.
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
        releaseBatch();   // free any followers (no leash to drop) so none are left "herded" with no herder
        PenClaims.releaseAll(citizen.getId());   // free our open-pen reservation so another herder can take it
        // Never leave our pen gate hanging open / reserved when the goal deactivates (e.g. tool removed mid-lead).
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

    /** The herder is the SINGLE owner of its pen gate the whole time it tends this pen — it {@linkplain
     *  GateHolds reserves} the gate every tick so the shared {@link OpenFenceGateGoal} never also touches it
     *  (two systems toggling the same gate each tick was the rapid open/close FLICKER). Open iff something is
     *  actually crossing: the flock is at/near the gate, or the herder itself is walking through it (entering
     *  to lead, or leaving). Closed otherwise, so the pen stays shut. The reservation lapses on its own, so
     *  nothing leaks if the herder dies or disengages; {@link #stop()} also releases it. */
    private void manageOwnGate(ServerLevel sl) {
        if (gatePos == null) return;
        GateHolds.hold(gatePos, citizen.getId(), sl.getGameTime());
        // Hold the gate OPEN the whole time there's a claimed flock to bring in. Proximity-only opening was a
        // DEADLOCK: a closed gate's 1.5-tall collision bar makes vanilla reject the gate cell (floor-level
        // step-up), so the animal can't path TO the gate to trigger the opening in the first place. Holding
        // it open from the start means the animal's pathfinder sees a clear, routable gate.
        boolean wantOpen = !batch.isEmpty()
            || herderCrossingGate()
            || herdedAnimalNear(sl, gatePos, 7.5)
            || (phase == Phase.LEAVE && insideHerder());
        setOwnGate(sl, wantOpen);
    }

    /** True while the herder is actively walking through (within ~2.5 of) its gate — so it opens for the
     *  herder's own entry/exit, not just the flock. The herder is citizen-narrow, so vanilla A* routes it
     *  through a 1-wide gate fine; it only needs the gate open by the time it arrives. */
    private boolean herderCrossingGate() {
        return !citizen.getNavigation().isDone()
            && citizen.distanceToSqr(gatePos.getX() + 0.5, gatePos.getY() + 0.5, gatePos.getZ() + 0.5) <= 6.25;
    }

    /** Open/close {@link #gatePos} by tag + the OPEN property (works for vanilla AND the rope gate; never
     *  {@code instanceof} a vanilla class). No-op if it's already in that state or isn't a gate. */
    private void setOwnGate(ServerLevel sl, boolean open) {
        BlockState state = sl.getBlockState(gatePos);
        if (!state.is(BlockTags.FENCE_GATES) || !state.hasProperty(BlockStateProperties.OPEN)) return;
        if (state.getValue(BlockStateProperties.OPEN) == open) return;
        sl.setBlock(gatePos, state.setValue(BlockStateProperties.OPEN, open), 10);
        sl.playSound(null, gatePos, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE,
            SoundSource.BLOCKS, 1.0f, 1.0f);
        sl.gameEvent(citizen, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, gatePos);
    }

    /** Is any animal the herder has claimed ({@code HERDED_BY} set) within {@code r} blocks of {@code pos}? */
    private boolean herdedAnimalNear(ServerLevel sl, BlockPos pos, double r) {
        AABB box = new AABB(pos).inflate(r);
        for (Animal a : sl.getEntitiesOfClass(Animal.class, box)) {
            Integer h = a.getExistingDataOrNull(BannerboundCore.HERDED_BY.get());
            if (h != null && h != 0) return true;
        }
        return false;
    }

    // ─── Assessment: pick the next task (or idle) ────────────────────────────────────────────────

    private void assess(ServerLevel sl) {
        PenEnclosure.Result r = PenEnclosure.scan(sl, anchor);
        if (!r.valid()) {
            // Pen destroyed (chunk loaded but no longer a valid enclosure) → remove its marker selection.
            if (sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) removeMarker(sl, findPenMarker(sl));
            phase = Phase.IDLE;
            return;
        }
        pen = r;
        gatePos = findGate(sl, r);
        List<Animal> herd = penHerd(sl, r);
        // Calm everything in the pen (incl. babies + any stray that wandered in) → it won't flee and is
        // re-corralled by proximity if it later escapes.
        for (Animal a : herd) if (!isDomesticated(a)) domesticate(a);
        int cap = capacity(sl);                 // the maximum population this pen can hold
        int keepAdults = adultKeepTarget(sl, cap); // the adult floor below which culling stops
        // Culling is a physical task now: pick one surplus adult, walk to it, then butcher it.
        Animal surplus = selectCullTarget(herd, keepAdults);
        if (surplus != null && DropOffContainers.resolveOrPreferred(citizen, citizen.getDropOff()) != null) {
            cullTarget = surplus;
            workTicks = 0;
            phase = Phase.TO_CULL;
            return;
        }
        int inside = herd.size();

        // Already carrying a batch? Lead it in.
        if (!batch.isEmpty()) { beginLead(sl, r); return; }

        // Maintenance: muck out the pen. Manure fouls fertility (see BreedingEvents), so clear it before
        // tending the herd — a clean pen breeds best. Not research-gated: basic upkeep any herder does.
        BlockPos manure = findManure(sl, r);
        if (manure != null) { manurePos = manure; phase = Phase.COLLECT_MANURE; return; }

        // Round up escapees + bring in wild stock, up to the remaining capacity.
        if (inside < cap && (findEscaped(sl, r) != null || findWild(sl, r) != null)) {
            seeking = null; seekTicks = 0;
            phase = Phase.COLLECT;
            return;
        }
        // No stock to bring in. If we don't even have a breeding pair, spawn one (last resort) — but only
        // once per half-day so a wiped pen doesn't instantly refill from nothing.
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
        // Otherwise breeding proceeds passively toward the cap (just needs a ready pair — the herder has
        // feed on hand; the baby itself is the chance roll in BreedingEvents). GATED on the settlement having
        // researched Animal Husbandry — same flag as the player-feed gate, so a herder without husbandry can
        // corral and cull but not breed.
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

    /** Nothing to do. The herder would rather not loiter in the pen (leave the space to the animals), so if
     *  it's standing inside, send it out to a rest spot first; otherwise just idle and let the goal yield. */
    private void goIdle(ServerLevel sl) {
        // Remember this pen had no work, so findPenMarker rotates a lone herder to other open pens for a bit.
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

    /** Walk out of the pen, then idle (the goal yields once IDLE). Re-engages from {@link #canStartWork}
     *  if the herder ever ends up back inside with no work — so it won't settle in the pen. If it can't
     *  path out the rope gate (stuck ~2s), teleport it out so it never gets trapped (and never holds the
     *  gate open). */
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

    /** A standing spot a couple blocks OUTSIDE the gate (away from the pen interior). */
    private BlockPos exteriorRestSpot() {
        BlockPos c = penCenter();
        BlockPos g = gatePos != null ? gatePos : c;
        int ox = Integer.signum(g.getX() - c.getX());
        int oz = Integer.signum(g.getZ() - c.getZ());
        if (ox == 0 && oz == 0) ox = 1;   // gate ~ at centre (degenerate) → just pick a direction
        return g.offset(ox * 2, 0, oz * 2);
    }

    // ─── Corral (leash-free: claim a batch, soft-pull it along, place it inside) ───────────────────

    private void tickCollect(ServerLevel sl) {
        pruneBatch();   // claimed animals WALK behind the herder via their own HerdFollowGoal

        int room = capacity(sl) - penHerd(sl, pen).size() - batch.size();
        if (batch.size() >= MAX_BATCH || room <= 0) { beginLead(sl, pen); return; }

        // Pick the next animal to walk to (escapees first), and calm it so it doesn't bolt on approach.
        if (seeking == null || !seeking.isAlive() || insidePen(seeking) || isClaimed(sl, seeking)
                || citizen.distanceToSqr(seeking) > (CAPTURE_RADIUS + 12) * (CAPTURE_RADIUS + 12)) {
            seeking = pickNext(sl);
            seekTicks = 0;
            if (seeking != null) domesticate(seeking);
        }
        if (seeking == null) { beginLead(sl, pen); return; }   // nothing more nearby → lead what we have

        lookAndApproach(seeking.blockPosition());
        if (citizen.distanceToSqr(seeking) <= REACH * REACH) {
            claim(seeking);          // it now follows the herder (and gets a cosmetic rope, later)
            seeking = null;
        } else if (++seekTicks > SEEK_TIMEOUT) {
            seeking = null;          // couldn't catch this one — move on
        }
    }

    private Animal pickNext(ServerLevel sl) {
        Animal escaped = findEscaped(sl, pen);
        return escaped != null ? escaped : findWild(sl, pen);
    }

    /** Switch to leading: if nothing was actually collected, re-assess instead (spawn/breed/idle). */
    private void beginLead(ServerLevel sl, PenEnclosure.Result r) {
        seeking = null;
        if (batch.isEmpty()) { assess(sl); return; }
        dropCell = findDropCell(sl, r);
        leadTicks = 0;
        herderStuck = 0;
        prevX = citizen.getX();
        prevZ = citizen.getZ();
        phase = Phase.LEAD;
    }

    private void tickLead(ServerLevel sl) {
        pruneBatch();
        if (batch.isEmpty()) { assess(sl); return; }
        if (dropCell == null) dropCell = findDropCell(sl, pen);
        lookAndApproach(dropCell);   // herder posts just inside the gate, holding it open for the flock

        // Herder can't reach its gate-side post (stuck ~2s) → teleport it there (last-resort un-stick).
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


        // Telepport the remaining animals when the herder reaches the goal path. Becuz sometimes the herder is stuck for too long allowing other animals to get out.
        double distToDest = citizen.distanceToSqr(dropCell.getX() + 0.5, dropCell.getY(), dropCell.getZ() + 0.5);
        if (distToDest <= 2.25) {
            placeBatch(sl);
            return;
        }

        // Each claimed animal FOLLOWS the herder via its own nav (HerdFollowGoal) — like a cow trailing a
        // player holding wheat. The herder posts just inside the gate, so the follow is a short hop straight
        // through the opening. Release each the moment it's genuinely inside; and if it has followed right up
        // to the herder but STOPPED in the gateway without committing the last step (small pen — the herder
        // is already within the follow's stop-distance of the gate), teleport it the final block onto an
        // interior cell. That short finishing hop reads as stepping in, and is the agreed-good completion.
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
                a.removeData(BannerboundCore.HERDED_BY.get());   // penned now — stays domesticated
                it.remove();
                citizen.grantJobXp(JOB_TYPE_ID, 0.5F, "herd");
            }
        }
        if (batch.isEmpty()) { assess(sl); return; }
        if (++leadTicks > LEAD_TIMEOUT) placeBatch(sl);          // couldn't walk them all in → place remainder
    }

    /** Fallback for any followers that never managed to walk in (lead timed out): teleport the remainder
     *  onto interior cells near the centre and release them. The normal path is walking in via nav. */
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
        assess(sl);
    }

    // ─── Claim / follow helpers ────────────────────────────────────────────────────────────────────

    /** Take this animal into the herd: mark it ours, calm it, and give it the follow goal so it WALKS to
     *  the herder (the goal lives in the animal's own AI, so it beats wander; driving the nav from outside
     *  got overridden and the animals never moved). */
    private void claim(Animal a) {
        a.setData(BannerboundCore.HERDED_BY.get(), citizen.getId());   // synced → the client draws the rope
        domesticate(a);
        a.setPersistenceRequired();
        ensureFollowGoal(a);
        if (!batch.contains(a)) batch.add(a);
    }

    /** Add {@link HerdFollowGoal} to the animal's own goalSelector if absent (claims don't persist a reload,
     *  so it's re-added when the herder re-claims). Mirrors {@code PetBonding.ensureFollowGoal}. */
    private static void ensureFollowGoal(Animal a) {
        boolean present = a.goalSelector.getAvailableGoals().stream()
            .anyMatch(g -> g.getGoal() instanceof HerdFollowGoal);
        if (!present) a.goalSelector.addGoal(2, new HerdFollowGoal(a));
    }

    /** Free every current follower (clear the claim). No leash to drop — that's the whole point. */
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

    /** True if some LIVING herder currently claims this animal (so others don't poach an in-progress
     *  catch). A claim whose herder is gone reads as free, which self-heals orphaned claims. */
    private boolean isClaimed(ServerLevel sl, Animal a) {
        Integer h = a.getExistingDataOrNull(BannerboundCore.HERDED_BY.get());
        return h != null && h != 0 && sl.getEntity(h) instanceof CitizenEntity c && c.isAlive();
    }

    /** Max animals this pen holds — the pen's size-units divided by this animal's size cost (min 2). */
    private int capacity(ServerLevel sl) {
        return PenEnclosure.stats(sl, pen).capacity(animalSize(herdType));
    }

    /** Per-animal "size" cost against pen capacity: large animals (cow, horse) take the configured
     *  footprint (default 3); the rest take 1. */
    public static int animalSize(EntityType<?> type) {
        return (type == EntityType.COW || type == EntityType.HORSE)
            ? com.bannerbound.core.Config.HERDER_PEN_LARGE_FOOTPRINT.get() : 1;
    }

    /** Per-animal FOOD size — how much passive settlement food this species is worth (see
     *  {@link HerderFoodBonus}). Distinct from {@link #animalSize} (pen-capacity footprint): chicken = 1,
     *  cow/sheep/pig = 2, horse = 3. */
    public static int foodSize(EntityType<?> type) {
        if (type == EntityType.HORSE) return 3;
        if (type == EntityType.COW || type == EntityType.SHEEP || type == EntityType.PIG) return 2;
        return 1;   // chicken (and any other small/unknown penned animal)
    }

    // ─── Pen geometry / queries ────────────────────────────────────────────────────────────────────

    /** Is the animal "in the pen" for MEMBERSHIP (counting the herd, escapee detection)? Uses a generous
     *  1-block margin so an animal hugging the edge / straddling a boundary cell still counts as inside —
     *  NOT used for deciding what to bring in (that's {@link #strictInside}). */
    private boolean insidePen(Animal a) {
        return nearInterior(a.getX(), a.blockPosition().getY(), a.getZ(), 1.0);
    }

    /** Strict membership: the animal's centre is on an interior cell (margin 0). Used for "bring this in?"
     *  (findWild — so a wild animal right outside the fence is still corralled). */
    private boolean strictInside(Animal a) {
        return nearInterior(a.getX(), a.blockPosition().getY(), a.getZ(), 0.0);
    }

    /** Lenient "arrived → release it": a half-block margin so an animal whose centre is straddling the
     *  boundary cell (or has stopped just shy of the centre-posted herder) is freed, instead of staying
     *  herded forever waiting to stand dead-centre on an interior cell. Kept separate from {@link
     *  #strictInside} so findWild's corral test stays at margin 0. */
    private boolean arrivedInside(Animal a) {
        return nearInterior(a.getX(), a.blockPosition().getY(), a.getZ(), 0.5);
    }

    /** True if the herder itself is inside the pen (precise — half-block margin). */
    private boolean insideHerder() {
        return nearInterior(citizen.getX(), citizen.blockPosition().getY(), citizen.getZ(), 0.5);
    }

    /** True if any interior column lies within {@code margin} blocks of the (x,z) centre at the entity's
     *  feet level (or the cell below it, since mobs stand on the floor). */
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

    /** The fence gate on the pen's boundary nearest the herder (rope or vanilla), or null. */
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

    /** The cell one block OUTSIDE ({@code inside=false}) or INSIDE ({@code inside=true}) the gate, along the
     *  gate's OPENING axis (its {@code FACING}, not the diagonal to the centre). A gate only opens
     *  orthogonally, so the approach cells must sit on that axis — otherwise a straight walk through it hits a
     *  fence post beside the gate. The inward side is whichever facing-neighbour is nearer the pen centre.
     *  Null if no gate / no facing. */
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

    // ─── Spawn fallback ────────────────────────────────────────────────────────────────────────────

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
        citizen.getPersistentData().putLong(SPAWN_AT_KEY, sl.getGameTime()); // start the half-day cooldown
        citizen.grantJobXp(JOB_TYPE_ID, 0.5F, "herd");
        citizen.consumeStamina(1);
        workTicks = 0;
        assess(sl);
    }

    /** Whether the spawn-pair fallback is off cooldown (half an MC day since the last one). */
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

    // ─── Cull + harvest (actual melee kill; drops route through LivingDropsEvent) ────────────────

    /** Pick the nearest mature surplus animal once the adult count is above the configured keep threshold.
     *  Capacity is not involved here: the pen still fills to capacity; this only decides slaughter income. */
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
            // Statistic: credit the "livestock" source with this cull's food yield (by animal food-size,
            // chicken=1 … horse=3), so crisis objectives / stats know how much food the pens have raised.
            if (citizen.getSettlement() != null) {
                citizen.getSettlement().addFoodProduced("livestock", foodSize(cullTarget.getType()));
            }
            cullTarget = null;
            workTicks = 0;
            assess(sl);
        }
    }

    /** Add to the pen marker's kill counter (packed in seedItemId) and re-broadcast for the rod readout. */
    private void bumpKills(ServerLevel sl, int add) {
        BlockSelection sel = penMarkerAt(sl);
        if (sel == null) return;
        String packed = sel.seedItemId();
        BlockSelectionRegistry.get(sl).register(
            sel.withSeed(packPen(penAnimalId(packed), penKills(packed) + add, penKeep(packed))));
        SelectionBroadcaster.broadcast(sl.getServer());
    }

    // ─── Breed ───────────────────────────────────────────────────────────────────────────────────
    // The herder has feed on hand (like a farmer with seeds) — no food chest needed. It tends the pair
    // (sets them in love); whether a baby is actually born is the global chance roll in BreedingEvents.

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

    // ─── Muck out (clear manure that fouls the pen's fertility) ──────────────────────────────────

    /** Walk to the targeted manure block and clear it (the dung goes to the harvest storage, like a
     *  cull's loot). Re-targets if the block is already gone; re-assesses when there's nothing left to
     *  muck out. The herder reaches it from an adjacent floor cell (manure is non-colliding, in the air
     *  cell above the floor). */
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

    /** Nearest manure block sitting in the pen — droppings occupy the air cell ABOVE an interior floor
     *  cell, so scan {@code interior().above()}. Null if the pen is clean. */
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

    /** Break a manure block, routing its drops (dung) into the marked harvest storage — overflow / no
     *  storage spills at the pen so the cleanup still happens. */
    private void collectManure(ServerLevel sl, BlockPos pos) {
        BlockState st = sl.getBlockState(pos);
        if (!st.is(BreedingEvents.MANURE)) return;
        List<ItemStack> drops = Block.getDrops(st, sl, pos, null);
        sl.removeBlock(pos, false);
        sl.levelEvent(2001, pos, Block.getId(st));   // block-break particles + sound
        Container harvest = DropOffContainers.resolveOrPreferred(citizen, citizen.getDropOff());
        for (ItemStack drop : drops) {
            ItemStack rem = harvest != null ? DropOffContainers.insert(harvest, drop) : drop;
            if (!rem.isEmpty()) Block.popResource(sl, pos, rem);
        }
        citizen.grantJobXp(JOB_TYPE_ID, 0.5F, "herd");
        citizen.consumeStamina(1);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private void holdRope() {
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, citizen.getJobTool().copy());
    }

    /** Held item mirrors the current chore: feed while luring/breeding, blade while culling, rope otherwise.
     *  Only re-equips on a real change so it doesn't re-sync to clients every tick. */
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

    /** The food a herder visually holds to lure each species. Purely cosmetic — the follow is driven by the
     *  claim, not this item — so it's just an era-appropriate "luring" prop (no golden carrots in 13,529 BC). */
    private static net.minecraft.world.item.ItemStack catalystFor(EntityType<?> type) {
        net.minecraft.world.item.Item food;
        if (type == EntityType.PIG) food = net.minecraft.world.item.Items.CARROT;
        else if (type == EntityType.CHICKEN) food = net.minecraft.world.item.Items.WHEAT_SEEDS;
        else food = net.minecraft.world.item.Items.WHEAT;   // cow, sheep, mooshroom, horse, default — grain/hay
        return new net.minecraft.world.item.ItemStack(food);
    }

    private void lookAndApproach(BlockPos pos) {
        citizen.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        // Re-issue on a cadence (not only when the path completes): the first path to an interior cell
        // may have been computed before the gate opened and stalls at it; re-pathing recomputes a route
        // through the now-open gate.
        if (citizen.getNavigation().isDone() || citizen.tickCount % 15 == 0) {
            citizen.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, skilledSpeed());
        }
    }

    /** Server-side hard teleport (sets position and resets interpolation) — the corral's reliable finish.
     *  Tags the entity so the rope-fence collision accepts the cross-rope jump instead of bouncing it back
     *  to where it came from (which was ejecting placed animals and the herder right back out of the pen). */
    private static void teleport(net.minecraft.world.entity.Entity e, double x, double y, double z) {
        e.teleportTo(x, y, z);
        e.getPersistentData().putLong(TELEPORT_AT, e.level().getGameTime());
    }

    private BlockPos penCenter() {
        return pen.min().offset((pen.max().getX() - pen.min().getX()) / 2, 0,
            (pen.max().getZ() - pen.min().getZ()) / 2);
    }

    private List<Animal> penHerd(ServerLevel sl, PenEnclosure.Result r) {
        // Count by ACTUAL interior membership (with the 0.5 margin), not the bounding box — an irregular
        // (L-shaped) pen's box covers ground outside the rope, where animals would be miscounted as in.
        return sl.getEntitiesOfClass(Animal.class, r.bounds().inflate(1.0, 2.0, 1.0),
            a -> a.isAlive() && a.getType() == herdType && insidePen(a));
    }

    /** Nearest wild (un-penned, un-domesticated, un-leashed) animal of the herd kind within range —
     *  any age (the herder brings the whole local herd in). Domestication already excludes claimed stock. */
    private Animal findWild(ServerLevel sl, PenEnclosure.Result r) {
        AABB search = r.bounds().inflate(CAPTURE_RADIUS, 8.0, CAPTURE_RADIUS);
        Animal best = null;
        double bestD = Double.MAX_VALUE;
        for (Animal a : sl.getEntitiesOfClass(Animal.class, search,
                a -> a.isAlive() && a.getType() == herdType && !a.isLeashed() && !isDomesticated(a))) {
            if (strictInside(a)) continue;   // genuinely in the pen — strict, so edge-outside wild stock IS brought in
            double d = citizen.distanceToSqr(a);
            if (d < bestD) { bestD = d; best = a; }
        }
        return best;
    }

    /** Nearest DOMESTICATED animal of the herd kind that's near this pen but OUTSIDE it and not already
     *  being herded — an escapee to round up. By proximity (not a stored home-pen id) so it survives pens
     *  being relocated or rebuilt: whatever herder works a valid pen re-corrals the stock near it. */
    private Animal findEscaped(ServerLevel sl, PenEnclosure.Result r) {
        AABB search = r.bounds().inflate(ESCAPE_RADIUS, 12.0, ESCAPE_RADIUS);
        Animal best = null;
        double bestD = Double.MAX_VALUE;
        for (Animal a : sl.getEntitiesOfClass(Animal.class, search,
                a -> a.isAlive() && a.getType() == herdType && !a.isLeashed()
                    && isDomesticated(a) && !isClaimed(sl, a))) {
            if (insidePen(a)) continue;   // still inside → fine
            double d = citizen.distanceToSqr(a);
            if (d < bestD) { bestD = d; best = a; }
        }
        return best;
    }

    /** Where the herder posts to lure the flock in: the interior cell JUST INSIDE the gate. The animals
     *  follow the herder with their OWN navigation (like a player luring with wheat), and that only works
     *  reliably for a SHORT, direct crossing — leading to the deep centre across the pen's water gave the
     *  animal's nav an unsolvable path. Posting one block inside the gate keeps the crossing to the single
     *  hop through the opening, exactly the path a tempted cow takes for a player. Falls back to a centre
     *  cell if that cell isn't a valid stand. */
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

    /** Up to {@code n} distinct interior cells nearest the centre with a solid floor and air above. */
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

    /** Has the herder's settlement researched Animal Husbandry? Gates the herder's breeding behind the same
     *  {@code bannerbound.allow_animal_breeding} flag the player-feed path uses ({@link com.bannerbound.core
     *  .event.AnimalBreedingGate}). {@code hasFlag(null, …)} is false, so an unsettled herder can't breed. */
    private boolean hasBreedingResearch() {
        return com.bannerbound.core.api.research.ResearchManager.hasFlag(
            citizen.getSettlement(), com.bannerbound.core.event.AnimalBreedingGate.FLAG);
    }

    private static boolean isDomesticated(Animal a) {
        return a.getPersistentData().getBoolean(DOMESTICATED_TAG);
    }

    private static void domesticate(Animal a) {
        a.getPersistentData().putBoolean(DOMESTICATED_TAG, true);
        // Horses only breed (and obey setInLove) once TAMED — tame the herd's horses so the herder can
        // actually breed them; otherwise the breeding event never fires (no baby, no smoke).
        if (a instanceof net.minecraft.world.entity.animal.horse.AbstractHorse h && !h.isTamed()) {
            h.setTamed(true);
        }
    }

    /** Remove a pen's marker selection (its enclosure was destroyed) and re-broadcast. Null-safe. */
    private void removeMarker(ServerLevel sl, BlockSelection sel) {
        if (sel == null) return;
        BlockSelectionRegistry.get(sl).unregister(sel.rodId());
        SelectionBroadcaster.broadcast(sl.getServer());
    }

    /** Pick which pen this herder works. Pens BOUND to a specific citizen are private to that citizen; OPEN
     *  pens are workable by ANY herder but each is reserved ({@link PenClaims}) by at most one at a time, so
     *  multiple herders spread across the open pens instead of clustering on the first. Preference: a pen
     *  bound to me, then a pen I already hold, then the nearest fresh open pen (a recently-idled open pen is
     *  deprioritised so a lone herder rotates across pens). Claims the chosen open pen before returning. */
    private BlockSelection findPenMarker(ServerLevel sl) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
        long now = sl.getGameTime();
        penCooldown.values().removeIf(t -> now >= t);   // drop expired entries so the map can't grow unbounded
        BlockSelection best = null;
        int bestScore = Integer.MAX_VALUE;
        double bestD = Double.MAX_VALUE;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;   // a pen bound to someone else → not mine
            BlockPos a = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            boolean open = sel.targetsAllWorkers();
            int score;
            if (!open) {
                score = 0;                                          // bound to me → always my top priority
            } else {
                if (PenClaims.isClaimedByOther(sl, a, citizen.getId())) continue;   // another herder has it
                score = PenClaims.ownedBy(a, citizen.getId()) ? 1 : 2;             // prefer the one I already hold
                Long until = penCooldown.get(a.asLong());
                if (until != null && now < until) score += 10;      // just idled here → try elsewhere first
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

    /** The marker for THIS herder's current pen ({@link #anchor}), without the claim side-effect of
     *  {@link #findPenMarker} — for reading live pen data (keep target, kills) during work. */
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

    /** How many ADULTS the herder keeps alive before butchering surplus. Breeding/collecting still fill to
     *  full pen capacity; this threshold only controls mature-animal harvest income. */
    private int adultKeepTarget(ServerLevel sl, int cap) {
        BlockSelection sel = penMarkerAt(sl);
        int keep = sel == null ? 0 : penKeep(sel.seedItemId());
        return keep <= 0 ? cap : Math.max(2, Math.min(keep, cap));
    }

    // ─── Pen marker packing: seedItemId stores "<animalId>|<kills>|<keep>" ───────────────────────────

    public static String packPen(String animalId, int kills) {
        return packPen(animalId, kills, 0);
    }

    /** {@code keep} = the player's "keep how many adults alive" threshold (0 = Auto → full capacity). */
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

    /** The player's "keep how many adults alive" threshold for the pen (0 = Auto → full capacity). */
    public static int penKeep(String packed) {
        return packedInt(packed, 2);
    }

    /** Parse the int at {@code index} of the "|"-packed seedItemId; 0 if absent/unparseable (back-compat with
     *  old 2-field "animalId|kills" markers, which read keep as 0). */
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

    /** The animal {@link EntityType} a pen marker is set to raise (parsed from its packed seedItemId). */
    @SuppressWarnings("unchecked")
    public static EntityType<? extends Animal> animalFromMarker(BlockSelection sel) {
        if (sel == null) return null;
        ResourceLocation id = ResourceLocation.tryParse(penAnimalId(sel.seedItemId()));
        EntityType<?> t = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        return t == null ? null : (EntityType<? extends Animal>) t;
    }
}
