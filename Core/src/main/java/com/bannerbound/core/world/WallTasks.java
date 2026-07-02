package com.bannerbound.core.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.walls.DefaultWallDesigns;
import com.bannerbound.core.api.walls.WallData;
import com.bannerbound.core.api.walls.WallLayoutEngine;
import com.bannerbound.core.api.walls.WallPlan;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The builders' task board (WALLS_PLAN.md Phase 4) — DERIVED STATE, never persisted (stocker
 * lesson): tasks are regenerated from "the blueprint/demolition-queue says X at pos P, the
 * world disagrees", so the board self-heals and player hand-building simply makes tasks
 * disappear. Regeneration is LAZY — it happens when a builder asks and the board is stale —
 * so settlements without builders pay nothing.
 *
 * <p>Task kinds: PLACE (blueprint position empty/replaceable), CLEAR (clearable vegetation/
 * decor in the footprint — logs are banked to the depot), DEMOLISH (a position in the plan's
 * obsolete queue still holding a block — broken and refunded). Non-terrain STRUCTURES in the
 * footprint never become tasks: red ghosts mark them, removal is the player's call.
 *
 * <p>Claims are per-citizen with a timeout (multiple builders = automatic load split). Tasks a
 * builder reports unreachable or unsupplied are skipped until the next regeneration, and the
 * counts surface in {@code /bannerbound walls status} so a stalled site is diagnosable.
 */
@ApiStatus.Internal
public final class WallTasks {

    public enum Kind { PLACE, CLEAR, DEMOLISH }

    public static final class Task {
        public final long pos;
        public final Kind kind;
        /** Expected state for PLACE; null for CLEAR/DEMOLISH. */
        @Nullable
        public final BlockState expected;
        @Nullable
        UUID claimedBy;
        long claimTick;
        boolean unreachable;
        boolean unsupplied;

        Task(long pos, Kind kind, @Nullable BlockState expected) {
            this.pos = pos;
            this.kind = kind;
            this.expected = expected;
        }

        public BlockPos blockPos() {
            return BlockPos.of(pos);
        }
    }

    private static final class Board {
        final List<Task> tasks = new ArrayList<>();
        /** -1 = never regenerated. NOT Long.MIN_VALUE — {@code now - MIN_VALUE} overflows
         *  negative and the staleness check would never fire (playtest bug 2026-06-11). */
        long lastRegenTick = -1L;
    }

    private static final Map<UUID, Board> BOARDS = new HashMap<>();
    private static final int REGEN_INTERVAL_TICKS = 200;
    private static final long CLAIM_TIMEOUT_TICKS = 600;

    private WallTasks() {
    }

    /**
     * Claims the best open task for {@code citizenId}: CLEAR/DEMOLISH first (the site must be
     * tidy before courses rise), then PLACE bottom-up, nearest first. Null = nothing workable.
     */
    @Nullable
    public static synchronized Task claim(ServerLevel level, Settlement settlement,
                                          UUID citizenId, BlockPos near) {
        Board board = BOARDS.computeIfAbsent(settlement.id(), k -> new Board());
        long now = level.getGameTime();
        if (board.lastRegenTick < 0 || now - board.lastRegenTick > REGEN_INTERVAL_TICKS) {
            regenerate(level, settlement, board, now);
        }
        Task best = null;
        double bestScore = Double.MAX_VALUE;
        for (Task task : board.tasks) {
            if (task.unreachable || task.unsupplied) continue;
            if (task.claimedBy != null && !task.claimedBy.equals(citizenId)
                && now - task.claimTick < CLAIM_TIMEOUT_TICKS) {
                continue;
            }
            BlockPos pos = task.blockPos();
            if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            // CLEAR/DEMOLISH outrank PLACE; PLACE sorts bottom-up so courses complete visibly.
            double score = (task.kind == Kind.PLACE ? 1_000_000.0 + pos.getY() * 10_000.0 : 0.0)
                + near.distSqr(pos);
            if (score < bestScore) {
                bestScore = score;
                best = task;
            }
        }
        if (best != null) {
            best.claimedBy = citizenId;
            best.claimTick = now;
        }
        return best;
    }

    public static synchronized void release(UUID settlementId, Task task) {
        task.claimedBy = null;
    }

    /** Builder couldn't path to it — skip until the next regeneration. */
    public static synchronized void markUnreachable(UUID settlementId, Task task) {
        task.unreachable = true;
        task.claimedBy = null;
    }

    /** PLACE task whose material is in neither the depot nor the stockpiles. */
    public static synchronized void markUnsupplied(UUID settlementId, Task task) {
        task.unsupplied = true;
        task.claimedBy = null;
    }

    public static synchronized void complete(UUID settlementId, Task task) {
        Board board = BOARDS.get(settlementId);
        if (board != null) {
            board.tasks.remove(task);
        }
    }

    /** [open, unsupplied, unreachable] — the walls status line. */
    public static synchronized int[] counts(UUID settlementId) {
        Board board = BOARDS.get(settlementId);
        if (board == null) return new int[]{0, 0, 0};
        int open = 0;
        int unsupplied = 0;
        int unreachable = 0;
        for (Task task : board.tasks) {
            if (task.unsupplied) unsupplied++;
            else if (task.unreachable) unreachable++;
            else open++;
        }
        return new int[]{open, unsupplied, unreachable};
    }

    private static void regenerate(ServerLevel level, Settlement settlement, Board board, long now) {
        board.lastRegenTick = now;
        board.tasks.clear();
        WallData walls = WallData.get(level);
        WallPlan plan = walls.plan(settlement.id());
        if (plan == null) return;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Long2ObjectMap<BlockState> blueprint =
            walls.blueprint(level, settlement.id(),
                com.bannerbound.core.api.walls.WallService.resolver(level, settlement));
        for (Long2ObjectMap.Entry<BlockState> entry : blueprint.long2ObjectEntrySet()) {
            long packed = entry.getLongKey();
            cursor.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            if (!level.hasChunk(cursor.getX() >> 4, cursor.getZ() >> 4)) continue;
            BlockState actual = level.getBlockState(cursor);
            BlockState expected = entry.getValue();
            if (actual.is(expected.getBlock())) continue;            // satisfied
            if (actual.isAir() || actual.canBeReplaced()) {
                board.tasks.add(new Task(packed, Kind.PLACE, expected));
            } else if (WallLayoutEngine.isClearableBlock(level, cursor, actual)) {
                board.tasks.add(new Task(packed, Kind.CLEAR, null));
            }
            // else: structure obstacle — red ghost, player's decision, never a task.
        }
        LongIterator obsolete = plan.obsolete().iterator();
        while (obsolete.hasNext()) {
            long packed = obsolete.nextLong();
            cursor.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            if (!level.hasChunk(cursor.getX() >> 4, cursor.getZ() >> 4)) continue;
            if (!level.getBlockState(cursor).isAir()) {
                board.tasks.add(new Task(packed, Kind.DEMOLISH, null));
            }
        }
    }
}
