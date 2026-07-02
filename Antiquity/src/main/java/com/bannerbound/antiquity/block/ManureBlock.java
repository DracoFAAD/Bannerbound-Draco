package com.bannerbound.antiquity.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A flat pat of manure that domesticated livestock leave on the floor of a pen (placed by
 * {@code ManureEvents}). It has no collision — animals and the herder walk right over it — but it
 * <b>fouls the pen</b>: every manure block within a breeding pair's radius shaves their breed chance
 * (Core's {@code BreedingEvents}, recognised by the {@code #bannerbound:manure} tag). Clearing it
 * (faster with a shovel via {@code minecraft:mineable/shovel}) yields {@code dung}, the bone-meal-style
 * fertilizer. Herders muck it out automatically as pen upkeep.
 *
 * <p>Sits in the air cell on top of a floor block (like a carpet) and pops off if its support is removed
 * or isn't a solid top face — so it never floats and never sits on a fence.
 */
public class ManureBlock extends Block {
    public static final MapCodec<ManureBlock> CODEC = simpleCodec(ManureBlock::new);

    /** A low, flat pat — outline only (collision is empty via {@code noCollission}). */
    private static final VoxelShape SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 2.0, 15.0);

    public ManureBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<ManureBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return !state.canSurvive(level, pos) ? Blocks.AIR.defaultBlockState()
            : super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }
}
