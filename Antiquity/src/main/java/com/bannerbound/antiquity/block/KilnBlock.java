package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.KilnBlockEntity;
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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The formed Kiln — a 2×2×2 multiblock built by claying eight cobblestone into a cube
 * ({@code KilnFormation} handles the formation). Occupies eight block positions; {@link #PART}
 * encodes each cell's offset from the controller (the min-corner, {@code PART == 0}), which carries
 * the block entity and whose {@link BlockEntityRenderer} draws the whole dome rotated to {@link #FACING}.
 * <p>
 * Mechanically the early-game cousin of the Bloomery — it has no door:
 * <ul>
 *   <li>Right-click with an item → puts the whole stack inside (kiln empty).</li>
 *   <li>Right-click empty-handed → takes the held stack back out.</li>
 *   <li>Right-click with flint &amp; steel → ignites it.</li>
 *   <li>Right-click with coal or charcoal while lit → stokes the fire (in place of the bloomery's bellows).</li>
 * </ul>
 * Fire Sticks are handled by the item itself (a held charge), so the block skips its own interaction
 * for them — aiming at the kiln starts the wind-up directly.
 */
public class KilnBlock extends Block implements EntityBlock {
    public static final MapCodec<KilnBlock> CODEC = simpleCodec(KilnBlock::new);
    /** Cell offset from the controller: {@code dx*4 + dy*2 + dz}, each of dx/dy/dz being 0 or 1. */
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 7);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** True while the fire is burning — drives the mouth's light emission (see {@link #lightEmission}). */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /** Mining the kiln is slow — it's a substantial fired-clay structure. Tune here. */
    public static final float DESTROY_TIME = 8.0F;
    /** Light level the mouth gives off while lit. */
    public static final int LIT_LIGHT = 13;

    public KilnBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(PART, 0)
            .setValue(FACING, Direction.NORTH)
            .setValue(LIT, false));
    }

    /**
     * Only the two bottom cells on the facing side — the mouth — give off light, and only while lit.
     * The rest of the dome stays dark, so the glow reads as coming from the opening. Wired in via
     * {@code BlockBehaviour.Properties.lightLevel(KilnBlock::lightEmission)} at registration.
     */
    public static int lightEmission(BlockState state) {
        if (!state.getValue(LIT)) {
            return 0;
        }
        int part = state.getValue(PART);
        int dx = (part >> 2) & 1;
        int dy = (part >> 1) & 1;
        int dz = part & 1;
        if (dy != 0) {
            return 0; // upper half never glows
        }
        Direction facing = state.getValue(FACING);
        boolean onMouthSide = switch (facing) {
            case NORTH -> dz == 0;
            case SOUTH -> dz == 1;
            case WEST -> dx == 0;
            case EAST -> dx == 1;
            default -> false;
        };
        return onMouthSide ? LIT_LIGHT : 0;
    }

    @Override
    protected MapCodec<KilnBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART, FACING, LIT);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // The whole dome is drawn by KilnRenderer (rotated about the 2×2×2 centre); nothing renders
        // in the chunk mesh. The controller's authored model is still baked, for the renderer + particles.
        return RenderShape.INVISIBLE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // Only the controller cell (PART 0, the min-corner) carries the block entity + renderer.
        return state.getValue(PART) == 0 ? new KilnBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        if (state.getValue(PART) != 0 || type != BannerboundAntiquity.KILN_BE.get()) {
            return null;
        }
        return (lvl, pos, st, be) -> KilnBlockEntity.tick(lvl, pos, st, (KilnBlockEntity) be);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        KilnBlockEntity controller = getController(level, pos, state);
        if (controller == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Flint & steel → ignite the kiln.
        if (stack.is(Items.FLINT_AND_STEEL)) {
            if (!level.isClientSide) {
                controller.ignite();
                stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Fire Sticks charge in the hand — skip every block interaction so the item's own use() runs
        // and the wind-up starts right here.
        if (stack.is(BannerboundAntiquity.FIRE_STICKS.get())) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }

        // Coal or charcoal → stoke an already-lit kiln (keeps the fire going, in place of the bellows).
        if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) {
            if (!controller.isLit()) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION; // light it first
            }
            if (!level.isClientSide && controller.stoke() && !player.hasInfiniteMaterials()) {
                stack.shrink(1);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Any other item → put the whole held stack inside (kiln empty).
        if (stack.isEmpty() || !controller.getHeldItem().isEmpty()) {
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
        KilnBlockEntity controller = getController(level, pos, state);
        if (controller == null) {
            return InteractionResult.PASS;
        }
        // Plain + empty hand → take the held stack back out.
        if (!controller.getHeldItem().isEmpty()) {
            giveOrDrop(player, controller.extract());
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    /** The controller cell's position, resolved from any cell via its {@link #PART} offset. */
    private static BlockPos controllerPos(BlockPos pos, BlockState state) {
        int part = state.getValue(PART);
        return pos.offset(-((part >> 2) & 1), -((part >> 1) & 1), -(part & 1));
    }

    /** The block entity on the controller cell, resolved from whichever cell was hit. */
    private static KilnBlockEntity getController(Level level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(controllerPos(pos, state)) instanceof KilnBlockEntity be ? be : null;
    }

    /** Resolves the kiln's controller from any block position it occupies, or {@code null}. */
    @Nullable
    public static KilnBlockEntity getController(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof KilnBlock ? getController(level, pos, state) : null;
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())) {
            BlockPos controller = controllerPos(pos, oldState);
            // Pop the held item (always — it's the player's, not tool-gated) and empty the BE so the
            // re-entrant onRemove from clearing the controller cell doesn't drop it twice. The 8
            // cobblestone come from the directly-broken cell's loot table (pickaxe-gated).
            if (!level.isClientSide && level.getBlockEntity(controller) instanceof KilnBlockEntity be) {
                ItemStack held = be.extract();
                if (!held.isEmpty()) {
                    Block.popResource(level, pos, held);
                }
            }
            // Tear down the other seven cells. The bounce terminates: each cleared cell's onRemove
            // sees its neighbours already air and stops.
            for (int dx = 0; dx < 2; dx++) {
                for (int dy = 0; dy < 2; dy++) {
                    for (int dz = 0; dz < 2; dz++) {
                        BlockPos cell = controller.offset(dx, dy, dz);
                        if (cell.equals(pos)) {
                            continue;
                        }
                        if (level.getBlockState(cell).is(this)) {
                            level.setBlock(cell, Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                        }
                    }
                }
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
