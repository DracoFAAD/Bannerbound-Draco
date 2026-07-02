package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.entity.BlowdartProjectile;
import com.bannerbound.antiquity.poison.PoisonType;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A coated blowdart for any {@link PoisonType}. Thrown by hand it's deliberately weak and short-range
 * (a desperation option); the blowgun fires the same dart far faster and straighter. One generic class
 * serves every poison — register a new dart with its {@code PoisonType} and it works with the blowgun.
 */
public class PoisonDartItem extends Item implements PoisonDart {
    // Hand-throwing is a feeble last resort: very short range (drops fast), wildly inaccurate, slow.
    // The blowgun (2–3× this speed, tight aim) is the real delivery.
    private static final float DART_SPEED = 0.9F;
    private static final float DART_INACCURACY = 13.0F;
    private static final int COOLDOWN_TICKS = 24;

    private final PoisonType poison;

    public PoisonDartItem(Properties properties, PoisonType poison) {
        super(properties);
        this.poison = poison;
    }

    @Override
    public PoisonType poison() {
        return poison;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.5F, 1.6F);
        if (!level.isClientSide) {
            BlowdartProjectile dart = new BlowdartProjectile(level, player, poison);
            dart.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, DART_SPEED, DART_INACCURACY);
            level.addFreshEntity(dart);
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
