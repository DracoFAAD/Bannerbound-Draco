package com.bannerbound.core.api.settlement;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * {@link SavedData} holding per-chunk appeal state ({@link ChunkAppealData}) for every tracked
 * chunk — a settlement's claimed chunks plus their 8-neighbour ring. Kept separate from
 * {@link SettlementData} so the per-chunk reference arrays and count maps don't bloat the
 * frequently-saved settlement blob.
 *
 * <p>The <i>set</i> of tracked chunks is not persisted — it is rebuilt from {@link SettlementData}
 * by {@link ChunkBeautyManager#recomputeTrackedSet}. Only the scan results are saved.
 */
@ApiStatus.Internal
public class ChunkBeautyData extends SavedData {
    private static final String DATA_NAME = "bannerbound_chunk_beauty";

    private final Map<Long, ChunkAppealData> chunks = new HashMap<>();

    public ChunkBeautyData() {
    }

    public static ChunkBeautyData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<ChunkBeautyData> factory() {
        return new Factory<>(ChunkBeautyData::new, ChunkBeautyData::load);
    }

    public Map<Long, ChunkAppealData> chunks() {
        return chunks;
    }

    public ChunkAppealData get(long packedChunk) {
        return chunks.get(packedChunk);
    }

    /** Begins tracking {@code packedChunk} if not already tracked. The new entry is unscanned —
     *  {@link ChunkBeautyManager} performs its first scan once the chunk is loaded. */
    public void track(long packedChunk) {
        if (chunks.putIfAbsent(packedChunk, new ChunkAppealData()) == null) {
            setDirty();
        }
    }

    /** Stops tracking {@code packedChunk} (settlement unclaimed it / disbanded). */
    public void untrack(long packedChunk) {
        if (chunks.remove(packedChunk) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, ChunkAppealData> e : chunks.entrySet()) {
            CompoundTag c = e.getValue().save();
            c.putLong("Pos", e.getKey());
            list.add(c);
        }
        tag.put("Chunks", list);
        return tag;
    }

    public static ChunkBeautyData load(CompoundTag tag, HolderLookup.Provider provider) {
        ChunkBeautyData data = new ChunkBeautyData();
        ListTag list = tag.getList("Chunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            data.chunks.put(c.getLong("Pos"), ChunkAppealData.load(c));
        }
        return data;
    }
}
