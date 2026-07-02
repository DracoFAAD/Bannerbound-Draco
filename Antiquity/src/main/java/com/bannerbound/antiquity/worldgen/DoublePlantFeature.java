package com.bannerbound.antiquity.worldgen;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;

/**
 * Worldgen feature that places a two-block {@link DoublePlantBlock} (BOTH halves) at the origin. Vanilla
 * {@code simple_block} only sets the named (lower) state, leaving the upper half missing and the lower
 * able to break on its next survival check — so a tall modded plant needs this. Configured with the
 * standard {@link SimpleBlockConfiguration} ({@code to_place} = the lower state); the upper is derived.
 */
public class DoublePlantFeature extends Feature<SimpleBlockConfiguration> {
    public DoublePlantFeature(Codec<SimpleBlockConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<SimpleBlockConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        BlockPos pos = ctx.origin();
        if (!level.isEmptyBlock(pos) || !level.isEmptyBlock(pos.above())) {
            return false;
        }
        BlockState lower = ctx.config().toPlace().getState(ctx.random(), pos);
        if (!lower.canSurvive(level, pos)) {
            return false;
        }
        DoublePlantBlock.placeAt(level, lower, pos, 2);
        return true;
    }
}
