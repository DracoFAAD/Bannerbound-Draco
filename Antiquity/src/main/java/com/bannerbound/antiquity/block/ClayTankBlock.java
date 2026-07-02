package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.ClayTankBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Clay Tank — a vertical modular pillar (up to {@link #MAX_PIECES} blocks) that stores a curing
 * liquid for the tannery. Placed piece-by-piece: each new block stacked on a tank takes the next
 * {@link #PART} index; the bottom (PART 0) is the controller and carries the {@link ClayTankBlockEntity}
 * (the liquid). Right-click with a water bucket to fill, an empty bucket to drain, or quicklime to
 * turn the held water into hide-curing liquid. Breaking any piece tears down the pieces above it.
 */
public class ClayTankBlock extends Block implements EntityBlock {
    public static final MapCodec<ClayTankBlock> CODEC = simpleCodec(ClayTankBlock::new);
    /** Max pillar height. */
    public static final int MAX_PIECES = 4;
    /** Height index in the pillar: 0 = bottom/controller. */
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, MAX_PIECES - 1);

    // Hollow shapes (walls 1px thick, interior 1..15) so entities can fall inside, cauldron-style.
    // The base keeps its floor (y 0..1); extensions are open tubes so you fall through to the base.
    private static final VoxelShape SHAPE_BASE = net.minecraft.world.phys.shapes.Shapes.join(
        net.minecraft.world.phys.shapes.Shapes.block(), Block.box(1, 1, 1, 15, 16, 15),
        net.minecraft.world.phys.shapes.BooleanOp.ONLY_FIRST);
    private static final VoxelShape SHAPE_EXTENSION = net.minecraft.world.phys.shapes.Shapes.join(
        net.minecraft.world.phys.shapes.Shapes.block(), Block.box(1, 0, 1, 15, 16, 15),
        net.minecraft.world.phys.shapes.BooleanOp.ONLY_FIRST);

    public ClayTankBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(PART, 0));
    }

    @Override
    protected MapCodec<ClayTankBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART);
    }

    private static VoxelShape shapeFor(BlockState state) {
        return state.getValue(PART) == 0 ? SHAPE_BASE : SHAPE_EXTENSION;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapeFor(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shapeFor(state);
    }

    /**
     * The collision box isn't a full block, so vanilla classifies the cell as walkable and NPCs path
     * onto it and snag. Mark it un-pathfindable so every pathfinder routes around it.
     */
    @Override
    protected boolean isPathfindable(BlockState state,
                                     net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    /** A piece placed on top of an existing tank takes the next PART; otherwise it's a new base.
     *  Stacking past {@link #MAX_PIECES} is refused (null → placement fails). */
    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState below = ctx.getLevel().getBlockState(ctx.getClickedPos().below());
        if (below.getBlock() instanceof ClayTankBlock) {
            int part = below.getValue(PART) + 1;
            if (part >= MAX_PIECES) return null;   // pillar is already at max height
            return defaultBlockState().setValue(PART, part);
        }
        return defaultBlockState().setValue(PART, 0);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == 0 ? new ClayTankBlockEntity(pos, state) : null;
    }

    /** The controller cell's position: the bottom of the pillar, {@code PART} blocks below. */
    private static BlockPos controllerPos(BlockPos pos, BlockState state) {
        return pos.below(state.getValue(PART));
    }

    /** The controller block entity, resolved from any cell of the pillar, or {@code null}. */
    @Nullable
    public static ClayTankBlockEntity getController(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ClayTankBlock)) return null;
        return level.getBlockEntity(controllerPos(pos, state)) instanceof ClayTankBlockEntity be ? be : null;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              net.minecraft.world.entity.player.Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        ClayTankBlockEntity controller = getController(level, pos);
        if (controller == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // Water bucket → add a bucket of water.
        if (stack.is(Items.WATER_BUCKET)) {
            if (!level.isClientSide && controller.addWater()) {
                if (!player.hasInfiniteMaterials()) {
                    player.setItemInHand(hand, new ItemStack(Items.BUCKET));
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        // Empty bucket → take a bucket of water back.
        if (stack.is(Items.BUCKET)) {
            if (!level.isClientSide && controller.removeWater()) {
                if (!player.hasInfiniteMaterials()) {
                    player.setItemInHand(hand, new ItemStack(Items.WATER_BUCKET));
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        // Quicklime → convert the held water into curing liquid.
        if (stack.is(BannerboundAntiquity.QUICKLIME.get())) {
            if (!level.isClientSide && controller.convertToCuring() && !player.hasInfiniteMaterials()) {
                stack.shrink(1);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        // Scraped hide on a curing-charged tank → cured hide (consumes one bucket of curing liquid).
        if (stack.is(BannerboundAntiquity.SCRAPED_HIDE.get()) && controller.hasCuring()) {
            if (!level.isClientSide && controller.drawCuring()) {
                if (!player.hasInfiniteMaterials()) {
                    stack.shrink(1);
                }
                ItemStack cured = new ItemStack(BannerboundAntiquity.CURED_HIDE.get());
                if (!player.getInventory().add(cured)) {
                    player.drop(cured, false);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                              net.minecraft.world.entity.player.Player player,
                                              BlockHitResult hit) {
        return InteractionResult.PASS;
    }

    /** Breaking any piece removes the pieces stacked above it (they lose their support) and drops the
     *  liquid back into the now-shorter tank (clamped to the remaining capacity). */
    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock()) && !level.isClientSide) {
            BlockPos above = pos.above();
            while (level.getBlockState(above).getBlock() instanceof ClayTankBlock) {
                level.destroyBlock(above, true);
                above = above.above();
            }
            // The controller (bottom) survives unless the base itself was broken; clamp its liquid to
            // the remaining height so the level renders back down into the shortened pillar.
            int part = oldState.getValue(PART);
            if (part > 0 && level.getBlockEntity(pos.below(part)) instanceof ClayTankBlockEntity be) {
                be.clampBuckets(ClayTankBlockEntity.BUCKETS_PER_PIECE * part);
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
