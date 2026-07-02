package com.bannerbound.core.ruin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persistent list of in-progress <b>ruination jobs</b> — areas (chunk sets) whose structures are
 * slowly crumbling to ruins (see {@link RuinManager}). Generic and palette-agnostic: used by both
 * razed AI city-states and disbanded/razed player settlements. Overworld-attached.
 */
public class RuinData extends SavedData {
    private static final String DATA_NAME = "bannerbound_ruins";

    private final List<RuinJob> jobs = new ArrayList<>();

    /** One crumbling area: its chunks, the last decay tick, and a "no blocks left" idle counter. */
    public static final class RuinJob {
        public final Set<Long> chunks;
        public long lastTick;
        public int idle;

        public RuinJob(Set<Long> chunks) {
            this.chunks = chunks;
        }
    }

    public static RuinData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<RuinData> factory() {
        return new Factory<>(RuinData::new, RuinData::load);
    }

    public List<RuinJob> jobs() {
        return jobs;
    }

    public void queue(Collection<Long> chunks) {
        if (chunks.isEmpty()) return;
        jobs.add(new RuinJob(new HashSet<>(chunks)));
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (RuinJob j : jobs) {
            CompoundTag jt = new CompoundTag();
            long[] chs = new long[j.chunks.size()];
            int i = 0;
            for (long c : j.chunks) chs[i++] = c;
            jt.putLongArray("Chunks", chs);
            jt.putLong("LastTick", j.lastTick);
            jt.putInt("Idle", j.idle);
            list.add(jt);
        }
        tag.put("Jobs", list);
        return tag;
    }

    public static RuinData load(CompoundTag tag, HolderLookup.Provider provider) {
        RuinData data = new RuinData();
        ListTag list = tag.getList("Jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag jt = list.getCompound(i);
            Set<Long> chunks = new HashSet<>();
            for (long c : jt.getLongArray("Chunks")) chunks.add(c);
            RuinJob j = new RuinJob(chunks);
            j.lastTick = jt.getLong("LastTick");
            j.idle = jt.getInt("Idle");
            data.jobs.add(j);
        }
        return data;
    }
}
