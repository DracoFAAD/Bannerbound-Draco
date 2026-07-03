package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.FirewoodPileBlock;

import com.bannerbound.core.codex.CodexManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Firewood — split logs that build a campfire by hand. Right-clicking the ground places a
 * {@link FirewoodPileBlock} (1 log); right-clicking the pile raises it 1 → 3, and the fourth
 * firewood swaps the pile for an unlit vanilla campfire. That campfire is the settlement seed
 * (Core's FactionEvents founds on it when right-clicked in unclaimed land) and can otherwise be
 * lit with Fire Sticks as an ordinary cook-fire.
 */
public class FirewoodItem extends Item {
    public FirewoodItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos clicked = ctx.getClickedPos();
        BlockState clickedState = level.getBlockState(clicked);
        Player player = ctx.getPlayer();
        ItemStack stack = ctx.getItemInHand();

        // Clicking an existing firewood pile → grow it, or (at max) turn it into a campfire.
        if (clickedState.getBlock() instanceof FirewoodPileBlock) {
            if (!level.isClientSide) {
                int logs = clickedState.getValue(FirewoodPileBlock.LOGS);
                if (logs < FirewoodPileBlock.MAX_LOGS) {
                    level.setBlock(clicked, clickedState.setValue(FirewoodPileBlock.LOGS, logs + 1),
                        Block.UPDATE_ALL);
                } else {
                    CodexManager.onItemObtained((ServerPlayer) player, "minecraft:campfire");

                    BlockState campfire = Blocks.CAMPFIRE.defaultBlockState()
                        .setValue(CampfireBlock.LIT, false)
                        .setValue(CampfireBlock.FACING, clickedState.getValue(FirewoodPileBlock.FACING));

                    level.setBlock(clicked, campfire, Block.UPDATE_ALL);
                }
                playPlace(level, clicked);
                consume(player, stack);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // Otherwise start a new pile on top of the clicked face, if it's a supported open spot.
        Direction face = ctx.getClickedFace();
        BlockPos placePos = clicked.relative(face);
        BlockPos below = placePos.below();
        boolean supported = level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
        if (!supported || !level.getBlockState(placePos).canBeReplaced()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            Direction facing = player != null ? player.getDirection() : Direction.NORTH;
            level.setBlock(placePos,
                BannerboundAntiquity.FIREWOOD_PILE.get().defaultBlockState()
                    .setValue(FirewoodPileBlock.LOGS, 1)
                    .setValue(FirewoodPileBlock.FACING, facing),
                Block.UPDATE_ALL);
            playPlace(level, placePos);
            consume(player, stack);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void playPlace(Level level, BlockPos pos) {
        level.playSound(null, pos, SoundType.WOOD.getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
    }

    private static void consume(Player player, ItemStack stack) {
        if (player == null || !player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
    }
}
