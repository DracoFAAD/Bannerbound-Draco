package com.bannerbound.core.territory;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Gives each specialized chunk its <i>natural content</i> the moment it generates: livestock chunks
 * spawn a small herd, metal chunks get a surface ore boulder, fish chunks get a school + seagrass.
 * The chunk simply <i>is</i> its {@link ChunkResource} — no marker block, no hiding (that comes with
 * the later work-the-chunk loop). Type is read from the deterministic {@link ChunkResources}.
 *
 * <p>We hook {@link ChunkEvent.Load} filtered to {@code isNewChunk()} (server-only, fires once when a
 * chunk is first generated). Per the event contract the chunk may not be {@code FULL} yet, so all
 * world edits are deferred one server tick via {@link net.minecraft.server.MinecraftServer#execute};
 * by then it's a normal loaded chunk and plain {@link ServerLevel} APIs are safe.
 *
 * <p>Marble/tin have no dedicated block yet, so their boulders use calcite / tuff+andesite stand-ins.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class ResourceChunkPopulator {
    private static final int HERD_HORSES = 3;

    private ResourceChunkPopulator() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return; // server-only; only freshly generated chunks
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (sl.dimension() != Level.OVERWORLD) return; // resources are overworld-themed
        ChunkPos cp = event.getChunk().getPos();
        // The chunk may not be FULL yet — defer all level interactions one tick to avoid deadlocks.
        // Guard the whole thing: a thrown task would otherwise propagate into the server task runner.
        sl.getServer().execute(() -> {
            try {
                if (sl.hasChunk(cp.x, cp.z)) populate(sl, cp);
            } catch (Exception e) {
                BannerboundCore.LOGGER.error("Resource-chunk populate failed at {}", cp, e);
            }
        });
    }

    private static void populate(ServerLevel sl, ChunkPos cp) {
        ChunkResource type = ChunkResources.typeAt(sl, cp);
        if (type == ChunkResource.NONE) return;
        // Deterministic per-chunk layout (stable on regen), independent of world tick RNG.
        RandomSource rand = RandomSource.create(sl.getSeed() ^ cp.toLong() ^ 0x5DEECE66DL);
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        // Livestock chunks get a little themed groundwork (paths, water, mud) before the herd, so the
        // animals stand on it and the fluid checks in spawnHerd see any new water.
        switch (type) {
            case HORSES -> { decorate(sl, rand, cx, cz); spawnHerd(sl, rand, cx, cz, EntityType.HORSE, HERD_HORSES); }
            // Boulder pattern comes from the pure per-position layout (shared with the miner's chip
            // targets and the vein-regen ticker) — see BoulderLayout for why it isn't this rand.
            case COPPER, IRON, MARBLE, TIN, COAL -> BoulderLayout.dress(sl, cp);
            case FISH -> spawnFishingGround(sl, rand, cx, cz);
            // Crop chunks: scatter dry-farmland patches with random-maturity crops (the field's
            // visible identity + the forager's wild pickings + the outpost's starter field).
            case WHEAT, CARROT, BEETROOT, POTATO -> CropChunks.dressWildField(sl, cp);
            // Generic material chunks: stone boulder, clay/sand pit. Diggers/quarryworkers work
            // these like miners work ore boulders: source faces swap to worked-out faces forever.
            case STONE, CLAY, SAND, LIMESTONE, ANDESITE, DIORITE, GRANITE -> MaterialDepositLayout.dress(sl, cp);
            // cow/pig/sheep/chicken are no longer chunk-typed (they spawn naturally); NONE = nothing.
            default -> { }
        }
    }

    // ─── Vanilla spawn suppression: managed farm animals come ONLY from their chunks ──────────────
    // Without this, natural (and chunk-gen) spawns drop random cows/pigs/etc into the wrong chunks,
    // muddying which animals "belong". Our own herds bypass this — the populator calls Mob#finalizeSpawn
    // DIRECTLY, which (unlike vanilla spawn paths) does not fire FinalizeSpawnEvent. Breeding, spawn
    // eggs and buckets are untouched, so a player can still raise animals obtained from a chunk.

    // Only horses are still chunk-exclusive: their natural/chunk-gen spawns are cancelled so they exist
    // only where the populator places them (horse chunks). Cow/pig/sheep/chicken now spawn naturally.
    private static final Set<EntityType<?>> MANAGED_ANIMALS = Set.of(EntityType.HORSE);

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        MobSpawnType st = event.getSpawnType();
        if (st != MobSpawnType.NATURAL && st != MobSpawnType.CHUNK_GENERATION) return;
        if (MANAGED_ANIMALS.contains(event.getEntity().getType())) {
            event.setSpawnCancelled(true);
        }
    }

    // ─── Livestock ──────────────────────────────────────────────────────────────────────────────

    private static final String ANIMAL_HOME_KEY = "BannerboundHerdHome";
    private static final double ANIMAL_LEASH = 12.0; // blocks from chunk centre before a free animal heads back

    private static void spawnHerd(ServerLevel sl, RandomSource rand, int cx, int cz,
                                  EntityType<? extends Mob> type, int count) {
        for (int i = 0; i < count; i++) {
            int ax = cx + rand.nextInt(9) - 4;
            int az = cz + rand.nextInt(9) - 4;
            int groundY = groundSurfaceY(sl, ax, az); // top solid ground, ignoring tree canopy/trunk
            int ay = groundY + 1;
            BlockPos feet = new BlockPos(ax, ay, az);
            // Two passable blocks above the ground (not inside a tree trunk) and dry footing.
            if (sl.getBlockState(feet).blocksMotion() || sl.getBlockState(feet.above()).blocksMotion()) continue;
            if (!sl.getBlockState(feet).getFluidState().isEmpty()) continue;
            Mob mob = type.create(sl);
            if (mob == null) continue;
            mob.moveTo(ax + 0.5, ay, az + 0.5, rand.nextFloat() * 360f, 0f);
            mob.finalizeSpawn(sl, sl.getCurrentDifficultyAt(feet), MobSpawnType.NATURAL, null);
            mob.setPersistenceRequired(); // belongs to the chunk — keep it marked
            mob.getPersistentData().putLong(ANIMAL_HOME_KEY, BlockPos.asLong(cx, ay, cz));
            sl.addFreshEntity(mob);
        }
    }

    /** A free (not led/spooked) herd animal that wanders out of its chunk heads back, so the herd keeps
     *  roaming its own chunk and visibly marks it. Leashing it = the player has claimed it (released for
     *  good); breeding/being a baby/spooked/ridden are left alone. */
    private static void tetherAnimal(Animal a) {
        CompoundTag d = a.getPersistentData();
        if (!d.contains(ANIMAL_HOME_KEY)) return;
        if (a.isLeashed()) { d.remove(ANIMAL_HOME_KEY); return; } // taken away → release permanently
        if (a.tickCount % 40 != 0) return;
        if (a.isInLove() || a.isBaby() || a.isVehicle() || a.isPassenger()) return;
        if (a.hurtTime > 0 || a.getLastHurtByMob() != null) return; // spooked → let it flee
        BlockPos home = BlockPos.of(d.getLong(ANIMAL_HOME_KEY));
        double dx = home.getX() + 0.5 - a.getX();
        double dz = home.getZ() + 0.5 - a.getZ();
        if (dx * dx + dz * dz > ANIMAL_LEASH * ANIMAL_LEASH) {
            a.getNavigation().moveTo(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5, 1.0);
        }
    }

    /** Topmost solid ground block at (x,z), walking down past tree leaves/logs, plants and water so we
     *  land on actual ground — {@code WORLD_SURFACE} alone would put things on the forest canopy. */
    private static int groundSurfaceY(ServerLevel sl, int x, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(
            x, sl.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
        int floor = sl.getMinBuildHeight();
        while (m.getY() > floor) {
            m.move(Direction.DOWN);
            BlockState s = sl.getBlockState(m);
            if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) continue;
            if (s.getFluidState().isEmpty() && s.blocksMotion()) return m.getY();
        }
        return floor;
    }

    // ─── Metals: a small surface ore boulder — placement now lives in BoulderLayout ──────────────

    // ─── Fish: a school + a patch of seagrass on the floor ───────────────────────────────────────

    private static void spawnFishingGround(ServerLevel sl, RandomSource rand, int cx, int cz) {
        int fishCount = 3 + rand.nextInt(3);
        for (int i = 0; i < fishCount; i++) {
            int fx = cx + rand.nextInt(9) - 4;
            int fz = cz + rand.nextInt(9) - 4;
            int floor = sl.getHeight(Heightmap.Types.OCEAN_FLOOR, fx, fz); // first water above ground
            int top = sl.getHeight(Heightmap.Types.WORLD_SURFACE, fx, fz); // first air above water
            int depth = top - floor;
            if (depth < 1) continue; // too shallow / dry
            int fy = floor + rand.nextInt(depth);
            BlockPos p = new BlockPos(fx, fy, fz);
            if (sl.getBlockState(p).getFluidState().isEmpty()) continue; // must be in water
            Mob fish = (rand.nextInt(4) == 0 ? EntityType.SALMON : EntityType.COD).create(sl);
            if (fish == null) continue;
            fish.moveTo(fx + 0.5, fy + 0.5, fz + 0.5, rand.nextFloat() * 360f, 0f);
            fish.finalizeSpawn(sl, sl.getCurrentDifficultyAt(p), MobSpawnType.NATURAL, null);
            fish.setPersistenceRequired(); // belongs to the chunk — don't despawn
            fish.getPersistentData().putLong(FISH_HOME_KEY, BlockPos.asLong(cx, fy, cz));
            sl.addFreshEntity(fish);
        }
        for (int i = 0; i < 4; i++) {
            int gx = cx + rand.nextInt(9) - 4;
            int gz = cz + rand.nextInt(9) - 4;
            int floor = sl.getHeight(Heightmap.Types.OCEAN_FLOOR, gx, gz);
            BlockPos water = new BlockPos(gx, floor, gz);
            BlockState ground = sl.getBlockState(water.below());
            if (sl.getBlockState(water).is(Blocks.WATER) && !ground.isAir()
                && ground.getFluidState().isEmpty()) { // seagrass needs solid ground under water
                sl.setBlock(water, Blocks.SEAGRASS.defaultBlockState(), 3);
            }
        }
    }

    // ─── Fish tether: keep a chunk's school in its chunk so it reads as "belonging" ───────────────
    private static final String FISH_HOME_KEY = "BannerboundFishHome";
    private static final double FISH_LEASH = 11.0; // horizontal blocks from chunk centre before recall

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        Entity e = event.getEntity();
        if (e.level().isClientSide) return;
        if (e instanceof AbstractFish fish) {
            tetherFish(fish);
        } else if (e instanceof Animal animal) {
            tetherAnimal(animal);
        }
    }

    private static void tetherFish(AbstractFish fish) {
        if (fish.tickCount % 20 != 0) return;
        CompoundTag data = fish.getPersistentData();
        if (!data.contains(FISH_HOME_KEY)) return;
        BlockPos home = BlockPos.of(data.getLong(FISH_HOME_KEY));
        double dx = home.getX() + 0.5 - fish.getX();
        double dz = home.getZ() + 0.5 - fish.getZ();
        if (dx * dx + dz * dz > FISH_LEASH * FISH_LEASH) {
            // Drifted out of its chunk — swim it back home (overrides the random swim until it returns).
            fish.getNavigation().moveTo(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5, 1.2);
        }
    }

    // ─── Themed groundwork per livestock chunk (terrain flavour, not a structure) ─────────────────
    // All sampling stays within the chunk (offsets ≤ ±6 of the +8 centre) — reaching the next chunk
    // would force-generate it and cascade into a server-freezing chunk-gen storm.

    private static void decorate(ServerLevel sl, RandomSource rand, int cx, int cz) {
        // One concentrated central HUB (the landmark — "the chunk is here") plus randomized scatter, so
        // chunks don't all look identical. (Only horse chunks are decorated now.)
        BlockState hubGround = (rand.nextBoolean() ? Blocks.COARSE_DIRT : Blocks.DIRT).defaultBlockState();
        buildHub(sl, rand, cx, cz, hubGround); // the chunk gets water (husbandry needs it)
        dirtPaths(sl, rand, cx, cz, 4 + rand.nextInt(7));
        shortGrass(sl, rand, cx, cz, 3 + rand.nextInt(8));
        // Random flourishes so no two chunks read the same.
        if (rand.nextInt(2) == 0) {
            groundPatch(sl, rand, cx, cz,
                (rand.nextBoolean() ? Blocks.COARSE_DIRT : Blocks.DIRT).defaultBlockState(), 1 + rand.nextInt(2));
        }
        if (rand.nextInt(2) == 0) {
            flowers(sl, rand, cx, cz, 2 + rand.nextInt(4));
        }
    }

    /** The central landmark: a trodden-earth disc of {@code ground} (radius 2, sometimes 3) at/near the
     *  chunk centre. Every livestock chunk gets a drinking pool (the husbandry system needs water): if the
     *  chunk has no natural water we dig a guaranteed-contained 2×2 pool at the hub. Stays well inside the
     *  chunk's inner region so the ring probe in {@link ChunkResources} never sees the pool. */
    private static void buildHub(ServerLevel sl, RandomSource rand, int cx, int cz, BlockState ground) {
        int radius = rand.nextInt(3) == 0 ? 3 : 2;      // mostly 2, sometimes 3
        // Spiral out from the centre for the closest spot that is flat AND on real ground (not a thin
        // crust over a cave/crack — that's what made pools float). Fall back to the closest merely-flat
        // spot, else the centre; the dig fills the floor down to solid either way.
        int hx = cx, hz = cz, hy = groundSurfaceY(sl, cx, cz);
        int fbx = hx, fbz = hz, fby = hy;
        boolean flat = false;
        boolean solid = false;
        for (int r = 0; r <= 3 && !solid; r++) {
            for (int dx = -r; dx <= r && !solid; dx++) {
                for (int dz = -r; dz <= r && !solid; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring at Chebyshev radius r
                    int x = cx + dx;
                    int z = cz + dz;
                    int y = groundSurfaceY(sl, x, z);
                    if (!isFlatArea(sl, x, z, y, 2)) continue;
                    if (!flat) { fbx = x; fbz = z; fby = y; flat = true; }
                    if (hasSolidBaseFor2x2(sl, x, z, y)) { hx = x; hz = z; hy = y; solid = true; }
                }
            }
        }
        if (!solid && flat) { hx = fbx; hz = fbz; hy = fby; }

        int rr = radius * radius + 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > rr) continue; // disc
                int x = hx + dx;
                int z = hz + dz;
                BlockPos p = new BlockPos(x, groundSurfaceY(sl, x, z), z);
                if (isReplaceableGround(sl.getBlockState(p))) sl.setBlock(p, ground, 3);
            }
        }
        if (!hasWaterInChunk(sl, cx, cz)) digDrinkingPool(sl, hx, hz, hy, ground);
    }

    /** True if the pool footprint (2×2 + border) sits on at least 2 solid blocks of ground — i.e. real
     *  ground, not a one-block crust over a cave (which would leave a pool floating over the cavity). */
    private static boolean hasSolidBaseFor2x2(ServerLevel sl, int hx, int hz, int y) {
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 2; dz++) {
                if (!sl.getBlockState(new BlockPos(hx + dx, y - 1, hz + dz)).blocksMotion()) return false;
                if (!sl.getBlockState(new BlockPos(hx + dx, y - 2, hz + dz)).blocksMotion()) return false;
            }
        }
        return true;
    }

    /** Whether the chunk already has natural water near the surface (so we needn't dig a pool). Samples
     *  only the inner region — never the neighbour chunks (that would force-load them). */
    private static boolean hasWaterInChunk(ServerLevel sl, int cx, int cz) {
        for (int dx = -6; dx <= 6; dx += 3) {
            for (int dz = -6; dz <= 6; dz += 3) {
                int gy = groundSurfaceY(sl, cx + dx, cz + dz);
                if (!sl.getBlockState(new BlockPos(cx + dx, gy + 1, cz + dz)).getFluidState().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Dig a guaranteed-contained 2×2 drinking pool flush at {@code y}: force a solid floor under it and a
     *  solid rim around it (building walls where the ground drops away), so it never spills regardless of
     *  how uneven the spot is — unlike the old "only if a flat spot exists" pond. */
    private static void digDrinkingPool(ServerLevel sl, int hx, int hz, int y, BlockState rim) {
        // Pass 1: build the basin (floor + walls) so it's fully sealed BEFORE any water exists.
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 2; dz++) {
                BlockPos at = new BlockPos(hx + dx, y, hz + dz);
                boolean inner = dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1;
                // Floor: fill DOWN from y-1 until we hit solid ground (cap a few blocks) so the basin is
                // grounded — never a one-block slab floating over a cave/crack.
                for (int d = 1; d <= 6; d++) {
                    BlockPos fp = new BlockPos(hx + dx, y - d, hz + dz);
                    if (sl.getBlockState(fp).blocksMotion()) break;
                    sl.setBlock(fp, rim, 3);
                }
                if (!inner) { // rim: wall in the water where the surrounding ground isn't already solid
                    BlockState s = sl.getBlockState(at);
                    if (!s.blocksMotion() || !s.getFluidState().isEmpty()) sl.setBlock(at, rim, 3);
                }
            }
        }
        // Pass 2: fill the inner 2×2 with water (now safely contained) and open the air above it.
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                BlockPos at = new BlockPos(hx + dx, y, hz + dz);
                sl.setBlock(at, Blocks.WATER.defaultBlockState(), 3);
                if (sl.getBlockState(at.above()).blocksMotion()) {
                    sl.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static final net.minecraft.world.level.block.Block[] FLOWERS = {
        Blocks.DANDELION, Blocks.POPPY, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET};

    /** A few flowers of one kind scattered on grass — cheap per-chunk variety. */
    private static void flowers(ServerLevel sl, RandomSource rand, int cx, int cz, int count) {
        net.minecraft.world.level.block.Block flower = FLOWERS[rand.nextInt(FLOWERS.length)];
        for (int i = 0; i < count; i++) {
            int x = cx + rand.nextInt(13) - 6;
            int z = cz + rand.nextInt(13) - 6;
            BlockPos ground = new BlockPos(x, groundSurfaceY(sl, x, z), z);
            BlockPos above = ground.above();
            if (sl.getBlockState(above).isAir() && sl.getBlockState(ground).is(Blocks.GRASS_BLOCK)) {
                sl.setBlock(above, flower.defaultBlockState(), 3);
            }
        }
    }

    /** A {@code (2*half+1)²} area centred on (x,z) that is uniform solid ground at {@code y} with air
     *  above (no trees) — flat enough that a pond placed inside is walled in and won't spill. */
    private static boolean isFlatArea(ServerLevel sl, int x, int z, int y, int half) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (groundSurfaceY(sl, x + dx, z + dz) != y) return false;
                BlockState s = sl.getBlockState(new BlockPos(x + dx, y, z + dz));
                if (!(s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT)
                    || s.is(Blocks.DIRT_PATH) || s.is(Blocks.SAND) || s.is(Blocks.SNOW_BLOCK))) {
                    return false;
                }
                if (sl.getBlockState(new BlockPos(x + dx, y + 1, z + dz)).blocksMotion()) return false;
            }
        }
        return true;
    }

    /** Scatter trodden dirt-path blocks across grass/dirt surface. */
    private static void dirtPaths(ServerLevel sl, RandomSource rand, int cx, int cz, int count) {
        for (int i = 0; i < count; i++) {
            int x = cx + rand.nextInt(13) - 6;
            int z = cz + rand.nextInt(13) - 6;
            BlockPos p = new BlockPos(x, groundSurfaceY(sl, x, z), z);
            BlockState s = sl.getBlockState(p);
            if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)) {
                sl.setBlock(p, Blocks.DIRT_PATH.defaultBlockState(), 3);
            }
        }
    }

    /** A ragged round patch (radius blocks) of a ground block: mud wallow, coarse-dirt scratch yard, … */
    private static void groundPatch(ServerLevel sl, RandomSource rand, int cx, int cz, BlockState block,
                                    int radius) {
        int px = cx + rand.nextInt(7) - 3;
        int pz = cz + rand.nextInt(7) - 3;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + 1) continue;          // round-ish
                if (dx * dx + dz * dz >= radius * radius && rand.nextBoolean()) continue; // ragged edge
                int x = px + dx;
                int z = pz + dz;
                BlockPos p = new BlockPos(x, groundSurfaceY(sl, x, z), z);
                if (isReplaceableGround(sl.getBlockState(p))) sl.setBlock(p, block, 3);
            }
        }
    }

    /** Scatter short-grass tufts on grass surface (skips spots under a trunk). */
    private static void shortGrass(ServerLevel sl, RandomSource rand, int cx, int cz, int count) {
        for (int i = 0; i < count; i++) {
            int x = cx + rand.nextInt(13) - 6;
            int z = cz + rand.nextInt(13) - 6;
            BlockPos ground = new BlockPos(x, groundSurfaceY(sl, x, z), z);
            BlockPos above = ground.above();
            if (sl.getBlockState(above).isAir() && sl.getBlockState(ground).is(Blocks.GRASS_BLOCK)) {
                sl.setBlock(above, Blocks.SHORT_GRASS.defaultBlockState(), 3);
            }
        }
    }

    private static boolean isReplaceableGround(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT)
            || s.is(Blocks.DIRT_PATH) || s.is(Blocks.MUD) || s.is(Blocks.PODZOL);
    }
}
