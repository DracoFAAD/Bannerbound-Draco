package com.bannerbound.antiquity.client;

import java.util.List;

import com.bannerbound.antiquity.item.BasketTooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Draws a picked-up basket's contents as a slot grid in its tooltip — the same look as a bundle,
 * reusing vanilla's bundle slot/background sprites (so no new textures). The grid is sized to the
 * number of occupied slots (a basket holds at most nine, so up to 3×3). Mirrors vanilla's
 * {@code ClientBundleTooltip}, minus the bundle's weight/"add-item" empty slot.
 */
@OnlyIn(Dist.CLIENT)
public class ClientBasketTooltip implements ClientTooltipComponent {
    private static final ResourceLocation BACKGROUND_SPRITE =
        ResourceLocation.withDefaultNamespace("container/bundle/background");
    private static final ResourceLocation SLOT_SPRITE =
        ResourceLocation.withDefaultNamespace("container/bundle/slot");
    private static final int SLOT_W = 18;
    private static final int SLOT_H = 20;
    private static final int BORDER = 1;
    private static final int MARGIN_Y = 4;

    private final List<ItemStack> items;

    public ClientBasketTooltip(BasketTooltip tooltip) {
        this.items = tooltip.contents().nonEmptyStream().toList();
    }

    private int columns() {
        return Math.max(1, (int) Math.ceil(Math.sqrt(items.size())));
    }

    private int rows() {
        return (int) Math.ceil((double) items.size() / columns());
    }

    private int backgroundWidth() {
        return columns() * SLOT_W + BORDER * 2;
    }

    private int backgroundHeight() {
        return rows() * SLOT_H + BORDER * 2;
    }

    @Override
    public int getWidth(Font font) {
        return backgroundWidth();
    }

    @Override
    public int getHeight() {
        return backgroundHeight() + MARGIN_Y;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics guiGraphics) {
        int cols = columns();
        guiGraphics.blitSprite(BACKGROUND_SPRITE, x, y, backgroundWidth(), backgroundHeight());
        for (int i = 0; i < items.size(); i++) {
            int slotX = x + (i % cols) * SLOT_W + BORDER;
            int slotY = y + (i / cols) * SLOT_H + BORDER;
            guiGraphics.blitSprite(SLOT_SPRITE, slotX, slotY, SLOT_W, SLOT_H);
            ItemStack stack = items.get(i);
            guiGraphics.renderItem(stack, slotX + 1, slotY + 1, i);
            guiGraphics.renderItemDecorations(font, stack, slotX + 1, slotY + 1);
        }
    }
}
