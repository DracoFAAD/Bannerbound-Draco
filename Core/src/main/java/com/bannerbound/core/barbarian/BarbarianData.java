package com.bannerbound.core.barbarian;

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
 * Top-level {@link SavedData} for all barbarian camps — the parallel-to-{@code SettlementData}
 * lightweight store (camps are not Settlements; see BARBARIANS_PLAN §1). Attached to the
 * <b>overworld</b> data storage; call {@link #get(ServerLevel)} server-side. Mutators call
 * {@link #setDirty()}.
 *
 * <p>{@link #razedChunks} keeps cleared camp sites permanently camp-free so a defeated camp never
 * re-seeds. {@link #chunkToCamp} is the realize / no-overlap reverse index, rebuilt on load.
 */
public class BarbarianData extends SavedData {
    private static final String DATA_NAME = "bannerbound_barbarians";

    private final Map<UUID, BarbarianCamp> camps = new HashMap<>();
    private final Map<Long, UUID> chunkToCamp = new HashMap<>();
    private final Set<Long> razedChunks = new HashSet<>();
    private long lastSeedScanTick;

    public BarbarianData() {
    }

    public static BarbarianData get(ServerLevel level) {
        // Overworld only, like SettlementData — camp state must not fork per-dimension.
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<BarbarianData> factory() {
        return new Factory<>(BarbarianData::new, BarbarianData::load);
    }

    public Collection<BarbarianCamp> all() {
        return Collections.unmodifiableCollection(camps.values());
    }

    public BarbarianCamp getById(UUID id) {
        return camps.get(id);
    }

    public BarbarianCamp getByChunk(long packedChunkPos) {
        UUID id = chunkToCamp.get(packedChunkPos);
        return id == null ? null : camps.get(id);
    }

    /** The camp whose banner sits at {@code pos}, or null. Checked first on a banner break so a
     *  camp banner isn't mistaken for a settlement banner loss. */
    public BarbarianCamp bannerAt(BlockPos pos) {
        for (BarbarianCamp c : camps.values()) {
            if (!c.razed && c.bannerPos != null && c.bannerPos.equals(pos)) return c;
        }
        return null;
    }

    public long lastSeedScanTick() {
        return lastSeedScanTick;
    }

    public void setLastSeedScanTick(long tick) {
        this.lastSeedScanTick = tick;
        setDirty();
    }

    public void addCamp(BarbarianCamp camp) {
        camps.put(camp.id, camp);
        chunkToCamp.put(new ChunkPos(camp.center).toLong(), camp.id);
        setDirty();
    }

    /** Permanently clears a camp: removes the record and marks its chunk razed (no re-seeding). */
    public void removeCamp(BarbarianCamp camp) {
        camps.remove(camp.id);
        long centerChunk = new ChunkPos(camp.center).toLong();
        chunkToCamp.remove(centerChunk);
        razedChunks.add(centerChunk);
        camp.razed = true;
        setDirty();
    }

    public boolean isRazedChunk(long packedChunkPos) {
        return razedChunks.contains(packedChunkPos);
    }

    /** Wipes all camp records and razed memory (op test reset; does NOT touch placed camp blocks). */
    public void clear() {
        camps.clear();
        chunkToCamp.clear();
        razedChunks.clear();
        setDirty();
    }

    /** True if a live camp center OR a razed site lies within {@code chebyshevChunks} of
     *  {@code center} — used by the seeder to keep camps spread out and razed areas empty. */
    public boolean hasCampOrRazedWithin(ChunkPos center, int chebyshevChunks) {
        // Live camps are bounded by the global cap, so iterate them directly.
        for (BarbarianCamp c : camps.values()) {
            ChunkPos cp = new ChunkPos(c.center);
            if (chebyshev(cp, center) <= chebyshevChunks) return true;
        }
        // razedChunks grows over the life of a server. Iterate it only while it's smaller than the
        // (2N+1)² neighborhood; past that, probe the fixed neighborhood instead so the cost stays
        // bounded regardless of how many camps have ever been razed.
        int span = 2 * chebyshevChunks + 1;
        if (razedChunks.size() <= span * span) {
            for (long packed : razedChunks) {
                if (chebyshev(new ChunkPos(packed), center) <= chebyshevChunks) return true;
            }
        } else {
            for (int dx = -chebyshevChunks; dx <= chebyshevChunks; dx++) {
                for (int dz = -chebyshevChunks; dz <= chebyshevChunks; dz++) {
                    if (razedChunks.contains(ChunkPos.asLong(center.x + dx, center.z + dz))) return true;
                }
            }
        }
        return false;
    }

    private static int chebyshev(ChunkPos a, ChunkPos b) {
        return Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (BarbarianCamp camp : camps.values()) {
            list.add(camp.save());
        }
        tag.put("Camps", list);
        long[] razed = new long[razedChunks.size()];
        int i = 0;
        for (long l : razedChunks) razed[i++] = l;
        tag.putLongArray("RazedChunks", razed);
        tag.putLong("LastSeedScanTick", lastSeedScanTick);
        return tag;
    }

    public static BarbarianData load(CompoundTag tag, HolderLookup.Provider provider) {
        BarbarianData data = new BarbarianData();
        ListTag list = tag.getList("Camps", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            BarbarianCamp camp = BarbarianCamp.load(list.getCompound(i));
            if (camp == null) continue;
            data.camps.put(camp.id, camp);
            data.chunkToCamp.put(new ChunkPos(camp.center).toLong(), camp.id);
        }
        for (long l : tag.getLongArray("RazedChunks")) {
            data.razedChunks.add(l);
        }
        data.lastSeedScanTick = tag.getLong("LastSeedScanTick");
        return data;
    }
}
