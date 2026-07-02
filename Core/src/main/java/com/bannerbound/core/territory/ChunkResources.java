package com.bannerbound.core.territory;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.territory.data.ChunkResourceLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Deterministic per-chunk resource typing for the specialized-chunks system. A chunk's type is a
 * <b>pure function of (world seed, chunk coords, biome)</b> — computed on demand, never stored (no save
 * bloat), identical for everyone, regenerates the same. The biome gates <i>which</i> resources are
 * possible (free regional clustering — plains span many chunks); a stable hash decides whether a chunk
 * is special and which resource it carries, weighted so e.g. tin is rare.
 *
 * <p>Everything here is tuning fodder — adjust the per-biome chances/weights by eye via
 * {@code /bannerbound chunktype <radius>}. Design notes: repo-root {@code SPECIALIZED_CHUNKS_PLAN.md}.
 *
 * <p>NOTE: biome categories are matched by registry-path substring for now (cheap + trivially tunable);
 * a biome-tag mapping would be more robust against modded biomes later.
 */
@ApiStatus.Internal
public final class ChunkResources {
    /** Y sampled for the chunk's biome — sea-level-ish so most overworld biomes resolve consistently. */
    private static final int SAMPLE_Y = 64;

    private ChunkResources() {
    }

    /** Outer-ring probe offsets from the chunk centre (±6). The populator only edits the inner ~±3
     *  region, so probing the ring keeps {@link #typeAt} a pure function of the natural terrain — the
     *  worldgen result and the later {@code /chunktype} overlay always agree (no "pig chunk reads FISH"
     *  because our own pond sat on the centre). */
    private static final int[][] RING = {
        {-6, -6}, {6, -6}, {-6, 6}, {6, 6}, {-6, 0}, {6, 0}, {0, -6}, {0, 6}};

    /** The resource type of chunk {@code cp} (deterministic; {@link ChunkResource#NONE} if ordinary).
     *  Sparsity/mix come from the data-driven {@link ChunkResourceDistribution} (edit JSON + /reload). */
    public static ChunkResource typeAt(ServerLevel level, ChunkPos cp) {
        ChunkResourceDistribution dist = ChunkResourceLoader.get();
        long h = hash(level.getSeed(), cp.x, cp.z);
        double rollSpecial = toUnit(h);                       // "is this chunk special?"
        // Cheap reject: chunks rolling above the biggest category chance can't be special — return without
        // touching the world. Keeps per-chunk cost near-zero (the terrain probe only runs for candidates).
        if (rollSpecial >= dist.maxChance()) return ChunkResource.NONE;

        double rollPick = toUnit(mix(h ^ 0x9E3779B97F4A7C15L)); // "which resource?"
        int x = cp.getMinBlockX() + 8;
        int z = cp.getMinBlockZ() + 8;

        // Probe the natural terrain over the outer ring: relief (for the steepness gate) + how much of
        // the chunk is water (a small pond can't flip it; a real lake/river does).
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int waterPts = 0;
        for (int[] o : RING) {
            int gy = groundSurfaceY(level, x + o[0], z + o[1]);
            minY = Math.min(minY, gy);
            maxY = Math.max(maxY, gy);
            if (!level.getBlockState(new BlockPos(x + o[0], gy + 1, z + o[1])).getFluidState().isEmpty()) {
                waterPts++;
            }
        }
        if (maxY - minY > dist.maxRelief()) return ChunkResource.NONE; // too steep — don't make it special
        boolean water = waterPts * 2 >= RING.length;                   // mostly water = a water chunk

        ResourceLocation biome = level.getBiome(new BlockPos(x, SAMPLE_Y, z))
            .unwrapKey().map(k -> k.location()).orElse(null);
        return resolve(dist, biome, water, rollSpecial, rollPick);
    }

    /** Topmost solid ground block at (x,z), walking down past tree leaves/logs, plants and water so the
     *  height reading reflects real ground (not canopy) and is stable to the populator's same-height edits. */
    private static int groundSurfaceY(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(
            x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
        int floor = level.getMinBuildHeight();
        while (m.getY() > floor) {
            m.move(Direction.DOWN);
            BlockState s = level.getBlockState(m);
            if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) continue;
            if (s.getFluidState().isEmpty() && s.blocksMotion()) return m.getY();
        }
        return floor;
    }

    private static ChunkResource resolve(ChunkResourceDistribution dist, ResourceLocation biome,
                                         boolean waterSurface, double rollSpecial, double rollPick) {
        if (biome == null) return ChunkResource.NONE;
        // Map biome → category (by registry-path substring). Water-surface chunks (lakes inside a land
        // biome) and aquatic biomes are the fish category regardless of name. Everything not plains/
        // mountainous/forest falls to "other" (desert/badlands/snowy — metals only, no livestock).
        String p = biome.getPath();
        String key = waterSurface || isAquatic(p) ? "aquatic"
            : isMountainous(p) ? "mountainous"
            : isPlains(p) ? "plains"
            : isForest(p) ? "forest"
            : "other";
        ChunkResourceDistribution.Category cat = dist.category(key);
        if (cat == null || rollSpecial >= cat.chance()) return ChunkResource.NONE;
        return cat.pick(rollPick);
    }

    private static boolean isAquatic(String p) {
        return p.contains("river") || p.contains("ocean") || p.contains("beach") || p.contains("swamp");
    }

    private static boolean isPlains(String p) {
        return p.contains("plains") || p.contains("savanna") || p.contains("meadow");
    }

    private static boolean isMountainous(String p) {
        return p.contains("hill") || p.contains("mountain") || p.contains("windswept")
            || p.contains("peak") || p.contains("slope") || p.contains("stony");
    }

    private static boolean isForest(String p) {
        return p.contains("forest") || p.contains("taiga") || p.contains("jungle") || p.contains("grove");
    }

    // ─── Deterministic hashing (splitmix64-style finalizer) ──────────────────────────────────────

    private static long hash(long seed, int x, int z) {
        long h = seed ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xC2B2AE3D27D4EB4FL);
        return mix(h);
    }

    private static long mix(long h) {
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return h;
    }

    /** Long → uniform double in [0, 1). Unsigned top-53-bit slice, scaled by 2^-53. */
    private static double toUnit(long v) {
        return (v >>> 11) * 0x1.0p-53;
    }
}
