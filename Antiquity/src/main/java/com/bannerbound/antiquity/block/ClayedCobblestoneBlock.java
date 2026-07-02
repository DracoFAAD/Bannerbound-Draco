package com.bannerbound.antiquity.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Cobblestone packed with clay — the building block of the Kiln. Created by right-clicking a
 * cobblestone block with a clay ball (see {@code AntiquityEvents}); eight of these arranged in a
 * 2×2×2 cube form a Kiln. Placing one directly also checks for a completed cube, so a kiln can be
 * built from pre-made clayed cobblestone too.
 */
public class ClayedCobblestoneBlock extends Block {
    public static final MapCodec<ClayedCobblestoneBlock> CODEC = simpleCodec(ClayedCobblestoneBlock::new);

    public ClayedCobblestoneBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<ClayedCobblestoneBlock> codec() {
        return CODEC;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) {
            KilnFormation.tryForm(level, pos, placer instanceof Player player ? player : null);
        }
    }
}
