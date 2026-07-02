package com.bannerbound.antiquity.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A two-block-tall cross-model plant — a lower + upper half, like a vanilla rose bush / peony — the
 * base for taller poison/remedy herbs such as the curare vine. Placement, survival and "break both
 * halves together" behaviour come from {@link DoublePlantBlock}; we only swap in a slim hover outline
 * and (via the blockstate) a cutout cross model per {@code half}. No collision.
 */
public class TallForagePlantBlock extends DoublePlantBlock {
    public static final MapCodec<TallForagePlantBlock> CODEC = simpleCodec(TallForagePlantBlock::new);

    /** Slim selection/outline box per half (no collision) — tune to the art. */
    private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 16.0, 13.0);

    public TallForagePlantBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public MapCodec<? extends DoublePlantBlock> codec() {
        return CODEC;
    }
}
