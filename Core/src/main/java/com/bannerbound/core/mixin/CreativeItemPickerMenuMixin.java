package com.bannerbound.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bannerbound.core.creative.CreativeSections;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;

/**
 * Keeps {@link CreativeSections#currentRow} in sync with the creative scrollbar so the renderer can tell
 * which section banners are currently on screen.
 */
@Mixin(CreativeModeInventoryScreen.ItemPickerMenu.class)
public abstract class CreativeItemPickerMenuMixin {

    @Shadow
    protected abstract int getRowIndexForScroll(float scroll);

    @Inject(method = "scrollTo", at = @At("HEAD"))
    private void bannerbound$trackRow(float scroll, CallbackInfo ci) {
        CreativeSections.currentRow = this.getRowIndexForScroll(scroll);
    }
}
