package com.bannerbound.antiquity.block;

import com.mojang.serialization.MapCodec;

import com.bannerbound.antiquity.network.OpenArmorerPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Armorer's Workbench — the player-designed-armor station (ARMOR_PLAN.md). A 2-cell multiblock laid
 * out exactly like the Carpenter's Table (a MASTER cell that renders the full 32px model extending one
 * block toward {@code FACING}, plus a SECONDARY cell that renders nothing and forwards interactions),
 * but with no block entity — the bench is a static JSON model and the design lives entirely in the
 * screen for now. <b>Shift + right-click</b> either half opens the {@link ArmorerScreen} design GUI.
 */
public class ArmorersWorkbenchBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<ArmorersWorkbenchBlock> CODEC = simpleCodec(ArmorersWorkbenchBlock::new);
    /** True for the master cell (renders the model); false for the secondary cell. */
    public static final BooleanProperty MAIN = BooleanProperty.create("main");
    /** Tabletop slab (y 12–15px), shared by both cells — rotation-invariant, so no per-facing shape. */
    public static final VoxelShape SHAPE = Block.box(0.0, 12.0, 0.0, 16.0, 15.0, 16.0);

    public ArmorersWorkbenchBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MAIN, true));
    }

    @Override
    protected MapCodec<ArmorersWorkbenchBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MAIN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection(); // the bench extends away from the player
        BlockPos secondary = context.getClickedPos().relative(facing);
        if (!context.getLevel().getBlockState(secondary).canBeReplaced(context)) return null;
        return defaultBlockState().setValue(FACING, facing).setValue(MAIN, true);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && state.getValue(MAIN)) {
            BlockPos secondary = pos.relative(state.getValue(FACING));
            level.setBlock(secondary, state.setValue(MAIN, false), Block.UPDATE_ALL);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
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

    /** The master cell of this multiblock (the cell whose pos the screen is anchored to). */
    private static BlockPos masterPos(BlockPos pos, BlockState state) {
        return state.getValue(MAIN) ? pos : pos.relative(state.getValue(FACING).getOpposite());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        // Shift + right-click (empty hand) opens the design screen; a plain right-click is left free
        // for future on-bench interactions.
        if (!player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new OpenArmorerPayload(masterPos(pos, state)));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())) {
            Direction facing = oldState.getValue(FACING);
            boolean main = oldState.getValue(MAIN);
            // Tear down the other half. The bounce terminates: by the time this fires, this cell is
            // already air, so the other cell's onRemove sees air here and stops.
            BlockPos other = main ? pos.relative(facing) : pos.relative(facing.getOpposite());
            if (level.getBlockState(other).is(this)) {
                level.removeBlock(other, false);
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
