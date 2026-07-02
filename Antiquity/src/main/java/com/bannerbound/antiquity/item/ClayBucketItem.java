package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Empty fired clay bucket. It only accepts source water/lava and returns the mod's filled variants. */
public class ClayBucketItem extends Item {
    public ClayBucketItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        HitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() == HitResult.Type.MISS) {
            return InteractionResultHolder.pass(stack);
        }
        if (!(hit instanceof BlockHitResult blockHit)) {
            return InteractionResultHolder.pass(stack);
        }

        BlockPos pos = blockHit.getBlockPos();
        Direction direction = blockHit.getDirection();
        if (!level.mayInteract(player, pos) || !player.mayUseItemAt(pos.relative(direction), direction, stack)) {
            return InteractionResultHolder.fail(stack);
        }

        FluidState fluidState = level.getFluidState(pos);
        ItemStack filled = filledBucketFor(fluidState);
        if (filled.isEmpty()) {
            return InteractionResultHolder.fail(stack);
        }

        BlockState blockState = level.getBlockState(pos);
        if (!(blockState.getBlock() instanceof BucketPickup pickup)) {
            return InteractionResultHolder.fail(stack);
        }

        ItemStack picked = pickup.pickupBlock(player, level, pos, blockState);
        if (picked.isEmpty()) {
            return InteractionResultHolder.fail(stack);
        }

        player.awardStat(Stats.ITEM_USED.get(this));
        pickup.getPickupSound(blockState).ifPresent(sound -> player.playSound(sound, 1.0F, 1.0F));
        level.gameEvent(player, GameEvent.FLUID_PICKUP, pos);
        ItemStack result = ItemUtils.createFilledResult(stack, player, filled);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            CriteriaTriggers.FILLED_BUCKET.trigger(serverPlayer, filled);
        }
        return InteractionResultHolder.sidedSuccess(result, level.isClientSide);
    }

    private static ItemStack filledBucketFor(FluidState fluidState) {
        if (!fluidState.isSource()) {
            return ItemStack.EMPTY;
        }
        if (fluidState.is(FluidTags.WATER)) {
            return new ItemStack(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get());
        }
        if (fluidState.is(FluidTags.LAVA)) {
            return new ItemStack(BannerboundAntiquity.CLAY_FIRED_LAVA_BUCKET.get());
        }
        return ItemStack.EMPTY;
    }
}
