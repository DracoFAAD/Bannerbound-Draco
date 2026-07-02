package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Fletching;
import com.bannerbound.antiquity.block.entity.FletchingStationBlockEntity;
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

/**
 * Fletching Station — the Tier-2 refinement workbench. Items are placed on it one at a time (mixed
 * types allowed), held by the {@link FletchingStationBlockEntity}; a matched recipe shows a floating
 * spinning preview. Shift-right-click does NOT craft instantly — it opens the in-world stretch
 * minigame (see {@link Fletching}), whose performance rolls the output's craftsmanship quality.
 * <ul>
 *   <li>Right-click with an item → add ONE of it to the pile.</li>
 *   <li>Right-click empty-handed → take the last item back out.</li>
 *   <li>Shift-right-click → start the fletching minigame (when a recipe is matched).</li>
 * </ul>
 */
public class FletchingStationBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<FletchingStationBlock> CODEC = simpleCodec(FletchingStationBlock::new);
    /** Hugs the Blockbench model's mass (x 3–13, z 3–14, 13px tall) so collision/selection match. */
    public static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 13.0, 14.0);

    public FletchingStationBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<FletchingStationBlock> codec() {
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

    /**
     * The collision box isn't a full block, so vanilla classifies the cell as walkable and NPCs path
     * onto it and snag. Mark it un-pathfindable so every pathfinder routes around it.
     */
    @Override
    protected boolean isPathfindable(BlockState state,
                                     net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FletchingStationBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (type != BannerboundAntiquity.FLETCHING_STATION_BE.get()) return null;
        return (lvl, pos, st, be) -> FletchingStationBlockEntity.tick(lvl, pos, st, (FletchingStationBlockEntity) be);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof FletchingStationBlockEntity be)) {
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
        if (!(level.getBlockEntity(pos) instanceof FletchingStationBlockEntity be)) {
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

    /** Opens the stretch minigame for {@code player} when the station has a valid, researched
     *  recipe and no citizen is mid-craft on it (the mutual work-block lock). */
    private static boolean tryStart(Level level, BlockPos pos, Player player,
                                    FletchingStationBlockEntity be) {
        if (com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                    net.minecraft.network.chat.Component
                        .translatable("bannerbound.workshop.station_busy")
                        .withStyle(net.minecraft.ChatFormatting.YELLOW), true);
            }
            return false;
        }
        if (be.matchedRecipe() == null) return false;
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            Fletching.startSession(sp, pos, be);
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
                && level.getBlockEntity(pos) instanceof FletchingStationBlockEntity be) {
            for (ItemStack s : be.getContents()) {
                Block.popResource(level, pos, s);
            }
            Fletching.abortSessionAt(pos);
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
