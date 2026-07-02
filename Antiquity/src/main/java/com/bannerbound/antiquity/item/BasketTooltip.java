package com.bannerbound.antiquity.item;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Data-side tooltip marker for a picked-up basket: carries the stored contents from the item's
 * {@code BASKET_CONTENTS} component to the client renderer ({@code ClientBasketTooltip}), which draws
 * them as a slot grid like a bundle. Returned from {@link BasketBlockItem#getTooltipImage}.
 */
public record BasketTooltip(ItemContainerContents contents) implements TooltipComponent {
}
