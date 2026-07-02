package com.bannerbound.core.barbarian;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Assembles a barbarian camp village-style: a procedural center (campfire + the type-coloured
 * standard = raze target), then a ring of authored {@code .nbt} building pieces around it — one
 * chief hut, a stockpile, and enough huts to house the camp's population (big hut = 2, others = 1) —
 * each rotated to face the campfire with a dirt path trodden back to the center. Layout is a pure
 * function of the camp seed (stable across re-stamps / per-chunk stamping).
 *
 * <p>Authored pieces (built facing South, ground included in the .nbt → placed one Y lower) come from
 * {@link CampPieces}. A type with no authored pieces yet falls back to placeholder procedural tents.
 * Every write is {@code hasChunk}-guarded so a camp straddling chunk borders never force-loads a
 * neighbor (the cascade-freeze hazard).
 */
@ApiStatus.Internal
public final class CampStructureStamper {
    private static final int MIN_RING = 8;   // ring radius bounds (blocks from the campfire)
    private static final int MAX_RING = 16;
    // Target arc between adjacent buildings. Huts are ~7–9 wide, so this must exceed a hut's width
    // plus a gap or they touch; the ring radius grows with the building count to hold it.
    private static final double PIECE_SPACING = 14.0;

    private CampStructureStamper() {
    }

