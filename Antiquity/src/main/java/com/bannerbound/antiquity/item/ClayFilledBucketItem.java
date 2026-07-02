package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

/** Filled fired clay bucket. Vanilla handles placement; this swaps the empty result back to clay. */
public class ClayFilledBucketItem extends BucketItem {
    private final boolean breaksOnPlace;

    public ClayFilledBucketItem(Fluid fluid, boolean breaksOnPlace, Properties properties) {
        super(fluid, properties);
        this.breaksOnPlace = breaksOnPlace;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        InteractionResultHolder<ItemStack> result = super.use(level, player, hand);
        ItemStack stack = result.getObject();
        if (stack.is(Items.BUCKET)) {
            if (breaksOnPlace) {
                return new InteractionResultHolder<>(result.getResult(), ItemStack.EMPTY);
            }
            return new InteractionResultHolder<>(result.getResult(),
                new ItemStack(BannerboundAntiquity.CLAY_FIRED_BUCKET.get(), stack.getCount()));
        }
        return result;
    }
}
