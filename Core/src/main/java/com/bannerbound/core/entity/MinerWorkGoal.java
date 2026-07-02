package com.bannerbound.core.entity;

import java.util.ArrayList;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.research.ToolAge;
import com.bannerbound.core.api.research.data.ToolAgeLoader;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.territory.BoulderLayout;
import com.bannerbound.core.territory.ChunkResource;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Miner {@link OrderedWorkGoal} — works the surface ore boulder of a marked resource chunk with the
 * <b>chip cycle</b>: swing at an ORE-state boulder block, swap it to the type's chipped/body state
 * (NEVER destroy it — block states change, the boulder's mass doesn't), and route the yield straight
 * into the marked drop-off (remote insert, like every other worker). The vein-regen ticker
 * ({@link com.bannerbound.core.territory.MinerVeinRegen}) slowly swaps chipped faces back to ore, so
 * the boulder is a permanent, self-refreshing work site and the chunk's identity marker forever.
 *
 * <p>Markers are committed by the Foreman's Rod (single click in an ore chunk → point selection of
 * type {@code "miner"}; the packed {@link BlockSelection#seedItemId()} carries resource + boulder
 * base height). Marker discovery mirrors the herder: bound markers are private, open markers are
 * reserved via {@link MinerClaims} so multiple miners spread across deposits. The chip mechanics
 * (reach + line-of-sight, approach tiles, swing pacing by tool age) mirror {@link DiggerWorkGoal} —
 * but where the digger's job is to REMOVE terrain, the miner's hard rule is the opposite: it makes
 * zero terrain edits beyond the ore↔chipped state swap.
 */
@ApiStatus.Internal
public class MinerWorkGoal extends OrderedWorkGoal {
    /** Per-citizen job id (Job tab / unlocks / icons all key off this). */
    public static final String JOB_TYPE_ID = "miners_claim";
    /** Type string the Foreman's Rod stamps on miner markers (the short unit name). */
    public static final String SELECTION_TYPE = "miner";

    private static final int DEFAULT_CHIP_TICKS = 80;
    /** Ore is harder than the digger's dirt/stone — chip duration is the tool age's mine ticks
     *  times this. Also the yield-rate knob, and tuned against MinerVeinRegen's interval so a
     *  working boulder stays mostly speckled instead of stripping bare. */
    private static final int ORE_HARDNESS = 3;
    private static final int RESCAN_COOLDOWN_TICKS = 60;
    /** Consecutive marker-not-found scans before we forget the outpost site. A single transient miss
     *  (e.g. the settlement lookup momentarily unresolved) must NOT clear the site — that would drop
     *  the worker to patrol and walk it home. Only a confirmed recall (the bound marker is genuinely
     *  gone) accumulates this and lets go. */
    private static final int MARKER_MISS_FORGET = 4;
    /** Same generous player-like reach + line-of-sight regime as the digger. */
    private static final double REACH = 4.0;
    private static final double REACH_SQ = REACH * REACH;
    private static final int TARGET_TIMEOUT_TICKS = 200;
    private static final int STAGNATION_LIMIT = 30;
    /** A face that couldn't be reached is skipped this many ticks so the miner tries OTHER faces
     *  instead of re-picking the same nearest-but-unreachable one forever (the digger's fix). */
    private static final int FAILED_TTL_TICKS = 80;
    /** Pre-filter for the "already in reach → chip in place, no pathfinding" pick: only run the
     *  (cheap) reach raycast on faces within this squared distance of the worker. */
    private static final double IMMEDIATE_PREFILTER_SQ = 30.0;

    private BlockPos anchor;            // marker anchor (the clicked boulder block)
    private ChunkResource resource = ChunkResource.NONE;
    private int baseY;
    private BlockPos targetPos;         // ore-state boulder block being chipped
    private int chipTimer;
    private int targetAge;
    private int rescanCooldown;
    private int markerMisses;           // consecutive findMineMarker() misses — debounces site forget
    private int abandons;               // consecutive targets given up unreached — drives BLOCKED status
    /** Faces that timed out / had no reachable approach, with their skip-until tick. Prunes on read. */
    private final java.util.Map<BlockPos, Integer> recentlyFailed = new java.util.HashMap<>();
    private java.util.List<BlockPos> approaches = java.util.List.of();
    private int approachIdx;
    private BlockPos approachPos;
    private double bestApproachDistSq = Double.MAX_VALUE;
    private int stagnation;

