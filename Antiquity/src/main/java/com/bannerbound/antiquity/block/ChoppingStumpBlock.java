package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.ChoppingStumpBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;

/**
 * Chopping Stump — a log butchering block made by right-clicking a lone log with an axe (see
 * {@code AntiquityEvents.onChopLoneLog}). It's skinned with the textures of the log it was carved
 * from (rendered by {@code ChoppingStumpRenderer}, so the static block model is invisible). Logs
 * dropped onto it are split into firewood one swing at a time.
 * <ul>
 *   <li>Right-click with logs → deposit the stack (slides in from your side).</li>
 *   <li>Right-click with an axe → chop one log: 50% chance to yield a firewood (with that log's
 *       chip particles), axe takes 1 durability. The pile shrinks each swing.</li>
 *   <li>Right-click empty-handed → take the remaining logs back.</li>
 * </ul>
 * Breaking the stump drops nothing and throws the source log's particles.
 */
public class ChoppingStumpBlock extends Block implements EntityBlock {
    public static final MapCodec<ChoppingStumpBlock> CODEC = simpleCodec(ChoppingStumpBlock::new);
    /** Matches the 6px-tall stump body the renderer draws. */
    public static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 6.0, 16.0);
    /** Chance each chopped log yields a firewood. */
    public static final float FIREWOOD_PER_LOG_CHANCE = 0.85f;
    /** Logs the stump can hold at once. */
    public static final int MAX_LOGS = 64;

    public ChoppingStumpBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<ChoppingStumpBlock> codec() {
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

    /** The stump body is drawn by the BER (using the source log's textures), so the static
     *  blockstate model must not render or it would double up. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChoppingStumpBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (type != BannerboundAntiquity.CHOPPING_STUMP_BE.get()) return null;
        return (lvl, pos, st, be) -> ChoppingStumpBlockEntity.tick(lvl, pos, st, (ChoppingStumpBlockEntity) be);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ChoppingStumpBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // Axe → chop ONE log into firewood (the pile shrinks a swing at a time).
        if (stack.getItem() instanceof AxeItem) {
            if (be.isEmpty()) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!level.isClientSide) {
                Block choppedLog = Block.byItem(be.getLogs().getItem());
                if (level.getRandom().nextFloat() < FIREWOOD_PER_LOG_CHANCE) {
                    Block.popResource(level, pos.above(),
                        new ItemStack(BannerboundAntiquity.FIREWOOD.get(), 1));
                }
                ItemStack remaining = be.getLogs().copy();
                remaining.shrink(1);
                be.setLogs(remaining);
                stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
                level.playSound(null, pos, SoundType.WOOD.getBreakSound(), SoundSource.BLOCKS, 1.0F, 0.9F);
                if (level instanceof ServerLevel sl && choppedLog != Blocks.AIR) {
                    sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, choppedLog.defaultBlockState()),
                        pos.getX() + 0.5, pos.getY() + 0.55, pos.getZ() + 0.5, 10, 0.25, 0.1, 0.25, 0.02);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        // Logs → deposit onto the stump (one type at a time, up to a stack).
        if (stack.is(ItemTags.LOGS)) {
            int held = be.isEmpty() ? 0 : be.getLogs().getCount();
            boolean sameType = be.isEmpty() || ItemStack.isSameItemSameComponents(be.getLogs(), stack);
            if (!sameType || held >= MAX_LOGS) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!level.isClientSide) {
                int move = Math.min(MAX_LOGS - held, stack.getCount());
                be.insert(stack.copyWithCount(held + move), player.getDirection().getOpposite());
                if (!player.hasInfiniteMaterials()) stack.shrink(move);
                level.playSound(null, pos, SoundType.WOOD.getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ChoppingStumpBlockEntity be) || be.isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            giveOrDrop(player, be.takeLogs());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof ChoppingStumpBlockEntity be && !be.isEmpty()) {
            Block.popResource(level, pos, be.getLogs());
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }

    /** Break particles use the source log's texture rather than the (invisible) stump model. The
     *  anonymous {@link IClientBlockExtensions} loads only on the client. (initializeClient is
     *  deprecated-for-removal but is the 1.21.1 registration point for per-block client extensions.) */
    @Override
    @SuppressWarnings("removal")
    public void initializeClient(java.util.function.Consumer<IClientBlockExtensions> consumer) {
        consumer.accept(new IClientBlockExtensions() {
            @Override
            public boolean addDestroyEffects(BlockState state, Level level, BlockPos pos, ParticleEngine engine) {
                if (level.getBlockEntity(pos) instanceof ChoppingStumpBlockEntity be) {
                    engine.destroy(pos, be.getLogType().defaultBlockState());
                    return true;
                }
                return false;
            }
        });
    }
}
