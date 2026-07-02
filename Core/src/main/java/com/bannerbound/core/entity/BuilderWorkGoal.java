package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.stockpile.StockpileService;
import com.bannerbound.core.api.walls.WallData;
import com.bannerbound.core.world.WallTasks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Builder {@link WorkGoal} (WALLS_PLAN.md Phase 4) — works the settlement's wall plan via the
 * derived {@link WallTasks} board: PLACE blueprint blocks (materials withdrawn from the
 * drop-off depot first, then settlement stockpiles — remote, like every worker's yield),
 * CLEAR footprint vegetation (logs banked to the depot), DEMOLISH obsolete wall blocks
 * (broken block refunded to the depot). No marker: the workplace IS the plan, so the goal
 * activates whenever the settlement has open wall tasks.
 *
 * <p>Approach/reach/swing mechanics mirror {@link MinerWorkGoal} (stand near, raycast,
 * generous reach, stagnation watchdog). Placement and demolition MUST call
 * {@code WallData.markBuilt}/{@code clearBuilt} inline — workers bypass block events
 * (established pattern), and the placement-time wall memory is what keeps terrain-block
 * walls from ever reading as terrain.
 *
 * <p>Known v1 limit (plan §F): reach ~4.5 from walkable ground covers ~5 courses; taller
 * designs' upper courses fall to the player until scaffolding ships. Unreachable tasks are
 * reported to the board, never silently spun on.
 */
@ApiStatus.Internal
public class BuilderWorkGoal extends WorkGoal {
    /** Per-citizen job id (Job tab / unlocks / icons all key off this). */
    public static final String JOB_TYPE_ID = "builder";

    /** Ticks per block: ONE swing, then the block lands (user decision 2026-06-11 — building
     *  reads as brisk placement, not mining; half a second keeps the arm animation visible). */
    private static final int WORK_TICKS = 10;
    private static final int RESCAN_COOLDOWN_TICKS = 60;
    private static final double REACH = 4.5;
    private static final double REACH_SQ = REACH * REACH;
    private static final int TARGET_TIMEOUT_TICKS = 240;
    private static final int STAGNATION_LIMIT = 30;
    /** Bounded attempts to find a workable task per scan (skipping unsupplied/unreachable). */
    private static final int CLAIM_ATTEMPTS = 8;

    private WallTasks.Task task;
    private BlockPos targetPos;
    private int workTimer;
    private int targetAge;
    private int rescanCooldown;
    private List<BlockPos> approaches = List.of();
    private int approachIdx;
    private BlockPos approachPos;
    private double bestApproachDistSq = Double.MAX_VALUE;
    private int stagnation;

    public BuilderWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    // ─── Start / continue ────────────────────────────────────────────────────────────────────

    @Override
    protected boolean canStartWork() {
        citizen.validateJobStorage();
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        if (!JOB_TYPE_ID.equals(citizen.getJobType())) return false;
        if (rescanCooldown-- > 0) return false;
        if (!citizen.isGatherJobReady(JOB_TYPE_ID)) return false;
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        if (WallData.get(sl).plan(settlement.id()) == null) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS * 2;
            return false;
        }

        for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
            WallTasks.Task candidate = WallTasks.claim(sl, settlement, citizen.getUUID(),
                citizen.blockPosition());
            if (candidate == null) break;
            if (candidate.kind == WallTasks.Kind.PLACE && !materialsAvailable(sl, settlement, candidate)) {
                WallTasks.markUnsupplied(settlement.id(), candidate);
                continue;
            }
            BlockPos pos = candidate.blockPos();
            List<BlockPos> found = findApproaches(sl, pos, citizen.blockPosition());
            if (found.isEmpty() && !canWorkFromHere(pos)) {
                WallTasks.markUnreachable(settlement.id(), candidate);
                continue;
            }
            task = candidate;
            targetPos = pos;
            approaches = found;
            approachIdx = 0;
            approachPos = found.isEmpty() ? null : found.get(0);
            resetApproachWatchdog();
            targetAge = 0;
            return true;
        }
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
        return false;
    }

    @Override
    protected boolean canKeepWorking() {
        if (!citizen.isGatherJobReady(JOB_TYPE_ID)) return false;
        return task != null && targetPos != null;
    }

    @Override
    public void start() {
        workTimer = 0;
        citizen.setWorking(true);
        showHandFor(task);
        resetApproachWatchdog();
        navigateToTarget();
    }

    @Override
    public void stop() {
        workTimer = 0;
        citizen.setWorking(false);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        Settlement settlement = citizen.getSettlement();
        if (task != null && settlement != null) {
            WallTasks.release(settlement.id(), task);
        }
        task = null;
        targetPos = null;
    }

    @Override
    public void tick() {
        if (task == null || targetPos == null) return;
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        targetAge++;
        if (targetAge > TARGET_TIMEOUT_TICKS) { abandonTask(); return; }
        citizen.getLookControl().setLookAt(
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

        if (!taskStillValid(sl)) { finishAndNext(sl, false); return; }

        if (canWorkFromHere(targetPos)) {
            citizen.getNavigation().stop();
            workTimer++;
            if (workTimer == 1) playSwing(sl, targetPos);   // exactly one swing per block
            if (workTimer >= skilledWorkTicks(WORK_TICKS)) { // a practiced builder lays blocks faster
                perform(sl);
                workTimer = 0;
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
            workTimer = 0;
        }
    }

    // ─── The work ────────────────────────────────────────────────────────────────────────────

    /** The world may have changed under the task (hand-built, regen, another builder). */
    private boolean taskStillValid(ServerLevel sl) {
        BlockState actual = sl.getBlockState(targetPos);
        return switch (task.kind) {
            case PLACE -> task.expected != null
                && !actual.is(task.expected.getBlock())
                && (actual.isAir() || actual.canBeReplaced());
            case CLEAR, DEMOLISH -> !actual.isAir();
        };
    }

    private void perform(ServerLevel sl) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) { abandonTask(); return; }
        switch (task.kind) {
            case PLACE -> {
                Item material = task.expected.getBlock().asItem();
                if (!withdrawMaterial(sl, settlement, material)) {
                    WallTasks.markUnsupplied(settlement.id(), task);
                    abandonTask();
                    return;
                }
                // The blueprint's expected states carry the design's BAKED connections
                // (WallData.blueprint → WallConnectivity.bake) — place them VERBATIM, and
                // with flag 2|16 (client update, NO neighbor shape updates) so vanilla can't
                // mutate this block or the already-placed wall around it ("keep exactly the
                // block-states/connections from the wall design", playtest 2026-06-12).
                sl.setBlock(targetPos, task.expected, 2 | 16);
                com.bannerbound.core.api.research.InsightManager.recordEvent(
                    sl.getServer(), settlement, "place_block",
                    com.bannerbound.core.api.research.InsightManager.matcherFor(task.expected.getBlock()), 1);
                WallData.get(sl).markBuilt(settlement.id(), targetPos.asLong());
                SoundType st = task.expected.getSoundType();
                sl.playSound(null, targetPos, st.getPlaceSound(), SoundSource.BLOCKS,
                    st.getVolume() * 0.8f, st.getPitch());
            }
            case CLEAR, DEMOLISH -> {
                BlockState before = sl.getBlockState(targetPos);
                // Refund: demolished wall blocks always; cleared vegetation banks logs only.
                Item refund = task.kind == WallTasks.Kind.DEMOLISH
                    ? before.getBlock().asItem()
                    : (before.is(BlockTags.LOGS) ? before.getBlock().asItem() : null);
                sl.playSound(null, targetPos, before.getSoundType().getBreakSound(),
                    SoundSource.BLOCKS, 0.8f, 1.0f);
                sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, before),
                    targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5,
                    10, 0.3, 0.3, 0.3, 0.0);
                sl.setBlock(targetPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                WallData.get(sl).clearBuilt(settlement.id(), targetPos.asLong());
                if (refund != null && refund != net.minecraft.world.item.Items.AIR) {
                    Container depot = resolveDepot();
                    ItemStack stack = new ItemStack(refund);
                    if (depot != null) {
                        ItemStack leftover = DropOffContainers.insert(depot, stack);
                        if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
                    } else {
                        citizen.spawnAtLocation(stack);
                    }
                }
            }
        }
        citizen.consumeStamina(1);
        finishAndNext(sl, true);
    }

    /** Depot first (the builder's own drop-off doubles as the material staging chest), then
     *  the settlement-wide stockpiles. Remote, like every other worker's yield routing. */
    private boolean withdrawMaterial(ServerLevel sl, Settlement settlement, Item material) {
        Container depot = resolveDepot();
        if (depot != null) {
            ItemStack got = DropOffContainers.extract(depot, material, 1);
            if (!got.isEmpty()) return true;
        }
        if (StockpileService.count(sl, settlement, material) > 0) {
            StockpileService.withdraw(sl, settlement, material, 1);
            return true;
        }
        return false;
    }

    private boolean materialsAvailable(ServerLevel sl, Settlement settlement, WallTasks.Task candidate) {
        Item material = candidate.expected.getBlock().asItem();
        Container depot = resolveDepot();
        if (depot != null) {
            for (int i = 0; i < depot.getContainerSize(); i++) {
                ItemStack s = depot.getItem(i);
                if (!s.isEmpty() && s.is(material)) return true;
            }
        }
        return StockpileService.count(sl, settlement, material) > 0;
    }

    private void finishAndNext(ServerLevel sl, boolean completed) {
        Settlement settlement = citizen.getSettlement();
        if (settlement != null && task != null) {
            if (completed) {
                WallTasks.complete(settlement.id(), task);
                citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "wall");
            } else {
                WallTasks.release(settlement.id(), task);
            }
        }
        task = null;
        targetPos = null;
        targetAge = 0;
        // Claim the next task inline so a builder flows along the wall without goal churn.
        if (settlement != null) {
            for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
                WallTasks.Task next = WallTasks.claim(sl, settlement, citizen.getUUID(),
                    citizen.blockPosition());
                if (next == null) break;
                if (next.kind == WallTasks.Kind.PLACE && !materialsAvailable(sl, settlement, next)) {
                    WallTasks.markUnsupplied(settlement.id(), next);
                    continue;
                }
                BlockPos pos = next.blockPos();
                List<BlockPos> found = findApproaches(sl, pos, citizen.blockPosition());
                if (found.isEmpty() && !canWorkFromHere(pos)) {
                    WallTasks.markUnreachable(settlement.id(), next);
                    continue;
                }
                task = next;
                targetPos = pos;
                approaches = found;
                approachIdx = 0;
                approachPos = found.isEmpty() ? null : found.get(0);
                resetApproachWatchdog();
                showHandFor(task);
                navigateToTarget();
                return;
            }
        }
        // Nothing workable — canKeepWorking goes false; rescan cooldown paces the restart.
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
    }

    /** Visible carry: PLACE shows the material in hand; CLEAR/DEMOLISH work bare-handed. */
    private void showHandFor(WallTasks.Task t) {
        if (t != null && t.kind == WallTasks.Kind.PLACE && t.expected != null) {
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                new ItemStack(t.expected.getBlock().asItem()));
        } else {
            citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    // ─── Movement / reach (miner regime) ─────────────────────────────────────────────────────

    private Container resolveDepot() {
        return DropOffContainers.resolveOrPreferred(citizen, citizen.getDropOff());
    }

    private boolean canWorkFromHere(BlockPos t) {
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

    private List<BlockPos> findApproaches(Level level, BlockPos target, BlockPos origin) {
        List<BlockPos> out = new ArrayList<>();
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

    private void navigateToTarget() {
        if (approachPos == null) return;
        citizen.getNavigation().moveTo(
            approachPos.getX() + 0.5, approachPos.getY(), approachPos.getZ() + 0.5, skilledSpeed());
    }

    private void advanceApproachOrAbandon() {
        approachIdx++;
        if (approachIdx >= approaches.size()) {
            Settlement settlement = citizen.getSettlement();
            if (settlement != null && task != null) {
                WallTasks.markUnreachable(settlement.id(), task);
            }
            abandonTask();
            return;
        }
        approachPos = approaches.get(approachIdx);
        resetApproachWatchdog();
        navigateToTarget();
    }

    private void abandonTask() {
        Settlement settlement = citizen.getSettlement();
        if (settlement != null && task != null) {
            WallTasks.release(settlement.id(), task);
        }
        task = null;
        targetPos = null;
        targetAge = 0;
        workTimer = 0;
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
        BlockState particleState = state.isAir()
            ? (task != null && task.expected != null ? task.expected : state)
            : state;
        if (!particleState.isAir()) {
            SoundType st = particleState.getSoundType();
            level.playSound(null, pos, st.getHitSound(), SoundSource.BLOCKS,
                st.getVolume() * 0.5f, st.getPitch());
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, particleState),
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.0);
        }
    }
}
