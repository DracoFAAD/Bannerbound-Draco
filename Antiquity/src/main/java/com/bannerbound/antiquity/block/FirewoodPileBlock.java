package com.bannerbound.antiquity.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A growing stack of firewood on the ground. Right-clicking it with more firewood raises
 * {@link #LOGS} from 1 → 3; the fourth firewood (see {@code FirewoodItem}) replaces the pile with
 * an unlit vanilla campfire — which, in unclaimed land, founds a settlement (Core's FactionEvents),
 * or can be lit with Fire Sticks as an ordinary cook-fire. Drops its firewood back when broken.
 */
public class FirewoodPileBlock extends Block {
    public static final MapCodec<FirewoodPileBlock> CODEC = simpleCodec(FirewoodPileBlock::new);
    /** Visible logs in the pile, 1..3. The 4th firewood swaps the pile for a campfire. */
    public static final IntegerProperty LOGS = IntegerProperty.create("logs", 1, 3);
    /** Carried through the stages and onto the resulting campfire so the rotation never jumps. */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** The pile to fill before the next firewood makes a campfire. */
    public static final int MAX_LOGS = 3;
    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 7.0, 16.0);

    public FirewoodPileBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LOGS, 1).setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<FirewoodPileBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LOGS, FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return canSurvive(state, level, pos)
            ? super.updateShape(state, direction, neighborState, level, pos, neighborPos)
            : Blocks.AIR.defaultBlockState();
    }
}
