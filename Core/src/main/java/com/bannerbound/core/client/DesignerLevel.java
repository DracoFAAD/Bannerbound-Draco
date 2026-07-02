package com.bannerbound.core.client;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

/**
 * A throwaway {@link Level} backed by the Wall Designer's edit grid — exists so the designer
 * can run the REAL placement code path ({@code Block.getStateForPlacement} with a genuine
 * {@code BlockPlaceContext}) instead of dropping default states: torches become wall torches
 * on side faces, slabs pick halves, stairs orient, ladders attach ("make sure ALL block
 * states are supported", playtest 2026-06-12).
 *
 * <p>Only the surface placement logic touches is real: {@code getBlockState}/{@code
 * getFluidState}/{@code setBlock} hit the grid (absent/out-of-bounds = air), light is a dead
 * engine, there are no entities, ticks are black holes, and the handful of registry-ish
 * lookups defer to the real client level.
 */
@ApiStatus.Internal
public final class DesignerLevel extends Level {

    /** The designer's live cell store, adapted to world coords (x=l, y=h, z=d). */
    public interface Grid {
        @Nullable
        BlockState get(BlockPos pos);

        void set(BlockPos pos, @Nullable BlockState state);
    }

    private final Level delegate;
    private final Grid grid;
    private final ChunkSource chunkSource = new DeadChunkSource();

    public DesignerLevel(Level delegate, Grid grid) {
        super(new net.minecraft.client.multiplayer.ClientLevel.ClientLevelData(
                Difficulty.PEACEFUL, false, true),
            Level.OVERWORLD, delegate.registryAccess(), delegate.dimensionTypeRegistration(),
            () -> net.minecraft.util.profiling.InactiveProfiler.INSTANCE,
            true, false, 0L, 0);
        this.delegate = delegate;
        this.grid = grid;
    }

    // ─── The parts placement logic actually reads/writes ────────────────────────────────────

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = grid.get(pos);
        return state != null ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        grid.set(pos.immutable(), state.isAir() ? null : state);
        return true;
    }

    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return true;
    }

    // ─── Dead machinery ─────────────────────────────────────────────────────────────────────

    @Override
    public ChunkSource getChunkSource() {
        return chunkSource;
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

    @Override
    public void playSeededSound(@Nullable Player player, double x, double y, double z,
                                Holder<SoundEvent> sound, SoundSource source,
                                float volume, float pitch, long seed) {
    }

    @Override
    public void playSeededSound(@Nullable Player player, Entity entity, Holder<SoundEvent> sound,
                                SoundSource source, float volume, float pitch, long seed) {
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

    @Override
    public String gatherChunkSourceStats() {
        return "";
    }

    @Override
    @Nullable
    public Entity getEntity(int id) {
        return null;
    }

    @Override
    @Nullable
    public MapItemSavedData getMapData(MapId id) {
        return null;
    }

    @Override
    public void setMapData(MapId id, MapItemSavedData data) {
    }

    @Override
    public MapId getFreeMapId() {
        return new MapId(0);
    }

    @Override
    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
    }

    @Override
    public LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        return EmptyEntityGetter.INSTANCE;
    }

    @Override
    public List<Entity> getEntities(@Nullable Entity except, AABB area,
                                    java.util.function.Predicate<? super Entity> predicate) {
        return List.of();
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB area,
                                                  java.util.function.Predicate<? super T> predicate) {
        return List.of();
    }

    @Override
    public List<? extends Player> players() {
        return List.of();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return 1.0f;
    }

    // ─── Deferred to the real level ─────────────────────────────────────────────────────────

    @Override
    public net.minecraft.world.TickRateManager tickRateManager() {
        return delegate.tickRateManager();
    }

    @Override
    public Scoreboard getScoreboard() {
        return delegate.getScoreboard();
    }

    @Override
    public RecipeManager getRecipeManager() {
        return delegate.getRecipeManager();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return delegate.getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return delegate.enabledFeatures();
    }

    @Override
    public net.minecraft.world.item.alchemy.PotionBrewing potionBrewing() {
        return delegate.potionBrewing();
    }

    // ─── NeoForge day-time extension: this level has no day ─────────────────────────────────

    @Override
    public float getDayTimeFraction() {
        return 0f;
    }

    @Override
    public float getDayTimePerTick() {
        return 1f;
    }

    @Override
    public void setDayTimeFraction(float fraction) {
    }

    @Override
    public void setDayTimePerTick(float perTick) {
    }

    /** No chunks, dead light — placement logic never gets this far with the grid overrides. */
    private final class DeadChunkSource extends ChunkSource {
        private final LevelLightEngine light = new LevelLightEngine(new LightChunkGetter() {
            @Override
            @Nullable
            public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
                return null;
            }

            @Override
            public BlockGetter getLevel() {
                return DesignerLevel.this;
            }
        }, false, false);

        @Override
        @Nullable
        public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus status, boolean requireChunk) {
            return null;
        }

        @Override
        public void tick(BooleanSupplier hasTimeLeft, boolean tickChunks) {
        }

        @Override
        public String gatherStats() {
            return "";
        }

        @Override
        public int getLoadedChunksCount() {
            return 0;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return light;
        }

        @Override
        public BlockGetter getLevel() {
            return DesignerLevel.this;
        }
    }

    /** Entity storage with nothing in it. */
    private static final class EmptyEntityGetter implements LevelEntityGetter<Entity> {
        static final EmptyEntityGetter INSTANCE = new EmptyEntityGetter();

        @Override
        @Nullable
        public Entity get(int id) {
            return null;
        }

        @Override
        @Nullable
        public Entity get(java.util.UUID uuid) {
            return null;
        }

        @Override
        public Iterable<Entity> getAll() {
            return List.of();
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> test,
                                           net.minecraft.util.AbortableIterationConsumer<U> consumer) {
        }

        @Override
        public void get(AABB area, java.util.function.Consumer<Entity> consumer) {
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AABB area,
                                           net.minecraft.util.AbortableIterationConsumer<U> consumer) {
        }
    }
}
