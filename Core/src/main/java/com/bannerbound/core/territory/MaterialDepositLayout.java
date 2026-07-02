package com.bannerbound.core.territory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Deterministic layout for the generic material deposits worked by diggers/quarryworkers:
 * stone boulders plus clay/sand surface pits. Like ore boulders, these are permanent work sites:
 * workers swap source blocks to a worked-out state, yield the material, and a slow heartbeat swaps
 * worked faces back later.
 */
@ApiStatus.Internal
public final class MaterialDepositLayout {
    public static final int STONE_RADIUS = 2;
    public static final int PIT_RADIUS = 4;
    public static final int MAX_RADIUS = PIT_RADIUS;

    private static final float STONE_SOURCE_CHANCE = 0.70f;
    private static final float PIT_SOURCE_CHANCE = 0.78f;

    /** Antiquity's real limestone block/item id. Resolved by string so Core stays standalone — without
     *  Antiquity, limestone deposits fall back to a vanilla stone/cobblestone stand-in. */
    private static final ResourceLocation LIMESTONE_ID =
        ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "limestone");

    private MaterialDepositLayout() {
    }

    public record Spot(BlockPos pos, boolean source) {}

    public static boolean isMaterialChunk(ChunkResource type) {
        return isStoneBoulder(type) || type == ChunkResource.CLAY || type == ChunkResource.SAND;
    }

    /** Stone-boulder deposits (worked with a pickaxe, like ore boulders): real building stones
     *  (STONE, LIMESTONE) plus the andesite/diorite/granite "red herring" deposits that read as a
     *  special chunk on the scout map but only yield common decorative stone. All are gated behind the
     *  quarry research, unlike the clay/sand surface pits. */
    public static boolean isStoneBoulder(ChunkResource type) {
        return type == ChunkResource.STONE || type == ChunkResource.LIMESTONE
            || type == ChunkResource.ANDESITE || type == ChunkResource.DIORITE
            || type == ChunkResource.GRANITE;
    }

    public static boolean isMaterialPacked(String packed) {
        return materialResource(packed) != ChunkResource.NONE;
    }

    public static String packDeposit(ChunkResource type, int baseY) {
        return type.name() + "|" + baseY;
    }

    public static ChunkResource materialResource(String packed) {
        ChunkResource type = MinerWorkGoalCompat.resourceFromPacked(packed);
        return isMaterialChunk(type) ? type : ChunkResource.NONE;
    }

    public static int materialBaseY(String packed) {
        return MinerWorkGoalCompat.baseYFromPacked(packed);
    }

    public static String requiredRole(ChunkResource type) {
        return isStoneBoulder(type) ? "pickaxe" : "shovel";
    }

    public static BlockState sourceBlock(ChunkResource type) {
        return switch (type) {
            case STONE -> Blocks.STONE.defaultBlockState();
            case LIMESTONE -> limestoneBlock().map(Block::defaultBlockState)
                .orElse(Blocks.STONE.defaultBlockState());
            case ANDESITE -> Blocks.ANDESITE.defaultBlockState();
            case DIORITE -> Blocks.DIORITE.defaultBlockState();
            case GRANITE -> Blocks.GRANITE.defaultBlockState();
            case CLAY -> Blocks.CLAY.defaultBlockState();
            case SAND -> Blocks.SAND.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    public static BlockState bodyBlock(ChunkResource type) {
        return switch (type) {
            case STONE, LIMESTONE, ANDESITE, DIORITE, GRANITE -> Blocks.COBBLESTONE.defaultBlockState();
            case CLAY -> Blocks.MUD.defaultBlockState();
            case SAND -> Blocks.SANDSTONE.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    /** Antiquity's limestone block, if the expansion is installed. */
    private static java.util.Optional<Block> limestoneBlock() {
        return BuiltInRegistries.BLOCK.getOptional(LIMESTONE_ID);
    }

    /** Antiquity's limestone item, falling back to cobblestone so a limestone deposit is always
     *  workable (yields a stand-in) even without the Antiquity expansion. */
    private static Item limestoneItem() {
        return BuiltInRegistries.ITEM.getOptional(LIMESTONE_ID).orElse(Items.COBBLESTONE);
    }

    public static BlockState workedBlock(ChunkResource type) {
        return bodyBlock(type);
    }

    public static boolean isWorkedState(ChunkResource type, BlockState state) {
        return state.is(workedBlock(type).getBlock());
    }

    public static Optional<Item> iconDropFor(ChunkResource type) {
        return switch (type) {
            case STONE -> Optional.of(Items.COBBLESTONE);
            case LIMESTONE -> Optional.of(limestoneItem());
            case ANDESITE -> Optional.of(Items.ANDESITE);
            case DIORITE -> Optional.of(Items.DIORITE);
            case GRANITE -> Optional.of(Items.GRANITE);
            case CLAY -> Optional.of(Items.CLAY_BALL);
            case SAND -> Optional.of(Items.SAND);
            default -> Optional.empty();
        };
    }

    public static List<ItemStack> dropsFor(ChunkResource type) {
        return switch (type) {
            case STONE -> List.of(new ItemStack(Items.COBBLESTONE));
            case LIMESTONE -> List.of(new ItemStack(limestoneItem()));
            case ANDESITE -> List.of(new ItemStack(Items.ANDESITE));
            case DIORITE -> List.of(new ItemStack(Items.DIORITE));
            case GRANITE -> List.of(new ItemStack(Items.GRANITE));
            case CLAY -> List.of(new ItemStack(Items.CLAY_BALL, 4));
            case SAND -> List.of(new ItemStack(Items.SAND));
            default -> List.of();
        };
    }

    public static List<Spot> spots(long worldSeed, ChunkPos cp, int baseY, ChunkResource type) {
        if (isStoneBoulder(type)) return stoneBoulderSpots(worldSeed, cp, baseY);
        if (type == ChunkResource.CLAY || type == ChunkResource.SAND) {
            return pitSpots(worldSeed, cp, baseY, type);
        }
        return List.of();
    }

    private static List<Spot> stoneBoulderSpots(long worldSeed, ChunkPos cp, int baseY) {
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        List<Spot> out = new ArrayList<>();
        int r = STONE_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= r; dy++) {
                    double d = dx * dx + dy * dy * 1.5 + dz * dz;
                    if (d > r * r + 0.7) continue;
                    RandomSource posRand = posRand(worldSeed, cp, dx, dy, dz, 0x51A7EL);
                    if (d > (r - 1) * (r - 1) && posRand.nextBoolean()) continue;
                    boolean source = posRand.nextFloat() < STONE_SOURCE_CHANCE;
                    out.add(new Spot(new BlockPos(cx + dx, baseY + dy, cz + dz), source));
                }
            }
        }
        return out;
    }

    private static List<Spot> pitSpots(long worldSeed, ChunkPos cp, int baseY, ChunkResource type) {
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        List<Spot> out = new ArrayList<>();
        int r = PIT_RADIUS;
        long salt = type == ChunkResource.CLAY ? 0xC1A7L : 0x5A2DL;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = dx * dx + dz * dz;
                if (d > r * r + 0.8) continue;
                RandomSource posRand = posRand(worldSeed, cp, dx, 0, dz, salt);
                if (d > (r - 1) * (r - 1) && posRand.nextBoolean()) continue;
                boolean source = posRand.nextFloat() < PIT_SOURCE_CHANCE;
                out.add(new Spot(new BlockPos(cx + dx, baseY, cz + dz), source));
                if (posRand.nextInt(5) == 0) {
                    out.add(new Spot(new BlockPos(cx + dx, baseY - 1, cz + dz), false));
                }
            }
        }
        return out;
    }

    private static RandomSource posRand(long worldSeed, ChunkPos cp, int dx, int dy, int dz, long salt) {
        long mixed = worldSeed
            ^ cp.toLong() * 0x9E3779B97F4A7C15L
            ^ BlockPos.asLong(dx, dy, dz) * 0xC2B2AE3D27D4EB4FL
            ^ salt * 0x6A09E667F3BCC909L;
        return RandomSource.create(mixed);
    }

    public static Optional<Integer> locateBaseY(ServerLevel sl, ChunkPos cp, ChunkResource type) {
        if (!isMaterialChunk(type)) return Optional.empty();
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        int surface = BoulderLayout.groundSurfaceY(sl, cx, cz);
        int bestY = Integer.MIN_VALUE;
        int bestScore = 0;
        for (int baseY = surface - 3; baseY <= surface + 1; baseY++) {
            int score = 0;
            for (Spot s : spots(sl.getSeed(), cp, baseY, type)) {
                BlockState at = sl.getBlockState(s.pos());
                if (at.is(sourceBlock(type).getBlock())
                    || at.is(bodyBlock(type).getBlock())
                    || isWorkedState(type, at)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestY = baseY;
            }
        }
        return bestScore >= 6 ? Optional.of(bestY) : Optional.empty();
    }

    public static int dress(ServerLevel sl, ChunkPos cp) {
        ChunkResource type = ChunkResources.typeAt(sl, cp);
        if (!isMaterialChunk(type)) return Integer.MIN_VALUE;
        int cx = cp.getMinBlockX() + 8;
        int cz = cp.getMinBlockZ() + 8;
        int baseY = BoulderLayout.groundSurfaceY(sl, cx, cz);
        for (Spot s : spots(sl.getSeed(), cp, baseY, type)) {
            BlockState current = sl.getBlockState(s.pos());
            if (!BoulderLayout.canCarve(current)) continue;
            if (type == ChunkResource.CLAY || type == ChunkResource.SAND) {
                if (current.isAir() || !current.getFluidState().isEmpty()) continue;
                if (sl.getBlockState(s.pos().above()).blocksMotion()) continue;
            }
            sl.setBlock(s.pos(), s.source() ? sourceBlock(type) : bodyBlock(type), 3);
        }
        return baseY;
    }

    private static final class MinerWorkGoalCompat {
        private static ChunkResource resourceFromPacked(String packed) {
            if (packed == null || packed.isEmpty()) return ChunkResource.NONE;
            int i = packed.indexOf('|');
            String name = i < 0 ? packed : packed.substring(0, i);
            try {
                return ChunkResource.valueOf(name);
            } catch (IllegalArgumentException e) {
                return ChunkResource.NONE;
            }
        }

        private static int baseYFromPacked(String packed) {
            if (packed == null) return Integer.MIN_VALUE;
            int i = packed.indexOf('|');
            if (i < 0 || i + 1 >= packed.length()) return Integer.MIN_VALUE;
            try {
                return Integer.parseInt(packed.substring(i + 1));
            } catch (NumberFormatException e) {
                return Integer.MIN_VALUE;
            }
        }
    }
}
