package com.bannerbound.antiquity.block;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.CraftingStoneBlockEntity;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Crafting Stone — a knapping workbench carved out of cobblestone/sandstone/red_sandstone by a
 * flint knife. Items are placed on it one at a time (mixed types allowed), held by the
 * {@link CraftingStoneBlockEntity}. When the pile matches a recipe, a floating spinning result
 * shows above it; shift-right-click crafts.
 * <ul>
 *   <li>Right-click with an item → add ONE of it to the pile.</li>
 *   <li>Right-click empty-handed → take the last item back out.</li>
 *   <li>Shift-right-click → craft (when a recipe is matched).</li>
 * </ul>
 */
public class CraftingStoneBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<CraftingStoneBlock> CODEC = simpleCodec(CraftingStoneBlock::new);
    /** Hugs the model's main stone (4–12, 0–7) so collision/selection match what's drawn. */
    public static final VoxelShape SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 7.0, 12.0);
    /** Which rock the stone was carved from — drives its texture variant + what it drops. */
    public static final EnumProperty<Material> MATERIAL = EnumProperty.create("material", Material.class);

    /** The carveable rocks, each with its own crafting-stone skin + drop. */
    public enum Material implements StringRepresentable {
        STONE("stone"),
        SANDSTONE("sandstone"),
        RED_SANDSTONE("red_sandstone");

        private final String name;

        Material(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public CraftingStoneBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(MATERIAL, Material.STONE));
    }

    @Override
    protected MapCodec<CraftingStoneBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MATERIAL);
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
     * The carved stone isn't a full block, so vanilla classifies the cell as walkable and citizens path
     * onto the workstation (standing on it, or snagging). Mark it un-pathfindable so the pathfinder routes
     * around it and the crafter stands beside it instead.
     */
    @Override
    protected boolean isPathfindable(BlockState state,
                                     net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CraftingStoneBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (type != BannerboundAntiquity.CRAFTING_STONE_BE.get()) return null;
        return (lvl, pos, st, be) -> CraftingStoneBlockEntity.tick(lvl, pos, st, (CraftingStoneBlockEntity) be);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CraftingStoneBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // Crafting on the block requires SHIFT (a plain click only adds/removes items, so finishing a
        // pile by accident no longer consumes it). The floating ghost preview is the no-shift craft.
        if (player.isSecondaryUseActive() && tryCraft(level, pos, be)) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (stack.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide
                && !com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())
                && be.insertOne(stack, player.getDirection().getOpposite())
                && !player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CraftingStoneBlockEntity be)) {
            return InteractionResult.PASS;
        }
        // A citizen mid-craft owns the stone — players can't pull the pile out from under them.
        if (!level.isClientSide
                && com.bannerbound.core.api.workshop.WorkBlockLocks.isLockedByOther(pos, player.getUUID())) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component
                    .translatable("bannerbound.workshop.station_busy")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW), true);
            return InteractionResult.CONSUME;
        }
        // SHIFT + empty-handed crafts a completed recipe. A plain empty-handed click never crafts —
        // it pulls the last item back off the stone, so a finished pile is never consumed by accident.
        if (player.isSecondaryUseActive() && tryCraft(level, pos, be)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide) {
            ItemStack out = be.removeOne();
            if (!out.isEmpty()) giveOrDrop(player, out);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Crafts when the stone has a valid result: consumes the pile, pops the result above. */
    private static boolean tryCraft(Level level, BlockPos pos, CraftingStoneBlockEntity be) {
        if (be.getResult().isEmpty()) return false;
        if (!level.isClientSide) {
            ItemStack out = be.craft();
            Block.popResource(level, pos.above(), out);
            level.playSound(null, pos, BannerboundAntiquity.KNAPPING_SOUND.get(),
                SoundSource.BLOCKS, 0.8F, 1.2F);
        }
        return true;
    }

    private static void giveOrDrop(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!oldState.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof CraftingStoneBlockEntity be) {
            for (ItemStack s : be.getContents()) {
                Block.popResource(level, pos, s);
            }
        }
        super.onRemove(oldState, level, pos, newState, moved);
    }
}
