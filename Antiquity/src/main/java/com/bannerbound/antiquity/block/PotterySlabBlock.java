package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Pottery;
import com.bannerbound.antiquity.block.entity.PotterySlabBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Pottery Slab: place clay, choose the floating recipe, then spin it on the wheel. */
public class PotterySlabBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<PotterySlabBlock> CODEC = simpleCodec(PotterySlabBlock::new);
    public static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 8.0, 16.0);

    public PotterySlabBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<PotterySlabBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    /** Partial-collision workstation: without this, vanilla classifies the half-height slab WALKABLE and
     *  NPCs path onto (and perch on) the wheel. Workers stand ADJACENT — same as every other station. */
    @Override
    protected boolean isPathfindable(BlockState state, net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PotterySlabBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (type != BannerboundAntiquity.POTTERY_SLAB_BE.get()) return null;
        return (lvl, pos, st, be) -> PotterySlabBlockEntity.tick(lvl, pos, st, (PotterySlabBlockEntity) be);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof PotterySlabBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (player.isSecondaryUseActive()) {
            return tryStart(level, pos, player, be)
                ? ItemInteractionResult.sidedSuccess(level.isClientSide)
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide
                && !com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())
                && be.insertOne(stack, player.getDirection().getOpposite())
                && !player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof PotterySlabBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (player.isSecondaryUseActive()) {
            return tryStart(level, pos, player, be)
                ? InteractionResult.sidedSuccess(level.isClientSide)
                : InteractionResult.PASS;
        }
        if (!level.isClientSide
                && !com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            ItemStack out = be.removeOne();
            if (!out.isEmpty()) giveOrDrop(player, out);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static boolean tryStart(Level level, BlockPos pos, Player player, PotterySlabBlockEntity be) {
        if (com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                    net.minecraft.network.chat.Component
                        .translatable("bannerbound.workshop.station_busy")
                        .withStyle(net.minecraft.ChatFormatting.YELLOW), true);
            }
            return false;
        }
        if (!be.getInProgress().isEmpty()) return false;
        if (be.matchedRecipe() == null) return false;
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            Pottery.startSession(sp, pos, be);
        }
        return true;
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof PotterySlabBlockEntity be) {
            for (ItemStack s : be.getContents()) {
                Block.popResource(level, pos, s);
            }
            Pottery.abortSessionAt(pos);
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
