package com.bannerbound.antiquity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.antiquity.block.BloomeryBlock;
import com.bannerbound.antiquity.block.CraftingStoneBlock;
import com.bannerbound.antiquity.block.MasonsBenchBlock;
import com.bannerbound.antiquity.block.TanningRackBlock;
import com.bannerbound.antiquity.block.WoodworkingTableBlock;
import com.bannerbound.antiquity.item.KnifeItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The canonical "right-click a block with a tool → it becomes another block" table — the single
 * source of truth for the world-interaction carves that {@link AntiquityEvents} executes (knife on
 * cobble → Crafting Stone, bone blade on a log → Drying Rack, axe on a lone log → Chopping Stump,
 * …). Each provider does only the <em>detection and result-state construction</em>: no side effects,
 * no block placement, no sounds, no durability, no research gate. It answers the question "if the
 * player committed this gesture right here, which positions would become which blockstates?".
 *
 * <p>The matching client-side ghost preview ({@code CarvePreviewController} /
 * {@code CarveGhostRenderer}) consumes this directly, so the silhouette the player sees is computed
 * from the exact same logic that performs the carve. The handlers in {@link AntiquityEvents} still
 * own the <em>execution</em> (placement + side effects + authoritative gating); the intent is that
 * they read their target state from here too, so detection can never drift between the preview and
 * the real carve. Until a handler is folded in, keep its detection in lockstep with the provider of
 * the same name below.
 *
 * <p>Order matters: the Tanning Rack (a 2×2 log square) is tried before the single-log carves,
 * mirroring how {@link AntiquityEvents#onCarveDryingRack}/{@code onCarveFermentationTrough} defer to
 * it when the click lands on a full square.
 */
public final class Carves {

    /** The positions a carve would write, and the item whose unlock state gates it (for preview). */
    public record Result(Map<BlockPos, BlockState> blocks, Item gateItem) {}

    /** Resolves a gesture into the blocks it would produce, or {@code null} if it isn't a carve. */
    @FunctionalInterface
    public interface Provider {
        @Nullable Result resolve(Level level, BlockPos pos, Player player, ItemStack held);
    }

    private static final List<Provider> PROVIDERS = List.of(
        Carves::craftingStone,
        Carves::tanningRack,          // 2×2 log square — must precede the single-log carves
        Carves::dryingRack,
        Carves::fermentationTrough,
        Carves::choppingStump,
        Carves::woodworkingTable,
        Carves::masonsBench,
        Carves::bloomery,
        Carves::clayedCobblestone
    );

    private Carves() {}

    /** The first carve that matches this gesture, or {@code null}. */
    public static @Nullable Result resolve(Level level, BlockPos pos, Player player, ItemStack held) {
        if (held.isEmpty()) {
            return null;
        }
        for (Provider provider : PROVIDERS) {
            Result result = provider.resolve(level, pos, player, held);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Server-authoritative commit for the in-world ghost preview. The previewed block is hidden
     * (air) on the client and so can't be ray-clicked — the use-key press arrives as
     * {@link com.bannerbound.antiquity.network.CarveCommitPayload} and lands here. Rather than
     * duplicate any placement/side-effect logic, this replays the gesture through the normal
     * interaction pipeline, which re-fires the real {@code AntiquityEvents} carve handler with all
     * of its gating, sounds, particles and durability intact.
     */
    public static void commit(ServerPlayer player, BlockPos anchor) {
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(anchor)) {
            return;
        }
        // Reach sanity bound — the client supplies the anchor, so never act on a distant one.
        if (player.distanceToSqr(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5) > 64.0) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        // Only replay the use-on if the server agrees this is a carve setup, so a spoofed payload
        // can't trigger an arbitrary right-click (e.g. placing a held block) at the anchor.
        if (resolve(level, anchor, player, held) == null) {
            return;
        }
        BlockHitResult hit = new BlockHitResult(
            Vec3.atCenterOf(anchor), player.getDirection().getOpposite(), anchor, false);
        player.gameMode.useItemOn(player, level, held, InteractionHand.MAIN_HAND, hit);
    }

    // ── Providers (mirror the like-named handlers in AntiquityEvents) ──────────────────────────

    /** Any knife or the flint blade carves cobblestone/sandstone/red_sandstone into a Crafting Stone,
     *  skinned to the source rock. Mirrors {@link KnifeItem#useOn} + {@code onCarveCraftingStoneWithFlintBlade}. */
    private static @Nullable Result craftingStone(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!(held.getItem() instanceof KnifeItem) && !held.is(BannerboundAntiquity.FLINT_BLADE.get())) {
            return null;
        }
        CraftingStoneBlock.Material material = KnifeItem.materialFor(level.getBlockState(pos));
        if (material == null) {
            return null;
        }
        BlockState result = BannerboundAntiquity.CRAFTING_STONE.get().defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, player.getDirection().getOpposite())
            .setValue(CraftingStoneBlock.MATERIAL, material);
        return single(pos, result, BannerboundAntiquity.CRAFTING_STONE.get().asItem());
    }

    /** A cutting tool on a 2×2 log square → the Tanning Rack 2×2 multiblock. Mirrors {@code onCarveTanningRack}. */
    private static @Nullable Result tanningRack(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(AntiquityEvents.CUTTING_TOOLS) || !level.getBlockState(pos).is(BlockTags.LOGS)) {
            return null;
        }
        Direction facing = player.getDirection().getOpposite();
        Direction width = facing.getClockWise();
        BlockPos master = findLogSquare(level, pos, width);
        if (master == null) {
            return null;
        }
        BlockState base = BannerboundAntiquity.TANNING_RACK.get().defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, facing);
        BlockPos w = master.relative(width);
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(master, base.setValue(TanningRackBlock.PART, 0));
        blocks.put(w, base.setValue(TanningRackBlock.PART, 1));
        blocks.put(master.above(), base.setValue(TanningRackBlock.PART, 2));
        blocks.put(w.above(), base.setValue(TanningRackBlock.PART, 3));
        return new Result(blocks, BannerboundAntiquity.TANNING_RACK.get().asItem());
    }

    /** Bone blade on a single log → a per-wood Drying Rack. Mirrors {@code onCarveDryingRack}. */
    private static @Nullable Result dryingRack(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(BannerboundAntiquity.BONE_BLADE.get())) {
            return null;
        }
        BlockState logState = level.getBlockState(pos);
        if (!logState.is(BlockTags.LOGS)) {
            return null;
        }
        Direction facing = player.getDirection().getOpposite();
        if (findLogSquare(level, pos, facing.getClockWise()) != null) {
            return null; // a full 2×2 square is a Tanning Rack
        }
        var rack = BannerboundAntiquity.DRYING_RACK_BY_WOOD.getOrDefault(
            woodKey(logState.getBlock(), BannerboundAntiquity.DRYING_RACK_BY_WOOD),
            BannerboundAntiquity.DRYING_RACK_BY_WOOD.get("oak"));
        BlockState result = rack.get().defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, facing);
        return single(pos, result, rack.get().asItem());
    }

    /** Bone knife on a single log → a per-wood Fermentation Trough. Mirrors {@code onCarveFermentationTrough}. */
    private static @Nullable Result fermentationTrough(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(BannerboundAntiquity.BONE_KNIFE.get())) {
            return null;
        }
        BlockState logState = level.getBlockState(pos);
        if (!logState.is(BlockTags.LOGS)) {
            return null;
        }
        Direction facing = player.getDirection().getOpposite();
        if (findLogSquare(level, pos, facing.getClockWise()) != null) {
            return null; // a full 2×2 square is a Tanning Rack
        }
        var trough = BannerboundAntiquity.FERMENTATION_TROUGH_BY_WOOD.getOrDefault(
            woodKey(logState.getBlock(), BannerboundAntiquity.FERMENTATION_TROUGH_BY_WOOD),
            BannerboundAntiquity.FERMENTATION_TROUGH_BY_WOOD.get("oak"));
        BlockState result = trough.get().defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, facing);
        return single(pos, result, trough.get().asItem());
    }

    /** Any axe on a lone log (no log among its 6 neighbours) → a Chopping Stump. Mirrors {@code onChopLoneLog}.
     *  The stump's log skin is a block-entity detail, so the ghost shows the default model. */
    private static @Nullable Result choppingStump(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!(held.getItem() instanceof AxeItem) || !level.getBlockState(pos).is(BlockTags.LOGS)) {
            return null;
        }
        for (Direction d : Direction.values()) {
            BlockState logState = level.getBlockState(pos.relative(d));
            if (logState.is(BlockTags.LOGS) || logState.is(BlockTags.LEAVES)) {
                return null; // part of a tree/cluster
            }
        }
        return single(pos, BannerboundAntiquity.CHOPPING_STUMP.get().defaultBlockState(),
            BannerboundAntiquity.CHOPPING_STUMP.get().asItem());
    }

    /** Bone saw on two adjacent logs → the Woodworking Table 2-block multiblock. Mirrors {@code onFormWoodworkingTable}. */
    private static @Nullable Result woodworkingTable(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(BannerboundAntiquity.BONE_SAW.get()) || !level.getBlockState(pos).is(BlockTags.LOGS)) {
            return null;
        }
        Direction dir = firstHorizontalNeighbour(level, pos, p -> level.getBlockState(p).is(BlockTags.LOGS));
        if (dir == null) {
            return null;
        }
        BlockState main = BannerboundAntiquity.WOODWORKING_TABLE.get().defaultBlockState()
            .setValue(WoodworkingTableBlock.FACING, dir)
            .setValue(WoodworkingTableBlock.MAIN, true);
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(pos, main);
        blocks.put(pos.relative(dir), main.setValue(WoodworkingTableBlock.MAIN, false));
        return new Result(blocks, BannerboundAntiquity.WOODWORKING_TABLE.get().asItem());
    }

    /** Stone chisel on two adjacent stone/cobble → the Mason's Bench 2-block multiblock. Mirrors {@code onFormMasonsBench}. */
    private static @Nullable Result masonsBench(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(BannerboundAntiquity.STONE_CHISEL.get()) || !isBenchStone(level.getBlockState(pos))) {
            return null;
        }
        Direction dir = firstHorizontalNeighbour(level, pos, p -> isBenchStone(level.getBlockState(p)));
        if (dir == null) {
            return null;
        }
        BlockState main = BannerboundAntiquity.MASONS_BENCH.get().defaultBlockState()
            .setValue(MasonsBenchBlock.FACING, dir)
            .setValue(MasonsBenchBlock.MAIN, true);
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(pos, main);
        blocks.put(pos.relative(dir), main.setValue(MasonsBenchBlock.MAIN, false));
        return new Result(blocks, BannerboundAntiquity.MASONS_BENCH.get().asItem());
    }

    /** Coal block on the bottom of a 1×1×2 mud-brick column → a Bloomery. Mirrors {@code onFormBloomery}.
     *  The Bloomery is block-entity rendered, so the ghost shows its base model only. */
    private static @Nullable Result bloomery(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(Items.COAL_BLOCK)) {
            return null;
        }
        BlockPos top = pos.above();
        if (!level.getBlockState(pos).is(Blocks.MUD_BRICKS) || !level.getBlockState(top).is(Blocks.MUD_BRICKS)) {
            return null;
        }
        BlockState lower = BannerboundAntiquity.BLOOMERY.get().defaultBlockState()
            .setValue(BloomeryBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(BloomeryBlock.FACING, player.getDirection().getOpposite());
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(pos, lower);
        blocks.put(top, lower.setValue(BloomeryBlock.HALF, DoubleBlockHalf.UPPER));
        return new Result(blocks, BannerboundAntiquity.BLOOMERY.get().asItem());
    }

    /** Clay ball on cobblestone → clayed cobblestone. Mirrors {@code onClayCobblestone}. Note: the real
     *  carve is gated on the Kilns research rather than the item; the preview approximates with item unlock. */
    private static @Nullable Result clayedCobblestone(Level level, BlockPos pos, Player player, ItemStack held) {
        if (!held.is(Items.CLAY_BALL) || !level.getBlockState(pos).is(Blocks.COBBLESTONE)) {
            return null;
        }
        return single(pos, BannerboundAntiquity.CLAYED_COBBLESTONE.get().defaultBlockState(),
            BannerboundAntiquity.CLAYED_COBBLESTONE.get().asItem());
    }

    // ── Shared detection helpers ──────────────────────────────────────────────────────────────

    /** The bottom-left (master) corner of a 2×2 log square containing {@code clicked}, or null. The clicked
     *  log can be any of the four corners, so each candidate master is tried. (Mirrors AntiquityEvents.) */
    public static @Nullable BlockPos findLogSquare(Level level, BlockPos clicked, Direction width) {
        BlockPos[] candidates = {
            clicked,
            clicked.relative(width.getOpposite()),
            clicked.below(),
            clicked.below().relative(width.getOpposite())
        };
        for (BlockPos m : candidates) {
            if (isLog(level, m) && isLog(level, m.relative(width))
                    && isLog(level, m.above()) && isLog(level, m.relative(width).above())) {
                return m;
            }
        }
        return null;
    }

    private static boolean isLog(Level level, BlockPos p) {
        return level.getBlockState(p).is(BlockTags.LOGS);
    }

    private static boolean isBenchStone(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE);
    }

    @FunctionalInterface
    private interface PosTest {
        boolean test(BlockPos pos);
    }

    /** The first horizontal direction in which {@code test} passes, or null. */
    private static @Nullable Direction firstHorizontalNeighbour(Level level, BlockPos pos, PosTest test) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (test.test(pos.relative(d))) {
                return d;
            }
        }
        return null;
    }

    /** The per-wood map key for a source log (e.g. {@code stripped_birch_log} → {@code birch}), or
     *  {@code oak} when the wood has no registered variant in {@code available}. */
    private static String woodKey(Block log, Map<String, ?> available) {
        String path = BuiltInRegistries.BLOCK.getKey(log).getPath();
        if (path.startsWith("stripped_")) {
            path = path.substring("stripped_".length());
        }
        for (String suffix : new String[] { "_log", "_wood", "_stem", "_hyphae" }) {
            if (path.endsWith(suffix)) {
                path = path.substring(0, path.length() - suffix.length());
                break;
            }
        }
        return available.containsKey(path) ? path : "oak";
    }

    private static Result single(BlockPos pos, BlockState state, Item gateItem) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(pos, state);
        return new Result(blocks, gateItem);
    }
}
