package com.bannerbound.antiquity.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A cross-model ground plant that also takes root in SAND (plus the usual dirt/grass) — for desert
 * herbs like oleander, which the base {@link ForageFlowerBlock}'s {@link BushBlock} support (dirt/
 * farmland only) would never let grow. Otherwise identical: no collision, slim outline, cutout cross.
 */
public class SandForagePlantBlock extends BushBlock {
    public static final MapCodec<SandForagePlantBlock> CODEC = simpleCodec(SandForagePlantBlock::new);

    private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 13.0, 13.0);

    public SandForagePlantBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return super.mayPlaceOn(state, level, pos) || state.is(BlockTags.SAND);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected MapCodec<SandForagePlantBlock> codec() {
        return CODEC;
    }
}
