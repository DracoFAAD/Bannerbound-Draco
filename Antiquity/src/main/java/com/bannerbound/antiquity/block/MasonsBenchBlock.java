package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Masonry;
import com.bannerbound.antiquity.block.entity.MasonsBenchBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Mason's Bench — a 2-block multiblock and the stone analogue of the Carpenter's Table: a MASTER cell
 * (holds the block entity, renders the full model that extends one block toward {@code FACING}) plus
 * a SECONDARY cell at {@code master + FACING} (renders nothing, forwards interactions). The item
 * places both halves bed-style; interactions on either half resolve to the master's block entity.
 * <ul>
 *   <li>Right-click with base stone (cobblestone, stone, sandstone…) → place one on the budget pile
 *       (sneak = the whole stack).</li>
 *   <li>Right-click with the stone chisel → start the chisel-strike minigame (needs a queued list).</li>
 *   <li>Right-click empty-handed → take the last (uncommitted) stone back; sneak = undo last queue.</li>
 * </ul>
 */
public class MasonsBenchBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<MasonsBenchBlock> CODEC = simpleCodec(MasonsBenchBlock::new);
    /** True for the master cell (model + block entity); false for the secondary cell. */
    public static final BooleanProperty MAIN = BooleanProperty.create("main");
    /** The full bench body (floor → top face at 14px), shared by both cells — rotation-invariant, so
     *  no per-facing shape. Extends down (legs + body), unlike the carpenter's floating-slab shape. */
    public static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 14.0, 16.0);

    public MasonsBenchBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MAIN, true));
    }

    @Override
    protected MapCodec<MasonsBenchBlock> codec() {
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

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(MAIN) ? new MasonsBenchBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (!state.getValue(MAIN) || type != BannerboundAntiquity.MASONS_BENCH_BE.get()) return null;
        return (lvl, pos, st, be) ->
            MasonsBenchBlockEntity.tick(lvl, pos, st, (MasonsBenchBlockEntity) be);
    }

    /** The master cell of this multiblock (the cell that holds the block entity). */
    private static BlockPos masterPos(BlockPos pos, BlockState state) {
        return state.getValue(MAIN) ? pos : pos.relative(state.getValue(FACING).getOpposite());
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        BlockPos mp = masterPos(pos, state);
        if (!(level.getBlockEntity(mp) instanceof MasonsBenchBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (isBusy(level, mp, player)) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.is(BannerboundAntiquity.STONE_CHISEL.get())) {
            return tryStartChiseling(level, mp, player, be)
                ? ItemInteractionResult.sidedSuccess(level.isClientSide)
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (MasonsBenchBlockEntity.isBudgetMaterial(stack)) {
            if (!level.isClientSide) {
                Direction from = player.getDirection().getOpposite();
                int added = player.isSecondaryUseActive()
                    ? be.insertStack(stack, from)
                    : (be.insertOne(stack, from) ? 1 : 0);
                if (added > 0) {
                    if (!player.hasInfiniteMaterials()) stack.shrink(added);
                    level.playSound(null, mp, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.7F, 1.1F);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        BlockPos mp = masterPos(pos, state);
        if (!(level.getBlockEntity(mp) instanceof MasonsBenchBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (isBusy(level, mp, player)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide) {
            if (player.isSecondaryUseActive()) {
                if (be.removeLastEntry()) {
                    level.playSound(null, mp, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6F, 1.0F);
                }
            } else {
                ItemStack out = be.removeOne();
                if (!out.isEmpty()) {
                    giveOrDrop(player, out);
                    level.playSound(null, mp, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 0.9F);
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static boolean isBusy(Level level, BlockPos masterPos, Player player) {
        if (!level.isClientSide
                && com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(masterPos, player.getUUID())) {
            player.displayClientMessage(Component.translatable("bannerbound.workshop.station_busy")
                .withStyle(ChatFormatting.YELLOW), true);
            return true;
        }
        return false;
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static boolean tryStartChiseling(Level level, BlockPos masterPos, Player player,
                                             MasonsBenchBlockEntity be) {
        if (!be.hasBuildList()) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("bannerboundantiquity.masonry.empty_list")
                    .withStyle(ChatFormatting.YELLOW), true);
            }
            return false;
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            Masonry.startChiseling(sp, masterPos, be);
        }
        return true;
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())) {
            Direction facing = oldState.getValue(FACING);
            boolean main = oldState.getValue(MAIN);
            // Drops + session cleanup happen once, from the master side.
            if (main) {
                if (level.getBlockEntity(pos) instanceof MasonsBenchBlockEntity be) {
                    be.dropStones(level);
                }
                Masonry.abortSessionAt(pos);
            }
            // Tear down the other half (terminates: by the time this fires this cell is already air).
            BlockPos other = main ? pos.relative(facing) : pos.relative(facing.getOpposite());
            if (level.getBlockState(other).is(this)) {
                level.removeBlock(other, false);
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
