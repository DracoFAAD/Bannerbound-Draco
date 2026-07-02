package com.bannerbound.core.territory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The crop-field analogue of {@link BoulderLayout}: the per-type block/seed mappings that make a
 * {@code WHEAT}/{@code CARROT}/{@code BEETROOT}/{@code POTATO} chunk a farmland resource, plus the
 * world-gen "dress" that scatters its visible <b>wild farmland patches</b>.
 *
 * <p>Unlike the ore boulder, nothing here needs a deterministic per-position layout: the farmer
 * just tends whatever crops stand on the farmland, and the forager picks whatever is ripe — neither
 * has to re-derive exact tile coordinates. So {@link #dressWildField} is a one-shot deterministic
 * scatter (seeded by chunk), not a recoverable {@code spots()}-style layout.
 *
 * <p>All edits stay within ±5 of the chunk's +8 centre, so the ±6 ring probe in
 * {@link ChunkResources#typeAt} never reads them and the chunk's type stays a pure function of the
 * natural terrain (same guarantee the boulder relies on).
 */
@ApiStatus.Internal
public final class CropChunks {
    private CropChunks() {
    }

    /** True for the four crop-field chunk types. */
    public static boolean isCropChunk(ChunkResource type) {
        return type == ChunkResource.WHEAT || type == ChunkResource.CARROT
            || type == ChunkResource.BEETROOT || type == ChunkResource.POTATO;
    }

    /** The {@link CropBlock} grown in a crop chunk (vanilla crops; handles beetroot's own age range). */
    public static CropBlock cropBlock(ChunkResource type) {
        return (CropBlock) switch (type) {
            case WHEAT -> Blocks.WHEAT;
            case CARROT -> Blocks.CARROTS;
            case BEETROOT -> Blocks.BEETROOTS;
            case POTATO -> Blocks.POTATOES;
            default -> Blocks.WHEAT;   // never reached (callers gate on isCropChunk)
        };
    }

    /** The seed item a farmer plants on this crop chunk (and the seed the bonus matches on). */
    public static Item seedFor(ChunkResource type) {
        return switch (type) {
            case WHEAT -> Items.WHEAT_SEEDS;
            case CARROT -> Items.CARROT;
            case BEETROOT -> Items.BEETROOT_SEEDS;
            case POTATO -> Items.POTATO;
            default -> Items.AIR;
        };
    }

    /** The crop chunk type that {@code seed} gets a yield bonus on, or {@link ChunkResource#NONE}. */
    public static ChunkResource cropChunkFor(Item seed) {
        if (seed == Items.WHEAT_SEEDS) return ChunkResource.WHEAT;
        if (seed == Items.CARROT) return ChunkResource.CARROT;
        if (seed == Items.BEETROOT_SEEDS) return ChunkResource.BEETROOT;
        if (seed == Items.POTATO) return ChunkResource.POTATO;
        return ChunkResource.NONE;
    }

    /** Seed item ids whose crop chunk overlaps the box [{@code minX..maxX} × {@code minZ..maxZ}] — the
     *  seeds a field in that box earns the 2× harvest bonus on. Drives the green hint in the seed UI. */
    public static List<String> bonusSeedIds(ServerLevel sl, int minX, int minZ, int maxX, int maxZ) {
        List<String> out = new ArrayList<>();
        EnumSet<ChunkResource> seen = EnumSet.noneOf(ChunkResource.class);
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                ChunkResource t = ChunkResources.typeAt(sl, new ChunkPos(cx, cz));
                if (isCropChunk(t) && seen.add(t)) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(seedFor(t));
                    if (id != null) out.add(id.toString());
                }
            }
        }
        return out;
    }

    /** Number of wild-farmland tiles scattered per crop chunk. */
    private static final int MIN_TILES = 10;
    private static final int TILE_SPREAD = 7;   // MIN_TILES .. MIN_TILES+TILE_SPREAD-1
    /** Horizontal half-extent of the scatter (±5) — strictly inside the ring probe. */
    private static final int FIELD_RADIUS = 5;

    /**
     * Scatters the chunk's wild farmland patches: dry {@link Blocks#FARMLAND} on grass/dirt with a
     * crop at a <b>random maturity</b> above. Deterministic per chunk (stable on regen), like the
     * livestock decorate and ore boulder. Only replaces natural ground ({@link BoulderLayout#canCarve}),
     * never player/structure blocks.
     */
    public static void dressWildField(ServerLevel sl, ChunkPos cp) {
        ChunkResource type = ChunkResources.typeAt(sl, cp);
        if (!isCropChunk(type)) return;
        CropBlock crop = cropBlock(type);
        int maxAge = crop.getMaxAge();
        RandomSource rand = RandomSource.create(sl.getSeed() ^ cp.toLong() ^ 0x5DEECE66DL);
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        int tiles = MIN_TILES + rand.nextInt(TILE_SPREAD);
        for (int i = 0; i < tiles; i++) {
            int x = cx + rand.nextInt(FIELD_RADIUS * 2 + 1) - FIELD_RADIUS;
            int z = cz + rand.nextInt(FIELD_RADIUS * 2 + 1) - FIELD_RADIUS;
            int y = BoulderLayout.groundSurfaceY(sl, x, z);
            BlockPos ground = new BlockPos(x, y, z);
            BlockState gs = sl.getBlockState(ground);
            // Farmland only on tillable natural ground, with room for the crop above.
            if (!(gs.is(Blocks.GRASS_BLOCK) || gs.is(Blocks.DIRT) || gs.is(Blocks.COARSE_DIRT))) continue;
            if (!sl.getBlockState(ground.above()).isAir()) continue;
            sl.setBlock(ground, Blocks.FARMLAND.defaultBlockState(), 3);   // dry (moisture 0 by default)
            sl.setBlock(ground.above(), crop.getStateForAge(rand.nextInt(maxAge + 1)), 3);
        }
    }
}
