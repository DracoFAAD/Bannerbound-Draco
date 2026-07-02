package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.DryingRackBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A Drying Rack — a primitive line, carved from a log with a bone blade (one block class per wood;
 * see {@code BannerboundAntiquity.DRYING_RACK_*}). Right-click with a dryable item to hang it on a
 * free spot, right-click empty-handed to take the most-recent spot back. Each rack has four spots
 * (see {@link DryingRackBlockEntity}); the {@link com.bannerbound.antiquity.client.DryingRackRenderer}
 * draws the hung items and their drying cross-fade.
 *
 * <p>Two adjacent racks of the same wood + facing render as one connected "double" (chest-style:
 * {@link ChestType} {@code LEFT}/{@code RIGHT}); this is purely cosmetic — each block keeps its own
 * four spots and block entity. The line runs along the facing's clockwise axis.
 */
public class DryingRackBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<DryingRackBlock> CODEC = simpleCodec(DryingRackBlock::new);
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<ChestType> TYPE =
        BlockStateProperties.CHEST_TYPE;

    // A single narrow box around the frame, oriented along the line (the facing's clockwise axis):
    // full along the line, shallow across it. Picked per-facing so it hugs the rack at any rotation.
    private static final VoxelShape OUTLINE_X = Block.box(0, 0, 4, 16, 16, 12); // line runs E–W
    private static final VoxelShape OUTLINE_Z = Block.box(4, 0, 0, 12, 16, 16); // line runs N–S

    public DryingRackBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(TYPE, ChestType.SINGLE));
    }

    @Override
    protected MapCodec<DryingRackBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TYPE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(FACING).getClockWise().getAxis() == Direction.Axis.X ? OUTLINE_X : OUTLINE_Z;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty(); // a frame — walk through it
    }

    // ─── Chest-style connection (cosmetic double) ──────────────────────────────────────────────────

    private boolean isPartner(LevelAccessor level, BlockPos pos, Direction facing) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() == this && s.getValue(FACING) == facing;
    }

    /** Pair adjacent racks deterministically from the run's counter-clockwise end: (0,1),(2,3),…
     *  Even index with a clockwise partner = LEFT, the next = RIGHT; a leftover stays SINGLE. */
    private ChestType connectionType(LevelAccessor level, BlockPos pos, Direction facing) {
        Direction cw = facing.getClockWise();
        Direction ccw = facing.getCounterClockWise();
        int offset = 0;
        BlockPos p = pos;
        while (isPartner(level, p.relative(ccw), facing)) {
            p = p.relative(ccw);
            offset++;
        }
        if (offset % 2 == 1) return ChestType.RIGHT; // second half of a pair started to the CCW
        return isPartner(level, pos.relative(cw), facing) ? ChestType.LEFT : ChestType.SINGLE;
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction facing = ctx.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing)
            .setValue(TYPE, connectionType(ctx.getLevel(), ctx.getClickedPos(), facing));
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        Direction facing = state.getValue(FACING);
        // Only the width-axis neighbours (the line direction) affect the connection.
        if (direction.getAxis() == facing.getClockWise().getAxis()) {
            return state.setValue(TYPE, connectionType(level, pos, facing));
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    /** Recompute the connection for the rack just placed/carved at {@code pos} (its neighbours are
     *  refreshed by the UPDATE_ALL set-block, but it never updates itself). */
    public static void refreshSelf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DryingRackBlock rack) {
            Direction facing = state.getValue(FACING);
            ChestType type = rack.connectionType(level, pos, facing);
            if (type != state.getValue(TYPE)) {
                level.setBlock(pos, state.setValue(TYPE, type), Block.UPDATE_CLIENTS);
            }
        }
    }

    // ─── Block entity ──────────────────────────────────────────────────────────────────────────────

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DryingRackBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        return type == BannerboundAntiquity.DRYING_RACK_BE.get()
            ? (lvl, pos, st, be) -> DryingRackBlockEntity.tick(lvl, pos, st, (DryingRackBlockEntity) be)
            : null;
    }

    @Nullable
    private static DryingRackBlockEntity rack(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof DryingRackBlockEntity be ? be : null;
    }

    // ─── Interaction ─────────────────────────────────────────────────────────────────────────────

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        DryingRackBlockEntity be = rack(level, pos);
        if (be == null || !be.canAccept(stack)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide && be.hang(stack)) {
            if (!player.hasInfiniteMaterials()) stack.shrink(1);
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.GRASS_PLACE,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.7F, 1.1F);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                              Player player, BlockHitResult hit) {
        DryingRackBlockEntity be = rack(level, pos);
        if (be == null || be.isEmpty()) return InteractionResult.PASS;
        if (!level.isClientSide) {
            returnToPlayer(player, be.takeLast());
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.ITEM_FRAME_REMOVE_ITEM,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.7F, 1.0F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Return a removed item to the player WITHOUT filling the held hotbar slot — otherwise the
     *  item lands in hand and the next right-click just re-hangs it (the "last item won't leave" bug).
     *  Prefers merging, then any empty non-selected slot, then falls back to a normal add / drop. */
    private static void returnToPlayer(Player player, ItemStack out) {
        if (out.isEmpty()) return;
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        int sel = inv.selected;
        for (int i = 0; i < inv.items.size(); i++) {
            if (i == sel) continue;
            ItemStack cur = inv.items.get(i);
            if (!cur.isEmpty() && cur.getCount() < cur.getMaxStackSize()
                    && ItemStack.isSameItemSameComponents(cur, out)) {
                cur.grow(out.getCount());
                return;
            }
        }
        for (int i = 0; i < inv.items.size(); i++) {
            if (i == sel) continue;
            if (inv.items.get(i).isEmpty()) {
                inv.items.set(i, out);
                return;
            }
        }
        if (!inv.add(out)) player.drop(out, false);
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof DryingRackBlockEntity be) {
                for (ItemStack drop : be.dropContents()) Block.popResource(level, pos, drop);
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
