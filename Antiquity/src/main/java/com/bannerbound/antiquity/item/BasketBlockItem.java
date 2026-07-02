package com.bannerbound.antiquity.item;

import java.util.Optional;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;

/**
 * The basket's item form. Behaves like a normal {@link BlockItem}, but when the stack carries the
 * contents of a sneak-broken basket ({@code BASKET_CONTENTS}) it offers a bundle-style slot grid as
 * its tooltip image (rendered by {@code ClientBasketTooltip}). A plain, empty basket has no such
 * component and shows the ordinary tooltip.
 */
public class BasketBlockItem extends BlockItem {
    public BasketBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        ItemContainerContents contents = stack.get(BannerboundAntiquity.BASKET_CONTENTS.get());
        if (contents == null || contents.nonEmptyStream().findAny().isEmpty()) {
            return super.getTooltipImage(stack);
        }
        return Optional.of(new BasketTooltip(contents));
    }
}
