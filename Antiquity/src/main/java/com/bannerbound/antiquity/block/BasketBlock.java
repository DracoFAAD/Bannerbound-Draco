package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.BasketBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Basket — a 9-slot storage block. The body is a normal JSON block model; a block entity
 * renderer ({@code BasketRenderer}) additionally draws whatever sits in the first slot on top.
 * Right-click opens the 3×3 storage screen. Rotates to face the player on placement.
 */
public class BasketBlock extends Block implements EntityBlock {
    public static final MapCodec<BasketBlock> CODEC = simpleCodec(BasketBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    // Pixel-perfect collision/outline traced from the JSON model (basket.json): the woven floor
    // slab + the four walls, at the model's true extents. The thin side handle-holes are filled —
    // they're a 1px decorative detail, not worth carving into collision. Built for the default
    // NORTH facing, then rotated to match the blockstate's per-facing y-spin.
    //   - floor slab: x 2→14, y 0→1, z 5→12
    //   - front (north) wall: z 3→4, y 2→8 (the front is the low, open-to-reach-in side)
    //   - back (south) wall: tall panel z 13→14 y 2→8, plus its low rim z 12→13 y 1→2
    //   - side (west/east) walls: x 1→2 / 14→15, z 4→13, y 1→8
    private static final VoxelShape SHAPE_NORTH = Shapes.or(
        Block.box(2.0, 0.0, 5.0, 14.0, 1.0, 12.0),   // woven floor slab
        Block.box(2.0, 2.0, 3.0, 14.0, 8.0, 4.0),    // front wall   (north, low Z)
        Block.box(2.0, 1.0, 12.0, 14.0, 2.0, 13.0),  // back rim     (south, low)
        Block.box(2.0, 2.0, 13.0, 14.0, 8.0, 14.0),  // back wall    (south, high Z)
        Block.box(1.0, 1.0, 4.0, 2.0, 8.0, 13.0),    // left wall    (west, low X)
        Block.box(14.0, 1.0, 4.0, 15.0, 8.0, 13.0)); // right wall   (east, high X)
    private static final VoxelShape SHAPE_EAST = rotateY(SHAPE_NORTH, 1);
    private static final VoxelShape SHAPE_SOUTH = rotateY(SHAPE_NORTH, 2);
    private static final VoxelShape SHAPE_WEST = rotateY(SHAPE_NORTH, 3);

    /** Rotates a shape {@code quarterTurns} × 90° clockwise about the Y axis (block centre), matching
     *  the blockstate's {@code "y"} model rotation (north=0, east=90, south=180, west=270). */
    private static VoxelShape rotateY(VoxelShape shape, int quarterTurns) {
        VoxelShape[] buf = { shape, Shapes.empty() };
        for (int i = 0; i < quarterTurns; i++) {
            VoxelShape src = buf[0];
            buf[1] = Shapes.empty();
            src.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
                buf[1] = Shapes.or(buf[1], Shapes.box(1 - maxZ, minY, minX, 1 - minZ, maxY, maxX)));
            buf[0] = buf[1];
        }
        return buf[0];
    }

    public BasketBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<BasketBlock> codec() {
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
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BasketBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case EAST -> SHAPE_EAST;
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            default -> SHAPE_NORTH;
        };
    }

    /**
     * The woven walls aren't a full block, so vanilla classifies the cell as walkable and NPCs path
     * straight onto the basket and snag. Mark it un-pathfindable so every pathfinder routes around it.
     * (Previously special-cased by id in {@code CitizenNodeEvaluator}; now declared on the block itself.)
     */
    @Override
    protected boolean isPathfindable(BlockState state,
                                     net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        // Sneak + use (empty hand): instantly pick the whole basket up — block and contents — straight
        // into the player's hands. No mining delay, nothing spilled; placing it back restores the items
        // (see setPlacedBy). Falls through to opening the storage screen on a normal right-click.
        if (player.isSecondaryUseActive()) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            if (level.getBlockEntity(pos) instanceof BasketBlockEntity basket) {
                ItemStack stack = new ItemStack(this);
                if (!basket.isEmpty()) {
                    stack.set(BannerboundAntiquity.BASKET_CONTENTS.get(),
                        ItemContainerContents.fromItems(basket.getItems()));
                }
                basket.markPickup();              // tell onRemove not to spill the contents
                level.levelEvent(2001, pos, Block.getId(state)); // break particles + sound, for feel
                level.removeBlock(pos, false);
                if (!player.addItem(stack)) {
                    player.drop(stack, false);
                }
            }
            return InteractionResult.CONSUME;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof BasketBlockEntity basket) {
            serverPlayer.openMenu(basket, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())) {
            // A sneak-picked-up basket carries its contents in the item it became (see useWithoutItem),
            // so don't also spill them loose here. A normal break leaves the flag unset and drops them.
            if (level.getBlockEntity(pos) instanceof BasketBlockEntity basket && !basket.isPickupRequested()) {
                Containers.dropContents(level, pos, basket.getDroppableInventory());
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }

    /** Restores a carried-contents basket's items into the freshly-placed block entity. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        ItemContainerContents contents = stack.get(BannerboundAntiquity.BASKET_CONTENTS.get());
        if (contents != null && !level.isClientSide()
                && level.getBlockEntity(pos) instanceof BasketBlockEntity basket) {
            basket.loadFromContents(contents);
        }
    }
}
