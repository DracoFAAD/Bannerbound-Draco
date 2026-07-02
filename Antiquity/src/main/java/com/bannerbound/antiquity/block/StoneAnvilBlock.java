package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.StoneAnvilBlockEntity;
import com.bannerbound.antiquity.item.CrucibleContents;
import com.bannerbound.antiquity.item.HammerItem;
import com.bannerbound.antiquity.metalworking.MetalworkingItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Stone Anvil — a Crafting-Stone-style pile station with the cold-hammer minigame, doubling as the
 * casting bench. Created by right-clicking a stone block with a hammer ({@link HammerItem}).
 *
 * <ul>
 *   <li>Right-click with an item → add ONE to the pile (blade, stick…); a matched recipe floats a preview.</li>
 *   <li><b>Shift</b>-right-click holding a hammer → start the cold-hammer minigame for the matched recipe.</li>
 *   <li>Right-click empty-handed → take the last pile item back.</li>
 *   <li>Right-click an <b>empty pile</b> with a fired mold → place it; pour a molten crucible; empty-hand
 *       to pop the casting (or scrap a cooled dud / take the empty mold back).</li>
 * </ul>
 */
public class StoneAnvilBlock extends BaseEntityBlock {
    public static final MapCodec<StoneAnvilBlock> CODEC = simpleCodec(StoneAnvilBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final VoxelShape SHAPE = Block.box(2, 0, 1, 14, 13, 15);

    public StoneAnvilBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    /**
     * The collision box isn't a full block, so vanilla classifies the cell as walkable and NPCs path
     * onto it and snag. Mark it un-pathfindable so every pathfinder routes around it.
     */
    @Override
    protected boolean isPathfindable(BlockState state,
                                     net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StoneAnvilBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (type != BannerboundAntiquity.STONE_ANVIL_BE.get()) return null;
        return (lvl, pos, st, be) -> StoneAnvilBlockEntity.tick(lvl, pos, st, (StoneAnvilBlockEntity) be);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof StoneAnvilBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // A hammer in hand starts the cold-hammer minigame — it never goes into the pile.
        if (stack.getItem() instanceof HammerItem) {
            return tryStart(level, pos, player, be)
                ? ItemInteractionResult.sidedSuccess(level.isClientSide)
                : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }

        // Shift → start the cold-hammer minigame on the matched pile recipe.
        if (player.isSecondaryUseActive()) {
            return tryStart(level, pos, player, be)
                ? ItemInteractionResult.sidedSuccess(level.isClientSide)
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Casting mode: place a fired mold on an empty pile, then pour a molten crucible into it.
        String moldShape = MetalworkingItems.shapeOfFiredMold(stack.getItem());
        if (moldShape != null && be.pileEmpty() && !be.hasMold()) {
            if (!level.isClientSide) {
                be.placeMold(moldShape);
                if (!player.hasInfiniteMaterials()) stack.shrink(1);
                level.playSound(null, pos, SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.8F, 1.2F);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (be.hasMold() && stack.is(BannerboundAntiquity.CRUCIBLE.get())
                && stack.has(BannerboundAntiquity.CRUCIBLE_CONTENTS.get())) {
            CrucibleContents charge = stack.get(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
            if (charge == null || charge.isEmpty()) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!charge.molten()) {
                if (!level.isClientSide) {
                    player.displayClientMessage(
                        Component.translatable("message.bannerboundantiquity.crucible_cooled"), true);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            // Hold to pour: the crucible item drains a little per tick (CrucibleItem.onUseTick) so the
            // mold fills gradually. Started on both sides so the use stays in sync.
            player.startUsingItem(hand);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Pile mode: add one item to the pile.
        if (!be.hasMold() && !stack.isEmpty()) {
            if (!level.isClientSide
                    && !com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())
                    && be.insertOne(stack, player.getDirection().getOpposite())
                    && !player.hasInfiniteMaterials()) {
                stack.shrink(1);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof StoneAnvilBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (player.isSecondaryUseActive()) {
            return tryStart(level, pos, player, be)
                ? InteractionResult.sidedSuccess(level.isClientSide)
                : InteractionResult.PASS;
        }
        if (be.hasMold()) {
            if (be.isCastReady()) {
                if (!level.isClientSide) {
                    giveOrDrop(player, be.extractCasting());
                    level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.7F, 1.1F);
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            if (be.isDud()) {
                if (!level.isClientSide) {
                    be.clearMold();
                    player.displayClientMessage(
                        Component.translatable("message.bannerboundantiquity.cast_underfilled"), true);
                    level.playSound(null, pos, SoundEvents.ITEM_BREAK, SoundSource.BLOCKS, 0.7F, 0.9F);
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!level.isClientSide) {
                ItemStack mold = be.takeMold();
                if (!mold.isEmpty()) giveOrDrop(player, mold);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        // Pile mode: take the last item back.
        if (!level.isClientSide
                && !com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            ItemStack out = be.removeOne();
            if (!out.isEmpty()) giveOrDrop(player, out);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Opens the cold-hammer minigame when the pile has a researched recipe and the player holds a
     *  hammer (its rank gates the quality), and no citizen is mid-craft on the station. */
    private static boolean tryStart(Level level, BlockPos pos, Player player, StoneAnvilBlockEntity be) {
        if (com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("bannerbound.workshop.station_busy")
                    .withStyle(ChatFormatting.YELLOW), true);
            }
            return false;
        }
        if (be.matchedRecipe() == null) return false;
        int rank = hammerRank(player);
        if (rank < 0) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                    Component.translatable("message.bannerboundantiquity.need_hammer")
                        .withStyle(ChatFormatting.YELLOW), true);
            }
            return false;
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            com.bannerbound.antiquity.Hammer.startSession(sp, pos, be, rank);
        }
        return true;
    }

    /** The rank of a hammer in either hand, or -1 if the player isn't holding one. */
    private static int hammerRank(Player player) {
        for (InteractionHand h : InteractionHand.values()) {
            if (player.getItemInHand(h).getItem() instanceof HammerItem hammer) return hammer.rank();
        }
        return -1;
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!stack.isEmpty() && !player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof StoneAnvilBlockEntity be) {
            for (ItemStack s : be.getContents()) {
                Block.popResource(level, pos, s);
            }
            if (be.isCastReady()) {
                Block.popResource(level, pos, be.extractCasting());
            } else if (be.hasMold()) {
                var moldItem = MetalworkingItems.MOLDS.get("fired_clay_mold_" + be.moldShape());
                if (moldItem != null) Block.popResource(level, pos, new ItemStack(moldItem.get()));
            }
            if (be.isForging()) Block.popResource(level, pos, be.forgeItem().copy());
            com.bannerbound.antiquity.Hammer.abortSessionAt(pos);
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