    public MinerWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    // ─── Marker seed packing: "<resource>|<baseY>" ───────────────────────────────────────────────

    public static String packMine(ChunkResource type, int baseY) {
        return type.name() + "|" + baseY;
    }

    public static ChunkResource mineResource(String packed) {
        if (packed == null || packed.isEmpty()) return ChunkResource.NONE;
        int i = packed.indexOf('|');
        String name = i < 0 ? packed : packed.substring(0, i);
        try {
            return ChunkResource.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ChunkResource.NONE;
        }
    }

    public static int mineBaseY(String packed) {
        if (packed == null) return Integer.MIN_VALUE;
        int i = packed.indexOf('|');
        if (i < 0 || i + 1 >= packed.length()) return Integer.MIN_VALUE;
        try {
            return Integer.parseInt(packed.substring(i + 1));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    // ─── Start / continue ────────────────────────────────────────────────────────────────────────

    @Override
    protected boolean canStartWork() {
        citizen.validateJobStorage();
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        // Job + tool only at this point — the drop-off may be OUTPOST-managed and auto-assigned
        // below, so the full isJobReady (which demands a drop-off) runs after marker resolution.
        if (!JOB_TYPE_ID.equals(citizen.getJobType()) || !citizen.hasJobTool()) return false;
        if (rescanCooldown-- > 0) return false;

        MinerClaims.releaseAll(citizen.getId());   // fresh each evaluation; re-claims below
        BlockSelection sel = findMineMarker(sl);
        if (sel == null) {
            // Forget a remembered outpost site only after several CONSECUTIVE misses (a confirmed
            // recall — the bound marker is gone for good), never on a one-scan blip. Clearing it on
            // a transient miss (e.g. the settlement lookup momentarily unresolved) would drop an
            // actively-assigned miner to patrol and walk it all the way home before the next scan
            // re-appoints it — a visible "the worker wandered off the outpost" round-trip.
            if (citizen.getOutpostSite() != null && citizen.getSettlement() != null
                    && ++markerMisses >= MARKER_MISS_FORGET) {
                citizen.setOutpostSite(null);
                markerMisses = 0;
            }
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        markerMisses = 0;
        anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());

        // Outpost detection is pure settlement data (no level read), so it MUST happen before the
        // chunk-load gate below. An appointed miner standing at home has to learn its remote site
        // even while that chunk is unloaded: that's what flips SettlementPatrolGoal to commute it
        // out there (loading the site en route) and keeps it AI-active for the whole trip. Doing
        // this only AFTER hasChunk (as it used to) deadlocked — the site never loads until a worker
        // arrives, and no worker arrives until the site is set. HerderWorkGoal already orders it
        // this way; the miner now matches.
        Settlement settlement = citizen.getSettlement();
        ChunkPos siteChunk = new ChunkPos(anchor);
        boolean outpost = settlement != null && settlement.workingClaims().contains(siteChunk.toLong());
        // Only (re)write the outpost site when the settlement actually resolved — a momentarily null
        // lookup must not clear it and send the worker home (see the marker-miss debounce above).
        if (settlement != null) citizen.setOutpostSite(outpost ? anchor.immutable() : null);

        if (!sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) return false; // still commuting — site not loaded
        resource = mineResource(sel.seedItemId());
        baseY = mineBaseY(sel.seedItemId());
        if (!BoulderLayout.isOreChunk(resource) || baseY == Integer.MIN_VALUE) return false;
        Item drop = BoulderLayout.dropFor(resource).orElse(null);
        if (drop == null) return false; // drop item not in this install

        // Outpost sites manage their own storage: the OUTPOST decides the chest (nearest drop-off
        // container inside the working-claimed chunk), not the Job tab — which greys its button.
        // The chest is auto-assigned as the citizen's drop-off so every downstream system
        // (resolveDepot, validateJobStorage, stocker pickup) sees a perfectly ordinary drop-off.
        if (outpost) {
            BlockPos storage = findOutpostStorage(sl, siteChunk, anchor);
            if (storage == null) {
                // No chest at the outpost yet — nowhere for the yield, don't start.
                citizen.setCurrentWorkStatus(CitizenWorkStatus.NO_DROPOFF);
                rescanCooldown = RESCAN_COOLDOWN_TICKS * 2;
                return false;
            }
            if (!storage.equals(citizen.getDropOff())) citizen.setDropOff(storage);
        }

        if (!citizen.isJobReady(JOB_TYPE_ID)) return false;
        Container depot = resolveDepot();
        if (depot == null) {
            citizen.setCurrentWorkStatus(CitizenWorkStatus.NO_DROPOFF);
            return false;
        }
        // Tool-tier gate: vanilla harvest rules decide what this pickaxe may chip (a bone pick
        // works marble but not the needs_stone_tool metals) — progression, same tags as players.
        if (!canChipWithTool()) {
            citizen.setCurrentWorkStatus(CitizenWorkStatus.NO_TOOL);   // has a pick, but too soft for this ore
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        // Don't chip what the chest can't hold — back off rather than spill at our feet.
        if (DropOffContainers.roomFor(depot, new ItemStack(drop)) < 1) {
            citizen.setCurrentWorkStatus(CitizenWorkStatus.STORAGE_FULL);
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }

        ChipPick pick = findChipPick(sl);
        if (pick == null) {
            // No exposed, ore-state, not-recently-failed face right now — the reachable faces are
            // chipped (or briefly skip-listed) and the vein refreshes in waves (MinerVeinRegen,
            // ~8000 ticks apart). Publish WAITING so the Job tab reads "Waiting" (amber) instead of
            // the misleading default "Working"; the citizen loiters tightly at the rock meanwhile.
            citizen.setCurrentWorkStatus(CitizenWorkStatus.WAITING);
            rescanCooldown = RESCAN_COOLDOWN_TICKS * 2;
            return false;
        }
        if (!commitTarget(sl, pick)) {
            // Found a face but no standable, line-of-sight tile to chip it from — skip it briefly and
            // try OTHER faces next scan (instead of re-locking onto the same unreachable one).
            markFailed(pick.pos());
            abandons++;
            citizen.setCurrentWorkStatus(CitizenWorkStatus.BLOCKED);
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        // Cleared every gate — about to work. Persistent reach trouble (abandons piling up) still
        // reads BLOCKED; otherwise the active chip cycle will publish IDLE→"Working" as it bites.
        citizen.setCurrentWorkStatus(abandons >= 2 ? CitizenWorkStatus.BLOCKED : CitizenWorkStatus.IDLE);
        return true;
    }

    @Override
    protected boolean canKeepWorking() {
        if (!citizen.isJobReady(JOB_TYPE_ID)) return false;
        if (resolveDepot() == null) return false;
        if (!canChipWithTool()) return false;   // tool swapped mid-job to one below the ore's tier
        return targetPos != null && anchor != null && markerStillMine();
    }

    /** Vanilla harvest-tier gate: the miner's pickaxe must be correct-tool for the resource's ORE
     *  block (the same {@code needs_*_tool} tags players obey). Tier gives progression — a bone
     *  pickaxe never chips iron; tool age still sets SPEED on top of this. */
    private boolean canChipWithTool() {
        return citizen.getJobTool().isCorrectToolForDrops(BoulderLayout.oreBlock(resource));
    }

    /** The marker may be deleted (shift+left-click) or re-bound to someone else mid-job. */
    private boolean markerStillMine() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (sel.minX() == anchor.getX() && sel.minY() == anchor.getY() && sel.minZ() == anchor.getZ()) {
                return sel.targetsCitizen(citizen.getUUID());
            }
        }
        return false;
    }

    @Override
    public void start() {
        chipTimer = 0;
        citizen.setWorking(true);
        // Status was already published by canStartWork (IDLE→"Working", or BLOCKED if reach-troubled);
        // don't clobber it here.
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, citizen.getJobTool().copy());
        resetApproachWatchdog();
        navigateToTarget();
    }

    @Override
    public void stop() {
        chipTimer = 0;
        citizen.setWorking(false);
        // Clear our published verdict so a re-jobbed / unassigned worker can't carry a stale WAITING
        // onto the Job tab. canStartWork re-publishes WAITING next scan if the vein's still empty.
        citizen.setCurrentWorkStatus(CitizenWorkStatus.IDLE);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        MinerClaims.releaseAll(citizen.getId());
        targetPos = null;
    }

    @Override
    public void tick() {
        if (!citizen.isJobReady(JOB_TYPE_ID)) return;
        if (targetPos == null) return;
        targetAge++;
        if (targetAge > TARGET_TIMEOUT_TICKS) { abandonTarget(); return; }
        citizen.getLookControl().setLookAt(
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

        if (!(citizen.level() instanceof ServerLevel sl)) return;
        // The face must still be ore-state — regen/players/another pass may have changed it.
        if (!isChippable(sl, targetPos)) { nextTarget(sl); return; }

        if (canMineFromHere(targetPos)) {
            citizen.getNavigation().stop();
            chipTimer++;
            ItemStack tool = citizen.getJobTool();
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, tool.copy());
            ToolAge toolAge = ToolAgeLoader.getByTool("pickaxe", tool.getItem());
            int budget = ORE_HARDNESS
                * (toolAge != null ? toolAge.mineTicks().orElse(DEFAULT_CHIP_TICKS) : DEFAULT_CHIP_TICKS);
            budget = com.bannerbound.core.api.quality.QualityTier.scaleWorkTicks(tool, budget);
            // Experience on top of pick age/quality: a veteran miner chips faster (interval and
            // completion both scale, so the swing count per ore stays steady).
            budget = skilledWorkTicks(budget);
            int interval = Math.max(1, budget / 6);
            if (chipTimer % interval == 0) playSwing(sl, targetPos);
            if (chipTimer >= budget) {
                chipBlock(sl);
                chipTimer = 0;
            }
        } else {
            if (approachPos != null) {
                double d = citizen.position().distanceToSqr(
                    approachPos.getX() + 0.5, approachPos.getY() + 0.5, approachPos.getZ() + 0.5);
                if (d + 0.05 < bestApproachDistSq) {
                    bestApproachDistSq = d;
                    stagnation = 0;
                } else if (++stagnation > STAGNATION_LIMIT) {
                    advanceApproachOrAbandon();
                    return;
                }
            }
            if (citizen.getNavigation().isDone()) navigateToTarget();
            chipTimer = 0;
        }
    }

    // ─── The chip ────────────────────────────────────────────────────────────────────────────────

    /** Swap the ore face to its chipped state (NEVER destroy), route the yield to the drop-off
     *  (remote insert — consistent with the digger and every other worker), spend stamina. */
    private void chipBlock(ServerLevel sl) {
        if (!isChippable(sl, targetPos)) { nextTarget(sl); return; }
        BlockState before = sl.getBlockState(targetPos);
        sl.setBlock(targetPos, BoulderLayout.chippedBlock(resource), 3);
        sl.playSound(null, targetPos, before.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.8f, 1.0f);
        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, before),
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.0);

