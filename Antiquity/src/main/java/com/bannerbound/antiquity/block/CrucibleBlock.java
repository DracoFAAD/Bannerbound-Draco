package com.bannerbound.antiquity.block;

import java.util.List;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.CrucibleBlockEntity;
import com.bannerbound.antiquity.item.CrucibleContents;
import com.bannerbound.antiquity.metalworking.MetalworkingItems;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A crucible placed on the ground (METALWORKING_PLAN.md Part 2 — overhauled). Right-click it with raw
 * ore / metal items to drop them in (they show inside); break it (faster with a pickaxe) to get the
 * crucible item back <b>carrying the charge</b>, then melt it in a bloomery. The block is invisible —
 * its {@link com.bannerbound.antiquity.client.CrucibleRenderer} draws the body + the charge.
 */
public class CrucibleBlock extends BaseEntityBlock {
    public static final MapCodec<CrucibleBlock> CODEC = simpleCodec(CrucibleBlock::new);
    private static final VoxelShape SHAPE = Block.box(4, 0, 4, 12, 6, 12);

    public CrucibleBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
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
        return RenderShape.MODEL; // the bowl is the blockstate model; the BER adds the charge inside
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CrucibleBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.getBlockEntity(pos) instanceof CrucibleBlockEntity be) {
            CrucibleContents c = stack.get(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
            be.setContents(c == null ? CrucibleContents.EMPTY : c);
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CrucibleBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!MetalworkingItems.isSmeltable(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && be.addItem(stack)) {
            if (!player.hasInfiniteMaterials()) stack.shrink(1);
            level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.6F, 1.4F);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Empty-hand right-click pops the last charged item back out (only while still solid). */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CrucibleBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            ItemStack popped = be.removeLast();
            if (popped.isEmpty()) return InteractionResult.PASS; // molten or empty — nothing to take
            if (!player.addItem(popped)) Block.popResource(level, pos.above(), popped);
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.6F, 1.2F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Break keeps the charge: drop a crucible item carrying it (no separate empty crucible). */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        BlockEntity be = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        CrucibleContents c = be instanceof CrucibleBlockEntity ce ? ce.contents() : CrucibleContents.EMPTY;
        return List.of(crucibleWith(c));
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(pos) instanceof CrucibleBlockEntity be
            ? crucibleWith(be.contents()) : crucibleWith(CrucibleContents.EMPTY);
    }

    private static ItemStack crucibleWith(CrucibleContents c) {
        ItemStack stack = new ItemStack(BannerboundAntiquity.CRUCIBLE.get());
        if (!c.isEmpty()) stack.set(BannerboundAntiquity.CRUCIBLE_CONTENTS.get(), c);
        return stack;
    }
}
