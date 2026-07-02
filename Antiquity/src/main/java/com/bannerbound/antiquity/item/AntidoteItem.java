package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.antiquity.poison.Poisons;

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
 * A remedy that cures exactly ONE poison (the cross-biome cure cycle — yarrow→wolfsbane,
 * marshmallow→belladonna, …). It grows in a different biome than the poison it treats, so a civ can
 * never brew the antidote to its own signature poison. Drinks like a potion.
 */
public class AntidoteItem extends Item {
    private static final int DRINK_TICKS = 32;

    private final PoisonType cures;

    public AntidoteItem(Properties properties, PoisonType cures) {
        super(properties);
        this.cures = cures;
    }

    /** The single poison this remedy treats (drives both drinking it and shift-applying it to others). */
    public PoisonType cures() {
        return cures;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.DRINK;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return DRINK_TICKS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return ItemUtils.startUsingInstantly(level, player, hand);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide) {
            Poisons.cure(entity, cures); // only clears this antidote's poison
        }
        if (!(entity instanceof Player player) || !player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return stack;
    }
}