        Item drop = BoulderLayout.dropFor(resource).orElse(null);
        if (drop != null) {
            ItemStack one = new ItemStack(drop);
            // Same knowledge gate as every worker yield — an unknown item never reaches the chest.
            if (SettlementDropFilter.shouldDrop(citizen.getSettlement(), null, one)) {
                Container depot = resolveDepot();
                if (depot == null) {
                    citizen.spawnAtLocation(one);
                } else {
                    ItemStack leftover = DropOffContainers.insert(depot, one);
                    // Mined yield is valuable — spill overflow at the citizen's feet, never discard.
                    if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
                }
            }
        }
        citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "ore");
        citizen.consumeStamina(1);
        // Productive bite — clear the reach-trouble tally and read as "Working".
        abandons = 0;
        citizen.setCurrentWorkStatus(CitizenWorkStatus.IDLE);
        targetPos = null;
        targetAge = 0;
        nextTarget(sl);
    }

    /** Pick the next ore face; none left → the vein is worked out and the goal yields (the regen
     *  ticker refreshes faces; the rescan cooldown paces the retry). */
    private void nextTarget(ServerLevel sl) {
        ChipPick next = findChipPick(sl);
        if (next != null && commitTarget(sl, next)) {
            chipTimer = 0;
            return;
        }
        if (next != null) markFailed(next.pos());   // had a face but couldn't commit — skip & retry others
        targetPos = null;
        // canKeepWorking goes false next check; the rescan cooldown paces the restart.
    }

    /** A chippable ore face plus whether it's reachable RIGHT NOW (chip in place, no pathfinding). */
    private record ChipPick(BlockPos pos, boolean immediate) {}

    /**
     * Pick the next ore face to chip — the digger's two-track selection. Among exposed, ore-state,
     * not-recently-failed layout faces it tracks both the nearest overall AND the nearest that's
     * already in reach + line of sight ({@link #canMineFromHere}). An immediate pick wins so the
     * miner chips everything it can hit from where it's standing before ever moving — which is what
     * makes it actually work a boulder instead of mining one face and stalling on the next.
     */
    private ChipPick findChipPick(ServerLevel sl) {
        ChunkPos cp = new ChunkPos(anchor);
        BlockPos origin = citizen.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos bestImm = null;
        double bestImmD = Double.MAX_VALUE;
        for (BoulderLayout.Spot s : BoulderLayout.spots(sl.getSeed(), cp, baseY)) {
            if (!s.ore()) continue;
            BlockPos pos = s.pos();
            if (!isChippable(sl, pos)) continue;
            if (!isExposed(sl, pos)) continue;
            if (isRecentlyFailed(pos)) continue;
            double d = origin.distSqr(pos);
            if (d < bestD) { bestD = d; best = pos.immutable(); }
            if (d < bestImmD && d <= IMMEDIATE_PREFILTER_SQ && canMineFromHere(pos)) {
                bestImmD = d;
                bestImm = pos.immutable();
            }
        }
        if (bestImm != null) return new ChipPick(bestImm, true);
        return best == null ? null : new ChipPick(best, false);
    }

    /**
     * Commit to a pick. An immediate pick mines in place (no approach, no pathfinding). A distant
     * pick resolves its approach tiles; if none is walkable-with-sight AND we can't already mine it,
     * return false so the caller skips it ({@link #markFailed}) and tries another face. Mirrors the
     * digger's {@code tryStartDeposit} commit step.
     */
    private boolean commitTarget(ServerLevel sl, ChipPick pick) {
        targetPos = pick.pos();
        if (pick.immediate()) {
            approaches = java.util.List.of();
            approachIdx = 0;
            approachPos = null;
        } else {
            approaches = findApproaches(sl, pick.pos(), citizen.blockPosition());
            approachIdx = 0;
            approachPos = approaches.isEmpty() ? null : approaches.get(0);
            if (approachPos == null && !canMineFromHere(pick.pos())) {
                return false;
            }
        }
        resetApproachWatchdog();
        targetAge = 0;
        return true;
    }

    /** True if {@code pos} failed recently and its skip window hasn't elapsed (prunes on access). */
    private boolean isRecentlyFailed(BlockPos pos) {
        Integer expiry = recentlyFailed.get(pos);
        if (expiry == null) return false;
        if (citizen.tickCount >= expiry) { recentlyFailed.remove(pos); return false; }
        return true;
    }

    /** Skip {@code pos} for {@link #FAILED_TTL_TICKS} so the miner works other faces meanwhile. */
    private void markFailed(BlockPos pos) {
        if (pos != null) recentlyFailed.put(pos.immutable(), citizen.tickCount + FAILED_TTL_TICKS);
    }

    /** The world currently shows the resource's ORE block at {@code pos}. */
    private boolean isChippable(ServerLevel sl, BlockPos pos) {
        return pos != null && sl.getBlockState(pos).is(BoulderLayout.oreBlock(resource).getBlock());
    }

    // ─── Marker discovery (herder pattern: bound → held → nearest open, with claims) ─────────────

    /** Pick which marked deposit this miner works. Bound markers are private; open ones are
     *  reserved via {@link MinerClaims} so miners spread across deposits. */
    private BlockSelection findMineMarker(ServerLevel sl) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
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
                score = 0;                                          // bound to me → top priority
            } else {
                if (MinerClaims.isClaimedByOther(sl, a, citizen.getId())) continue;
                score = MinerClaims.ownedBy(a, citizen.getId()) ? 1 : 2;
            }
            double d = citizen.distanceToSqr(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
            if (score < bestScore || (score == bestScore && d < bestD)) {
                best = sel; bestScore = score; bestD = d;
            }
        }
        if (best != null && best.targetsAllWorkers()) {
            MinerClaims.claim(new BlockPos(best.minX(), best.minY(), best.minZ()), citizen.getId());
        }
        return best;
    }

    // ─── Movement / reach (digger regime: stand near, raycast, generous reach) ───────────────────

    private Container resolveDepot() {
        return DropOffContainers.resolveOrPreferred(citizen, citizen.getDropOff());
    }

    /** The outpost's storage: the drop-off container (chest/basket/stockpile rack) inside the
     *  working-claimed chunk nearest the boulder. The outpost decides — never the Job tab.
     *  Public: the Outpost Banner's status screen shows the same resolution. */
    public static BlockPos findOutpostStorage(ServerLevel sl, ChunkPos cp, BlockPos anchor) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (var e : sl.getChunk(cp.x, cp.z).getBlockEntities().entrySet()) {
            BlockPos pos = e.getKey();
            if (!(e.getValue() instanceof Container)) continue;
            if (!DropOffContainers.isDropOffBlock(sl, pos)) continue;
            if (DropOffContainers.isWildStorage(sl, pos)) continue; // never a generated loot chest
            double d = anchor.distSqr(pos);
            if (d < bestD) { bestD = d; best = pos.immutable(); }
        }
        return best;
    }

    private boolean canMineFromHere(BlockPos t) {
        Vec3 eye = citizen.getEyePosition();
        Vec3 closest = new Vec3(
            Mth.clamp(eye.x, t.getX(), t.getX() + 1.0),
            Mth.clamp(eye.y, t.getY(), t.getY() + 1.0),
            Mth.clamp(eye.z, t.getZ(), t.getZ() + 1.0));
        if (eye.distanceToSqr(closest) > REACH_SQ) return false;
        BlockHitResult hit = citizen.level().clip(new ClipContext(
            eye, closest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, citizen));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(t);
    }

    private java.util.List<BlockPos> findApproaches(Level level, BlockPos target, BlockPos origin) {
        java.util.List<BlockPos> out = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos f = target.offset(dx, dy, dz);
                    if (WorkerPathing.isWalkable(level, f) && hasSightFrom(level, f, target)) {
                        out.add(f);
                    }
                }
            }
        }
        out.sort(java.util.Comparator.comparingDouble(origin::distSqr));
        return out;
    }

    private boolean hasSightFrom(Level level, BlockPos stand, BlockPos target) {
        Vec3 eye = new Vec3(stand.getX() + 0.5, stand.getY() + 1.62, stand.getZ() + 0.5);
        Vec3 closest = new Vec3(
            Mth.clamp(eye.x, target.getX(), target.getX() + 1.0),
            Mth.clamp(eye.y, target.getY(), target.getY() + 1.0),
            Mth.clamp(eye.z, target.getZ(), target.getZ() + 1.0));
        BlockHitResult hit = level.clip(new ClipContext(
            eye, closest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, citizen));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    private static boolean isExposed(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            n.setWithOffset(pos, d);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }

    private void navigateToTarget() {
        if (approachPos == null) return;
        citizen.getNavigation().moveTo(
            approachPos.getX() + 0.5, approachPos.getY(), approachPos.getZ() + 0.5, skilledSpeed());
    }

    private void advanceApproachOrAbandon() {
        approachIdx++;
        if (approachIdx >= approaches.size()) {
            abandonTarget();
            return;
        }
        approachPos = approaches.get(approachIdx);
        resetApproachWatchdog();
        navigateToTarget();
    }

    private void abandonTarget() {
        if (targetPos != null) markFailed(targetPos);   // skip this face so the next scan tries others
        abandons++;
        targetPos = null;
        targetAge = 0;
        chipTimer = 0;
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
    }

    private void resetApproachWatchdog() {
        bestApproachDistSq = Double.MAX_VALUE;
        stagnation = 0;
    }

    private void playSwing(ServerLevel level, BlockPos pos) {
        net.minecraft.network.protocol.game.ClientboundAnimatePacket packet =
            new net.minecraft.network.protocol.game.ClientboundAnimatePacket(citizen, 0);
        level.getChunkSource().broadcastAndSend(citizen, packet);
        BlockState state = level.getBlockState(pos);
        SoundType st = state.getSoundType();
        level.playSound(null, pos, st.getHitSound(), SoundSource.BLOCKS, st.getVolume() * 0.5f, st.getPitch());
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.0);
    }
}
