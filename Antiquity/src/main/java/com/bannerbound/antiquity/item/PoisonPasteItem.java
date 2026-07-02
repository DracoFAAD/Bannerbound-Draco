package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.poison.PoisonType;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * A ground poison paste (the dart coating) that also COATS food or arrows: hold the paste and a food
 * item OR any arrow ({@code #minecraft:arrows}, so vanilla + flint arrows alike) in opposite hands, then
 * hold right-click — the player rubs the paste on (a brushing animation) and, on finish, coats exactly
 * ONE item (never the whole stack — no duping). Food gets a hidden, dose-stacking {@link PoisonedFoodData}
 * (applied a short while after eating); an arrow gets the {@code ARROW_POISON} id (delivered on hit by
 * {@code PoisonArrowEvents}). Carries its {@link PoisonType} so any paste reuses this one item class.
 */
public class PoisonPasteItem extends Item {
    private static final int RUB_TICKS = 30; // ~1.5s of brushing

    private final PoisonType poison;

    public PoisonPasteItem(Properties properties, PoisonType poison) {
        super(properties);
        this.poison = poison;
    }

    /** The food/arrow in the hand NOT holding the paste (or EMPTY if it's neither). */
    private static ItemStack coatableInOtherHand(LivingEntity entity, InteractionHand pasteHand) {
        InteractionHand other = pasteHand == InteractionHand.MAIN_HAND
            ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack stack = entity.getItemInHand(other);
        return (stack.has(DataComponents.FOOD) || stack.is(ItemTags.ARROWS)) ? stack : ItemStack.EMPTY;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack paste = player.getItemInHand(hand);
        if (coatableInOtherHand(player, hand).isEmpty()) {
            return InteractionResultHolder.pass(paste); // nothing to coat — behave like a plain item
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(paste);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BRUSH; // a rubbing/polishing motion
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return RUB_TICKS;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingTicks) {
        if (coatableInOtherHand(entity, entity.getUsedItemHand()).isEmpty()) {
            entity.stopUsingItem(); // the item left the hand mid-rub — abort
            return;
        }
        if ((RUB_TICKS - remainingTicks) % 6 == 0) {
            if (level.isClientSide) {
                level.addParticle(ParticleTypes.CRIT,
                    entity.getX(), entity.getEyeY() - 0.2, entity.getZ(), 0.0, 0.0, 0.0);
            } else {
                // A soft "rub a coating on" sound — fits smearing paste on far better than a harsh scrape.
                level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                    SoundEvents.HONEYCOMB_WAX_ON, SoundSource.PLAYERS, 0.7F, 0.9F);
            }
        }
    }

    @Override
    public ItemStack finishUsingItem(ItemStack paste, Level level, LivingEntity entity) {
        ItemStack target = coatableInOtherHand(entity, entity.getUsedItemHand());
        if (target.isEmpty() || level.isClientSide || !(entity instanceof Player player)) {
            return paste;
        }
        // Coat exactly ONE item: in place if the held stack is alone, else split one off and hand the
        // single coated piece back (so a stack never all gets coated — and never duplicates).
        if (target.getCount() == 1) {
            applyCoat(target, player);
        } else {
            ItemStack one = target.copyWithCount(1);
            applyCoat(one, player);
            target.shrink(1);
            if (!player.getInventory().add(one)) {
                player.drop(one, false);
            }
        }
        if (!player.getAbilities().instabuild) {
            paste.shrink(1);
        }
        return paste;
    }

    /** Stamp the poison onto a single item — food gets a dose-stacking {@link PoisonedFoodData} (with the
     *  poisoner for the settlement-gated tooltip); an arrow gets the openly-shown {@code ARROW_POISON} id. */
    private void applyCoat(ItemStack one, Player player) {
        if (one.has(DataComponents.FOOD)) {
            PoisonedFoodData cur = one.get(BannerboundAntiquity.POISONED_FOOD.get());
            PoisonedFoodData next = (cur != null && cur.poisonId().equals(poison.id()))
                ? cur.withAnotherDose(poison.maxDose())
                : new PoisonedFoodData(poison.id(), 1, player.getStringUUID());
            one.set(BannerboundAntiquity.POISONED_FOOD.get(), next);
        } else {
            one.set(BannerboundAntiquity.ARROW_POISON.get(), poison.id());
        }
    }
}
