package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.food.FoodSpoilageData;
import com.bannerbound.antiquity.food.Spoilage;

import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;

/**
 * Salt — a preservative. Hold it in one hand and a perishable food in the other, then hold
 * right-click for ~1.6s to rub it in: the food is marked salted and keeps 25% longer (a one-time
 * bonus). Consumes one salt per food stack. Salting in the cooking pot comes later (COOKING_PLAN.md).
 *
 * <p>The "rubbing" feel borrows Create's sandpaper: the EAT use-pose plus a gritty sand sound
 * replayed every 7 ticks during the hold (instead of the default eating crunch).
 */
public class SaltItem extends Item {
    /** Hold time before the salt rubs in (matches Create's sandpaper). */
    private static final int USE_TICKS = 32;

    public SaltItem(Properties properties) {
        super(properties);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.EAT;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_TICKS;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        // Gritty sand-rub audio every 7 ticks while held — like Create's sanding (the EAT pose
        // already shows the rubbing motion + salt particles). Server-side so everyone nearby hears it.
        int elapsed = USE_TICKS - remainingUseDuration;
        if (!level.isClientSide && elapsed > 0 && (elapsed - 6) % 7 == 0) {
            level.playSound(null, entity.blockPosition(), SoundType.SAND.getHitSound(),
                SoundSource.PLAYERS, 0.7F, 0.9F + level.random.nextFloat() * 0.2F);
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack food = player.getItemInHand(otherHand(hand));
        if (FoodSpoilageData.isPerishable(food.getItem())
                && !Spoilage.isSalted(food)
                && !Spoilage.isSpoiled(food)) {
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(player.getItemInHand(hand));
        }
        return InteractionResultHolder.fail(player.getItemInHand(hand));
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide && entity instanceof Player player) {
            ItemStack food = player.getItemInHand(otherHand(player.getUsedItemHand()));
            if (Spoilage.applySalt(food, level)) {
                stack.shrink(1);
                level.playSound(null, player.blockPosition(), SoundType.SAND.getPlaceSound(),
                    SoundSource.PLAYERS, 0.6F, 1.6F);
            }
        }
        return stack;
    }

    private static InteractionHand otherHand(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }
}
