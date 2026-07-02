package com.bannerbound.core.mixin;

import org.jetbrains.annotations.ApiStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.bannerbound.core.client.UnknownItemHelper;

import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Swap the baked model for any unknown item to bannerbound:item/question_mark so the item
 * renders as a question mark in inventory, hotbar, as a dropped entity, and in third-person hands.
 */
@Mixin(ItemRenderer.class)
@ApiStatus.Internal
public class ItemRendererMixin {
    @Inject(method = "getModel(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("RETURN"), cancellable = true)
    private void bannerbound$swapModelForUnknown(ItemStack stack, Level level, LivingEntity entity, int seed,
                                             CallbackInfoReturnable<BakedModel> cir) {
        // Bypass flag set by render code that explicitly wants the real model (e.g. research
        // tooltip's "Unlocked Items:" grid — showing question marks there defeats the point).
        if (UnknownItemHelper.isBypassActive()) {
            return;
        }
        if (!UnknownItemHelper.isUnknownForLocalPlayer(stack)) {
            return;
        }
        BakedModel qm = UnknownItemHelper.getQuestionMarkModel();
        if (qm != null) {
            cir.setReturnValue(qm);
        }
    }
}
