package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.MortarAndPestleBlockEntity;
import com.bannerbound.antiquity.recipe.MortarDyeing;
import com.bannerbound.antiquity.recipe.MortarRecipeManager;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Mortar and Pestle block. Body + dynamic liquid are drawn by {@code MortarAndPestleRenderer};
 * the JSON block model is intentionally empty.
 * <p>
 * Interaction:
 * <ul>
 *   <li>Right-click with a water bottle → fills the bowl with water.</li>
 *   <li>Right-click with a recipe ingredient → puts it in (swaps if one's already inside).</li>
 *   <li>Right-click empty-handed → runs one grind (5 are needed to finish a recipe).</li>
 *   <li>Right-click a dyeable item on a bowl of finished dye → dyes up to 8 of it, empties the bowl.</li>
 *   <li>Shift-right-click empty-handed → takes the ingredient out.</li>
 *   <li>Shift-right-click with an empty bottle → scoops the water back out
 *       (handled in {@code AntiquityEvents}, since vanilla skips block use when sneaking + holding).</li>
 * </ul>
 */
public class MortarAndPestleBlock extends Block implements EntityBlock {
    public static final MapCodec<MortarAndPestleBlock> CODEC = simpleCodec(MortarAndPestleBlock::new);
    private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 12.0, 13.0);
    /** Most items a single dip in the dye can colour at once. */
    private static final int DYE_BATCH = 8;

    public MortarAndPestleBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<MortarAndPestleBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MortarAndPestleBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        return type == BannerboundAntiquity.MORTAR_AND_PESTLE_BE.get()
            ? (lvl, pos, st, be) -> MortarAndPestleBlockEntity.tick(lvl, pos, st, (MortarAndPestleBlockEntity) be)
            : null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /**
     * The bowl's collision box isn't a full block, so vanilla's default classifies the tile as walkable
     * and NPCs path straight onto it and physically snag. Mark it un-pathfindable so every pathfinder
     * routes around it (the same fix the basket and the other partial-collision workstations carry).
     */
    @Override
    protected boolean isPathfindable(BlockState state, net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof MortarAndPestleBlockEntity mortar)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (mortar.isMixing()
                || com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            return ItemInteractionResult.CONSUME; // locked while another grind is in progress
        }

        // Water bottle → pour water into the bowl, hand back an empty bottle.
        if (!mortar.hasLiquid() && isWaterBottle(stack)) {
            if (!level.isClientSide) {
                mortar.setLiquid("water");
                giveBack(player, hand, stack, new ItemStack(Items.GLASS_BOTTLE));
                level.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Finished dye + a dyeable item → dye up to a batch of the item and use up the liquid.
        DyeColor dye = MortarDyeing.dyeColorFor(mortar.getLiquidId());
        if (dye != null && !stack.isEmpty()) {
            int batch = Math.min(DYE_BATCH, stack.getCount());
            ItemStack dyed = MortarDyeing.recolor(stack.copyWithCount(batch), dye);
            if (!dyed.isEmpty()) {
                if (!level.isClientSide) {
                    mortar.setLiquid("");
                    if (!player.hasInfiniteMaterials()) {
                        stack.shrink(batch);
                    }
                    if (stack.isEmpty()) {
                        player.setItemInHand(hand, dyed);
                    } else {
                        giveOrDrop(player, dyed);
                    }
                    level.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
        }

        // Recipe ingredient → load a batch. Item-output recipes (bricks, poison pastes) hold up to
        // MAX_BATCH and grind a whole stack at once; liquid-output recipes (ink, dyes) take just one.
        if (!stack.isEmpty() && MortarRecipeManager.hasRecipeFor(stack)) {
            int cap = MortarRecipeManager.isBatchable(stack)
                ? MortarAndPestleBlockEntity.MAX_BATCH : 1;
            ItemStack existing = mortar.getIngredient();
            boolean canTopUp = !existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack);
            int alreadyIn = canTopUp ? existing.getCount() : 0;
            int add = Math.min(cap - alreadyIn, stack.getCount());
            if (add <= 0) {
                return ItemInteractionResult.CONSUME; // bowl already full of this ingredient
            }
            if (!level.isClientSide) {
                if (canTopUp) {
                    mortar.setIngredient(existing.copyWithCount(alreadyIn + add));
                } else {
                    ItemStack previous = existing;
                    mortar.setIngredient(stack.copyWithCount(add));
                    if (!previous.isEmpty()) {
                        giveOrDrop(player, previous);
                    }
                }
                if (!player.hasInfiniteMaterials()) {
                    stack.shrink(add);
                }
                level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.7F, 1.0F);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof MortarAndPestleBlockEntity mortar)) {
            return InteractionResult.PASS;
        }
        if (mortar.isMixing()
                || com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            return InteractionResult.CONSUME; // locked while another grind is in progress
        }
        // A non-empty hand here means a no-recipe item fell through useItemOn — don't act.
        if (!player.getMainHandItem().isEmpty()) {
            return InteractionResult.PASS;
        }

        // Shift + empty hand → take the whole loaded batch out.
        if (player.isSecondaryUseActive()) {
            ItemStack inside = mortar.getIngredient();
            if (!inside.isEmpty()) {
                mortar.setIngredient(ItemStack.EMPTY);
                giveOrDrop(player, inside);
                level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.7F, 1.0F);
                return InteractionResult.CONSUME;
            }
            return InteractionResult.PASS;
        }

        // Plain + empty hand → open the press-and-grind minigame (needs a valid ingredient + liquid).
        if (player instanceof net.minecraft.server.level.ServerPlayer sp
                && !mortar.getIngredient().isEmpty()
                && MortarRecipeManager.find(mortar.getIngredient(), mortar.getLiquidId()) != null) {
            if (!com.bannerbound.antiquity.MortarGrind.canGrindAt(level, pos)) {
                // Grinding is gated by Herbalism — say so rather than failing silently.
                sp.displayClientMessage(net.minecraft.network.chat.Component
                    .translatable("bannerboundantiquity.mortar.locked")
                    .withStyle(net.minecraft.ChatFormatting.RED), true);
            } else {
                com.bannerbound.antiquity.MortarGrind.startSession(sp, pos, mortar);
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof MortarAndPestleBlockEntity mortar) {
            ItemStack loaded = mortar.getIngredient();
            if (!loaded.isEmpty()) {
                Block.popResource(level, pos, loaded);
            }
            com.bannerbound.antiquity.MortarGrind.abortSessionAt(pos);
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }

    private static boolean isWaterBottle(ItemStack stack) {
        if (!stack.is(Items.POTION)) {
            return false;
        }
        PotionContents contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        return contents != null && contents.is(Potions.WATER);
    }

    /** Consumes one of {@code used} and gives {@code result} to the player (creative is exempt). */
    private static void giveBack(Player player, InteractionHand hand, ItemStack used, ItemStack result) {
        if (player.hasInfiniteMaterials()) {
            return;
        }
        used.shrink(1);
        if (used.isEmpty()) {
            player.setItemInHand(hand, result);
        } else {
            giveOrDrop(player, result);
        }
    }

    /** Adds {@code stack} to the player's inventory, dropping it in the world if there's no room. */
    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }
}
