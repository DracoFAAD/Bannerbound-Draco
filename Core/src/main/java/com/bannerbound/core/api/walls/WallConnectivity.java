package com.bannerbound.core.api.walls;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

/**
 * Simulates vanilla shape connections (fences, walls, panes, stairs, doors, chests…) over a
 * detached map of block states — WITHOUT touching the real level. Used everywhere wall designs
 * are shown before they exist as placed blocks: the designer viewport and the in-world ghost
 * preview both render raw authored states, which left connectable blocks floating as lone posts
 * (playtest 2026-06-12, "simulate connected blocks and their connections").
 *
 * <p>Technique: run each state through {@link BlockState#updateShape} against all six neighbors
 * — the exact code path the real world runs on neighbor changes — inside a throwaway
 * {@link LevelAccessor} whose {@code getBlockState} serves the map. Two sweeps so order-dependent
 * properties settle (wall posts read side connections computed in the first pass).
 */
public final class WallConnectivity {

    /** Horizontals first, then vertical — wall/fence side props settle before up-post logic. */
    private static final Direction[] UPDATE_ORDER = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST,
        Direction.UP, Direction.DOWN};

    private WallConnectivity() {
    }

    /**
     * Bakes design connections INTO a packed-pos blueprint: every state's connection
     * properties resolved against the other blueprint blocks only (void outside — the
     * design, not the terrain, is the authority). This is what makes the blueprint's
     * expected states the EXACT final wall states: builders place them verbatim, ghosts
     * show them verbatim, and player placements get snapped to them ("keep exactly the
     * block-states/connections from the wall design", playtest 2026-06-12).
     */
    public static it.unimi.dsi.fastutil.longs.Long2ObjectMap<BlockState> bake(
            it.unimi.dsi.fastutil.longs.Long2ObjectMap<BlockState> raw, Level context) {
        Map<BlockPos, BlockState> in = new HashMap<>(raw.size());
        for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<BlockState> entry
                : raw.long2ObjectEntrySet()) {
            in.put(BlockPos.of(entry.getLongKey()), entry.getValue());
        }
        Map<BlockPos, BlockState> connected = simulate(in, context, false);
        it.unimi.dsi.fastutil.longs.Long2ObjectMap<BlockState> out =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(raw.size());
        for (Map.Entry<BlockPos, BlockState> entry : connected.entrySet()) {
            out.put(entry.getKey().asLong(), entry.getValue());
        }
        return out;
    }

    /**
     * Returns a copy of {@code raw} with every state's connection properties resolved against
     * its map neighbors. {@code worldFallback} controls what an absent neighbor reads as:
     * {@code true} = the real level's block (in-world ghosts connect to existing terrain),
     * {@code false} = air (the designer's piece floats in a void). States whose updateShape
     * collapses to air (e.g. a lone door half) keep their raw state — a block must never
     * vanish from a preview just because its partner isn't authored.
     */
    public static Map<BlockPos, BlockState> simulate(Map<BlockPos, BlockState> raw, Level context,
                                                     boolean worldFallback) {
        Map<BlockPos, BlockState> out = new HashMap<>(raw);
        SimLevel sim = new SimLevel(context, out, worldFallback);
        for (int pass = 0; pass < 2; pass++) {
            for (Map.Entry<BlockPos, BlockState> entry : raw.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState state = out.get(pos);
                if (state == null || state.isAir()) state = entry.getValue();
                for (Direction dir : UPDATE_ORDER) {
                    BlockPos npos = pos.relative(dir);
                    state = state.updateShape(dir, sim.getBlockState(npos), sim, pos, npos);
                    if (state.isAir()) break;
                }
                out.put(pos, state.isAir() ? entry.getValue() : state);
            }
        }
        return out;
    }

    /**
     * Map-backed throwaway level: {@code getBlockState} serves the simulation map (then the
     * real level or air, per {@code worldFallback}); writes mutate the map; ticks/sounds/
     * particles/events are black holes; everything else defers to the real level. Only the
     * surface {@code updateShape} implementations actually touch is meaningfully implemented.
     */
    private static final class SimLevel implements LevelAccessor {
        private final Level delegate;
        private final Map<BlockPos, BlockState> states;
        private final boolean worldFallback;

        SimLevel(Level delegate, Map<BlockPos, BlockState> states, boolean worldFallback) {
            this.delegate = delegate;
            this.states = states;
            this.worldFallback = worldFallback;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState state = states.get(pos);
            if (state != null) return state;
            return worldFallback ? delegate.getBlockState(pos) : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
            states.put(pos.immutable(), state);
            return true;
        }

        @Override
        public boolean removeBlock(BlockPos pos, boolean isMoving) {
            states.remove(pos);
            return true;
        }

        @Override
        public boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity,
                                    int recursionLeft) {
            states.remove(pos);
            return true;
        }

        // ─── Ticks, effects, events: black holes ────────────────────────────────────────────

        @Override
        public LevelTickAccess<Block> getBlockTicks() {
            return BlackholeTickAccess.emptyLevelList();
        }

        @Override
        public LevelTickAccess<Fluid> getFluidTicks() {
            return BlackholeTickAccess.emptyLevelList();
        }

        @Override
        public long nextSubTickCount() {
            return 0L;
        }

        @Override
        public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound,
                              SoundSource source, float volume, float pitch) {
        }

        @Override
        public void addParticle(ParticleOptions options, double x, double y, double z,
                                double xSpeed, double ySpeed, double zSpeed) {
        }

        @Override
        public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
        }

        @Override
        public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
        }

        // ─── Inert queries ──────────────────────────────────────────────────────────────────

        @Override
        public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
            return predicate.test(getBlockState(pos));
        }

        @Override
        public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
            return predicate.test(getFluidState(pos));
        }

        @Override
        public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos,
                                                                  BlockEntityType<T> type) {
            return Optional.empty();
        }

        @Override
        public List<Entity> getEntities(@Nullable Entity except, AABB area,
                                        Predicate<? super Entity> predicate) {
            return List.of();
        }

        @Override
        public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB area,
                                                      Predicate<? super T> predicate) {
            return List.of();
        }

        @Override
        public List<? extends Player> players() {
            return List.of();
        }

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            return true;
        }

        // ─── Deferred to the real level ─────────────────────────────────────────────────────

        @Override
        public LevelData getLevelData() {
            return delegate.getLevelData();
        }

        @Override
        public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
            return delegate.getCurrentDifficultyAt(pos);
        }

        @Override
        @Nullable
        public MinecraftServer getServer() {
            return delegate.getServer();
        }

        @Override
        public ChunkSource getChunkSource() {
            return delegate.getChunkSource();
        }

        @Override
        public RandomSource getRandom() {
            return delegate.getRandom();
        }

        @Override
        public long dayTime() {
            return delegate.dayTime();
        }

        @Override
        @Nullable
        public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean requireChunk) {
            return delegate.getChunk(x, z, status, requireChunk);
        }

        @Override
        public int getHeight(Heightmap.Types heightmapType, int x, int z) {
            return delegate.getHeight(heightmapType, x, z);
        }

        @Override
        public int getSkyDarken() {
            return delegate.getSkyDarken();
        }

        @Override
        public BiomeManager getBiomeManager() {
            return delegate.getBiomeManager();
        }

        @Override
        public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
            return delegate.getUncachedNoiseBiome(x, y, z);
        }

        @Override
        public boolean isClientSide() {
            return delegate.isClientSide();
        }

        @Override
        @Deprecated
        public int getSeaLevel() {
            return delegate.getSeaLevel();
        }

        @Override
        public DimensionType dimensionType() {
            return delegate.dimensionType();
        }

        @Override
        public RegistryAccess registryAccess() {
            return delegate.registryAccess();
        }

        @Override
        public FeatureFlagSet enabledFeatures() {
            return delegate.enabledFeatures();
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            return delegate.getShade(direction, shade);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return delegate.getLightEngine();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return delegate.getBlockTint(pos, colorResolver);
        }

        @Override
        public WorldBorder getWorldBorder() {
            return delegate.getWorldBorder();
        }

        @Override
        @Nullable
        public net.minecraft.world.level.BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public int getHeight() {
            return delegate.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return delegate.getMinBuildHeight();
        }
    }
}
