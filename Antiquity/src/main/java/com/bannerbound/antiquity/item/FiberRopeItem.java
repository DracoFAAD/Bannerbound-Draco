package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.SpearFishing;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Fiber Rope — the crafting/utility cordage that, held in your off hand while you throw a spear,
 * tethers the spear with a rope (see {@code SpearItem} + {@code SpearProjectile}). Its only active
 * behaviour is the reel-in: <b>shift + right-click</b> with rope in hand pulls your tethered spear /
 * speared-fish catch back to you (the held rope is not consumed — the spent rope is the one already
 * thrown). The empty-handed reel is handled separately via {@code ReelTetherPayload}.
 */
public class FiberRopeItem extends Item {
    public FiberRopeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(held); // plain cordage otherwise
        }
        if (!level.isClientSide) {
            boolean reeled = SpearFishing.startReel(player);
            if (reeled) {
                player.swing(hand);
                return InteractionResultHolder.success(held);
            }
            return InteractionResultHolder.pass(held);
        }
        // Client: predict the pull swing (server is authoritative for the actual reel).
        return InteractionResultHolder.success(held);
    }
}
