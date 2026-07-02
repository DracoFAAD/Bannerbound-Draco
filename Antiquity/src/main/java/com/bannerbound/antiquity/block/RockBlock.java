package com.bannerbound.antiquity.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A small loose rock scattered on the ground — the worldgen seed of the stone-age opening loop
 * (four of them craft into the matching stone block). Purely decorative: no collision (until
 * snow-logged), breaks instantly, and pops off if its support is removed.
 *
 * <p><b>Snow-logging.</b> Like waterlogging but for snow: the rock carries a {@link #SNOW} level
 * (0–8) and renders a vanilla snow layer of that height around itself (see the multipart
 * blockstate), so it sits cleanly INSIDE the snow instead of carving a hole. It absorbs the snow
 * it's placed into, a {@link #randomTick} keeps it matched to the surrounding snow (so worldgen
 * rocks fill in once the snow generates), and breaking it leaves the snow layer behind (handled by
 * {@code AntiquityEvents.onRockBreakKeepsSnow}). Also waterloggable for underwater rocks.
 */
public class RockBlock extends Block implements SimpleWaterloggedBlock {
    public static final MapCodec<RockBlock> CODEC = simpleCodec(RockBlock::new);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    /** Contained snow, 0 = none, 1–8 = snow layers (matches {@link SnowLayerBlock#LAYERS} heights). */
    public static final IntegerProperty SNOW = IntegerProperty.create("snow", 0, 8);

    /** The rock cluster itself (main lump of {@code models/block/rock.json}). */
    private static final VoxelShape ROCK_SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 2.0, 12.0);

    public RockBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(WATERLOGGED, false).setValue(SNOW, 0));
    }

    @Override
    protected MapCodec<RockBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WATERLOGGED, SNOW);
    }

    /** Selection outline: the rock cluster, plus the contained snow's height when snow-logged. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        int snow = state.getValue(SNOW);
        return snow > 0 ? Shapes.or(ROCK_SHAPE, snowShape(snow)) : ROCK_SHAPE;
    }

    /** No collision for the bare rock (you walk through it); the contained snow IS collidable. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        int snow = state.getValue(SNOW);
        return snow > 0 ? snowShape(snow) : Shapes.empty();
    }

    private static VoxelShape snowShape(int snowLayers) {
        return Block.box(0.0, 0.0, 0.0, 16.0, snowLayers * 2.0, 16.0);
    }

    /** Survives on a sturdy top face below it, or on any snow (so it sits cleanly on snowy tiles). */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        return belowState.isFaceSturdy(level, below, Direction.UP) || belowState.is(BlockTags.SNOW);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState replaced = ctx.getLevel().getBlockState(ctx.getClickedPos());
        boolean water = ctx.getLevel().getFluidState(ctx.getClickedPos()).getType() == Fluids.WATER;
        return defaultBlockState()
            .setValue(WATERLOGGED, water)
            .setValue(SNOW, snowLevelOf(replaced)); // absorb the snow we're placed into
    }

    /** Snow contained in {@code state}: a snow layer's height, 8 for a full snow block, else 0. */
    public static int snowLevelOf(BlockState state) {
        if (state.is(Blocks.SNOW)) return state.getValue(SnowLayerBlock.LAYERS);
        if (state.is(Blocks.SNOW_BLOCK)) return 8;
        return 0;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return canSurvive(state, level, pos)
            ? super.updateShape(state, direction, neighborState, level, pos, neighborPos)
            : Blocks.AIR.defaultBlockState();
    }

    /** Keep the contained snow in step with the surroundings: a rock in a snowfield fills with
     *  snow to match its neighbours (so freshly-generated rocks aren't left in a bare hole), and
     *  loses it when the snow around it is gone or it's sheltered from the sky. */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(WATERLOGGED)) return;
        int target = level.canSeeSky(pos) ? maxNeighbourSnow(level, pos) : 0;
        if (target != state.getValue(SNOW)) {
            level.setBlock(pos, state.setValue(SNOW, target), Block.UPDATE_ALL);
        }
    }

    private static int maxNeighbourSnow(LevelReader level, BlockPos pos) {
        int max = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int s = snowLevelOf(level.getBlockState(pos.relative(d)));
            if (s > max) max = s;
        }
        return max;
    }
}