    public static void stamp(ServerLevel level, BarbarianCamp camp) {
        int cx = camp.center.getX();
        int cz = camp.center.getZ();

        // ── Center: campfire + the standard (raze target) ──
        prepArea(level, cx, cz, 1);
        BlockPos fire = surface(level, cx, cz);
        if (fire != null) {
            level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos bannerPos = surface(level, cx + 1, cz);
        if (bannerPos != null) {
            BannerBlock banner = (BannerBlock) BannerBlock.byColor(camp.type.bannerDye());
            level.setBlock(bannerPos, banner.defaultBlockState().setValue(BannerBlock.ROTATION, 12),
                Block.UPDATE_ALL);
            camp.bannerPos = bannerPos;
        }

        // ── Ring of buildings (authored pieces if any, else procedural) ──
        RandomSource rng = RandomSource.create(camp.languageSeed);
        List<ResourceLocation> ring = buildPlacementList(level, camp, rng);
        if (ring == null) {
            proceduralRing(level, camp, rng); // no authored pieces for this type yet
            return;
        }
        int n = ring.size();
        // Ring radius scaled to the building count so the arc between neighbours is ~PIECE_SPACING
        // (a hut is ~5–7 wide) — prevents the overlap you get from a fixed small radius.
        int ringR = Math.max(MIN_RING, Math.min(MAX_RING,
            (int) Math.round(n * PIECE_SPACING / (2.0 * Math.PI))));
        for (int i = 0; i < n; i++) {
            StructureTemplate template = level.getStructureManager().get(ring.get(i)).orElse(null);
            if (template == null) continue;
            double angle = (Math.PI * 2.0 / n) * i + (rng.nextDouble() - 0.5) * 0.25;
            // Pick the flattest spot along this spoke so the building isn't half-buried (its door stays
            // above ground and keeps line-of-sight to the campfire).
            int[] anchor = findFlatAnchor(level, cx, cz, angle, ringR, template.getSize());
            int ax = anchor[0], az = anchor[1];
            placePiece(level, template, ax, az, rotationFacingCenter(ax, az, cx, cz),
                camp.type.bannerDye());
            drawPath(level, ax, az, cx, cz, rng);
        }
    }

    /** Tries several radii along {@code angle} and returns the (x,z) whose piece-footprint ground is
     *  flattest (first one within tolerance, else the least-uneven), so buildings don't sink into slopes. */
    private static int[] findFlatAnchor(ServerLevel level, int cx, int cz, double angle, int ringR,
                                        Vec3i size) {
        int half = Math.max(size.getX(), size.getZ()) / 2 + 1;
        int[] radii = { ringR, ringR - 2, ringR + 2, ringR - 3, ringR + 3, ringR - 4, ringR + 4 };
        int[] best = null;
        int bestRelief = Integer.MAX_VALUE;
        for (int r : radii) {
            if (r < MIN_RING - 2) continue;
            int ax = cx + (int) Math.round(Math.cos(angle) * r);
            int az = cz + (int) Math.round(Math.sin(angle) * r);
            int relief = footprintRelief(level, ax, az, half);
            if (relief <= 2) return new int[] { ax, az };
            if (relief < bestRelief) {
                bestRelief = relief;
                best = new int[] { ax, az };
            }
        }
        return best != null ? best
            : new int[] { cx + (int) Math.round(Math.cos(angle) * ringR),
                          cz + (int) Math.round(Math.sin(angle) * ringR) };
    }

    /** Ground relief (max − min height) over a piece footprint, sampling corners + center. */
    private static int footprintRelief(ServerLevel level, int cx, int cz, int half) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = -half; dx <= half; dx += half) {
            for (int dz = -half; dz <= half; dz += half) {
                int x = cx + dx, z = cz + dz;
                if (!loaded(level, x, z)) continue;
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        return max == Integer.MIN_VALUE ? 0 : max - min;
    }

    /**
     * The ring contents for this camp: one chief hut, one stockpile, then huts sized to the camp's
     * population (big hut houses 2, others 1; the chief hut already houses one). Returns null if the
     * type has no authored pieces (→ procedural fallback).
     */
    private static List<ResourceLocation> buildPlacementList(ServerLevel level, BarbarianCamp camp,
                                                             RandomSource rng) {
        List<CampPieces.Piece> all = CampPieces.forType(level.getServer(), camp.type);
        if (all.isEmpty()) return null;
        List<CampPieces.Piece> chiefs = ofRole(all, CampPieces.Role.CHIEF);
        List<CampPieces.Piece> stores = ofRole(all, CampPieces.Role.STOCKPILE);
        List<CampPieces.Piece> huts = ofRole(all, CampPieces.Role.HUT);

        List<ResourceLocation> ring = new ArrayList<>();
        int housed = 0;
        if (!chiefs.isEmpty()) {
            ring.add(pick(chiefs, rng).id());
            housed += 1; // the chief hut shelters one (the commander)
        }
        if (!stores.isEmpty()) {
            ring.add(pick(stores, rng).id()); // functional, no housing
        }
        // Fill huts until the rest of the population is housed (big hut = 2, others = 1).
        while (housed < camp.memberTarget && !huts.isEmpty()) {
            CampPieces.Piece hut = pick(huts, rng);
            ring.add(hut.id());
            housed += hutCapacity(hut.id());
        }
        return ring;
    }

    private static int hutCapacity(ResourceLocation id) {
        return id.getPath().toLowerCase(Locale.ROOT).contains("big") ? 2 : 1;
    }

    /** Places one authored piece centered on the anchor, rotated to {@code facing}, dropped one Y
     *  lower because the .nbt includes its own ground layer. No-op if the template is missing/unloaded. */
    private static void placePiece(ServerLevel level, StructureTemplate template, int x, int z,
                                   Rotation facing, DyeColor dye) {
        if (!loaded(level, x, z)) return;
        Vec3i size = template.getSize();
        prepArea(level, x, z, Math.max(size.getX(), size.getZ()) / 2 + 1);
        BlockPos pivot = new BlockPos(size.getX() / 2, 0, size.getZ() / 2);
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(facing).setRotationPivot(pivot).setIgnoreEntities(true)
            // Don't stamp the .nbt's air (or its structure blocks) — air cells would carve the terrain.
            .addProcessor(net.minecraft.world.level.levelgen.structure.templatesystem
                .BlockIgnoreProcessor.STRUCTURE_AND_AIR);
        int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // Centre the footprint on (x,z); ground row sits at the top solid block (gy-1), so the camp
        // reads as built into the terrain rather than floating one block up.
        BlockPos placePos = new BlockPos(x - pivot.getX(), gy - 1, z - pivot.getZ());
        template.placeInWorld(level, placePos, placePos, settings, level.random, Block.UPDATE_CLIENTS);
        // Recolour any white standard built into the piece to the camp type's banner colour.
        int reach = Math.max(size.getX(), size.getZ());
        recolorBanners(level, x, z, gy - 1, reach, size.getY() + 1, dye);
    }

    /** Swaps any WHITE banner (standing or wall) in the placed piece's bounds to {@code dye},
     *  preserving rotation/facing — so a white standard built into a hut adopts the camp's colour. */
    private static void recolorBanners(ServerLevel level, int cx, int cz, int y0, int reach,
                                       int height, DyeColor dye) {
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = cx + dx, z = cz + dz;
                if (!loaded(level, x, z)) continue;
                for (int dy = 0; dy <= height; dy++) {
                    BlockPos p = new BlockPos(x, y0 + dy, z);
                    BlockState st = level.getBlockState(p);
                    if (st.is(Blocks.WHITE_BANNER)) {
                        level.setBlock(p, BannerBlock.byColor(dye).defaultBlockState()
                            .setValue(BannerBlock.ROTATION, st.getValue(BannerBlock.ROTATION)),
                            Block.UPDATE_CLIENTS);
                    } else if (st.is(Blocks.WHITE_WALL_BANNER)) {
                        level.setBlock(p, wallBannerFor(dye).defaultBlockState().setValue(
                            net.minecraft.world.level.block.WallBannerBlock.FACING,
                            st.getValue(net.minecraft.world.level.block.WallBannerBlock.FACING)),
                            Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    private static Block wallBannerFor(DyeColor color) {
        return switch (color) {
            case YELLOW -> Blocks.YELLOW_WALL_BANNER;
            case GREEN -> Blocks.GREEN_WALL_BANNER;
            case LIGHT_BLUE -> Blocks.LIGHT_BLUE_WALL_BANNER;
            case RED -> Blocks.RED_WALL_BANNER;
            default -> Blocks.WHITE_WALL_BANNER;
        };
    }

    private static CampPieces.Piece pick(List<CampPieces.Piece> list, RandomSource rng) {
        return list.get(rng.nextInt(list.size()));
    }

    private static List<CampPieces.Piece> ofRole(List<CampPieces.Piece> all, CampPieces.Role role) {
        List<CampPieces.Piece> out = new ArrayList<>();
        for (CampPieces.Piece p : all) {
            if (p.role() == role) out.add(p);
        }
        return out;
    }

    // ─── Procedural fallback (types without authored pieces yet) ──────────────────────────────────

    private static void proceduralRing(ServerLevel level, BarbarianCamp camp, RandomSource rng) {
        int tents = Math.max(3, camp.memberTarget / 2);
        for (int i = 0; i < tents; i++) {
            double angle = (Math.PI * 2.0 / tents) * i + (rng.nextDouble() - 0.5) * 0.4;
            int radius = MIN_RING + rng.nextInt(MAX_RING - MIN_RING + 1);
            int ax = camp.center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int az = camp.center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            placeProceduralTent(level, camp, ax, az);
            drawPath(level, ax, az, camp.center.getX(), camp.center.getZ(), rng);
        }
    }

    private static void placeProceduralTent(ServerLevel level, BarbarianCamp camp, int x, int z) {
        prepArea(level, x, z, 1);
        BlockPos base = surface(level, x, z);
        if (base == null) return;
        BlockState canopy = woolFor(camp.type.bannerDye());
        level.setBlock(base, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.above(), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.above(2), canopy, Block.UPDATE_ALL);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = base.above().relative(d);
            if (loaded(level, side.getX(), side.getZ()) && level.getBlockState(side).canBeReplaced()) {
                level.setBlock(side, canopy, Block.UPDATE_ALL);
            }
        }
        level.setBlock(base.above(3), Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ─── Shared helpers ───────────────────────────────────────────────────────────────────────────

    /** Lays a 3-wide imperfect dirt-path trail from an anchor to the center: the middle lane is
     *  always paved, the two edge lanes ~50% of the time, so it reads as a trodden village track. */
    private static void drawPath(ServerLevel level, int x0, int z0, int x1, int z1, RandomSource rng) {
        int dx = x1 - x0, dz = z1 - z0;
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) return;
        boolean alongX = Math.abs(dx) >= Math.abs(dz); // widen perpendicular to the dominant axis
        for (int s = 1; s < steps; s++) {
            int x = x0 + Math.round((float) dx * s / steps);
            int z = z0 + Math.round((float) dz * s / steps);
            pavePathCell(level, x, z, true, rng);
            if (alongX) {
                pavePathCell(level, x, z - 1, false, rng);
                pavePathCell(level, x, z + 1, false, rng);
            } else {
                pavePathCell(level, x - 1, z, false, rng);
                pavePathCell(level, x + 1, z, false, rng);
            }
        }
    }

    /** Paves one path cell over earth-like ground. Edge cells ({@code !always}) place only ~50% of
     *  the time for the imperfect look. */
    private static void pavePathCell(ServerLevel level, int x, int z, boolean always, RandomSource rng) {
        if (!always && rng.nextFloat() < 0.5f) return;
        if (!loaded(level, x, z)) return;
        int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos ground = new BlockPos(x, gy - 1, z);
        BlockState gs = level.getBlockState(ground);
        if (gs.is(Blocks.GRASS_BLOCK) || gs.is(Blocks.DIRT) || gs.is(Blocks.COARSE_DIRT)
                || gs.is(Blocks.PODZOL) || gs.is(Blocks.SAND) || gs.is(Blocks.GRAVEL)) {
            level.setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
            BlockPos above = new BlockPos(x, gy, z);
            if (level.getBlockState(above).canBeReplaced() && level.getFluidState(above).isEmpty()) {
                level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    /** Whole-step rotation pointing a NORTH-built entrance toward the center from (x,z). Verified
     *  against {@code Rotation.rotate(NORTH)}: NONE→NORTH, CW90→EAST, CW180→SOUTH, CCW90→WEST. */
    private static Rotation rotationFacingCenter(int x, int z, int cx, int cz) {
        int dx = cx - x, dz = cz - z;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Rotation.CLOCKWISE_90          // center is east  → face east
                           : Rotation.COUNTERCLOCKWISE_90;  // center is west  → face west
        }
        return dz >= 0 ? Rotation.CLOCKWISE_180    // center is south → face south
                       : Rotation.NONE;             // center is north → face north (built facing)
    }

    private static void prepArea(ServerLevel level, int cx, int cz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = cx + dx, z = cz + dz;
                if (!loaded(level, x, z)) continue;
                int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                for (int dy = 0; dy < 3; dy++) {
                    BlockPos p = new BlockPos(x, gy + dy, z);
                    BlockState st = level.getBlockState(p);
                    if (st.isAir()) continue;
                    if (st.canBeReplaced() && st.getFluidState().isEmpty()) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    private static BlockPos surface(ServerLevel level, int x, int z) {
        if (!loaded(level, x, z)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private static boolean loaded(ServerLevel level, int x, int z) {
        return level.hasChunk(x >> 4, z >> 4);
    }

    private static BlockState woolFor(DyeColor color) {
        return (switch (color) {
            case YELLOW -> Blocks.YELLOW_WOOL;
            case GREEN -> Blocks.GREEN_WOOL;
            case LIGHT_BLUE -> Blocks.LIGHT_BLUE_WOOL;
            case RED -> Blocks.RED_WOOL;
            default -> Blocks.WHITE_WOOL;
        }).defaultBlockState();
    }
}
