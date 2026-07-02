package com.bannerbound.core.territory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The ore boulder of a metal/marble resource chunk as a <b>pure deterministic function</b> of
 * world seed + chunk coords + base height: every consumer — worldgen placement
 * ({@link ResourceChunkPopulator}), the miner's chip targets ({@code MinerWorkGoal}) and the vein
 * regen ticker ({@link MinerVeinRegen}) — derives the SAME pos→ore/body pattern with zero save
 * data. Per-position hash RNG (never a sequential stream), so the pattern is stable regardless of
 * what already exists in the world or in which order positions are visited.
 *
 * <p>The miner never destroys boulder blocks: it swaps an ORE-state block to the type's
 * {@link #chippedBlock} ("the vein face is worked out") and the regen ticker swaps it back over
 * time ("the face refreshes"). Block STATES change; the boulder's mass never does — it stays the
 * chunk's permanent identity marker.
 *
 * <p>Pre-existing boulders were placed with a sequential RNG whose stream consumed
 * terrain-dependent rolls, so their exact pattern is unrecoverable; they simply drift toward this
 * layout as they're worked (the miner only chips world-ore at layout-ore positions, regen restores
 * layout-ore positions). Geometry is identical, so the drift is invisible in practice.
 */
@ApiStatus.Internal
public final class BoulderLayout {
    /** Boulder radius — mirrors the populator's historical geometry. */
    public static final int RADIUS = 2;
    /** Ore share of the boulder by {@link #richness} tier: poor / normal / rich. Scouting depth —
     *  deposits are worth COMPARING, not just finding; "the rich tin chunk" is fight-worthy. */
    private static final float[] ORE_CHANCE_BY_RICHNESS = {0.30f, 0.45f, 0.60f};
    /** Item id the miner yields on TIN chunks (lives in the Antiquity expansion). */
    private static final ResourceLocation RAW_TIN_ID =
        ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "raw_tin");
    /** Block id of Antiquity's real tin ore. Resolved by string so Core stays standalone —
     *  without Antiquity, tin boulders fall back to the andesite speckle stand-in. */
    private static final ResourceLocation TIN_ORE_ID =
        ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "tin_ore");

    private BoulderLayout() {
    }

    /** A boulder position and whether the layout makes it ORE (chippable/regenerating) or body. */
    public record Spot(BlockPos pos, boolean ore) {}

    /**
     * Every position of the chunk's boulder for a base height, with its ore/body assignment.
     * Centered on the chunk's +8,+8 column (where the populator always builds). Pure — no level
     * reads; callers intersect with the actual world state.
     */
    public static List<Spot> spots(long worldSeed, ChunkPos cp, int baseY) {
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        float oreChance = ORE_CHANCE_BY_RICHNESS[richness(worldSeed, cp)];
        List<Spot> out = new ArrayList<>();
        int r = RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= r; dy++) {
                    double d = dx * dx + dy * dy * 1.5 + dz * dz; // slightly flattened ellipsoid
                    if (d > r * r + 0.7) continue;
                    RandomSource posRand = posRand(worldSeed, cp, dx, dy, dz);
                    if (d > (r - 1) * (r - 1) && posRand.nextBoolean()) continue; // ragged edge
                    boolean ore = posRand.nextFloat() < oreChance;
                    out.add(new Spot(new BlockPos(cx + dx, baseY + dy, cz + dz), ore));
                }
            }
        }
        return out;
    }

    /** Deterministic per-chunk vein richness: 0 = poor (25%), 1 = normal (50%), 2 = rich (25%).
     *  Pure function of seed + chunk, like the chunk's resource type itself — scoutable intel. */
    public static int richness(long worldSeed, ChunkPos cp) {
        RandomSource r = RandomSource.create(worldSeed ^ cp.toLong() * 0x6A09E667F3BCC909L);
        int roll = r.nextInt(4);
        return roll == 0 ? 0 : roll == 3 ? 2 : 1;
    }

    /** Hash-seeded RNG for ONE boulder position — independent of visit order and world state. */
    private static RandomSource posRand(long worldSeed, ChunkPos cp, int dx, int dy, int dz) {
        long mixed = worldSeed
            ^ cp.toLong() * 0x9E3779B97F4A7C15L
            ^ BlockPos.asLong(dx, dy, dz) * 0xC2B2AE3D27D4EB4FL;
        return RandomSource.create(mixed);
    }

    // ─── Block palette per resource type ─────────────────────────────────────────────────────────

    /** The visible ORE block of a boulder. Marble's "ore" is the calcite itself; tin resolves
     *  Antiquity's real tin ore by id (andesite speckle stand-in when Antiquity is absent). */
    public static BlockState oreBlock(ChunkResource type) {
        return switch (type) {
            case COPPER -> Blocks.COPPER_ORE.defaultBlockState();
            case IRON -> Blocks.IRON_ORE.defaultBlockState();
            case COAL -> Blocks.COAL_ORE.defaultBlockState();
            case MARBLE -> Blocks.CALCITE.defaultBlockState();
            case TIN -> BuiltInRegistries.BLOCK.getOptional(TIN_ORE_ID)
                .map(Block::defaultBlockState)
                .orElse(Blocks.ANDESITE.defaultBlockState());
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    /** The filler/body block between ore. */
    public static BlockState bodyBlock(ChunkResource type) {
        return switch (type) {
            case MARBLE -> Blocks.CALCITE.defaultBlockState();
            case TIN -> Blocks.TUFF.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState(); // copper / iron body
        };
    }

    /** The "worked out" state an ore block swaps TO when the miner chips it (and FROM when the
     *  vein regenerates). For marble — whose ore and body are both calcite — the chipped state is
     *  tuff so a worked outcrop visibly greys; everything else falls back to its body block. */
    public static BlockState chippedBlock(ChunkResource type) {
        return type == ChunkResource.MARBLE ? Blocks.TUFF.defaultBlockState() : bodyBlock(type);
    }

    /** True if {@code state} counts as a chipped/worked face of a {@code type} boulder — i.e. the
     *  regen ticker may swap it back to ore. Besides the canonical {@link #chippedBlock}, TIN also
     *  accepts ANDESITE: boulders placed before the real tin-ore block existed used andesite as
     *  the ore speckle, so regen quietly migrates those legacy faces to true tin ore over time. */
    public static boolean isChippedState(ChunkResource type, BlockState state) {
        if (state.is(chippedBlock(type).getBlock())) return true;
        return type == ChunkResource.TIN && state.is(Blocks.ANDESITE)
            && BuiltInRegistries.BLOCK.getOptional(TIN_ORE_ID).isPresent();
    }

    /** What one chipped ore block yields. Empty when the item doesn't exist in this install
     *  (raw tin lives in the Antiquity expansion) — callers refuse to mark/work such a chunk. */
    public static Optional<Item> dropFor(ChunkResource type) {
        return switch (type) {
            case COPPER -> Optional.of(net.minecraft.world.item.Items.RAW_COPPER);
            case IRON -> Optional.of(net.minecraft.world.item.Items.RAW_IRON);
            case COAL -> Optional.of(net.minecraft.world.item.Items.COAL);
            case MARBLE -> Optional.of(Blocks.CALCITE.asItem()); // placeholder until real marble
            case TIN -> BuiltInRegistries.ITEM.getOptional(RAW_TIN_ID);
            default -> Optional.empty();
        };
    }

    /** True for the chunk types the miner works (a surface ore boulder exists/can be dressed). */
    public static boolean isOreChunk(ChunkResource type) {
        return type == ChunkResource.COPPER || type == ChunkResource.IRON
            || type == ChunkResource.MARBLE || type == ChunkResource.TIN
            || type == ChunkResource.COAL;
    }

    // ─── Locating / dressing the boulder in the world ────────────────────────────────────────────

    /**
     * Finds the base height of this chunk's existing boulder by scoring candidate base heights
     * against the layout (how many layout positions hold the type's ore/body/chipped blocks).
     * Returns empty when no plausible boulder exists — e.g. the chunk generated before the
     * populator feature — in which case {@link #dress} can build one.
     */
    public static Optional<Integer> locateBaseY(ServerLevel sl, ChunkPos cp, ChunkResource type) {
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        int surface = groundSurfaceY(sl, cx, cz);
        Block ore = oreBlock(type).getBlock();
        Block body = bodyBlock(type).getBlock();
        int bestY = Integer.MIN_VALUE;
        int bestScore = 0;
        // The boulder's top is at baseY+2 and groundSurfaceY lands on the topmost solid block, so
        // candidate bases live a few blocks under the current surface reading.
        for (int baseY = surface - RADIUS - 1; baseY <= surface + 1; baseY++) {
            int score = 0;
            for (Spot s : spots(sl.getSeed(), cp, baseY)) {
                BlockState at = sl.getBlockState(s.pos());
                // Counts chipped + legacy stand-in states too (isChippedState), so a boulder from
                // before the real tin-ore block existed still scores as a boulder.
                if (at.is(ore) || at.is(body) || isChippedState(type, at)) score++;
            }
            if (score > bestScore) { bestScore = score; bestY = baseY; }
        }
        // Demand a real cluster, not a couple of coincidental stone blocks.
        return bestScore >= 6 ? Optional.of(bestY) : Optional.empty();
    }

    /**
     * Builds (or re-dresses) the chunk's boulder from the layout at the natural surface and
     * returns its base height. Replaces only natural/replaceable ground ({@link #canCarve}) —
     * never logs/leaves or player blocks. This is the populator's placement path AND the rod's
     * commit-time fallback for pre-feature chunks.
     */
    public static int dress(ServerLevel sl, ChunkPos cp) {
        ChunkResource type = ChunkResources.typeAt(sl, cp);
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        int baseY = groundSurfaceY(sl, cx, cz);
        for (Spot s : spots(sl.getSeed(), cp, baseY)) {
            if (!canCarve(sl.getBlockState(s.pos()))) continue;
            sl.setBlock(s.pos(), s.ore() ? oreBlock(type) : bodyBlock(type), 3);
        }
        return baseY;
    }

    /** Only replace air, plants, and natural terrain — never logs/leaves/player or structure blocks. */
    public static boolean canCarve(BlockState s) {
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)) return false;
        return s.canBeReplaced()
            || s.is(BlockTags.DIRT)
            || s.is(BlockTags.SAND)
            || s.is(BlockTags.BASE_STONE_OVERWORLD)
            || s.is(Blocks.GRAVEL)
            || s.is(Blocks.SNOW_BLOCK)
            || s.is(Blocks.GRASS_BLOCK);
    }

    /** Topmost solid ground block at (x,z), walking down past tree leaves/logs, plants and water so
     *  we land on actual ground — {@code WORLD_SURFACE} alone would land on the forest canopy.
     *  (Same walk as {@link ResourceChunkPopulator}'s.) */
    public static int groundSurfaceY(ServerLevel sl, int x, int z) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(
            x, sl.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
        int floor = sl.getMinBuildHeight();
        while (m.getY() > floor) {
            m.move(Direction.DOWN);
            BlockState s = sl.getBlockState(m);
            if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) continue;
            if (s.getFluidState().isEmpty() && s.blocksMotion()) return m.getY();
        }
        return floor;
    }
}
