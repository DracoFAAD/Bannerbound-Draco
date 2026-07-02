package com.bannerbound.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bannerbound.core.client.creative.CreativeSectionRenderer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;

/**
 * Draws the section banners over the blank rows of the creative item grid (see
 * {@link CreativeSectionRenderer}). Runs at the tail of the screen render so banners sit on top of the
 * empty cells of their row.
 *
 * <p>The panel origin comes from NeoForge's public {@code getGuiLeft()}/{@code getGuiTop()} accessors
 * rather than {@code @Shadow}ing {@code leftPos}/{@code topPos}: those fields are declared on the
 * superclass {@code AbstractContainerScreen}, and shadowing an inherited field fails to resolve here.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenMixin {

    @Shadow
    private static CreativeModeTab selectedTab;

    @Inject(method = "render", at = @At("TAIL"))
    private void bannerbound$renderSections(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                            CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        CreativeSectionRenderer.render(selectedTab, graphics, self.getGuiLeft(), self.getGuiTop(), mouseX, mouseY);
    }
}
