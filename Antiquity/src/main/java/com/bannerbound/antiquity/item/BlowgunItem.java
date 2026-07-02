package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.entity.BlowdartProjectile;
import com.bannerbound.antiquity.poison.PoisonType;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * A bamboo blowgun — fires a {@link PoisonDart} from the inventory as ammo, and it draws like a bow:
 * hold to build breath, release to puff. A longer draw flings the dart faster, straighter and further
 * (far beyond a hand throw, which is the weak desperation option). Reusable; one dart per shot. Any
 * poison's dart works as ammo.
 */
public class BlowgunItem extends Item {
    /** Ticks of draw for a full-power puff (~1.2s). */
    private static final int FULL_DRAW_TICKS = 24;
    private static final float MIN_SPEED = 1.6F;
    private static final float MAX_SPEED = 3.4F;

    public BlowgunItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack blowgun = player.getItemInHand(hand);
        if (findDart(player).isEmpty() && !player.getAbilities().instabuild) {
            return InteractionResultHolder.fail(blowgun); // no ammo → nothing to draw
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(blowgun);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        // TOOT_HORN = the goat-horn "raised to the mouth" pose, which reads as blowing into the
        // blowgun. Vanilla renders this pose natively in third person; first person is supplied by
        // FirstPersonTootHornMixin (vanilla's first-person switch has no TOOT_HORN case), after which
        // the blowgun_draw model's firstperson display transforms place the tube at the mouth.
        return UseAnim.TOOT_HORN;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    @Override
    public void releaseUsing(ItemStack blowgun, Level level, LivingEntity entity, int timeLeft) {
        if (!(entity instanceof Player player)) {
            return;
        }
        int charge = getUseDuration(blowgun, entity) - timeLeft;
        float power = drawPower(charge);
        if (power < 0.12F) {
            return; // barely a breath — no shot
        }
        boolean creative = player.getAbilities().instabuild;
        ItemStack dart = findDart(player);
        if (dart.isEmpty() && !creative) {
            return;
        }
        PoisonType poison = (dart.getItem() instanceof PoisonDart pd) ? pd.poison() : PoisonType.WOLFSBANE;
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            com.bannerbound.antiquity.BannerboundAntiquity.BLOWGUN_SHOOT.get(),
            SoundSource.PLAYERS, 0.8F, 0.85F + power * 0.4F);
        if (!level.isClientSide) {
            BlowdartProjectile d = new BlowdartProjectile(level, player, poison);
            float speed = Mth.lerp(power, MIN_SPEED, MAX_SPEED);
            d.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F,
                speed, (1.0F - power) * 1.5F); // tighter aim at full draw
            level.addFreshEntity(d);
        }
        if (!creative && !dart.isEmpty()) {
            dart.shrink(1);
        }
        player.getCooldowns().addCooldown(this, 8);
    }

    /** Bow-style power curve: ramps over {@link #FULL_DRAW_TICKS}, accelerating toward full. */
    private static float drawPower(int charge) {
        float f = charge / (float) FULL_DRAW_TICKS;
        f = (f * f + f * 2.0F) / 3.0F;
        return Mth.clamp(f, 0.0F, 1.0F);
    }

    /** The first poison dart found in the player's inventory (empty if none). */
    private static ItemStack findDart(Player player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof PoisonDart) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }
}
