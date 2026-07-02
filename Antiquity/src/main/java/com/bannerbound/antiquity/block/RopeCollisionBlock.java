package com.bannerbound.antiquity.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Invisible marker placed in the cells a rope spans (by {@code RopeTies}, removed when the rope breaks).
 * It has <b>no collision shape of its own</b> — block collision is axis-aligned and can't follow a
 * diagonal rope smoothly (a real band staircases and fights the smooth clamp), so the actual blocking is
 * done analytically per tick by {@code RopeFenceCollision} against the rope's true segment. This block:
 * <ul>
 *   <li><b>stores the rope line</b> — {@link #ROTATION} (16-step {@code atan2(dz,dx)}) + {@link #OFFSET}
 *       (where the line crosses this cell), used by mob pathing / future tooling;</li>
 *   <li><b>is a pathfinding obstacle</b> — {@link #isPathfindable} false, so mobs route <em>around</em>
 *       the rope instead of walking into the invisible wall and jittering.</li>
 * </ul>
 * It renders nothing and can't be targeted/selected.
 */
public class RopeCollisionBlock extends Block {
    public static final MapCodec<RopeCollisionBlock> CODEC = simpleCodec(RopeCollisionBlock::new);
    /** Rope's horizontal direction through this cell, 0–15 around the compass (atan2(dz, dx) convention,
     *  set on placement). Reuses the vanilla 16-step property as plain storage — the block is invisible. */
    public static final IntegerProperty ROTATION = BlockStateProperties.ROTATION_16;
    /** Perpendicular position of the rope line within this cell, in px from centre, biased by
     *  {@link #OFFSET_ZERO} (so 0 → −{@value #OFFSET_ZERO}px, {@value #OFFSET_ZERO} → centred). */
    public static final IntegerProperty OFFSET = IntegerProperty.create("offset", 0, 20);
    /** Index that means "rope crosses the cell centre"; px offset = value − OFFSET_ZERO. */
    public static final int OFFSET_ZERO = 10;
    public static final int OFFSET_MAX = 20;

    public RopeCollisionBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ROTATION, 0).setValue(OFFSET, OFFSET_ZERO));
    }

    @Override
    protected MapCodec<RopeCollisionBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ROTATION, OFFSET);
    }

    /** No model — the rope mesh from the posts' renderer is the only thing the player sees here. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /** Empty shape everywhere: invisible and un-targetable. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    /** SPLIT collision, per entity kind — this is what finally makes the rope non-janky for NPCs:
     *  <ul>
     *    <li><b>Mobs (non-player living entities): a REAL solid cell.</b> Pathfinding and physics then
     *        agree by construction — vanilla {@code move()} enforces the wall, so there is no
     *        clamp-vs-path fight, no side-tracking, no jump-hop loop, no way to squeeze through a
     *        junction. Mobs don't care that a diagonal rope's block wall is staircased.</li>
     *    <li><b>Players (and non-living: items, projectiles): EMPTY.</b> The player's blocking stays the
     *        smooth analytical clamp in {@code RopeFenceCollision} — a block band under the player was
     *        tried and rejected (janky/staircased feel on diagonals, and block collision dominates the
     *        smooth clamp when both act on the same entity).</li>
     *  </ul>
     *  The analytical clamp still runs for mobs as a BACKSTOP for the cells markers can't occupy
     *  (non-replaceable span cells, the gap between directly-adjacent posts). */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        if (ctx instanceof EntityCollisionContext entityCtx
                && entityCtx.getEntity() instanceof LivingEntity
                && !(entityCtx.getEntity() instanceof Player)) {
            return Shapes.block();
        }
        return Shapes.empty();
    }

    /** Mobs treat the rope cell as blocked and path around it (the analytical wall stops anything that
     *  still tries to cross — e.g. a mob shoved into it). */
    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }
}
