package com.bannerbound.antiquity.block;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.FermentationTroughBlockEntity;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A Fermentation Trough — a hollowed-log vessel for the earliest alcohol (grog). Carved in place from
 * a log with a bone knife once the civ knows the Fermentation research (one block class per wood; see
 * {@code BannerboundAntiquity.FERMENTATION_TROUGH_BY_WOOD}). Right-click with a water bucket to fill
 * it; the {@link com.bannerbound.antiquity.client.FermentationTroughRenderer} draws the liquid surface
 * (and, once fermenting, the bubbling — GROG_PLAN.md Phase 2).
 *
 * <p>Adjacent troughs of the same wood + facing connect along the facing's clockwise axis into a
 * longer run (cosmetic, chest-style — each block keeps its own block entity and its own liquid, like
 * the Drying Rack). {@code LEFT} = a connecting trough on the clockwise side (→ the open
 * {@code connected_left} end), {@code RIGHT} = one on the counter-clockwise side.
 */
public class FermentationTroughBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<FermentationTroughBlock> CODEC = simpleCodec(FermentationTroughBlock::new);
    /** A connecting trough on the clockwise side — the model opens that end ({@code connected_left}). */
    public static final BooleanProperty LEFT = BooleanProperty.create("left");
    /** A connecting trough on the counter-clockwise side ({@code connected_right}). */
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");

    // A shallow tub: full footprint, 8px tall (the rim height). Short enough to step onto.
    private static final VoxelShape SHAPE = Block.box(0, 0, 1, 16, 8, 15);

    public FermentationTroughBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH).setValue(LEFT, false).setValue(RIGHT, false));
    }

    @Override
    protected MapCodec<FermentationTroughBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LEFT, RIGHT);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    // ─── Connection (cosmetic run, same wood + facing) ──────────────────────────────────────────────

    private boolean isPartner(BlockGetter level, BlockPos pos, Direction facing) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() == this && s.getValue(FACING) == facing;
    }

    /** A connected pool maxes out at this many troughs (left · middle · right). A physical line longer
     *  than this is split into back-to-back pools of ≤ 3. */
    public static final int MAX_RUN = 3;

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Just the facing; {@link #onPlace} refreshes the whole line's connections once it's in the world.
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        if (!oldState.is(state.getBlock()) && !level.isClientSide) {
            refreshLine(level, pos);
        }
    }

    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState, boolean moved) {
        boolean removed = !oldState.is(newState.getBlock());
        super.onRemove(oldState, level, pos, newState, moved);
        if (removed && !level.isClientSide && oldState.getBlock() instanceof FermentationTroughBlock) {
            Direction facing = oldState.getValue(FACING);
            // The two halves that survive on either side of the gap each re-group on their own.
            refreshLine(level, pos.relative(facing.getClockWise()));
            refreshLine(level, pos.relative(facing.getCounterClockWise()));
        }
    }

    /**
     * Recompute every trough's connection flags along the whole contiguous line through {@code pos},
     * grouping it into pools of at most {@link #MAX_RUN} from the counter-clockwise end. Inserting a
     * trough anywhere shifts the grouping, so the entire line is rewritten (not just the touched cell).
     * Uses {@code UPDATE_CLIENTS} (no neighbour notify), so it doesn't re-enter via shape updates.
     */
    public static void refreshLine(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FermentationTroughBlock self)) return;
        Direction facing = state.getValue(FACING);
        Direction cw = facing.getClockWise();
        Direction ccw = facing.getCounterClockWise();

        // Walk to the counter-clockwise end of the physical line, then assign groups travelling CW.
        BlockPos start = pos;
        while (self.isPartner(level, start.relative(ccw), facing)) start = start.relative(ccw);

        BlockPos p = start;
        int offset = 0;
        while (self.isPartner(level, p, facing)) {
            int idx = offset % MAX_RUN; // 0 = pool's left end, 1 = middle, 2 = right end
            boolean cwPartner = self.isPartner(level, p.relative(cw), facing);
            boolean left;   // open on the CW side (connected_left)
            boolean right;  // open on the CCW side (connected_right)
            if (idx == 0)      { left = cwPartner; right = false; }       // pool start
            else if (idx == 1) { left = cwPartner; right = true; }        // middle (CW joins only if present)
            else               { left = false;     right = true; }        // pool end — caps the run at 3
            BlockState cur = level.getBlockState(p);
            BlockState updated = cur.setValue(LEFT, left).setValue(RIGHT, right);
            if (updated != cur) level.setBlock(p, updated, Block.UPDATE_CLIENTS);
            p = p.relative(cw);
            offset++;
        }

        // Second pass: keep each resulting pool's fermentation state in lockstep. A cell joining a
        // fermenting pool inherits its grog; a pool with no charged cell is cleared. (Connections are
        // already set above, so runCells() now reflects the final ≤3 grouping.)
        BlockPos q = start;
        while (self.isPartner(level, q, facing)) {
            if (!level.getBlockState(q).getValue(RIGHT)) consolidatePool(level, q); // q is a pool start
            q = q.relative(cw);
        }
    }

    /** Copy any one charged cell's ferment state to every cell of its pool (or clear the pool if none
     *  is charged), so a connected pool always renders/serves as one batch. */
    private static void consolidatePool(Level level, BlockPos poolStart) {
        java.util.List<BlockPos> cells = runCells(level, poolStart);
        String id = "";
        long start = 0L;
        int ticks = 0;
        for (BlockPos c : cells) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be && be.isCharged()) {
                id = be.grogRecipeId();
                start = be.fermentStart();
                ticks = be.fermentTicks();
                break;
            }
        }
        for (BlockPos c : cells) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) {
                be.setFerment(id, start, ticks);
            }
        }
    }

    // ─── Shared liquid (the pool) ────────────────────────────────────────────────────────────────

    /** The troughs sharing this one's liquid pool — walked via the connection flags, so it honours the
     *  ≤ {@link #MAX_RUN} grouping. Capacity = cells × UNITS_PER_CELL. */
    public static java.util.List<BlockPos> runCells(BlockGetter level, BlockPos pos) {
        java.util.List<BlockPos> cells = new java.util.ArrayList<>();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FermentationTroughBlock self)) return cells;
        Direction facing = state.getValue(FACING);
        cells.add(pos);
        // Follow open ends: LEFT = open clockwise, RIGHT = open counter-clockwise. Stops at pool bounds.
        BlockPos cur = pos;
        BlockState cs = state;
        while (cs.getValue(LEFT)) {
            BlockPos n = cur.relative(facing.getClockWise());
            if (!self.isPartner(level, n, facing)) break;
            cells.add(n);
            cur = n;
            cs = level.getBlockState(n);
        }
        cur = pos;
        cs = state;
        while (cs.getValue(RIGHT)) {
            BlockPos n = cur.relative(facing.getCounterClockWise());
            if (!self.isPartner(level, n, facing)) break;
            cells.add(n);
            cur = n;
            cs = level.getBlockState(n);
        }
        return cells;
    }

    /** The run's shared fill 0..1 = total stored units ÷ capacity. Every cell renders its surface at
     *  this same level, so a connected run reads as one pool (and adding a cell drops the % on all). */
    public static float runFraction(BlockGetter level, BlockPos pos) {
        java.util.List<BlockPos> cells = runCells(level, pos);
        if (cells.isEmpty()) return 0.0F;
        int total = 0;
        for (BlockPos c : cells) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) total += be.units();
        }
        int cap = cells.size() * FermentationTroughBlockEntity.UNITS_PER_CELL;
        return cap <= 0 ? 0.0F : Math.min(1.0F, total / (float) cap);
    }

    /** Add up to {@code units} of water to the run's shared pool, spilling cell-to-cell. Returns true
     *  if any was added (false if the run is already full). */
    private static boolean addWaterToRun(Level level, BlockPos pos, int units) {
        int remaining = units;
        for (BlockPos c : runCells(level, pos)) {
            if (remaining <= 0) break;
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) {
                remaining -= be.addUnits(remaining);
            }
        }
        return remaining < units;
    }

    /** Start the whole pool fermenting into {@code recipe}, if it isn't already charged and holds at
     *  least the recipe's water. Warmth (a nearby fire/campfire/lava) shortens the ferment. */
    private static boolean chargePool(Level level, BlockPos pos, String recipeId,
                                      com.bannerbound.antiquity.recipe.GrogRecipe recipe) {
        java.util.List<BlockPos> cells = runCells(level, pos);
        int water = 0;
        for (BlockPos c : cells) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) {
                if (be.isCharged()) return false; // already fermenting / holds grog
                water += be.units();
            }
        }
        if (water < recipe.minWaterUnits()) return false;
        int ticks = fermentTicksWithWarmth(level, pos, recipe.fermentTicks());
        long now = level.getGameTime();
        for (BlockPos c : cells) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) {
                be.charge(recipeId, now, ticks);
            }
        }
        return true;
    }

    /** Ferment duration, cut to 60% when a heat source (fire, lit campfire, lava, magma) sits within
     *  the 3×3×3 — warmth speeds fermentation (a fire under the trough is the idiom). */
    private static int fermentTicksWithWarmth(Level level, BlockPos pos, int baseTicks) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
            BlockState s = level.getBlockState(p);
            boolean hot = s.is(net.minecraft.world.level.block.Blocks.FIRE)
                || s.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                || s.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                || level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA)
                || (s.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock
                    && s.getValue(net.minecraft.world.level.block.CampfireBlock.LIT));
            if (hot) return Math.max(1, (int) (baseTicks * 0.6F));
        }
        return baseTicks;
    }

    // ─── NPC seams (BrewerExecutor — the trough-tending crafter) ────────────────────────────────

    /** The pool's total water capacity in units (cells × {@link FermentationTroughBlockEntity#UNITS_PER_CELL}). */
    public static int poolCapacity(Level level, BlockPos pos) {
        return runCells(level, pos).size() * FermentationTroughBlockEntity.UNITS_PER_CELL;
    }

    /** NPC water-scoop into the pool: same shared-pool spill as a player's bucket. Returns true if
     *  any was added; refuses a charged pool (no diluting a fermenting batch), like the player path. */
    public static boolean npcAddWater(Level level, BlockPos pos, int units) {
        if (isPoolCharged(level, pos)) return false;
        return addWaterToRun(level, pos, units);
    }

    /** NPC charge: start the pool fermenting from a grog-input item. False when no recipe matches,
     *  the pool is already charged, or it holds too little water — the caller redeposits the item. */
    public static boolean npcCharge(Level level, BlockPos pos, net.minecraft.world.item.Item input) {
        java.util.Map.Entry<net.minecraft.resources.ResourceLocation,
            com.bannerbound.antiquity.recipe.GrogRecipe> match =
            com.bannerbound.antiquity.recipe.GrogRecipeManager.findForInput(input);
        return match != null && chargePool(level, pos, match.getKey().toString(), match.getValue());
    }

    /** A water block within hand-scoop range (the same 3×3×3 the player scoop uses) of ANY cell of
     *  the pool through {@code pos}, or {@code null}. The brewer walks to the returned cell's side. */
    @Nullable
    public static BlockPos findScoopWater(Level level, BlockPos pos) {
        for (BlockPos cell : runCells(level, pos)) {
            for (BlockPos p : BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 1, 1))) {
                if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) {
                    return cell.immutable();
                }
            }
        }
        return null;
    }

    // ─── Grog servings (Phase 3) ─────────────────────────────────────────────────────────────────

    /** Total liquid units across the pool. */
    public static int poolUnits(Level level, BlockPos pos) {
        int total = 0;
        for (BlockPos c : runCells(level, pos)) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) total += be.units();
        }
        return total;
    }

    /** True if any cell of the pool is charged (fermenting or holding finished grog). */
    public static boolean isPoolCharged(Level level, BlockPos pos) {
        for (BlockPos c : runCells(level, pos)) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be && be.isCharged()) {
                return true;
            }
        }
        return false;
    }

    /** The pool's grog recipe once finished fermenting, or {@code null} (still fermenting / plain water). */
    @Nullable
    private static com.bannerbound.antiquity.recipe.GrogRecipe readyGrog(Level level, BlockPos pos) {
        long now = level.getGameTime();
        for (BlockPos c : runCells(level, pos)) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be && be.grogReady(now)) {
                return com.bannerbound.antiquity.recipe.GrogRecipeManager.byId(be.grogRecipeId());
            }
        }
        return null;
    }

    /** Drain one serving (one unit) from the pool; when the last unit goes, the grog is gone and the
     *  pool resets to empty (ferment cleared on every cell). */
    private static void drainServing(Level level, BlockPos pos) {
        java.util.List<BlockPos> cells = runCells(level, pos);
        boolean drained = false;
        for (BlockPos c : cells) {
            if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be && be.removeUnit()) {
                drained = true;
                break;
            }
        }
        if (drained && poolUnits(level, pos) <= 0) {
            for (BlockPos c : cells) {
                if (level.getBlockEntity(c) instanceof FermentationTroughBlockEntity be) {
                    be.setFerment("", 0L, 0);
                }
            }
        }
    }

    /** True if the pool at {@code pos} has a finished grog serving available (used by the citizen
     *  {@code TavernGoal} via {@link com.bannerbound.antiquity.block.entity.FermentationTroughBlockEntity}'s
     *  {@code GrogSource}). */
    public static boolean hasReadyServing(Level level, BlockPos pos) {
        return readyGrog(level, pos) != null && poolUnits(level, pos) > 0;
    }

    /** Take one finished serving from the pool (a citizen drinking at the trough); false if none was
     *  available. Drains exactly like a player's communal trough-drink, including clearing the grog
     *  when the last unit goes. */
    public static boolean takeServing(Level level, BlockPos pos) {
        if (!hasReadyServing(level, pos)) return false;
        drainServing(level, pos);
        return true;
    }

    /** Pour a serving into the held vessel: a copy stamped with the grog, consuming one from the stack
     *  (or filling in place when it's the last one). */
    private static void fillVessel(Player player, InteractionHand hand, ItemStack stack,
                                   com.bannerbound.antiquity.recipe.GrogRecipe grog) {
        ItemStack filled = new ItemStack(stack.getItem());
        filled.set(BannerboundAntiquity.GROG_CONTENTS.get(), new com.bannerbound.antiquity.item.GrogContents(
            grog.name(), grog.tint(), grog.strength(), grog.foodValue(), grog.effects()));
        if (player.hasInfiniteMaterials()) {
            if (!player.getInventory().add(filled)) player.drop(filled, false);
        } else if (stack.getCount() == 1) {
            player.setItemInHand(hand, filled);
        } else {
            stack.shrink(1);
            if (!player.getInventory().add(filled)) player.drop(filled, false);
        }
    }

    // ─── Block entity ──────────────────────────────────────────────────────────────────────────────

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FermentationTroughBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (level.isClientSide) return null; // the "ready" cue is server-emitted to all viewers
        return type == BannerboundAntiquity.FERMENTATION_TROUGH_BE.get()
            ? (lvl, pos, st, be) -> FermentationTroughBlockEntity.serverTick(
                lvl, pos, st, (FermentationTroughBlockEntity) be)
            : null;
    }

    /** Client ambience while a pool ferments: bubbles popping at the surface + an occasional soft brew. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos,
                            net.minecraft.util.RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof FermentationTroughBlockEntity be)
                || !be.fermenting(level.getGameTime())) {
            return;
        }
        double surfaceY = pos.getY() + (2.0 + runFraction(level, pos) * 4.5) / 16.0 + 0.02;
        if (random.nextInt(2) == 0) {
            level.addParticle(net.minecraft.core.particles.ParticleTypes.BUBBLE_POP,
                pos.getX() + 0.25 + random.nextDouble() * 0.5, surfaceY,
                pos.getZ() + 0.25 + random.nextDouble() * 0.5, 0.0, 0.0, 0.0);
        }
        if (random.nextInt(50) == 0) {
            level.playLocalSound(pos.getX() + 0.5, surfaceY, pos.getZ() + 0.5,
                SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS,
                0.4F, 0.7F + random.nextFloat() * 0.3F, false);
        }
    }

    @Nullable
    private static FermentationTroughBlockEntity trough(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FermentationTroughBlockEntity be ? be : null;
    }

    // ─── Interaction ─────────────────────────────────────────────────────────────────────────────

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        FermentationTroughBlockEntity be = trough(level, pos);
        if (be == null) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        // Fill with water from a bucket (vanilla iron or the era's fired-clay water bucket). Blocked
        // once the pool is charged — no diluting a fermenting/finished batch.
        boolean vanillaWater = stack.is(Items.WATER_BUCKET);
        boolean clayWater = stack.is(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get());
        if ((vanillaWater || clayWater) && !isPoolCharged(level, pos)) {
            // A bucket pours a whole bucket (one cell's worth) into the shared pool.
            if (!level.isClientSide
                    && addWaterToRun(level, pos, FermentationTroughBlockEntity.UNITS_PER_CELL)) {
                if (!player.hasInfiniteMaterials()) {
                    ItemStack empty = new ItemStack(vanillaWater
                        ? Items.BUCKET : BannerboundAntiquity.CLAY_FIRED_BUCKET.get());
                    player.setItemInHand(hand, empty);
                }
                level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.8F, 1.0F);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Charge with a fermentable → start the water fermenting into grog.
        java.util.Map.Entry<net.minecraft.resources.ResourceLocation,
            com.bannerbound.antiquity.recipe.GrogRecipe> match =
            com.bannerbound.antiquity.recipe.GrogRecipeManager.findForInput(stack.getItem());
        if (match != null) {
            if (!level.isClientSide
                    && chargePool(level, pos, match.getKey().toString(), match.getValue())) {
                if (!player.hasInfiniteMaterials()) stack.shrink(1);
                level.playSound(null, pos, SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 0.6F, 0.8F);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Fill an empty mug / horn from a finished grog pool (one serving = one unit).
        if (stack.getItem() instanceof com.bannerbound.antiquity.item.GrogVesselItem
                && !stack.has(BannerboundAntiquity.GROG_CONTENTS.get())) {
            com.bannerbound.antiquity.recipe.GrogRecipe grog = readyGrog(level, pos);
            if (grog != null && poolUnits(level, pos) > 0) {
                if (!level.isClientSide) {
                    fillVessel(player, hand, stack, grog);
                    drainServing(level, pos);
                    level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 0.7F, 1.0F);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                              Player player, BlockHitResult hit) {
        FermentationTroughBlockEntity be = trough(level, pos);
        if (be == null) return InteractionResult.PASS;

        // Empty hand on a finished pool = drink straight from the trough (communal): food + the grog's
        // effects + a tier of intoxication. (Holding a mug/horn instead fills it — see useItemOn.)
        com.bannerbound.antiquity.recipe.GrogRecipe grog = readyGrog(level, pos);
        if (grog != null && poolUnits(level, pos) > 0) {
            if (!level.isClientSide) {
                com.bannerbound.antiquity.item.Intoxication.sip(
                    player, grog.effects(), grog.strength(), grog.foodValue());
                drainServing(level, pos);
                level.playSound(null, pos, SoundEvents.GENERIC_DRINK, SoundSource.BLOCKS, 0.6F, 1.0F);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // Otherwise hand-scoop water from an adjacent source for the next batch (only into an uncharged pool).
        if (!isPoolCharged(level, pos) && hasNearbyWater(level, pos)) {
            if (!level.isClientSide && addWaterToRun(level, pos, 1)) {
                level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.7F, 1.1F);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    /** True if any water (source or flow) sits within the 3×3×3 around the trough — what an empty hand
     *  scoops from. Wider than touching, so a trough carved a block or so off a stream/pond still fills
     *  (water rarely sits flush against the rim). */
    private static boolean hasNearbyWater(Level level, BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
            if (level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /** Open-top trough: catch rain like a cauldron, so it fills even away from a water source. */
    @Override
    public void handlePrecipitation(BlockState state, Level level, BlockPos pos,
                                    net.minecraft.world.level.biome.Biome.Precipitation precipitation) {
        if (precipitation == net.minecraft.world.level.biome.Biome.Precipitation.RAIN
                && level.getRandom().nextFloat() < 0.25F) {
            addWaterToRun(level, pos, 1);
        }
    }
}
