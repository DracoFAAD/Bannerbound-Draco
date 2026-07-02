package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * A drinking vessel (mug / goat horn) for grog (GROG_PLAN.md Phase 3). An empty vessel does nothing;
 * filled at a ready Fermentation Trough it gains a {@link GrogContents} component (which tints its
 * alcohol layer and shows it as full). Drinking restores the grog's food value, then leaves an empty
 * vessel behind — honey-bottle style. The behaviour is identical for every vessel; only the textures
 * differ (the item id picks the model).
 */
public class GrogVesselItem extends Item {
    public GrogVesselItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.has(BannerboundAntiquity.GROG_CONTENTS.get())) {
            return InteractionResultHolder.pass(stack); // empty vessel — nothing to drink
        }
        return ItemUtils.startUsingInstantly(level, player, hand);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 32;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.DRINK;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        GrogContents grog = stack.get(BannerboundAntiquity.GROG_CONTENTS.get());
        Player player = entity instanceof Player p ? p : null;
        if (grog != null && !level.isClientSide && player != null) {
            // Restore food, apply the grog's effects, and add a tier of intoxication (stacks if chugged).
            Intoxication.sip(player, grog.effects(), grog.strength(), grog.foodValue());
        }
        if (player != null) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.5F, 1.0F);
        }
        ItemStack empty = new ItemStack(this); // the drained vessel (no GrogContents)
        if (player != null && player.hasInfiniteMaterials()) {
            return stack;
        }
        stack.shrink(1);
        if (stack.isEmpty()) {
            return empty;
        }
        if (player != null && !player.getInventory().add(empty)) {
            player.drop(empty, false);
        }
        return stack;
    }
}
