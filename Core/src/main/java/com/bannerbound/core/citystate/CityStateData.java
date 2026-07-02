package com.bannerbound.core.citystate;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Top-level {@link SavedData} for all AI city-states — the parallel-to-{@code BarbarianData}
 * lightweight store (city-states are discovered vanilla villages, not Settlements). Attached to the
 * <b>overworld</b>; call {@link #get(ServerLevel)} server-side. Mutators call {@link #setDirty()}.
 *
 * <p>{@link #chunkToCityState} is the dedupe / no-overlap reverse index, rebuilt on load.
 */
public class CityStateData extends SavedData {
    private static final String DATA_NAME = "bannerbound_citystates";

    private final Map<UUID, CityState> cityStates = new HashMap<>();
    private final Map<Long, UUID> chunkToCityState = new HashMap<>();
    /** Chunks of razed villages — never re-detected as a new city-state (permanent ruin memory). */
    private final Set<Long> razedChunks = new HashSet<>();

    public CityStateData() {
    }

    public static CityStateData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<CityStateData> factory() {
        return new Factory<>(CityStateData::new, CityStateData::load);
    }

    public Collection<CityState> all() {
        return Collections.unmodifiableCollection(cityStates.values());
    }

    public CityState getById(UUID id) {
        return id == null ? null : cityStates.get(id);
    }

    public CityState getByChunk(long packedChunkPos) {
        UUID id = chunkToCityState.get(packedChunkPos);
        return id == null ? null : cityStates.get(id);
    }

    /** The city-state whose banner sits at {@code pos}, or null. */
    public CityState bannerAt(BlockPos pos) {
        for (CityState cs : cityStates.values()) {
            if (!cs.bannerRazed && cs.bannerPos != null && cs.bannerPos.equals(pos)) return cs;
        }
        return null;
    }

    public void add(CityState cs) {
        cityStates.put(cs.id, cs);
        index(cs);
        setDirty();
    }

    public void remove(CityState cs) {
        cityStates.remove(cs.id);
        chunkToCityState.remove(new ChunkPos(cs.center).toLong());
        for (long c : cs.claimedChunks) chunkToCityState.remove(c, cs.id);
        setDirty();
    }

    /** Re-runs the reverse index after a city-state's claimed chunks grew (claims only ever grow). */
    public void reindex(CityState cs) {
        index(cs);
        setDirty();
    }

    /** Indexes a city-state's centre + every claimed chunk into the reverse lookup. */
    private void index(CityState cs) {
        chunkToCityState.put(new ChunkPos(cs.center).toLong(), cs.id);
        for (long c : cs.claimedChunks) chunkToCityState.put(c, cs.id);
    }

    public void clear() {
        cityStates.clear();
        chunkToCityState.clear();
        razedChunks.clear();
        setDirty();
    }

    /** True if {@code packedChunkPos} belongs to a razed village — blocks re-detection forever. */
    public boolean isRazedChunk(long packedChunkPos) {
        return razedChunks.contains(packedChunkPos);
    }

    /** Razes a city-state: marks its chunks as permanent ruin memory (so it never re-detects) and
     *  removes the record. Returns the razed area; the caller queues the actual {@code RuinManager}
     *  decay job for it. */
    public Set<Long> razeVillage(CityState cs) {
        Set<Long> area = new HashSet<>(cs.claimedChunks);
        area.add(new ChunkPos(cs.center).toLong());
        razedChunks.addAll(area);
        remove(cs);
        setDirty();
        return area;
    }

    /** True if a city-state centre already lies within {@code chebyshevChunks} of {@code center} —
     *  used to keep one record per village (a village's bell is the single anchor). */
    public boolean hasCityStateWithin(ChunkPos center, int chebyshevChunks) {
        for (CityState cs : cityStates.values()) {
            ChunkPos cp = new ChunkPos(cs.center);
            if (Math.max(Math.abs(cp.x - center.x), Math.abs(cp.z - center.z)) <= chebyshevChunks) {
                return true;
            }
        }
        return false;
    }

    /** True if any city-state's CLAIMED chunk lies within {@code chebyshevChunks} of {@code center}.
     *  {@code 0} = the chunk is inside a claim; {@code >0} adds a nearby buffer. Used to keep player
     *  settlements (and outposts) off city-state territory. */
    public boolean hasClaimWithin(ChunkPos center, int chebyshevChunks) {
        for (CityState cs : cityStates.values()) {
            for (long packed : cs.claimedChunks) {
                ChunkPos cp = new ChunkPos(packed);
                if (Math.max(Math.abs(cp.x - center.x), Math.abs(cp.z - center.z)) <= chebyshevChunks) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (CityState cs : cityStates.values()) list.add(cs.save());
        tag.put("CityStates", list);

        long[] razed = new long[razedChunks.size()];
        int ri = 0;
        for (long c : razedChunks) razed[ri++] = c;
        tag.putLongArray("RazedChunks", razed);
        return tag;
    }

    public static CityStateData load(CompoundTag tag, HolderLookup.Provider provider) {
        CityStateData data = new CityStateData();
        ListTag list = tag.getList("CityStates", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CityState cs = CityState.load(list.getCompound(i));
            if (cs == null) continue;
            data.cityStates.put(cs.id, cs);
            data.index(cs);
        }
        for (long c : tag.getLongArray("RazedChunks")) data.razedChunks.add(c);
        return data;
    }
}
