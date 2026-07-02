package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.world.inventory.BasketMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Container screen for the Basket — a 3×3 storage grid over the player inventory. Hand-drawn
 * panel, same flat style as Core's workstation screens.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class BasketScreen extends AbstractContainerScreen<BasketMenu> {
    private static final int SLOT_SIZE = 18;
    private static final int BASKET_X = 62;
    private static final int BASKET_Y = 18;
    private static final int PLAYER_INV_X = 8;
    private static final int PLAYER_INV_Y = 84;
    private static final int HOTBAR_Y = 142;

    public BasketScreen(BasketMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = this.imageHeight - 94;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xFFC6C6C6);
        graphics.fill(x, y, x + this.imageWidth, y + 1, 0xFFFFFFFF);
        graphics.fill(x, y, x + 1, y + this.imageHeight, 0xFFFFFFFF);
        graphics.fill(x + this.imageWidth - 1, y, x + this.imageWidth, y + this.imageHeight, 0xFF555555);
        graphics.fill(x, y + this.imageHeight - 1, x + this.imageWidth, y + this.imageHeight, 0xFF555555);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                drawSlot(graphics, x + BASKET_X + col * SLOT_SIZE, y + BASKET_Y + row * SLOT_SIZE);
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawSlot(graphics, x + PLAYER_INV_X + col * SLOT_SIZE, y + PLAYER_INV_Y + row * SLOT_SIZE);
            }
        }
        for (int col = 0; col < 9; col++) {
            drawSlot(graphics, x + PLAYER_INV_X + col * SLOT_SIZE, y + HOTBAR_Y);
        }
    }

    private static void drawSlot(GuiGraphics graphics, int slotX, int slotY) {
        graphics.fill(slotX - 1, slotY - 1, slotX + 17, slotY + 17, 0xFF8B8B8B);
        graphics.fill(slotX - 1, slotY - 1, slotX + 17, slotY, 0xFF373737);
        graphics.fill(slotX - 1, slotY - 1, slotX, slotY + 17, 0xFF373737);
        graphics.fill(slotX - 1, slotY + 16, slotX + 17, slotY + 17, 0xFFFFFFFF);
        graphics.fill(slotX + 16, slotY - 1, slotX + 17, slotY + 17, 0xFFFFFFFF);
        graphics.fill(slotX, slotY, slotX + 16, slotY + 16, 0xFF8B8B8B);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
