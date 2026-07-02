package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.LeashRopeEvents;
import com.bannerbound.antiquity.RopeAnchor;
import com.bannerbound.antiquity.RopeTies;
import com.bannerbound.antiquity.block.entity.RopeFencePostBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A Rope Fence Post — one rope tie point (slot 0, its centre). Right-click two posts/gate-uprights in
 * turn with a {@code fiber_rope} to tie a rope between them; the rope is drawn client-side as a
 * leash-style catenary and faked as a fence by {@link RopeTies}. The {@link #ROPED} blockstate shows
 * the rope-wrap coil once it has a connection. One block class serves every wood type (the wood is
 * just the model); all rope-fence posts share one {@code ROPE_FENCE_POST_BE}.
 */
public class RopeFencePostBlock extends Block implements EntityBlock {
    public static final MapCodec<RopeFencePostBlock> CODEC = simpleCodec(RopeFencePostBlock::new);
    /** True once roped to at least one other tie point — selects the roped post model. */
    public static final BooleanProperty ROPED = BooleanProperty.create("roped");
    /** Visual outline: a 4×16×4 post, matching the model's main column ([6,0,6]→[10,16,10]). */
    public static final VoxelShape SHAPE = Block.box(6.0, 0.0, 6.0, 10.0, 16.0, 10.0);
    /** Collision: 1.5 tall (vanilla fence height) — jump-proof, with natural standing on top. */
    public static final VoxelShape COLLISION_SHAPE = Block.box(6.0, 0.0, 6.0, 10.0, 24.0, 10.0);

    public RopeFencePostBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ROPED, Boolean.FALSE));
    }

    @Override
    protected MapCodec<RopeFencePostBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ROPED);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return COLLISION_SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RopeFencePostBlockEntity(pos, state);
    }

    /** Impassable to pathfinding, like a vanilla fence post — mobs route around it instead of walking
     *  into the rope and getting shoved by the analytical collision (it's also in {@code minecraft:fences}). */
    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    /** Non-shift right-click with fiber rope ties from this post; shift/other items fall through (so
     *  fiber rope's shift+right-click spear reel still works). */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(BannerboundAntiquity.FIBER_ROPE.get()) || player.isShiftKeyDown()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // Leading animals on the rope? Tie them to this post (vanilla lead-to-fence) instead of
        // starting a post-to-post rope tie — the leash holder is synced, so both sides agree.
        if (LeashRopeEvents.hasLedAnimalsNear(player, pos)) {
            if (!level.isClientSide) {
                LeashRopeEvents.tieLedAnimalsToFence(player, level, pos);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        RopeAnchor anchor = new RopeAnchor(pos, 0);
        if (level.isClientSide) {
            RopeTies.handleTieClient(level, anchor);
            return ItemInteractionResult.sidedSuccess(true);
        }
        if (RopeTies.handleTieServer(level, player, anchor) && !player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        return ItemInteractionResult.sidedSuccess(false);
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        // Only on an actual break/replace (not our own roped-property flip, where the block is unchanged).
        if (!oldState.is(newState.getBlock()) && !level.isClientSide
                && level.getBlockEntity(pos) instanceof RopeFencePostBlockEntity be) {
            RopeTies.breakAllAndDrop(level, pos, be);
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
