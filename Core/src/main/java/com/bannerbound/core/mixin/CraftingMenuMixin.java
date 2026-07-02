package com.bannerbound.core.mixin;

import org.jetbrains.annotations.ApiStatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bannerbound.core.event.UnknownItemBlocker;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/**
 * Server-authoritative: after vanilla computes the crafting result, clear it if the player's civ
 * doesn't know yet either (a) any input item, or (b) the result item itself — you can't craft
 * <i>toward</i> something you haven't researched. Covers both the 3x3 crafting table and the 2x2
 * inventory grid (both use this static helper).
 */
@Mixin(CraftingMenu.class)
@ApiStatus.Internal
public class CraftingMenuMixin {
    @Inject(method = "slotChangedCraftingGrid", at = @At("TAIL"))
    private static void bannerbound$clearUnknownResult(
            AbstractContainerMenu menu,
            Level level,
            Player player,
            CraftingContainer container,
            ResultContainer result,
            RecipeHolder<CraftingRecipe> recipe,
            CallbackInfo ci) {
        if (level == null || level.isClientSide) {
            return;
        }
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && UnknownItemBlocker.isUnknownForPlayer(sp, stack.getItem())) {
                result.setItem(0, ItemStack.EMPTY);
                return;
            }
        }
        // Gate on the result too: a recipe whose output the civ hasn't researched yet can't be made,
        // even when every ingredient is already known.
        ItemStack output = result.getItem(0);
        if (!output.isEmpty() && UnknownItemBlocker.isUnknownForPlayer(sp, output.getItem())) {
            result.setItem(0, ItemStack.EMPTY);
        }
    }
}
