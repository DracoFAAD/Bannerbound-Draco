package com.bannerbound.core.menu;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * One summed line in the Stockpile terminal: a {@code display} stack (count 1, carrying the item +
 * components for rendering and item-matching) and the {@code total} quantity across all of the
 * stockpile's enclosed containers. Counts can exceed a stack, which is exactly why the terminal
 * can't be backed by real {@code Slot}s and syncs this virtual list instead.
 */
@ApiStatus.Internal
public record StockEntry(ItemStack display, int total) {
    public static final StreamCodec<RegistryFriendlyByteBuf, StockEntry> STREAM_CODEC =
        StreamCodec.composite(
            ItemStack.STREAM_CODEC, StockEntry::display,
            ByteBufCodecs.VAR_INT, StockEntry::total,
            StockEntry::new);
}
