package com.bannerbound.core.entity;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ToolAge;
import com.bannerbound.core.api.research.SettlementDropFilter;
import com.bannerbound.core.api.research.data.ToolAgeLoader;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.territory.ChunkResource;
import com.bannerbound.core.territory.MaterialDepositLayout;
import com.bannerbound.core.world.SelectionBroadcaster;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Digger {@link OrderedWorkGoal} — an ORDERED worker. Unlike the gatherer Forester (which scans the
 * world freely), a digger never digs on its own: it only mines blocks inside areas the player marked
 * with the Foreman's Rod (stored in {@link BlockSelectionRegistry} with {@code workstationType ==
 * "digger"}). A selection is either bound to one specific citizen or open to "all diggers" — see
 * {@link BlockSelection#targetsCitizen}.
 * <p>
 * It mines like a player would: it walks loosely toward the work and breaks any selected block within
 * {@link #REACH} that it has a clear line of sight to ({@link #canMineFromHere}). The raycast is what
 * stops it digging through walls/floors, so the reach can be generous — there's deliberately no
 * "navigate to an exact adjacent tile" step, which was brittle in dug-out terrain. It reads its tool +
 * drop-off off the {@link CitizenEntity} (job tab), routes drops into the marked chest/basket, and
 * paces by the SHOVEL/pickaxe it was handed (its tool age's {@code mine_speed}).
 */
@ApiStatus.Internal
public class DiggerWorkGoal extends OrderedWorkGoal {
    /** Per-citizen job id (matches {@link com.bannerbound.core.social.WorkstationIcons} /
     *  {@link com.bannerbound.core.api.settlement.WorkstationUnlocks}). */
    public static final String JOB_TYPE_ID = "diggers_slab";
    /** Type string the Foreman's Rod stamps on digger selections (the short unit name). */
    public static final String SELECTION_TYPE = "digger";

    private static final int DEFAULT_MINE_TICKS = 80;
    /** Research flag (Quarry) that lets quarryworkers mine pickaxe-tier blocks (stone, ores, coal). */
    private static final String FLAG_QUARRY = "bannerbound.unlock_quarry";
    private static final int DEPOSIT_MINE_NUMERATOR = 3;
    private static final int DEPOSIT_MINE_DENOMINATOR = 2;
    private static final int RESCAN_COOLDOWN_TICKS = 40;
    /** Player-like reach: the worker mines any in-sight selected block whose nearest point is within
     *  this many blocks of its eyes. Generous on purpose — line of sight, not distance, is what keeps
     *  it from digging through walls, so it never gets "stuck" beside a block it can plainly reach. */
    private static final double REACH = 4.0;
    private static final double REACH_SQ = REACH * REACH;
    /** Hard backstop: if a target is somehow never minable for this long, drop it and pick another. */
    private static final int TARGET_TIMEOUT_TICKS = 160;
    /** How long (ticks) a block we couldn't get to is skipped before retrying — short so the worker
     *  recovers quickly instead of "giving up" on a zone for a long time. */
    private static final int FAILED_TTL_TICKS = 80;
    /** Ticks of no approach-progress before giving up on a target (and releasing its claim) so a
     *  better-placed digger can take it — keeps a stuck worker from hogging a block. ~1.5s. */
    private static final int STAGNATION_LIMIT = 30;
    /** A digger won't commit to a standing spot this close to another digger's reservation. Big enough
     *  to keep two workers out of the same one-wide tunnel, small enough that they still share an open
     *  pit / a long wall in parallel. */
    private static final double CONTEST_RADIUS = 2.5;

    private BlockPos targetPos;
    private boolean targetDeposit;
    private BlockPos depositAnchor;
    private ChunkResource depositResource = ChunkResource.NONE;
    private int depositBaseY = Integer.MIN_VALUE;
    /** What mining {@link #targetPos} will yield — computed once when the target is chosen (the block
     *  can't change until we break it) so the depot-room gate can ask about the REAL drop (cobblestone
     *  from stone, dirt from dirt...) instead of a worst-case "any free slot". {@link DropOffContainers#roomFor}
     *  is still evaluated live against the depot each check, so a chest that fills mid-job still stops us. */
    private List<ItemStack> targetDrops = List.of();
    private int mineTimer;
    private int targetAge;
    private int rescanCooldown;
    /** Walkable tiles near the target we can navigate to (so the pathfinder walks DOWN to a standable
     *  spot instead of parking on the rim above a solid block), nearest-worker first, and the index of
     *  the one we're trying. Falling back to the next when one proves unreachable lets the worker reach
     *  a block from whichever side actually connects. {@code approachPos} is the current goal. */
    private java.util.List<BlockPos> approaches = java.util.List.of();
    private int approachIdx;
    private BlockPos approachPos;
    /** No-progress watchdog: smallest distance² to {@code approachPos} seen this approach + ticks since
     *  it last improved. */
    private double bestApproachDistSq = Double.MAX_VALUE;
    private int stagnation;
    /** Blocks we couldn't reach recently → {@code citizen.tickCount} they may be retried. Skipped by the
     *  scanner until then so the worker doesn't re-fixate on the same unreachable block. */
    private final java.util.Map<BlockPos, Integer> recentlyFailed = new java.util.HashMap<>();

    public DiggerWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    private Container resolveDepot() {
        return DropOffContainers.resolveOrPreferred(citizen, citizen.getDropOff());
    }

    /** Exactly what mining {@code pos} will drop — same path as {@link #mineBlock} (loot table + the
     *  civ's drop filter) so the room gate is keyed to the real item. Empty/unrecognized → empty list
     *  (nothing to store). Returns empty off the server thread (no level to evaluate against). */
    private List<ItemStack> computeDrops(BlockPos pos) {
        if (pos == null || !(citizen.level() instanceof ServerLevel sl)) return List.of();
        BlockState state = sl.getBlockState(pos);
        List<ItemStack> drops = Block.getDrops(state, sl, pos, sl.getBlockEntity(pos));
        com.bannerbound.core.api.research.SettlementDropFilter.filterStacks(citizen.getSettlement(),
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()), drops);
        return drops;
    }

    private List<ItemStack> computeDepositDrops(ChunkResource type) {
        List<ItemStack> drops = new java.util.ArrayList<>(MaterialDepositLayout.dropsFor(type));
        SettlementDropFilter.filterStacks(citizen.getSettlement(),
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                MaterialDepositLayout.sourceBlock(type).getBlock()),
            drops);
        return drops;
    }

    /** True if {@code depot} can hold every stack in {@code drops}. An empty drop list (block yields
     *  nothing the civ recognizes) is trivially "has room" — there's nothing to deposit. */
    private boolean depotHasRoomFor(Container depot, List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            if (DropOffContainers.roomFor(depot, drop) < drop.getCount()) return false;
        }
        return true;
    }

    @Override
    protected boolean canStartWork() {
        citizen.validateJobStorage();   // clear a broken drop-off so we don't dig for a dead container
        if (!JOB_TYPE_ID.equals(citizen.getJobType()) || !citizen.hasJobTool()) return false;

        // Outpost commute bootstrap: findDepositSite below skips unloaded chunks, so a digger
        // appointed to a remote material deposit could never learn its site (and thus never set
        // out) while standing at home. Seed the outpost site straight from the registry here for
        // the unloaded case; once the digger reaches it and the chunk loads, the normal
        // findDepositSite/wireDepositStorage path takes over. Mirrors the miner/herder ordering.
        maybeBootstrapOutpostCommute();

        // Continue a still-valid target — but only while it's still inside a live order AND the depot
        // can still take its drop. If the player deleted the selection (shift+left-click) the cached
        // target is no longer ordered; if the chest filled up we'd just spill the yield at our feet —
        // either way, drop it.
        if (targetPos != null) {
            Container depot = resolveDepot();
            if (targetDeposit) {
                if (depot != null && markerStillDeposit() && isDepositSource(targetPos)
                        && canMineRole(MaterialDepositLayout.requiredRole(depositResource))
                        && depotHasRoomFor(depot, targetDrops)) {
                    claimWorkArea();
                    return true;
                }
            } else if (citizen.isJobReady(JOB_TYPE_ID) && depot != null
                    && isStillOrdered(targetPos) && isMineable(citizen.level(), targetPos)
                    && depotHasRoomFor(depot, targetDrops)) {
                claimWorkArea();   // keep our reservation (block + standing spot) fresh
                return true;
            }
        }
        if (targetPos != null) clearTargetState();
        if (rescanCooldown-- > 0) return false;

        DepositSite boundDeposit = findDepositSite(true);
        if (boundDeposit != null) return tryStartDeposit(boundDeposit);

        if (!citizen.isJobReady(JOB_TYPE_ID)) return false;
        Container depot = resolveDepot();
        if (depot == null) return false;

        TargetPick pick = findTarget();
        if (pick == null) {
            DepositSite openDeposit = findDepositSite(false);
            if (openDeposit != null) return tryStartDeposit(openDeposit);
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        // Don't commit to a block the depot can't hold the drop of (deterministic 1:1 — cobblestone
        // from stone, etc.). Back off briefly rather than walk over and spill it.
        List<ItemStack> pickDrops = computeDrops(pick.block());
        if (!depotHasRoomFor(depot, pickDrops)) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        if (pick.immediate()) {
            // Already in reach + sight — mine it where we stand, no pathfinding at all.
            approaches = java.util.List.of();
            approachIdx = 0;
            approachPos = null;
        } else {
            java.util.List<BlockPos> appr = findApproaches(citizen.level(), pick.block(), citizen.blockPosition());
            if (appr.isEmpty()) {
                // No walkable tile near it to mine from yet (fully buried) — skip briefly, try another.
                recentlyFailed.put(pick.block().immutable(), citizen.tickCount + FAILED_TTL_TICKS);
                rescanCooldown = RESCAN_COOLDOWN_TICKS;
                return false;
            }
            approaches = appr;
            approachIdx = 0;
            approachPos = appr.get(0);
        }
        targetPos = pick.block();
        targetDeposit = false;
        depositAnchor = null;
        depositResource = ChunkResource.NONE;
        depositBaseY = Integer.MIN_VALUE;
        citizen.setOutpostSite(null);
        targetDrops = pickDrops;   // cache the (stable) drop so canKeepWorking can re-vet depot room live
        claimWorkArea();   // reserve the block AND our standing spot so no other digger crowds in
        resetApproachWatchdog();
        targetAge = 0;
        return true;
    }

    private record DepositSite(BlockSelection selection, BlockPos anchor,
                               ChunkResource resource, int baseY) {}

    private boolean isMaterialDepositSelection(BlockSelection sel) {
        return sel != null
            && SELECTION_TYPE.equals(sel.workstationType())
            && MaterialDepositLayout.isMaterialPacked(sel.seedItemId());
    }

    private DepositSite findDepositSite(boolean boundOnly) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
        if (!(citizen.level() instanceof ServerLevel sl)) return null;
        DepositSite best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.completed()) continue;
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!isMaterialDepositSelection(sel)) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            if (boundOnly && sel.targetsAllWorkers()) continue;
            if (!boundOnly && !sel.targetsAllWorkers()) continue;
            ChunkResource type = MaterialDepositLayout.materialResource(sel.seedItemId());
            int baseY = MaterialDepositLayout.materialBaseY(sel.seedItemId());
            if (!MaterialDepositLayout.isMaterialChunk(type) || baseY == Integer.MIN_VALUE) continue;
            BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            if (!sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) continue;
            double d = citizen.distanceToSqr(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = new DepositSite(sel, anchor, type, baseY);
            }
        }
        return best;
    }

    private boolean tryStartDeposit(DepositSite site) {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        if (!wireDepositStorage(sl, site)) return false;
        if (!citizen.isJobReady(JOB_TYPE_ID)) return false;
        Container depot = resolveDepot();
        if (depot == null) return false;
        String role = MaterialDepositLayout.requiredRole(site.resource());
        if (!canMineRole(role)) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        List<ItemStack> drops = computeDepositDrops(site.resource());
        if (!depotHasRoomFor(depot, drops)) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            return false;
        }
        TargetPick pick = findDepositTarget(sl, site);
        if (pick == null) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS * 2;
            return false;
        }
        if (pick.immediate()) {
            approaches = java.util.List.of();
            approachIdx = 0;
            approachPos = null;
        } else {
            java.util.List<BlockPos> appr = findApproaches(citizen.level(), pick.block(), citizen.blockPosition());
            if (appr.isEmpty()) {
                recentlyFailed.put(pick.block().immutable(), citizen.tickCount + FAILED_TTL_TICKS);
                rescanCooldown = RESCAN_COOLDOWN_TICKS;
                return false;
            }
            approaches = appr;
            approachIdx = 0;
            approachPos = appr.get(0);
        }
        targetPos = pick.block();
        targetDeposit = true;
        depositAnchor = site.anchor();
        depositResource = site.resource();
        depositBaseY = site.baseY();
        targetDrops = drops;
        claimWorkArea();
        resetApproachWatchdog();
        targetAge = 0;
        return true;
    }

    /** Sets {@link CitizenEntity#setOutpostSite} from this digger's bound material-deposit marker
     *  ONLY while that outpost chunk is unloaded — the one case {@link #findDepositSite} /
     *  {@link #wireDepositStorage} can't cover, since they require the chunk loaded. A no-op when the
     *  site is loaded (the normal path then owns the outpost site) or when this digger holds no bound
     *  outpost marker, so it can't clobber an in-territory worker's state. */
    private void maybeBootstrapOutpostCommute() {
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!isMaterialDepositSelection(sel)) continue;
            if (!sel.targetsCitizen(citizen.getUUID()) || sel.targetsAllWorkers()) continue; // bound to me only
            BlockPos anchor = new BlockPos(sel.minX(), sel.minY(), sel.minZ());
            if (sl.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) return; // loaded — normal path handles it
            if (settlement.workingClaims().contains(new ChunkPos(anchor).toLong())) {
                citizen.setOutpostSite(anchor.immutable());
            }
            return;
        }
    }

    private boolean wireDepositStorage(ServerLevel sl, DepositSite site) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        ChunkPos cp = new ChunkPos(site.anchor());
        boolean outpost = settlement.workingClaims().contains(cp.toLong());
        citizen.setOutpostSite(outpost ? site.anchor().immutable() : null);
        if (!outpost) return true;
        BlockPos storage = MinerWorkGoal.findOutpostStorage(sl, cp, site.anchor());
        if (storage == null) return false;
        if (!storage.equals(citizen.getDropOff())) citizen.setDropOff(storage);
        return true;
    }

    private TargetPick findDepositTarget(ServerLevel sl, DepositSite site) {
        BlockPos origin = citizen.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos bestImm = null;
        double bestImmD = Double.MAX_VALUE;
        for (MaterialDepositLayout.Spot s
                : MaterialDepositLayout.spots(sl.getSeed(), new ChunkPos(site.anchor()), site.baseY(), site.resource())) {
            if (!s.source()) continue;
            BlockPos pos = s.pos();
            if (!isDepositSource(site.resource(), pos)) continue;
            if (!isExposed(sl, pos)) continue;
            if (isRecentlyFailed(pos)) continue;
            if (DiggerClaims.isClaimedByOther(sl, pos, citizen.getId())) continue;
            double d = origin.distSqr(pos);
            if (d < bestD) {
                bestD = d;
                best = pos.immutable();
            }
            if (d < bestImmD && d <= IMMEDIATE_PREFILTER_SQ && canMineFromHere(pos)) {
                bestImmD = d;
                bestImm = pos.immutable();
            }
        }
        if (bestImm != null) return new TargetPick(bestImm, true);
        return best == null ? null : new TargetPick(best, false);
    }

    private boolean markerStillDeposit() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null || depositAnchor == null) return false;
        for (BlockSelection sel : BlockSelectionRegistry.get(sl).getForSettlement(settlement.id())) {
            if (sel.completed() || sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!isMaterialDepositSelection(sel)) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            if (sel.minX() == depositAnchor.getX()
                    && sel.minY() == depositAnchor.getY()
                    && sel.minZ() == depositAnchor.getZ()
                    && MaterialDepositLayout.materialResource(sel.seedItemId()) == depositResource
                    && MaterialDepositLayout.materialBaseY(sel.seedItemId()) == depositBaseY) {
                return true;
            }
        }
        return false;
    }

    private boolean isDepositSource(BlockPos pos) {
        return isDepositSource(depositResource, pos);
    }

    private boolean isDepositSource(ChunkResource type, BlockPos pos) {
        if (pos == null || !(citizen.level() instanceof ServerLevel sl)) return false;
        return sl.getBlockState(pos).is(MaterialDepositLayout.sourceBlock(type).getBlock());
    }

    private void clearTargetState() {
        DiggerClaims.releaseAll(citizen.getId());
        targetPos = null;
        targetDrops = List.of();
        targetDeposit = false;
        depositAnchor = null;
        depositResource = ChunkResource.NONE;
        depositBaseY = Integer.MIN_VALUE;
    }

    /** Reserve both the target block and the tile we'll stand on (the approach tile, or our feet for an
     *  in-place mine). The standing-spot reservation is what lets {@link #contestedTile} keep a second
     *  digger out of a space this one already occupies. */
    private void claimWorkArea() {
        int id = citizen.getId();
        if (targetPos != null) DiggerClaims.claim(targetPos, id);
        DiggerClaims.claim(approachPos != null ? approachPos : citizen.blockPosition(), id);
    }

    /** True if another digger has reserved a block or standing spot within {@link #CONTEST_RADIUS} of
     *  {@code tile} — i.e. this tile is inside an area another worker is already working. */
    private boolean contestedTile(BlockPos tile) {
        return citizen.level() instanceof ServerLevel sl
            && DiggerClaims.hasOtherClaimNear(sl, tile, citizen.getId(), CONTEST_RADIUS);
    }

    /** The current approach tile proved unreachable (no progress): try the next one for the SAME block;
     *  only when all are exhausted do we abandon the block + release its claim for a better-placed
     *  digger. This is how a worker reaches a block from a different side on its own. */
    private void advanceApproachOrAbandon() {
        approachIdx++;
        if (approachIdx >= approaches.size()) {
            markFailedAndAbandon();
            return;
        }
        approachPos = approaches.get(approachIdx);
        resetApproachWatchdog();
        navigateToTarget();
    }

    /**
     * Tiles within ~2 blocks of {@code target} that the worker can both STAND on and SEE the target
     * from (sorted nearest-{@code origin} first). Both conditions matter: navigating to a standable
     * tile (never the solid block) makes the navigator walk down into a pit / up to a wall instead of
     * parking on the rim; requiring line of sight from that tile means we only ever commit to a block
     * we'll actually be able to mine when we arrive — no more chasing blocks we can't see.
     */
    private java.util.List<BlockPos> findApproaches(Level level, BlockPos target, BlockPos origin) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos f = target.offset(dx, dy, dz);
                    if (WorkerPathing.isWalkable(level, f) && hasSightFrom(level, f, target)
                        && !contestedTile(f)) {
                        out.add(f);
                    }
                }
            }
        }
        out.sort(java.util.Comparator.comparingDouble(origin::distSqr));
        return out;
    }

    /** Would a worker standing at {@code stand} have a clear line of sight to {@code target}? Same
     *  block raycast as {@link #canMineFromHere} but from a hypothetical eye over {@code stand}, used
     *  to vet an approach tile before we commit to walking there. */
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

    @Override
    protected boolean canKeepWorking() {
        Container depot = resolveDepot();
        if (depot == null || !depotHasRoomFor(depot, targetDrops)) return false;
        if (targetDeposit) {
            if (!JOB_TYPE_ID.equals(citizen.getJobType()) || !citizen.hasJobTool()) return false;
            return targetPos != null
                && markerStillDeposit()
                && isDepositSource(targetPos)
                && canMineRole(MaterialDepositLayout.requiredRole(depositResource));
        }
        if (!citizen.isJobReady(JOB_TYPE_ID)) return false;
        return targetPos != null && isStillOrdered(targetPos) && isMineable(citizen.level(), targetPos);
    }

    @Override
    public void start() {
        mineTimer = 0;
        citizen.setWorking(true);
        // Let the pathfinder route down into the pit (and ride the drop unharmed) while excavating —
        // vanilla won't path a drop > 3, which strands the worker at the rim of any deeper hole.
        citizen.setDeepDigDescent(true);
        // Render the player-provided shovel in hand (a copy — the canonical tool lives on the citizen).
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, citizen.getJobTool().copy());
        resetApproachWatchdog();
        navigateToTarget();
    }

    @Override
    public void stop() {
        mineTimer = 0;
        citizen.setWorking(false);
        citizen.setDeepDigDescent(false);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        DiggerClaims.releaseAll(citizen.getId());   // free our reservation for other diggers
        clearTargetState();
    }

    @Override
    public void tick() {
        if (!citizen.isJobReady(JOB_TYPE_ID)) return;
        if (targetPos == null) return;
        targetAge++;
        if (targetAge > TARGET_TIMEOUT_TICKS) { markFailedAndAbandon(); return; }
        citizen.getLookControl().setLookAt(
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

        if (canMineFromHere(targetPos)) {
            // In reach + line of sight → mine it. No precise positioning needed.
            citizen.getNavigation().stop();
            mineTimer++;
            String role = targetDeposit
                ? MaterialDepositLayout.requiredRole(depositResource)
                : requiredRole(citizen.level().getBlockState(targetPos));
            ItemStack tool = toolForRole(role);
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, tool.copy());
            ToolAge toolAge = ToolAgeLoader.getByTool(role, tool.getItem());
            int budget = toolAge != null ? toolAge.mineTicks().orElse(DEFAULT_MINE_TICKS) : DEFAULT_MINE_TICKS;
            if (targetDeposit) {
                budget = Math.max(1, (budget * DEPOSIT_MINE_NUMERATOR + DEPOSIT_MINE_DENOMINATOR - 1)
                    / DEPOSIT_MINE_DENOMINATOR);
            }
            budget = com.bannerbound.core.api.quality.QualityTier.scaleWorkTicks(tool, budget);
            // Experience on top of tool age/quality: a seasoned digger works faster.
            budget = skilledWorkTicks(budget);
            int interval = Math.max(1, budget / 3);
            if (mineTimer % interval == 0 && citizen.level() instanceof ServerLevel sl) {
                playSwing(sl, targetPos);
            }
            if (mineTimer >= budget) {
                if (targetDeposit) mineDepositBlock(); else mineBlock();
                mineTimer = 0;
            }
        } else {
            // Not in reach/sight yet → walk to the approach tile. Watchdog: if we stop getting closer
            // to it, that approach is unreachable from here — try the next stand tile, then (when all
            // are exhausted) abandon the block so a better-placed digger takes it. No stalling.
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
            if (citizen.getNavigation().isDone()) {
                navigateToTarget();
            }
            mineTimer = 0;
        }
    }

    /**
     * True if the worker can mine {@code t} right now: its nearest point is within {@link #REACH} of
     * the worker's eyes AND there's a clear line of sight to it (a block raycast that isn't interrupted
     * by another block). The line-of-sight test is what prevents digging through walls or floors — a
     * wall between the worker and {@code t} stops the ray — so the reach can be generous without the
     * worker ever reaching "through" anything.
     */
    private boolean canMineFromHere(BlockPos t) {
        Vec3 eye = citizen.getEyePosition();
        // Closest point on the target block's box to the eye — "can I touch any part of it", not just
        // its centre, so a block we're right on top of / beside always qualifies.
        Vec3 closest = new Vec3(
            Mth.clamp(eye.x, t.getX(), t.getX() + 1.0),
            Mth.clamp(eye.y, t.getY(), t.getY() + 1.0),
            Mth.clamp(eye.z, t.getZ(), t.getZ() + 1.0));
        if (eye.distanceToSqr(closest) > REACH_SQ) return false;
        BlockHitResult hit = citizen.level().clip(new ClipContext(
            eye, closest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, citizen));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(t);
    }

    /** Walk to the current approach tile — a real standable spot near the target (so the navigator
     *  descends the steps into a pit instead of parking on the rim). The reach + line-of-sight check
     *  does the actual mining, so we don't need to land on an exact adjacent tile. */
    private void navigateToTarget() {
        if (approachPos == null) return;
        citizen.getNavigation().moveTo(
            approachPos.getX() + 0.5, approachPos.getY(), approachPos.getZ() + 0.5, skilledSpeed());
    }

    private void resetApproachWatchdog() {
        bestApproachDistSq = Double.MAX_VALUE;
        stagnation = 0;
    }

    /** True if {@code pos} timed out recently and its skip window hasn't expired (prunes on access). */
    private boolean isRecentlyFailed(BlockPos pos) {
        Integer expiry = recentlyFailed.get(pos);
        if (expiry == null) return false;
        if (citizen.tickCount >= expiry) { recentlyFailed.remove(pos); return false; }
        return true;
    }

    /** Couldn't reach this block — skip it for a while, release its claim, and move on. */
    private void markFailedAndAbandon() {
        if (targetPos != null) {
            recentlyFailed.put(targetPos.immutable(), citizen.tickCount + FAILED_TTL_TICKS);
            DiggerClaims.releaseAll(citizen.getId());   // free both block + standing-spot reservations
        }
        clearTargetState();
        targetAge = 0;
        mineTimer = 0;
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
    }

    /** Works a material-deposit face without destroying the deposit's mass. */
    private void mineDepositBlock() {
        Level level = citizen.level();
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!isDepositSource(targetPos) || !markerStillDeposit()) {
            clearTargetState();
            targetAge = 0;
            mineTimer = 0;
            return;
        }
        Container depot = resolveDepot();
        BlockState before = level.getBlockState(targetPos);
        List<ItemStack> drops = computeDepositDrops(depositResource);
        level.setBlock(targetPos, MaterialDepositLayout.workedBlock(depositResource), 3);
        serverLevel.playSound(null, targetPos, before.getSoundType().getBreakSound(),
            SoundSource.BLOCKS, 0.8f, 1.0f);
        serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, before),
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5,
            10, 0.3, 0.3, 0.3, 0.0);
        com.bannerbound.core.api.research.InsightManager.recordEvent(
            serverLevel.getServer(), citizen.getSettlement(), "mine_block",
            com.bannerbound.core.api.research.InsightManager.matcherFor(before.getBlock()), 1);
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            if (depot == null) {
                citizen.spawnAtLocation(drop);
                continue;
            }
            ItemStack leftover = DropOffContainers.insert(depot, drop);
            if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
        }
        citizen.grantJobXp(JOB_TYPE_ID, 1.0F,
            depositResource.name().toLowerCase(java.util.Locale.ROOT));
        citizen.consumeStamina(1);
        clearTargetState();
        targetAge = 0;
    }

    /** Breaks the target block, routes its drops to the drop-off container, and spends one stamina. */
    private void mineBlock() {
        Level level = citizen.level();
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!isMineable(level, targetPos)) {
            DiggerClaims.releaseAll(citizen.getId());
            targetPos = null; targetAge = 0; mineTimer = 0;
            return;
        }
        Container depot = resolveDepot();
        BlockState state = level.getBlockState(targetPos);
        List<ItemStack> drops = Block.getDrops(state, serverLevel, targetPos, level.getBlockEntity(targetPos));
        // Strip drops the civ doesn't recognize yet (same gate as a player breaking the block).
        com.bannerbound.core.api.research.SettlementDropFilter.filterStacks(citizen.getSettlement(),
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()), drops);
        // Prospecting Quarry policy: small, daily-capped chance that natural stone turns up a
        // common raw ore as a bonus drop (the ore-poor start's scarcity floor — see the class doc).
        ItemStack prospected = ProspectingQuarry.tryBonus(serverLevel, citizen.getSettlement(),
            state, requiredRole(state));
        if (!prospected.isEmpty()) drops.add(prospected);
        level.destroyBlock(targetPos, false, citizen);
        com.bannerbound.core.api.research.InsightManager.recordEvent(
            serverLevel.getServer(), citizen.getSettlement(), "mine_block",
            com.bannerbound.core.api.research.InsightManager.matcherFor(state.getBlock()), 1);
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            if (depot == null) { citizen.spawnAtLocation(drop); continue; }
            ItemStack leftover = DropOffContainers.insert(depot, drop);
            // Mined drops are valuable (ores, stone) — spill the overflow at the citizen's feet
            // rather than discarding it (unlike the forester's leaf clutter).
            if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
        }
        citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "stone");
        citizen.consumeStamina(1);
        DiggerClaims.releaseAll(citizen.getId());   // block's gone — free block + standing-spot reservations
        // Completion (a selection with nothing left to dig) is detected + removed in findTarget on
        // the next scan — cheaper than re-scanning the whole box after every single block here.
        targetPos = null;
        targetAge = 0;
    }

    /**
     * Scans this settlement's digger selections that target this citizen and returns the best block to
     * mine next (highest, then nearest). A selection is removed only when it's truly dug out — no
     * terrain blocks of ANY kind remain in it. A selection the worker can't finish yet (stone left but
     * no pickaxe / no Quarry research, or blocks still buried) is kept so it lingers until the work is
     * actually done. Selections otherwise clear only on shift-left-click delete or settlement disband.
     */
    /** A chosen block plus whether it's minable from where the worker already stands ({@code true} →
     *  mine in place, no navigation) or has to be walked to ({@code false}). */
    private record TargetPick(BlockPos block, boolean immediate) {}

    private TargetPick findTarget() {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
        Level level = citizen.level();
        if (!(level instanceof ServerLevel serverLevel)) return null;
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(serverLevel);
        BlockPos origin = citizen.blockPosition();
        BlockPos best = null;            // nearest exposed block to WALK to (top-down)
        int bestY = Integer.MIN_VALUE;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos bestImm = null;         // nearest block minable from right here (preferred)
        double bestImmDistSq = Double.MAX_VALUE;
        boolean removedAny = false;
        for (BlockSelection sel : registry.getForSettlement(settlement.id())) {
            if (sel.completed()) continue;
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (isMaterialDepositSelection(sel)) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            Scan scan = scanSelection(level, sel, origin);
            // Only declare a zone "done" (and remove it) when it's FULLY LOADED and holds no terrain.
            // Without the loaded check, an unloaded chunk reads as air and the order would vanish
            // mid-dig the moment the worker walks out of range — the "random clearing" bug.
            if (scan.allLoaded() && !scan.anyTerrain()) {
                registry.unregister(sel.rodId());
                removedAny = true;
                continue;
            }
            if (scan.immediate() != null && scan.immediateDistSq() < bestImmDistSq) {
                bestImmDistSq = scan.immediateDistSq();
                bestImm = scan.immediate();
            }
            // Top-down across all selections: the highest block wins, nearest breaks ties.
            if (scan.nearest() != null && isBetterTarget(scan.bestY(), scan.bestDistSq(), bestY, bestDistSq)) {
                bestY = scan.bestY();
                bestDistSq = scan.bestDistSq();
                best = scan.nearest();
            }
        }
        if (removedAny) SelectionBroadcaster.broadcast(serverLevel.getServer());
        // Always clear what's in reach first — that's the block "in front of" the worker, no walking.
        if (bestImm != null) return new TargetPick(bestImm, true);
        if (best != null) return new TargetPick(best, false);
        return null;
    }

    /** Top-down ordering: a candidate beats the incumbent if it's higher, or at the same height and
     *  closer. Keeps the work face at the surface where blocks stay exposed and in sight. */
    private static boolean isBetterTarget(int y, double distSq, int bestY, double bestDistSq) {
        if (y != bestY) return y > bestY;
        return distSq < bestDistSq;
    }

    /** True while {@code pos} still lies inside at least one live (non-completed) digger selection that
     *  targets this citizen. Goes false the instant the player deletes the order (shift+left-click) or
     *  the settlement disbands — so the worker abandons any cached target/dig from a removed selection. */
    private boolean isStillOrdered(BlockPos pos) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        if (!(citizen.level() instanceof ServerLevel serverLevel)) return false;
        for (BlockSelection sel : BlockSelectionRegistry.get(serverLevel).getForSettlement(settlement.id())) {
            if (sel.completed()) continue;
            if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
            if (!SELECTION_TYPE.equals(sel.workstationType())) continue;
            if (isMaterialDepositSelection(sel)) continue;
            if (!sel.targetsCitizen(citizen.getUUID())) continue;
            if (pos.getX() >= sel.minX() && pos.getX() <= sel.maxX()
             && pos.getY() >= sel.minY() && pos.getY() <= sel.maxY()
             && pos.getZ() >= sel.minZ() && pos.getZ() <= sel.maxZ()) {
                return true;
            }
        }
        return false;
    }

    /** Result of one selection scan. {@code immediate} is the nearest block the worker can mine from
     *  exactly where it stands (reach + line of sight, NO walking) — always preferred so it clears
     *  what's in front of it before pathing anywhere. {@code nearest} (with {@code bestY}/{@code
     *  bestDistSq}) is the best block to WALK to otherwise (top-down). The flags tell "dug out" from
     *  "unloaded" so the order isn't cleared prematurely. */
    private record Scan(BlockPos immediate, double immediateDistSq,
                        BlockPos nearest, int bestY, double bestDistSq,
                        boolean anyTerrain, boolean allLoaded) {}

    /** Squared feet-distance pre-filter: only blocks this close get the (cheap but non-free) reach +
     *  line-of-sight raycast for the "mine from here" test. A bit beyond {@link #REACH}. */
    private static final double IMMEDIATE_PREFILTER_SQ = 30.0;

    private Scan scanSelection(Level level, BlockSelection sel, BlockPos origin) {
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        int bestY = Integer.MIN_VALUE;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos imm = null;
        double immDistSq = Double.MAX_VALUE;
        boolean anyTerrain = false;
        boolean allLoaded = true;
        for (int y = sel.maxY(); y >= sel.minY(); y--) {        // top-down
            for (int x = sel.minX(); x <= sel.maxX(); x++) {
                for (int z = sel.minZ(); z <= sel.maxZ(); z++) {
                    c.set(x, y, z);
                    // Don't read (or force-load) unloaded chunks — just note they're unresolved.
                    if (!level.isLoaded(c)) { allLoaded = false; continue; }
                    if (!isTerrain(level, c)) continue;          // a block a quarryworker ever digs
                    anyTerrain = true;
                    if (!isMineable(level, c)) continue;          // ...that THIS worker can mine now
                    if (!isExposed(level, c)) continue;           // ...has an open face (visible/reachable)
                    if (isRecentlyFailed(c)) continue;            // ...and didn't just fail to reach
                    if (level instanceof ServerLevel sl
                        && DiggerClaims.isClaimedByOther(sl, c, citizen.getId())) continue; // ...or another digger's
                    double d = origin.distSqr(c);
                    if (isBetterTarget(y, d, bestY, bestDistSq)) { bestY = y; bestDistSq = d; best = c.immutable(); }
                    // Can we mine it from right here, no walking? Prefer the nearest such block.
                    if (d < immDistSq && d <= IMMEDIATE_PREFILTER_SQ && canMineFromHere(c)) {
                        immDistSq = d; imm = c.immutable();
                    }
                }
            }
        }
        return new Scan(imm, immDistSq, best, bestY, bestDistSq, anyTerrain, allLoaded);
    }

    /** True if {@code pos} has at least one open (non-colliding) face — air, foliage, water, etc. — so
     *  it's reachable and visible to a worker standing on that side, rather than fully entombed in solid
     *  rock. Targeting only exposed blocks means the worker never fixates on something it can't see; as
     *  the exposed face is mined, the block behind it becomes exposed and is picked next (digging
     *  front-to-back into a wall), which is exactly the "mine the first block you can see" behaviour. */
    private static boolean isExposed(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            n.setWithOffset(pos, d);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }

    /** True if {@code pos} holds a terrain block a quarryworker would eventually dig (soil- or
     *  stone-tier), regardless of whether THIS worker currently has the tool. Used to tell "zone fully
     *  excavated" apart from "this worker can't finish it yet". */
    private boolean isTerrain(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (state.getDestroySpeed(level, pos) < 0) return false;          // unbreakable
        if (state.getCollisionShape(level, pos).isEmpty()) return false;  // foliage/décor
        return requiredRole(state) != null;
    }

    /**
     * Mineable = a breakable terrain block the citizen has the right tool for. Soil-tier blocks need
     * the shovel (the primary tool); stone-tier blocks (stone, ores, coal) need a pickaxe in the
     * second slot AND the Quarry research. Anything that's neither tag (wood, wool, décor) is left
     * alone — a quarryworker clears terrain, not builds. Reachability isn't decided here; the reach +
     * line-of-sight check in {@link #canMineFromHere} handles that at mine time.
     */
    private boolean isMineable(Level level, BlockPos pos) {
        if (pos == null) return false;
        if (pos.equals(citizen.getDropOff())) return false;
        if (!isTerrain(level, pos)) return false;
        return canMineRole(requiredRole(level.getBlockState(pos)));
    }

    /** Tool role needed to mine {@code state}: {@code "shovel"} for soil-tier (dirt/sand/gravel),
     *  {@code "pickaxe"} for stone-tier (stone/ores/coal), or {@code null} for blocks the digger
     *  doesn't touch. */
    private static String requiredRole(BlockState state) {
        if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)) return "shovel";
        if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) return "pickaxe";
        return null;
    }

    /** True if this citizen can currently mine a block of {@code role}: shovel is the primary tool
     *  (already required by {@code isJobReady}); pickaxe also needs the Quarry research + a pickaxe. */
    private boolean canMineRole(String role) {
        if ("shovel".equals(role)) return true;
        if ("pickaxe".equals(role)) {
            Settlement s = citizen.getSettlement();
            return citizen.hasJobPickaxe() && s != null
                && com.bannerbound.core.api.research.ResearchManager.hasFlag(s, FLAG_QUARRY);
        }
        return false;
    }

    private ItemStack toolForRole(String role) {
        return "pickaxe".equals(role) ? citizen.getJobPickaxe() : citizen.getJobTool();
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
