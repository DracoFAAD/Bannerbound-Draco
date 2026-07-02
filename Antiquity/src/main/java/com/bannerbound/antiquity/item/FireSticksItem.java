package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.block.BloomeryBlock;
import com.bannerbound.antiquity.block.KilnBlock;
import com.bannerbound.antiquity.block.entity.BloomeryBlockEntity;
import com.bannerbound.antiquity.block.entity.KilnBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Fire Sticks — a primitive reusable fire starter. Held like a bow for 2 seconds, then it lights
 * whatever block is aimed at (placing a fire), or ignites a bloomery whose door is open.
 */
public class FireSticksItem extends Item {
    /** Hold time before it ignites — 2 seconds. */
    private static final int CHARGE_TICKS = 40;

    public FireSticksItem(Properties properties) {
        super(properties);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return CHARGE_TICKS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        // Reached only if the player held the full 2 seconds (releasing early cancels it).
        if (!level.isClientSide && entity instanceof Player player) {
            HitResult hit = player.pick(5.0, 1.0F, false);
            if (hit instanceof BlockHitResult blockHit && tryIgnite(level, blockHit)) {
                level.playSound(null, player.blockPosition(), SoundEvents.FLINTANDSTEEL_USE,
                    SoundSource.BLOCKS, 1.0F, 1.0F);
                // Friction fire wears the sticks down — they break after a handful of lights.
                EquipmentSlot slot = entity.getUsedItemHand() == InteractionHand.OFF_HAND
                    ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
                stack.hurtAndBreak(1, player, slot);
            }
        }
        return stack;
    }

    /** Ignites the targeted bloomery (door open), lights an unlit campfire, or places a fire.
     *  Returns true if anything lit. */
    private static boolean tryIgnite(Level level, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BloomeryBlockEntity bloomery = BloomeryBlock.getController(level, pos);
        if (bloomery != null) {
            if (bloomery.isOpen()) {
                bloomery.ignite();
                return true;
            }
            return false; // a closed bloomery can't be lit inside
        }
        // The kiln has no door — fire sticks light it directly.
        KilnBlockEntity kiln = KilnBlock.getController(level, pos);
        if (kiln != null) {
            kiln.ignite();
            return true;
        }
        // An unlit campfire (e.g. one built from firewood, used as a cook-fire) lights directly.
        BlockState targeted = level.getBlockState(pos);
        if (targeted.getBlock() instanceof CampfireBlock && !targeted.getValue(CampfireBlock.LIT)) {
            level.setBlock(pos, targeted.setValue(CampfireBlock.LIT, true), Block.UPDATE_ALL);
            return true;
        }
        BlockPos firePos = pos.relative(hit.getDirection());
        if (BaseFireBlock.canBePlacedAt(level, firePos, hit.getDirection())) {
            level.setBlockAndUpdate(firePos, BaseFireBlock.getState(level, firePos));
            return true;
        }
        return false;
    }
}
