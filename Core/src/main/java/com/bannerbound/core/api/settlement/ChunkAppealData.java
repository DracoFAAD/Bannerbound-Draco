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
 * Per-chunk appeal state: a frozen per-column terrain reference plus, for each block type, a
 * placement-order queue of the positions present "surface and up".
 *
 * <p>Scan model ("full structures"): the first time a chunk is scanned, captureReferences
 * snapshots the WORLD_SURFACE heightmap into refY (indexed (localX &lt;&lt; 4) | localZ, with
 * Short.MIN_VALUE as the "not captured yet" sentinel) - the natural terrain floor. Every block at
 * or above its column's refY counts; terrain, caves, and mines below it do not.
 *
 * <p>Queues: per block type, positions in placement order (queue slot = list index + 1). Slot N
 * contributes appeal x 0.9^(N-1) (diminishing returns via AppealResolver.typeContribution); when
 * a block is removed the rest clamp up. Player place/break update queues incrementally
 * (recordPlace/recordBreak); fullScan reconciles - blocks still present keep their known order,
 * newly found ones (non-player changes, initial scan) append in canonical scan order. The chunk
 * score depends only on each type's count, so ordering never changes the score.
 *
 * <p>recomputeScore is cheap (no block reads) and self-skipping: scoreStale plus a hash of the
 * (styles, palettes) inputs let the per-second refresh skip chunks where nothing changed - it
 * used to recompute every scanned chunk unconditionally. Persistence: save/load round-trip refY,
 * the scanned flag, and the queues (block ids removed since the save was written are dropped);
 * a save predating the queue format is marked dirty so a reconcile rescan rebuilds it.
 */
@ApiStatus.Internal
public class ChunkAppealData {
    public static final int COLUMNS = 256;
    private static final short UNCAPTURED = Short.MIN_VALUE;

    private final short[] refY = new short[COLUMNS];
    private Map<Block, List<BlockPos>> queues = new HashMap<>();
    private boolean scanned;
    private boolean dirty;
    private double cachedScore;
    private ChunkBeauty cachedTag = ChunkBeauty.BLAND;
    private transient boolean scoreStale = true;
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

    private boolean inBand(BlockPos pos) {
        int ref = refY[idx(pos.getX() & 15, pos.getZ() & 15)];
        return ref != UNCAPTURED && pos.getY() >= ref;
    }

    public void recordPlace(BlockPos pos, Block placed) {
        BlockPos p = pos.immutable();
        removeFromAllQueues(p);
        if (inBand(p)) {
            queues.computeIfAbsent(placed, k -> new ArrayList<>()).add(p);
        }
        scoreStale = true;
    }

    public void recordBreak(BlockPos pos) {
        removeFromAllQueues(pos.immutable());
        scoreStale = true;
    }

    private void removeFromAllQueues(BlockPos pos) {
        for (List<BlockPos> queue : queues.values()) {
            queue.remove(pos);
        }
    }

    public void captureReferences(ChunkAccess chunk) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                refY[idx(x, z)] = (short) chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            }
        }
    }

    public void fullScan(ChunkAccess chunk) {
        scoreStale = true;
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
                    // Skip the UPPER half of two-tall blocks (doors, rose bush) so they count once.
                    if (AppealResolver.isAppealDuplicateHalf(st)) continue;
                    scannedNow.computeIfAbsent(st.getBlock(), k -> new ArrayList<>())
                        .add(cursor.immutable());
                }
            }
        }
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
                if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) continue;
                long[] packed = c.getLongArray("Positions");
                List<BlockPos> list = new ArrayList<>(packed.length);
                for (long p : packed) list.add(BlockPos.of(p));
                d.queues.put(BuiltInRegistries.BLOCK.get(id), list);
            }
        } else {
            // Pre-queue save format: mark dirty so a reconcile rescan rebuilds the queues.
            d.dirty = true;
        }
        return d;
    }
}
