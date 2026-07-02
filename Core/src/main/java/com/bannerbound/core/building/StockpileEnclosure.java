package com.bannerbound.core.building;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Stockpile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Enclosure scanner for the Stockpile Rack.
 * <p>
 * Unlike {@link BuildingValidator} — a 3D air flood that leaks over a 1-tall fence and never
 * checks boundary block type — this is a <b>2D flood at the rack's own Y level</b>. It walks
 * outward, treats fences / fence gates / walls as the ONLY perimeter, and floods through
 * everything else (air, terrain, raised platforms, decorations, containers) as interior — so you
 * can put solid blocks inside. Every interior tile must be roofed, and the perimeter must include
 * at least one fence gate so citizens can walk in and out.
 * <p>
 * Storage blocks (anything exposing an {@code IItemHandler} block capability — chests, barrels,
 * hoppers, modded containers) found adjacent to the interior are collected, capped at
 * {@link #MAX_STORAGE}.
 */
@ApiStatus.Internal
public final class StockpileEnclosure {
    /** Max edge length of the interior bounding box, in blocks. */
    public static final int MAX_SPAN = 32;
    public static final int MAX_INTERIOR = 1024;
    public static final int MAX_ROOF_HEIGHT = 16;
    /** Container cap per stockpile — single source of truth on {@link Stockpile}. */
    public static final int MAX_STORAGE = Stockpile.MAX_CONTAINERS;

    public enum FailReason { NOT_ENCLOSED, NO_GATE, NO_ROOF, TOO_LARGE }

    public record Result(boolean valid, FailReason reason, BlockPos failPos,
                          Set<BlockPos> interior, List<BlockPos> storage) {
        public static Result success(Set<BlockPos> interior, List<BlockPos> storage) {
            return new Result(true, null, null, interior, storage);
        }

        public static Result fail(FailReason reason, BlockPos pos, Set<BlockPos> interior) {
            return new Result(false, reason, pos, interior, List.of());
        }
    }

    private StockpileEnclosure() {
    }

    /** Max blocks the enclosure floor may sit below an elevated stockpile block — the down-search
     *  range for the anchor plane. */
    public static final int MAX_ANCHOR_DROP = 8;

    /**
     * Scans the enclosure for the Stockpile at {@code rackPos}. The fenced floor may sit BELOW an
     * elevated block (fence ring on the ground, block up on a platform), so this tries the block's
     * own plane first and then successively lower planes; the first plane whose flood yields a valid
     * enclosure wins. If none validate, the block-plane result is returned so the failure message
     * reflects where the player is looking.
     */
    public static Result scan(ServerLevel level, BlockPos rackPos) {
        Result blockPlane = null;
        for (int drop = 0; drop <= MAX_ANCHOR_DROP; drop++) {
            Result r = scanAt(level, new BlockPos(rackPos.getX(), rackPos.getY() - drop, rackPos.getZ()));
            if (r.valid()) return r;
            if (blockPlane == null) blockPlane = r;
        }
        return blockPlane;
    }

    /** One enclosure scan with the footprint flooded at {@code seed}'s Y plane. See {@link #scan}. */
    private static Result scanAt(ServerLevel level, BlockPos seed) {
        Set<BlockPos> interior = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = seed.immutable();
        interior.add(start);
        queue.add(start);

        int minX = start.getX(), maxX = start.getX();
        int minZ = start.getZ(), maxZ = start.getZ();
        boolean hasGate = false;

        // 2D flood at the seed's Y. The fence/wall/gate ring (projected across a small vertical band
        // so a ring that steps with the terrain reads as one continuous wall) is the only boundary;
        // everything else inside it (air, terrain, platforms, decorations, containers) floods as
        // passable interior. An un-fenced/gapped side leaks → TOO_LARGE.
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos n = cur.relative(dir);
                if (interior.contains(n)) continue;
                int boundary = boundaryColumn(level, n.getX(), n.getZ(), seed.getY());
                if (boundary > 0) {
                    if (boundary == 2) hasGate = true;
                    continue;
                }
                interior.add(n);
                queue.add(n);
                minX = Math.min(minX, n.getX());
                maxX = Math.max(maxX, n.getX());
                minZ = Math.min(minZ, n.getZ());
                maxZ = Math.max(maxZ, n.getZ());
                if (maxX - minX + 1 > MAX_SPAN || maxZ - minZ + 1 > MAX_SPAN
                        || interior.size() > MAX_INTERIOR) {
                    return Result.fail(FailReason.TOO_LARGE, n, interior);
                }
            }
        }

        // Every interior tile must have a non-air block somewhere above it (the roof).
        BlockPos.MutableBlockPos rp = new BlockPos.MutableBlockPos();
        for (BlockPos cell : interior) {
            boolean roofed = false;
            for (int dy = 1; dy <= MAX_ROOF_HEIGHT; dy++) {
                rp.set(cell.getX(), cell.getY() + dy, cell.getZ());
                if (!level.getBlockState(rp).isAir()) {
                    roofed = true;
                    break;
                }
            }
            if (!roofed) return Result.fail(FailReason.NO_ROOF, cell, interior);
        }

        if (!hasGate) return Result.fail(FailReason.NO_GATE, seed, interior);

        // Storage: every container in the structure's whole volume — the footprint's bounding box,
        // widened a block (so storage hung on the perimeter posts counts), across the enclosure plane
        // ±MAX_ROOF_HEIGHT. So storage on the ground, on shelves, on perimeter posts, or beneath an
        // elevated block all reads, wherever the block sits. Capped at MAX_STORAGE.
        List<BlockPos> storage = new ArrayList<>();
        BlockPos.MutableBlockPos sp = new BlockPos.MutableBlockPos();
        int y0 = seed.getY() - MAX_ROOF_HEIGHT;
        int y1 = seed.getY() + MAX_ROOF_HEIGHT;
        storageScan:
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                for (int y = y0; y <= y1; y++) {
                    if (isContainer(level, sp.set(x, y, z))) {
                        storage.add(new BlockPos(x, y, z));
                        if (storage.size() >= MAX_STORAGE) break storageScan;
                    }
                }
            }
        }
        return Result.success(interior, storage);
    }

    private static boolean isFenceLike(BlockState state) {
        return state.is(BlockTags.FENCES)
            || state.is(BlockTags.FENCE_GATES)
            || state.is(BlockTags.WALLS);
    }

    /** Vertical band scanned around the scan plane when projecting the fence ring down to it, so a
     *  ring that follows terraced terrain still reads as a continuous wall. ±2 covers the common
     *  one- and two-block terrain steps; a steeply-sloped pen beyond that should be levelled. */
    private static final int PROJECT_DOWN = 2;
    private static final int PROJECT_UP = 2;

    /** Classifies column {@code (x,z)} against the projected fence ring at {@code scanY}:
     *  {@code 0} = not boundary, {@code 1} = boundary (fence/wall in the band), {@code 2} = boundary
     *  that includes a fence gate. */
    private static int boundaryColumn(ServerLevel level, int x, int z, int scanY) {
        int code = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -PROJECT_DOWN; dy <= PROJECT_UP; dy++) {
            BlockState s = level.getBlockState(p.set(x, scanY + dy, z));
            if (isFenceLike(s)) {
                if (s.is(BlockTags.FENCE_GATES)) return 2;
                code = 1;
            }
        }
        return code;
    }

    /** True if {@code pos} is a storage block: it exposes the item-handler capability (vanilla
     *  chests/barrels, modded containers) OR its block entity is a vanilla
     *  {@link net.minecraft.world.Container} (the Antiquity basket, which backs a Container but does
     *  not register the capability). */
    public static boolean isContainer(ServerLevel level, BlockPos pos) {
        if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null) return true;
        return level.getBlockEntity(pos) instanceof net.minecraft.world.Container;
    }
}
