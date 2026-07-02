package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.BloomeryBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The formed Bloomery — a 1×1×2 multiblock built by stacking two mud-brick blocks and right-
 * clicking the lower one with a block of coal ({@code AntiquityEvents} handles that formation).
 * Occupies two block positions ({@link DoubleBlockHalf} LOWER/UPPER); the lower carries the
 * block entity and its renderer draws the whole model.
 * <ul>
 *   <li>Right-click with an item → puts the whole stack inside (door must be open, bloomery empty).</li>
 *   <li>Right-click empty-handed → takes the held stack back out (door must be open).</li>
 *   <li>Right-click with flint &amp; steel → ignites it (door must be open).</li>
 *   <li>Shift-right-click empty-handed → toggles the door.</li>
 * </ul>
 * Fire Sticks and Bellows are handled by the items themselves (a held charge), so this block
 * skips its own interactions for them — aiming at the bloomery starts the wind-up directly.
 */
public class BloomeryBlock extends Block implements EntityBlock {
    public static final MapCodec<BloomeryBlock> CODEC = simpleCodec(BloomeryBlock::new);
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public BloomeryBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(HALF, DoubleBlockHalf.LOWER)
            .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<BloomeryBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HALF, FACING);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // Only the lower segment carries the block entity (and its renderer).
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new BloomeryBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        if (state.getValue(HALF) != DoubleBlockHalf.LOWER
                || type != BannerboundAntiquity.BLOOMERY_BE.get()) {
            return null;
        }
        return (lvl, pos, st, be) -> BloomeryBlockEntity.tick(lvl, pos, st, (BloomeryBlockEntity) be);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        BloomeryBlockEntity controller = getController(level, pos, state);
        if (controller == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Flint & steel → ignite the bloomery (door must be open).
        if (stack.is(Items.FLINT_AND_STEEL)) {
            if (!controller.isOpen()) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!level.isClientSide) {
                controller.ignite();
                stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Fire Sticks charge in the hand — skip every block interaction (including the default
        // empty-hand one) so the item's own use() runs and the wind-up starts right here.
        if (stack.is(BannerboundAntiquity.FIRE_STICKS.get())) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }

        // Any other item → put the whole held stack inside (door open, bloomery empty).
        if (stack.isEmpty() || !controller.isOpen() || !controller.getHeldItem().isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) {
            controller.insert(stack.copy());
            if (!player.hasInfiniteMaterials()) {
                player.setItemInHand(hand, ItemStack.EMPTY);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!player.getMainHandItem().isEmpty()) {
            return InteractionResult.PASS; // an unhandled item fell through useItemOn
        }
        BloomeryBlockEntity controller = getController(level, pos, state);
        if (controller == null) {
            return InteractionResult.PASS;
        }
        // Shift → toggle the door.
        if (player.isSecondaryUseActive()) {
            controller.toggle();
            return InteractionResult.CONSUME;
        }
        // Plain + empty hand → take the held stack back out (only through an open door).
        if (controller.isOpen() && !controller.getHeldItem().isEmpty()) {
            giveOrDrop(player, controller.extract());
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    /** The block entity on the lower segment, resolving up/down from whichever segment was hit. */
    private static BloomeryBlockEntity getController(Level level, BlockPos pos, BlockState state) {
        BlockPos lower = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        return level.getBlockEntity(lower) instanceof BloomeryBlockEntity be ? be : null;
    }

    /** Resolves the bloomery's controller from any block position it occupies, or {@code null}. */
    @Nullable
    public static BloomeryBlockEntity getController(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof BloomeryBlock ? getController(level, pos, state) : null;
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())) {
            BlockPos partner = oldState.getValue(HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            // The first segment to break clears its partner and returns the two mud bricks.
            if (level.getBlockState(partner).is(this)) {
                if (!level.isClientSide) {
                    Block.popResource(level, pos, new ItemStack(Items.MUD_BRICKS, 2));
                    if (level.getBlockEntity(
                            oldState.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : partner)
                            instanceof BloomeryBlockEntity be && !be.getHeldItem().isEmpty()) {
                        Block.popResource(level, pos, be.getHeldItem());
                    }
                }
                level.setBlock(partner, Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
