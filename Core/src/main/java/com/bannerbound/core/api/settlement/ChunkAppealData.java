package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Per-chunk appeal state: a frozen per-column terrain reference, and a per-block-type
 * <b>placement-order queue</b> of the positions present "surface and up".
 *
 * <p><b>Scan model ("full structures").</b> When a chunk is first scanned, the
 * {@code WORLD_SURFACE} heightmap is snapshotted into {@link #refY} — the natural terrain floor.
 * Every block at or above its column's {@code refY} counts; terrain, caves, and mines below it
 * do not.
 *
 * <p><b>Queues.</b> For each block type, {@link #queues} holds its positions in placement order:
 * the 1st block placed is queue slot 1, the 2nd is slot 2, and so on. A block's slot {@code N}
 * contributes {@code appeal × 0.9^(N-1)} (diminishing returns); when a block is removed the rest
 * clamp up. Player place/break update the queue incrementally ({@link #recordPlace}/{@link
 * #recordBreak}); a {@link #fullScan} reconciles — it keeps the known order of blocks still
 * present and appends newly-found ones (non-player changes, initial scan) in scan order.
 *
 * <p>The chunk score only depends on each type's <i>count</i> (queue size), so it is unaffected
 * by ordering — the queue exists so a specific block can be told which slot it occupies.
 */
@ApiStatus.Internal
public class ChunkAppealData {
    public static final int COLUMNS = 256;
    /** {@link #refY} sentinel meaning "terrain reference not captured yet". */
    private static final short UNCAPTURED = Short.MIN_VALUE;

    /** Per-column natural terrain Y, indexed {@code (localX << 4) | localZ}. */
    private final short[] refY = new short[COLUMNS];
    /** Block type → its positions in placement order (queue slot = list index + 1). */
    private Map<Block, List<BlockPos>> queues = new HashMap<>();
    private boolean scanned;
    private boolean dirty;
    private double cachedScore;
    private ChunkBeauty cachedTag = ChunkBeauty.BLAND;
    /** True when the queues changed since the last {@link #recomputeScore} (transient). Together
     *  with {@link #lastStyleHash} it lets the per-second score refresh skip chunks where nothing
     *  changed — the recompute used to run unconditionally for every scanned chunk. */
    private transient boolean scoreStale = true;
    /** Hash of the (styles, palettes) lists the cached score was computed under (transient). */
    private transient int lastStyleHash;

    public ChunkAppealData() {
        Arrays.fill(refY, UNCAPTURED);
    }

    public boolean isScanned() { return scanned; }
    public boolean isDirty() { return dirty; }
    public void markDirty() { this.dirty = true; }
    public void clearDirty() { this.dirty = false; }
    public void markScanned() { this.scanned = true; }
    public double score() { return cachedScore; }
    public ChunkBeauty tag() { return cachedTag; }

    private static int idx(int localX, int localZ) {
        return (localX << 4) | localZ;
    }

    /** True if {@code pos} is at or above its column's captured terrain reference. */
    private boolean inBand(BlockPos pos) {
        int ref = refY[idx(pos.getX() & 15, pos.getZ() & 15)];
        return ref != UNCAPTURED && pos.getY() >= ref;
    }

    /** Queue slot (1-based) of the block at {@code target} among its type, or 0 if not counted. */
    public int queuePositionOf(BlockPos target) {
        for (List<BlockPos> queue : queues.values()) {
            int i = queue.indexOf(target);
            if (i >= 0) return i + 1;
        }
        return 0;
    }

    /** Records a player-placed block: it joins the end of its type's queue (placement order). */
    public void recordPlace(BlockPos pos, Block placed) {
        BlockPos p = pos.immutable();
        removeFromAllQueues(p);
        if (inBand(p)) {
            queues.computeIfAbsent(placed, k -> new ArrayList<>()).add(p);
        }
        scoreStale = true;
    }

    /** Records a player-broken block: it leaves its queue and the rest clamp up. */
    public void recordBreak(BlockPos pos) {
        removeFromAllQueues(pos.immutable());
        scoreStale = true;
    }

    private void removeFromAllQueues(BlockPos pos) {
        for (List<BlockPos> queue : queues.values()) {
            queue.remove(pos);
        }
    }

    /** Snapshots the chunk's current {@code WORLD_SURFACE} heightmap as the per-column terrain
     *  reference. Done once, the first time the chunk is scanned. */
    public void captureReferences(ChunkAccess chunk) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                refY[idx(x, z)] = (short) chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            }
        }
    }

    /**
     * Rescans the surface-and-up volume and reconciles the queues: a block already queued keeps
     * its placement slot, a block newly found (non-player change, or the initial scan) is
     * appended in scan order, and a block no longer present drops out.
     */
    public void fullScan(ChunkAccess chunk) {
        scoreStale = true; // the queues are about to be rebuilt — the cached score can't survive
        // Current contents per type, in canonical scan order (x, then z, then y ascending).
        Map<Block, List<BlockPos>> scannedNow = new HashMap<>();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int ref = refY[idx(x, z)];
                if (ref == UNCAPTURED) continue;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                for (int y = ref; y <= top; y++) {
                    cursor.set(baseX + x, y, baseZ + z);
                    BlockState st = chunk.getBlockState(cursor);
                    if (st.isAir()) continue;
                    // Count two-tall plants/doors once: skip the UPPER half so a rose bush / peony /
                    // tall grass doesn't tally twice (anchored at its lower half).
                    if (AppealResolver.isAppealDuplicateHalf(st)) continue;
                    scannedNow.computeIfAbsent(st.getBlock(), k -> new ArrayList<>())
                        .add(cursor.immutable());
                }
            }
        }
        // Reconcile against the existing queues.
        Map<Block, List<BlockPos>> reconciled = new HashMap<>();
        for (Map.Entry<Block, List<BlockPos>> e : scannedNow.entrySet()) {
            Block block = e.getKey();
            List<BlockPos> current = e.getValue();
            Set<BlockPos> currentSet = new HashSet<>(current);
            Set<BlockPos> kept = new HashSet<>();
            List<BlockPos> ordered = new ArrayList<>(current.size());
            List<BlockPos> old = queues.get(block);
            if (old != null) {
                for (BlockPos p : old) {
                    if (currentSet.contains(p) && kept.add(p)) {
                        ordered.add(p);
                    }
                }
            }
            for (BlockPos p : current) {
                if (!kept.contains(p)) ordered.add(p);
            }
            reconciled.put(block, ordered);
        }
        this.queues = reconciled;
    }

    /** Recomputes {@link #score()} and {@link #tag()} from the current queue sizes under the
     *  given culture styles and active palettes. Cheap — no block reads — and self-skipping:
     *  when neither the queues nor the styles/palettes changed since the last call, the cached
     *  score is already right and the walk is skipped entirely (the per-second refresh used to
     *  recompute every scanned chunk unconditionally). */
    public void recomputeScore(List<String> styleIds, List<String> paletteIds) {
        int styleHash = styleIds.hashCode() * 31 + paletteIds.hashCode();
        if (!scoreStale && styleHash == lastStyleHash) return;
        double score = 0.0;
        for (Map.Entry<Block, List<BlockPos>> e : queues.entrySet()) {
            float appeal = AppealResolver.appealOf(e.getKey(), styleIds, paletteIds);
            if (appeal == 0f) continue;
            score += AppealResolver.typeContribution(appeal, e.getValue().size());
        }
        this.cachedScore = score;
        this.cachedTag = ChunkBeauty.fromScore(score);
        this.scoreStale = false;
        this.lastStyleHash = styleHash;
    }

    // ─── Persistence ────────────────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        int[] ref = new int[COLUMNS];
        for (int i = 0; i < COLUMNS; i++) ref[i] = refY[i];
        tag.putIntArray("RefY", ref);
        tag.putBoolean("Scanned", scanned);
        ListTag queueList = new ListTag();
        for (Map.Entry<Block, List<BlockPos>> e : queues.entrySet()) {
            List<BlockPos> positions = e.getValue();
            if (positions.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(e.getKey());
            CompoundTag c = new CompoundTag();
            c.putString("Block", id.toString());
            long[] packed = new long[positions.size()];
            for (int i = 0; i < packed.length; i++) packed[i] = positions.get(i).asLong();
            c.putLongArray("Positions", packed);
            queueList.add(c);
        }
        tag.put("Queues", queueList);
        return tag;
    }

    public static ChunkAppealData load(CompoundTag tag) {
        ChunkAppealData d = new ChunkAppealData();
        if (tag.contains("RefY")) {
            int[] ref = tag.getIntArray("RefY");
            for (int i = 0; i < COLUMNS && i < ref.length; i++) {
                d.refY[i] = (short) ref[i];
            }
        }
        d.scanned = tag.getBoolean("Scanned");
        if (tag.contains("Queues")) {
            ListTag queueList = tag.getList("Queues", Tag.TAG_COMPOUND);
            for (int i = 0; i < queueList.size(); i++) {
                CompoundTag c = queueList.getCompound(i);
                ResourceLocation id = ResourceLocation.tryParse(c.getString("Block"));
                // Block ids removed since the save was written are simply dropped.
                if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) continue;
                long[] packed = c.getLongArray("Positions");
                List<BlockPos> list = new ArrayList<>(packed.length);
                for (long p : packed) list.add(BlockPos.of(p));
                d.queues.put(BuiltInRegistries.BLOCK.get(id), list);
            }
        } else {
            // Pre-queue save format — force a reconcile rescan to rebuild the queues.
            d.dirty = true;
        }
        return d;
    }
}
