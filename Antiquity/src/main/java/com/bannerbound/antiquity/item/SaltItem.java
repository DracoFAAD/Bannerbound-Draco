package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.food.FoodSpoilageData;
import com.bannerbound.antiquity.food.Spoilage;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.phys.Vec3;

/**
 * Salt - a preservative. Hold it in one hand and a perishable food in the other, then hold
 * right-click for ~1.6s (32 ticks, matching Create's sandpaper hold) to rub it in: the food is
 * marked salted and keeps 25% longer (a one-time bonus). Consumes one salt per food stack.
 * Salting in the cooking pot comes later (COOKING_PLAN.md).
 *
 * <p>The rub is styled after Create's sandpaper polishing, minus its custom renderer: the BRUSH
 * use-pose (a scrubbing motion, same as {@link PoisonPasteItem} - never EAT, which reads as
 * snacking on salt and plays the vanilla crunch), flecks of the food flying off while rubbing,
 * and a gritty sand sound replayed every 7 ticks, played server-side so everyone nearby hears
 * it. The rub aborts if the food leaves the other hand mid-hold.
 */
public class SaltItem extends Item {
    private static final int USE_TICKS = 32;

    public SaltItem(Properties properties) {
        super(properties);
    }

    private static ItemStack saltableInOtherHand(LivingEntity entity, InteractionHand saltHand) {
        ItemStack food = entity.getItemInHand(otherHand(saltHand));
        return (FoodSpoilageData.isPerishable(food.getItem())
            && !Spoilage.isSalted(food)
            && !Spoilage.isSpoiled(food)) ? food : ItemStack.EMPTY;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BRUSH;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_TICKS;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        ItemStack food = saltableInOtherHand(entity, entity.getUsedItemHand());
        if (food.isEmpty()) {
            entity.stopUsingItem();
            return;
        }
        int elapsed = USE_TICKS - remainingUseDuration;
        if (level.isClientSide) {
            if (elapsed % 2 == 0) {
                spawnFoodFleck(level, entity, food);
            }
        } else if (elapsed > 0 && (elapsed - 6) % 7 == 0) {
            level.playSound(null, entity.blockPosition(), SoundType.SAND.getHitSound(),
                SoundSource.PLAYERS, 0.7F, 0.9F + level.random.nextFloat() * 0.2F);
        }
    }

    private static void spawnFoodFleck(Level level, LivingEntity entity, ItemStack food) {
        Vec3 pos = entity.getEyePosition().add(entity.getLookAngle().scale(0.6)).add(0.0, -0.3, 0.0);
        level.addParticle(new ItemParticleOption(ParticleTypes.ITEM, food),
            pos.x + (level.random.nextFloat() - 0.5) * 0.3,
            pos.y,
            pos.z + (level.random.nextFloat() - 0.5) * 0.3,
            (level.random.nextFloat() - 0.5) * 0.1,
            0.1,
            (level.random.nextFloat() - 0.5) * 0.1);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!saltableInOtherHand(player, hand).isEmpty()) {
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
