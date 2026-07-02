package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.AntiquityEvents;
import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.TanningRackBlockEntity;
import com.bannerbound.antiquity.tannery.Hides;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Tanning Rack — a tier-2 tannery work block. The model is 2 wide and 2 tall, so the block is a
 * <b>2×2 multiblock</b>: the bottom block on the rack's left (the {@link #PART} 0 "master", facing
 * the player) carries the single {@link TanningRackBlockEntity}; the other three cells are empty
 * shells that render nothing and resolve every interaction back to the master — so the rack holds
 * exactly one hide at a time. Lay a raw hide, right-click with a knife to scrape, cure at a clay
 * tank, then lay the cured hide back to dry into leather.
 */
public class TanningRackBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<TanningRackBlock> CODEC = simpleCodec(TanningRackBlock::new);
    /** How many knife swipes the scrape minigame takes (non-skill — pure duration). */
    public static final int SCRAPE_SWIPES = 6;
    /** Cell within the 2×2: bit 0 = offset along the width (facing's clockwise), bit 1 = up. 0 = master. */
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 3);

    private static final VoxelShape OUTLINE = Block.box(0, 0, 0, 16, 16, 16);

    public TanningRackBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, 0));
    }

    @Override
    protected MapCodec<TanningRackBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return OUTLINE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty(); // a frame — walk through it
    }

    // ─── 2×2 cell geometry ────────────────────────────────────────────────────────────────────────

    private static boolean widthBit(int part) {
        return (part & 1) != 0;
    }

    private static boolean heightBit(int part) {
        return (part & 2) != 0;
    }

    /** The master (PART 0) position, resolved from any cell. */
    public static BlockPos masterPos(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof TanningRackBlock)) return pos;
        int part = state.getValue(PART);
        Direction width = state.getValue(FACING).getClockWise();
        BlockPos p = pos;
        if (widthBit(part)) p = p.relative(width.getOpposite());
        if (heightBit(part)) p = p.below();
        return p;
    }

    private static BlockPos cellPos(BlockPos master, Direction facing, int part) {
        BlockPos p = master;
        if (widthBit(part)) p = p.relative(facing.getClockWise());
        if (heightBit(part)) p = p.above();
        return p;
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction facing = ctx.getHorizontalDirection().getOpposite();
        BlockPos base = ctx.getClickedPos();
        Level level = ctx.getLevel();
        for (int part = 1; part < 4; part++) {
            BlockPos cell = cellPos(base, facing, part);
            if (level.isOutsideBuildHeight(cell) || !level.getBlockState(cell).canBeReplaced(ctx)) {
                return null; // not enough room for the full 2×2
            }
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, 0);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        if (level.isClientSide) return;
        Direction facing = state.getValue(FACING);
        for (int part = 1; part < 4; part++) {
            level.setBlock(cellPos(pos, facing, part), state.setValue(PART, part), Block.UPDATE_ALL);
        }
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == 0 ? new TanningRackBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (state.getValue(PART) != 0 || type != BannerboundAntiquity.TANNING_RACK_BE.get()) return null;
        return (lvl, pos, st, be) -> TanningRackBlockEntity.tick(lvl, pos, st, (TanningRackBlockEntity) be);
    }

    @Nullable
    private static TanningRackBlockEntity master(Level level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(masterPos(pos, state)) instanceof TanningRackBlockEntity be ? be : null;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        TanningRackBlockEntity be = master(level, pos, state);
        if (be == null) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        BlockPos mPos = masterPos(pos, state);
        // Lay a raw hide on an empty rack — unless an NPC tanner has the station claimed.
        if (be.getPhase() == TanningRackBlockEntity.Phase.EMPTY && Hides.isRawHide(stack.getItem())) {
            if (stationBusy(level, mPos, player)) return ItemInteractionResult.sidedSuccess(level.isClientSide);
            if (!level.isClientSide && be.placeRaw(stack) && !player.hasInfiniteMaterials()) stack.shrink(1);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        // Lay a cured hide on an empty rack → starts drying (also blocked while an NPC works it).
        if (be.getPhase() == TanningRackBlockEntity.Phase.EMPTY
                && stack.is(BannerboundAntiquity.CURED_HIDE.get())) {
            if (stationBusy(level, mPos, player)) return ItemInteractionResult.sidedSuccess(level.isClientSide);
            if (!level.isClientSide && be.placeCured() && !player.hasInfiniteMaterials()) stack.shrink(1);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        // Knife on a raw hide → open the scrape minigame (driven from the master cell).
        if (be.isRaw() && stack.is(AntiquityEvents.CUTTING_TOOLS)) {
            if (!level.isClientSide && player instanceof ServerPlayer sp) {
                if (com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(mPos, sp.getUUID())) {
                    showBusy(player);
                } else {
                    com.bannerbound.antiquity.Tannery.startSession(sp, mPos);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                              Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        TanningRackBlockEntity be = master(level, pos, state);
        if (be == null || be.getPhase() == TanningRackBlockEntity.Phase.EMPTY) return InteractionResult.PASS;
        // Don't let a player yank the hide off a rack an NPC tanner is mid-craft on.
        if (stationBusy(level, masterPos(pos, state), player)) return InteractionResult.CONSUME;
        ItemStack out = be.retrieve();
        if (!out.isEmpty() && !player.getInventory().add(out)) player.drop(out, false);
        return InteractionResult.CONSUME;
    }

    /** True (and warns the player) when the rack work slot is locked by another worker — an NPC tanner
     *  mid-craft, or another player's scrape session. Mirrors the pottery slab's station guard. */
    private static boolean stationBusy(Level level, BlockPos masterPos, Player player) {
        if (level.isClientSide) return false;
        if (com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(masterPos, player.getUUID())) {
            showBusy(player);
            return true;
        }
        return false;
    }

    private static void showBusy(Player player) {
        player.displayClientMessage(net.minecraft.network.chat.Component
            .translatable("bannerbound.workshop.station_busy")
            .withStyle(net.minecraft.ChatFormatting.YELLOW), true);
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())) {
            BlockPos mPos = masterPos(pos, oldState);
            Direction facing = oldState.getValue(FACING);
            com.bannerbound.antiquity.Tannery.abortSessionAt(mPos);
            if (!level.isClientSide && level.getBlockEntity(mPos) instanceof TanningRackBlockEntity be) {
                ItemStack out = be.retrieve();
                if (!out.isEmpty()) Block.popResource(level, mPos, out);
            }
            // Tear down the other three cells (suppress their drops so breaking yields one rack).
            for (int part = 0; part < 4; part++) {
                BlockPos cell = cellPos(mPos, facing, part);
                if (cell.equals(pos)) continue;
                if (level.getBlockState(cell).getBlock() instanceof TanningRackBlock) {
                    level.setBlock(cell, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
