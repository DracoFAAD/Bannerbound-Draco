package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Carpentry;
import com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity;
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
import net.minecraft.world.item.Items;
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
 * Carpenter's Table — a 2-block multiblock: a MASTER cell (holds the block entity, renders the full
 * 32px-wide model that extends one block toward {@code FACING}) plus a SECONDARY cell at
 * {@code master + FACING} (renders nothing, just occupies + forwards interactions). Formed by placing
 * two logs and right-clicking with a saw (see {@code AntiquityEvents.onFormWoodworkingTable}); the item
 * places both halves bed-style. Interactions on either half resolve to the master's block entity.
 * <ul>
 *   <li>Right-click with logs → place one on the budget pile (sneak = the whole stack).</li>
 *   <li>Right-click with the saw → start the saw minigame (needs a queued list).</li>
 *   <li>Right-click empty-handed → take the last (uncommitted) log back; sneak = undo last queue entry.</li>
 * </ul>
 */
public class WoodworkingTableBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<WoodworkingTableBlock> CODEC = simpleCodec(WoodworkingTableBlock::new);
    /** True for the master cell (model + block entity); false for the secondary (north) cell. */
    public static final BooleanProperty MAIN = BooleanProperty.create("main");
    /** The tabletop slab (y 12–15px), shared by both cells — rotation-invariant, so no per-facing shape. */
    public static final VoxelShape SHAPE = Block.box(0.0, 12.0, 0.0, 16.0, 15.0, 16.0);

    public WoodworkingTableBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MAIN, true));
    }

    @Override
    protected MapCodec<WoodworkingTableBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MAIN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection(); // the table extends away from the player
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
        return state.getValue(MAIN) ? new WoodworkingTableBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (!state.getValue(MAIN) || type != BannerboundAntiquity.WOODWORKING_TABLE_BE.get()) return null;
        return (lvl, pos, st, be) ->
            WoodworkingTableBlockEntity.tick(lvl, pos, st, (WoodworkingTableBlockEntity) be);
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
        if (!(level.getBlockEntity(mp) instanceof WoodworkingTableBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (isBusy(level, mp, player)) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.is(BannerboundAntiquity.BONE_SAW.get())) {
            return tryStartSawing(level, mp, player, be)
                ? ItemInteractionResult.sidedSuccess(level.isClientSide)
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (WoodworkingTableBlockEntity.isBudgetMaterial(stack)) {
            if (!level.isClientSide) {
                Direction from = player.getDirection().getOpposite();
                int added = player.isSecondaryUseActive()
                    ? be.insertStack(stack, from)
                    : (be.insertOne(stack, from) ? 1 : 0);
                if (added > 0) {
                    if (!player.hasInfiniteMaterials()) stack.shrink(added);
                    level.playSound(null, mp, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.7F, 1.1F);
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
        if (!(level.getBlockEntity(mp) instanceof WoodworkingTableBlockEntity be)) {
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

    private static boolean tryStartSawing(Level level, BlockPos masterPos, Player player,
                                          WoodworkingTableBlockEntity be) {
        if (!be.hasBuildList()) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("bannerboundantiquity.carpentry.empty_list")
                    .withStyle(ChatFormatting.YELLOW), true);
            }
            return false;
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            Carpentry.startSawing(sp, masterPos, be);
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
                if (level.getBlockEntity(pos) instanceof WoodworkingTableBlockEntity be) {
                    be.dropLogs(level);
                }
                Block.popResource(level, pos, new ItemStack(Items.OAK_LOG, 2)); // the two structure logs
                Carpentry.abortSessionAt(pos);
            }
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
