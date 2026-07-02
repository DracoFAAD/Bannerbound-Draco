package com.bannerbound.antiquity.block.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.world.inventory.BasketMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Basket — a 9-slot storage block. Implements {@link Container} directly so
 * it can back the menu without a separate inventory object. The first non-empty slot is the one
 * the {@code BasketRenderer} displays sitting on top of the basket (see {@link #getDisplayStack}).
 */
@ApiStatus.Internal
public class BasketBlockEntity extends BlockEntity implements Container, MenuProvider {
    public static final int SIZE = 9;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    /** Transient: set in {@code BasketBlock.playerWillDestroy} when a player sneak-breaks the basket,
     *  so the break drops a single basket item carrying these contents instead of dropping them loose.
     *  Lives only for the destroy tick — never saved to NBT. */
    private boolean pickupRequested;

    public BasketBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.BASKET_BE.get(), pos, state);
    }

    /** Snapshot of the slots, used to bake the contents onto a sneak-broken basket item. */
    public NonNullList<ItemStack> getItems() {
        return items;
    }

    /** Restore contents from a placed basket item's stored component (see {@code BasketBlock.setPlacedBy}). */
    public void loadFromContents(net.minecraft.world.item.component.ItemContainerContents contents) {
        contents.copyInto(items);
        setChanged();
    }

    /** Flag this basket as sneak-broken so the break preserves its contents in the dropped item. */
    public void markPickup() {
        pickupRequested = true;
    }

    public boolean isPickupRequested() {
        return pickupRequested;
    }

    /** The stack {@code BasketRenderer} draws on top of the basket: the first <em>used</em> slot
     *  (lowest index that isn't empty), so the basket shows its contents no matter which slot the
     *  player dropped them into. Empty when every slot is empty. */
    public ItemStack getDisplayStack() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) return stack;
        }
        return ItemStack.EMPTY;
    }

    // ─── Container ─────────────────────────────────────────────────────────────────────────────

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = ContainerHelper.takeItem(items, slot);
        setChanged();
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    /** Marks dirty and re-syncs to clients — the renderer mirrors the displayed slot, so every
     *  edit (which could change the first non-empty slot) syncs. */
    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ─── NBT + client sync ─────────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        ContainerHelper.saveAllItems(tag, items, provider);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        items.clear();
        ContainerHelper.loadAllItems(tag, items, provider);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, provider);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Read-only view used to drop the contents when the block breaks. */
    public Container getDroppableInventory() {
        return this;
    }

    // ─── MenuProvider ──────────────────────────────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.bannerboundantiquity.basket");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return new BasketMenu(containerId, playerInv, this);
    }
}
