package com.bannerbound.core.mixin;

import org.jetbrains.annotations.ApiStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.bannerbound.core.client.UnknownItemHelper;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Client-only mixin: when an item is unknown to the local player's civ, override the hover name to
 * "Unknown item" (red). This drives the hotbar selector name, anvil display, chat hover, and the
 * tooltip's first line — basically every surface that asks ItemStack what to call itself.
 */
@Mixin(ItemStack.class)
@ApiStatus.Internal
public class ItemStackMixin {
    @Inject(method = "getHoverName", at = @At("HEAD"), cancellable = true)
    private void bannerbound$obfuscateHoverName(CallbackInfoReturnable<Component> cir) {
        ItemStack self = (ItemStack) (Object) this;
        if (UnknownItemHelper.isUnknownForLocalPlayer(self)) {
            cir.setReturnValue(UnknownItemHelper.unknownName());
            return;
        }
        Component customLanguageName = com.bannerbound.core.client.ClientLanguageState.itemName(self);
        if (customLanguageName != null) {
            cir.setReturnValue(customLanguageName);
        }
    }
}
