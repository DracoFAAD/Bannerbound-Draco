package com.bannerbound.antiquity.item;

import java.util.List;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The stone cooking pot as a held/placed item. Right-click a water source to fill it (the
 * {@code STONE_POT_FILLED} boolean component), then place it; the block reads the component and starts
 * the placed pot with water. A pot already holding water won't refill.
 */
public class StoneCookingPotItem extends BlockItem {
    public StoneCookingPotItem(Block block, Properties properties) {
        super(block, properties);
    }

    public static boolean isFilled(ItemStack stack) {
        return Boolean.TRUE.equals(stack.get(BannerboundAntiquity.STONE_POT_FILLED.get()));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Only a single, empty pot can be dipped — keep stack-of-pots behaviour simple.
        if (stack.getCount() != 1 || isFilled(stack)) {
            return InteractionResultHolder.pass(stack);
        }
        HitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() == HitResult.Type.MISS) {
            return InteractionResultHolder.pass(stack);
        }
        BlockPos pos = blockHit.getBlockPos();
        if (!level.mayInteract(player, pos)) {
            return InteractionResultHolder.fail(stack);
        }
        FluidState fluid = level.getFluidState(pos);
        if (!fluid.isSource() || !fluid.is(FluidTags.WATER)) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide) {
            level.playSound(null, player.blockPosition(), SoundEvents.BUCKET_FILL, net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.0F);
        }
        ItemStack filled = stack.copy();
        filled.set(BannerboundAntiquity.STONE_POT_FILLED.get(), true);
        return InteractionResultHolder.sidedSuccess(filled, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        if (isFilled(stack)) {
            tooltip.add(Component.translatable("tooltip.bannerboundantiquity.stone_cooking_pot.filled")
                .withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.translatable("tooltip.bannerboundantiquity.stone_cooking_pot.place_on_fire")
                .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.bannerboundantiquity.stone_cooking_pot.fill")
                .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.bannerboundantiquity.stone_cooking_pot.add_food")
            .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.bannerboundantiquity.stone_cooking_pot.eat")
            .withStyle(ChatFormatting.DARK_GRAY));
    }
}
