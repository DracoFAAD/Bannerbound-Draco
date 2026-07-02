package com.bannerbound.core.mixin;

import org.jetbrains.annotations.ApiStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.bannerbound.core.client.ClientOreState;
import com.bannerbound.core.api.research.OreDisguise;

import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Swap the baked model for any ore that's currently disguised for the local player. The chunk
 * mesher calls {@code getBlockModel(BlockState)} during meshing — by returning the disguise's
 * model (e.g. stone), the chunk visually contains stone where iron ore actually sits. When the
 * player's research unlocks the reveal flag, ClientResearchState triggers a chunk re-mesh and
 * the real model is used.
 */
@Mixin(BlockModelShaper.class)
@ApiStatus.Internal
public class BlockModelShaperMixin {
    @Inject(method = "getBlockModel", at = @At("HEAD"), cancellable = true)
    private void bannerbound$swapDisguisedOre(BlockState state, CallbackInfoReturnable<BakedModel> cir) {
        if (!ClientOreState.isCurrentlyDisguised(state.getBlock())) {
            return;
        }
        OreDisguise disguise = ClientOreState.getDisguiseFor(state.getBlock());
        if (disguise == null) {
            return;
        }
        BakedModel disguiseModel = ClientOreState.getCachedDisguiseModel(disguise);
        if (disguiseModel != null) {
            cir.setReturnValue(disguiseModel);
        }
    }
}
