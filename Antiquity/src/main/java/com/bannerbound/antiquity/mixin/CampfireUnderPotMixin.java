package com.bannerbound.antiquity.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bannerbound.antiquity.block.StoneCookingPotBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Suppress a campfire's client display FX — its tall smoke column, spark/lava pops, and crackle — while
 * a {@link StoneCookingPotBlock} sits directly on top. The pot is rendered down inside the flames, so
 * the campfire's own smoke would pour straight up through the pot (and its flame would peek through);
 * the pot supplies its own simmer particles instead. Plain campfires (no pot) are untouched. Client
 * display tick only; {@code require = 0} so a mappings shift just no-ops rather than crashing.
 */
@Mixin(CampfireBlock.class)
public class CampfireUnderPotMixin {
    @Inject(method = "animateTick", at = @At("HEAD"), cancellable = true, require = 0)
    private void bannerbound$noFxUnderPot(BlockState state, Level level, BlockPos pos,
                                          RandomSource random, CallbackInfo ci) {
        if (level.getBlockState(pos.above()).getBlock() instanceof StoneCookingPotBlock) {
            ci.cancel();
        }
    }
}
