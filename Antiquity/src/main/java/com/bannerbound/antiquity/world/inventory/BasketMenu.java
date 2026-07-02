package com.bannerbound.antiquity.world.inventory;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.BasketBlockEntity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Menu for the Basket — a 3×3 grid of storage slots plus the player inventory. The basket's
 * 9 slots are backed directly by the {@link BasketBlockEntity} (which is itself a Container);
 * the client side uses a throwaway {@link SimpleContainer}, kept in sync by the vanilla menu.
 */
@ApiStatus.Internal
public class BasketMenu extends AbstractContainerMenu {
    public static final int BASKET_SLOTS = 9;
    private static final int PLAYER_INV_START = BASKET_SLOTS;
    private static final int HOTBAR_END = BASKET_SLOTS + 36;

    private final Container container;

    public BasketMenu(int containerId, Inventory playerInv, BasketBlockEntity be) {
        super(BannerboundAntiquity.BASKET_MENU.get(), containerId);
        this.container = be;
        addSlots(playerInv);
    }

    public BasketMenu(int containerId, Inventory playerInv, RegistryFriendlyByteBuf buf) {
        super(BannerboundAntiquity.BASKET_MENU.get(), containerId);
        buf.readBlockPos(); // basket position — sent for parity, not needed client-side
        this.container = new SimpleContainer(BasketBlockEntity.SIZE);
        addSlots(playerInv);
    }

    private void addSlots(Inventory playerInv) {
        // 3×3 basket grid, centred in the 176-wide panel.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                this.addSlot(new Slot(container, row * 3 + col, 62 + col * 18, 18 + row * 18));
            }
        }
        // Player inventory.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, 9 + row * 9 + col, 8 + col * 18, 84 + row * 18));
            }
        }
        // Hotbar.
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, 142));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack moved = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            moved = stack.copy();
            if (index < PLAYER_INV_START) {
                if (!this.moveItemStackTo(stack, PLAYER_INV_START, HOTBAR_END, true)) return ItemStack.EMPTY;
            } else {
                if (!this.moveItemStackTo(stack, 0, PLAYER_INV_START, false)) return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
        }
        return moved;
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }
}
