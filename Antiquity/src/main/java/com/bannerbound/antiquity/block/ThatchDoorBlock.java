package com.bannerbound.antiquity.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A thatch curtain "door" that does NOT swing: right-clicking just toggles it between
 * <b>closed</b> (a solid straw panel you can't walk through) and <b>open</b> (passable — no
 * collision). It reuses vanilla {@link DoorBlock} for the two-tall placement, breaking, redstone
 * and the {@code OPEN} toggle; only the shape is overridden so "open" removes collision instead of
 * sliding a leaf aside. The blockstate swaps the closed/open texture rather than rotating a hinge.
 *
 * <p>The collision panel is a thin slab on the facing-side edge, matching the visible model
 * (which is a 16×32×2 panel modelled on the north edge and rotated by facing in the blockstate).
 */
public class ThatchDoorBlock extends DoorBlock {

    // Thin 2px collision slabs matching exactly where the visible panel sits for each facing
    // (the blockstate rotates the north-edge model by facing to match vanilla door placement).
    private static final VoxelShape NORTH_EDGE = Block.box(0, 0, 0, 16, 16, 2);
    private static final VoxelShape SOUTH_EDGE = Block.box(0, 0, 14, 16, 16, 16);
    private static final VoxelShape WEST_EDGE  = Block.box(0, 0, 0, 2, 16, 16);
    private static final VoxelShape EAST_EDGE  = Block.box(14, 0, 0, 16, 16, 16);

    public ThatchDoorBlock(BlockSetType type, Properties properties) {
        super(type, properties);
    }

    private static VoxelShape panel(BlockState state) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> NORTH_EDGE; // y=0    → panel on north edge
            case NORTH -> SOUTH_EDGE; // y=180  → panel on south edge
            case EAST  -> WEST_EDGE;  // y=270  → panel on west edge
            case WEST  -> EAST_EDGE;  // y=90   → panel on east edge
            default    -> NORTH_EDGE;
        };
    }

    /** Outline/selection is always the panel, so you can still target an open door to close it. */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return panel(state);
    }

    /** Open → walk-through (no collision); closed → solid panel. */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(OPEN) ? Shapes.empty() : panel(state);
    }
}
