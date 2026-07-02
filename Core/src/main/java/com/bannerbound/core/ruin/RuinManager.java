package com.bannerbound.core.ruin;

import java.util.Collection;
import java.util.Iterator;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.AABB;

/**
 * Generic, palette-agnostic <b>ruination</b>: turns the buildings of a razed/disbanded area into
 * ruins by slowly peeling each column down toward its NATURAL worldgen terrain height. Anything a
 * player or villager built sits above the generator's base height, so it crumbles; the natural
 * ground beneath is left intact — no per-block whitelist, so it works for any build (cobble keeps,
 * dirt huts, log cabins, mixed). Trees are spared by skipping columns under a leaf canopy.
 *
 * <p>Reusable across systems: AI city-states (on raze) and player settlements (on disband/raze) just
 * call {@link #queue}. Lazy — a job only crumbles while its chunks are loaded. Driven from
 * {@code ResearchEvents.onServerTick}.
 */
@ApiStatus.Internal
public final class RuinManager {
    private static final int INTERVAL_TICKS = 40;     // ruin pass cadence
    private static final long DECAY_PERIOD = 60L;     // ~3s between decay bites per job
    private static final int COLUMNS_PER_CHUNK = 8;   // columns sampled per loaded chunk per bite
    private static final int CHUNKS_PER_PASS = 24;    // per-bite chunk budget (getBaseHeight is worldgen
                                                      // noise — a fully-loaded 100+-chunk ruin must not
                                                      // sample every chunk every bite); rotates per pass
    private static final int IDLE_DONE = 5;           // empty scans in a row → job complete
    private static final int STUB_MAX = 3;            // a column keeps 0..3 random blocks above grade (rubble)
    private static final int CANOPY_SCAN = 4;         // leaves within this many blocks above = a tree, skip

    private RuinManager() {
    }

    /** Queues an area (packed chunk longs) to crumble to ruins. */
    public static void queue(ServerLevel level, Collection<Long> chunks) {
        RuinData.get(level).queue(chunks);
    }

    public static void tickAll(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level.getGameTime() % INTERVAL_TICKS != 0) return;
        RuinData data = RuinData.get(level);
        if (data.jobs().isEmpty()) return;
        long now = level.getGameTime();
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        boolean dirty = false;
        Iterator<RuinData.RuinJob> it = data.jobs().iterator();
        while (it.hasNext()) {
            RuinData.RuinJob job = it.next();
            if (!anyChunkLoaded(level, job)) continue; // only crumble what's loaded
            clearVillagers(level, job);                // a dead place keeps no villagers
            if (now - job.lastTick < DECAY_PERIOD) continue;
            job.lastTick = now;
            int removed = decayPass(level, gen, rs, job);
            if (removed == 0) {
                if (++job.idle >= IDLE_DONE) it.remove(); // crumbled to grade — done
            } else {
                job.idle = 0;
            }
            dirty = true;
        }
        if (dirty) data.setDirty();
    }

    private static boolean anyChunkLoaded(ServerLevel level, RuinData.RuinJob job) {
        for (long c : job.chunks) {
            ChunkPos cp = new ChunkPos(c);
            if (level.hasChunk(cp.x, cp.z)) return true;
        }
        return false;
    }

    private static void clearVillagers(ServerLevel level, RuinData.RuinJob job) {
        com.bannerbound.core.api.settlement.SettlementData claims =
            com.bannerbound.core.api.settlement.SettlementData.get(level);
        for (long c : job.chunks) {
            ChunkPos cp = new ChunkPos(c);
            if (!level.hasChunk(cp.x, cp.z)) continue;
            if (claims.getByChunk(c) != null) continue; // re-claimed → the new owner's problem
            AABB box = new AABB(cp.getMinBlockX(), level.getMinBuildHeight(), cp.getMinBlockZ(),
                cp.getMaxBlockX() + 1, level.getMaxBuildHeight(), cp.getMaxBlockZ() + 1);
            for (AbstractVillager v : level.getEntitiesOfClass(AbstractVillager.class, box)) v.discard();
        }
    }

    /** Peels the topmost built block off a sample of columns in each loaded chunk. A built block is
     *  anything above the worldgen base height; columns under a leaf canopy (trees) are skipped. */
    private static int decayPass(ServerLevel level, ChunkGenerator gen, RandomState rs, RuinData.RuinJob job) {
        int removed = 0;
        com.bannerbound.core.api.settlement.SettlementData claims =
            com.bannerbound.core.api.settlement.SettlementData.get(level);
        // Rotate which slice of the job's chunks this bite samples, so huge ruins stay within
        // budget but every chunk still gets its turn across consecutive bites.
        Long[] chunks = job.chunks.toArray(new Long[0]);
        int offset = chunks.length <= CHUNKS_PER_PASS
            ? 0 : (int) Math.floorMod(job.lastTick / DECAY_PERIOD, chunks.length);
        int budget = Math.min(chunks.length, CHUNKS_PER_PASS);
        for (int n = 0; n < budget; n++) {
            long c = chunks[(offset + n) % chunks.length];
            ChunkPos cp = new ChunkPos(c);
            if (!level.hasChunk(cp.x, cp.z)) continue;
            // A chunk RE-claimed by a living settlement (someone resettled the ruins) is theirs
            // now — never crumble a new owner's builds.
            if (claims.getByChunk(c) != null) continue;
            for (int i = 0; i < COLUMNS_PER_CHUNK; i++) {
                int x = cp.getMinBlockX() + level.getRandom().nextInt(16);
                int z = cp.getMinBlockZ() + level.getRandom().nextInt(16);
                if (peelColumn(level, gen, rs, x, z)) removed++;
            }
        }
        return removed;
    }

    private static boolean peelColumn(ServerLevel level, ChunkGenerator gen, RandomState rs, int x, int z) {
        // Natural terrain top from worldgen (ignores anything built/placed afterward).
        int naturalTop = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, rs) - 1;
        int keepUpTo = naturalTop + jitter(x, z); // leave a little uneven rubble above grade
        int currentTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (currentTop <= keepUpTo) return false; // already at/below natural grade
        BlockPos pos = new BlockPos(x, currentTop, z);
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;   // don't drain water/lava
        if (state.getDestroySpeed(level, pos) < 0) return false;               // unbreakable (bedrock)
        if (underCanopy(level, x, z, currentTop)) return false;                // it's a tree — spare it
        level.destroyBlock(pos, false); // crumble: break particles + sound, no drops
        return true;
    }

    /** True if NATURAL tree leaves sit within {@link #CANOPY_SCAN} blocks above the column top (or its
     *  neighbours) — i.e. this column is part of / beneath a real tree, so we leave it standing.
     *  Only NON-persistent leaves count: natural worldgen leaves decay-check (persistent=false), while
     *  player-placed leaves are persistent — so leaf/log player builds (treehouses, hedges) still crumble. */
    private static boolean underCanopy(ServerLevel level, int x, int z, int top) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= CANOPY_SCAN; dy++) {
                    m.set(x + dx, top + dy, z + dz);
                    BlockState s = level.getBlockState(m);
                    if (s.is(BlockTags.LEAVES)
                            && !(s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT))) {
                        return true; // natural canopy → spare; persistent (player) leaves don't count
                    }
                }
            }
        }
        return false;
    }

    /** Deterministic 0..STUB_MAX per column, so ruins keep a stable, uneven rubble line. */
    private static int jitter(int x, int z) {
        long h = x * 341873128712L + z * 132897987541L;
        return (int) Math.floorMod(h, STUB_MAX + 1);
    }
}
