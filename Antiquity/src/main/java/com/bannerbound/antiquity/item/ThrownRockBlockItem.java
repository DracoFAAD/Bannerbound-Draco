package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.entity.ThrownRock;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * A rock that can be both PLACED (right-click a block face — vanilla BlockItem behaviour via
 * {@link #useOn}) and THROWN like a snowball (right-click air/entity → {@link #use} spawns a
 * {@link ThrownRock} for minimal damage and a brief stun).
 */
public class ThrownRockBlockItem extends BlockItem {
    private static final float THROW_VELOCITY = 1.5F;
    private static final float THROW_INACCURACY = 1.0F;

    public ThrownRockBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.5F,
            0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
        player.getCooldowns().addCooldown(this, 8);
        if (!level.isClientSide) {
            ThrownRock rock = new ThrownRock(level, player);
            rock.setItem(stack);
            rock.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F,
                THROW_VELOCITY, THROW_INACCURACY);
            level.addFreshEntity(rock);
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
