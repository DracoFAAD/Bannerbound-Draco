package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.StoneCookingPotBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The stone cooking pot — a single placeable block (5 cobblestone + 1 stick at the crafting stone).
 * Fill it with water (held pot on a water source, or a water bucket on the placed pot), set it on a
 * lit campfire that isn't the town hall, add food, and it cooks into a stew. Right-click a finished
 * pot empty-handed to eat a serving; the settlement larder eats the rest over time. See
 * {@link StoneCookingPotBlockEntity}.
 */
public class StoneCookingPotBlock extends Block implements EntityBlock {
    public static final MapCodec<StoneCookingPotBlock> CODEC = simpleCodec(StoneCookingPotBlock::new);
    /** Holds water or a stew → renders the filled (water/stew) model instead of the empty pot. */
    public static final BooleanProperty FILLED = BooleanProperty.create("filled");
    /** True when a campfire is directly below — the pot then renders + collides DROPPED so it sits on
     *  the fire instead of floating in the block above. False on the ground (the pot sits normally). */
    public static final BooleanProperty ON_FIRE = BooleanProperty.create("on_fire");

    private static final VoxelShape SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 11.0, 15.0);
    private static final double DROP_PX = StoneCookingPotBlockEntity.VISUAL_DROP * 16.0;
    /** On a campfire the pot is rendered DROPPED onto the fire; the shape follows so selection/collision
     *  hugs the lowered pot. It reaches below this block into the campfire's space — whose own collision
     *  is a short slab, so clicks still reach the pot. */
    private static final VoxelShape SHAPE_ON_FIRE =
        Block.box(1.0, -DROP_PX, 1.0, 15.0, 11.0 - DROP_PX, 15.0);

    public StoneCookingPotBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FILLED, false).setValue(ON_FIRE, false));
    }

    @Override
    protected MapCodec<StoneCookingPotBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FILLED, ON_FIRE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(ON_FIRE) ? SHAPE_ON_FIRE : SHAPE;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        // Partial-collision block — keep NPCs from snagging on it.
        return false;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(ON_FIRE, isOnCampfire(ctx.getLevel(), ctx.getClickedPos()));
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbor,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // Keep ON_FIRE in step with the block below: light/place/break a campfire under the pot and it
        // drops onto / lifts off the fire.
        if (dir == Direction.DOWN) {
            boolean onFire = neighbor.getBlock() instanceof CampfireBlock;
            if (state.getValue(ON_FIRE) != onFire) {
                return state.setValue(ON_FIRE, onFire);
            }
        }
        return super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    /** True if the block directly below is a campfire (the pot sits ON it → dropped render + shape). */
    private static boolean isOnCampfire(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos.below()).getBlock() instanceof CampfireBlock;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        hideCampfireFlame(level, pos);   // swap the campfire below for the flame-less variant
    }

    /** Swap a vanilla campfire directly below {@code potPos} for the flame-less {@link CookingFireBlock}
     *  (preserving its state) so no flame pokes up through the pot. A real block change always re-meshes,
     *  so this works under any renderer (Sodium/Iris). Server-only; safe to call repeatedly (no-op once
     *  already swapped). Called on pot placement AND from the pot's tick so already-placed pots in a
     *  loaded world get swapped too. */
    public static void hideCampfireFlame(Level level, BlockPos potPos) {
        if (level.isClientSide) return;
        BlockPos cf = potPos.below();
        BlockState below = level.getBlockState(cf);
        if (below.getBlock() == net.minecraft.world.level.block.Blocks.CAMPFIRE) {
            BlockState cfState = copyCampfireState(
                BannerboundAntiquity.COOKING_FIRE.get().defaultBlockState(), below);
            level.setBlock(cf, cfState, Block.UPDATE_ALL);
            // The cooking fire emits less light than the campfire it replaced (10 vs 15). A swap that
            // LOWERS a light source can leave the light field stale (old light removed, new not
            // re-propagated) → the area goes dark. Force a re-evaluation so it re-lights to 10.
            level.getLightEngine().checkBlock(cf);
            BannerboundAntiquity.LOGGER.info("[cookingfire] swapped: lit={} emission={}",
                cfState.getValue(CampfireBlock.LIT), cfState.getLightEmission());
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        super.onRemove(state, level, pos, newState, moved);
        // Pot removed → swap the cooking fire back to a normal campfire (its flame returns).
        if (!level.isClientSide && !newState.is(this)) {
            BlockPos cf = pos.below();
            BlockState below = level.getBlockState(cf);
            if (below.getBlock() instanceof CookingFireBlock) {
                level.setBlock(cf, copyCampfireState(
                    net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState(), below),
                    Block.UPDATE_ALL);
            }
        }
    }

    /** Copy a campfire's facing/lit/signal-fire/waterlogged onto {@code target} (for the campfire ↔
     *  cooking-fire swap, so the swapped block keeps the same orientation and lit/water state). */
    private static BlockState copyCampfireState(BlockState target, BlockState src) {
        return target
            .setValue(CampfireBlock.FACING, src.getValue(CampfireBlock.FACING))
            .setValue(CampfireBlock.LIT, src.getValue(CampfireBlock.LIT))
            .setValue(CampfireBlock.SIGNAL_FIRE, src.getValue(CampfireBlock.SIGNAL_FIRE))
            .setValue(CampfireBlock.WATERLOGGED, src.getValue(CampfireBlock.WATERLOGGED));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        // A pot placed from a water-filled item starts with water in it.
        if (!level.isClientSide
                && com.bannerbound.antiquity.item.StoneCookingPotItem.isFilled(stack)
                && level.getBlockEntity(pos) instanceof StoneCookingPotBlockEntity be) {
            be.setWater(true);
        }
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StoneCookingPotBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        if (type != BannerboundAntiquity.STONE_COOKING_POT_BE.get()) return null;
        return (lvl, pos, st, be) -> StoneCookingPotBlockEntity.tick(lvl, pos, st, (StoneCookingPotBlockEntity) be);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof StoneCookingPotBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // A finished stew can't take more ingredients — empty-hand eating runs in useWithoutItem.
        if (be.hasStew()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Empty pot + a water bucket → fill it (vanilla or the mod's fired-clay water bucket).
        if (!be.hasWater()) {
            ItemStack emptied = emptiedWaterBucket(stack);
            if (emptied != null) {
                if (!level.isClientSide) {
                    be.setWater(true);
                    if (!player.hasInfiniteMaterials()) {
                        player.setItemInHand(hand, emptied);
                    }
                    level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.8F, 1.0F);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Has water, no stew yet → add a food ingredient.
        if (!stack.isEmpty()) {
            if (!level.isClientSide && be.addIngredient(stack)) {
                if (!player.hasInfiniteMaterials()) stack.shrink(1);
                return ItemInteractionResult.sidedSuccess(false);
            }
            return level.isClientSide ? ItemInteractionResult.sidedSuccess(true)
                                      : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof StoneCookingPotBlockEntity be) || !be.hasStew()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        return be.eatServing(player) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    /** True if the pot holds a finished, non-poisoned stew a citizen could eat (used by {@code StewEatGoal}),
     *  mirroring {@code FermentationTroughBlock.hasReadyServing}. */
    public static boolean hasReadyServing(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof StoneCookingPotBlockEntity be
            && be.hasStew() && !be.stew().poisoned();
    }

    /** Take one serving for a citizen drinking/eating at the pot; false if none was available. */
    public static boolean takeServing(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof StoneCookingPotBlockEntity be && be.takeServing();
    }

    /** The empty bucket a water bucket leaves behind, or null if {@code stack} isn't a water bucket. */
    @Nullable
    private static ItemStack emptiedWaterBucket(ItemStack stack) {
        if (stack.is(Items.WATER_BUCKET)) {
            return new ItemStack(Items.BUCKET);
        }
        if (stack.is(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get())) {
            return new ItemStack(BannerboundAntiquity.CLAY_FIRED_BUCKET.get());
        }
        return null;
    }
}
