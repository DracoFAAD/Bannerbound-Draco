package com.bannerbound.core.mixin;

import java.util.Collection;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bannerbound.core.creative.CreativeSections;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * After a creative tab finishes building its contents, re-lay any section-enabled tab into labelled
 * bands (see {@link CreativeSections}). We replace the two backing collections wholesale because
 * vanilla stores them in an {@code ItemStackLinkedSet}, which dedupes by type+components and cannot
 * hold the blank {@link ItemStack#EMPTY} spacer rows the banners are drawn over.
 *
 * <p>Tabs without registered sections are left exactly as vanilla built them.
 */
@Mixin(CreativeModeTab.class)
public class CreativeModeTabMixin {

    @Shadow
    private Collection<ItemStack> displayItems;
    @Shadow
    private Set<ItemStack> displayItemsSearchTab;

    @Inject(method = "buildContents", at = @At("TAIL"))
    private void bannerbound$sectionize(CreativeModeTab.ItemDisplayParameters params, CallbackInfo ci) {
        CreativeModeTab self = (CreativeModeTab) (Object) this;
        CreativeSections.TabSections ts = CreativeSections.forResolvedTab(self);
        if (ts == null) {
            return;
        }
        CreativeSections.Built built = CreativeSections.layout(ts, this.displayItems);
        this.displayItems = built.display;
        this.displayItemsSearchTab = built.search;
    }
}
