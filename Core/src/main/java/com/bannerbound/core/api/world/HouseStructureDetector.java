package com.bannerbound.core.api.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Home;
import com.bannerbound.core.api.settlement.HousingLimits;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Auto-detects a house's shell (walls / roof / floor) from its House Block so the player doesn't
 * have to hand-draw every box with the Home Marker Rod. The result is a set of {@link BlockSelection}
 * HOME boxes ready to register for the home, which then feed the existing
 * {@link com.bannerbound.core.api.settlement.Homes} validation untouched. (Legacy: the Detect
 * entry point is no longer wired to a payload — only {@link #isWallOpening} is still used, by the
 * enclosure flood in {@code Homes.validate}.)
 *
 * <p><b>Why anchor on interior air, not solid-to-solid flooding.</b> The flood is air-only:
 * walls/roof/floor block it, so it traces the enclosed room of any shape (non-cube falls out for
 * free), and — crucially — it never tunnels into the ground. The floor (solid directly under the
 * interior air) is part of the shell, but the dirt below it is not adjacent to interior air, so the
 * detection stops at the ground exactly as required. The resulting shell is naturally 26-connected,
 * matching the adjacency the design sketches show (origin House Block = gold, detected shell =
 * green, everything else = not detected).
 *
 * <p><b>Open-hole windows + multiple rooms.</b> Antiquity windows may be plain holes in a wall, which
 * would let the interior flood leak out. First a strict flood (no sealing) is tried — a fully-enclosed
 * house of any size, with any number of rooms joined by interior doorways, fills cleanly there. If that
 * leaks, the house has openings, and detection switches to an <i>outside-in</i> classification
 * ({@link #floodOpenHouse}): flood the exterior air, sealing {@link #isWallOpening wall holes} (a window
 * / 1-wide doorway, identified by having ≥3 solid face-neighbours) so the outside can't reach in, then
 * keep everything the seed reaches that the outside can't. Because the interior flood itself never
 * seals, interior doorways stay open and every room is captured; a genuine ≥2-wide missing wall lets
 * the outside reach the seed → reported as "not enclosed". The {@link #isWallOpening} predicate is also
 * reused by the periodic validator so a windowed house both detects and validates.
 */
public final class HouseStructureDetector {
    /** An air cell with this many (or more) solid face-neighbours is treated as a hole in a wall
     *  (window / 1-wide doorway / skylight) rather than open room space — see {@link #isWallOpening}. */
    public static final int OPENING_MIN_SOLID_FACES = 3;
    /** Interior-air volume cap. A flood that exceeds this is leaking through an open wall → fail.
     *  ~20³; comfortably larger than any real hut interior, small enough to catch outdoor leaks. */
    public static final int MAX_INTERIOR = 8000;
    /** Cap on emitted boxes. Real houses decompose to a few dozen 1-tall rectangles; this only
     *  bounds pathological shapes. */
    public static final int MAX_BOXES = 128;
    /** How long the detect wireframe flashes, in ticks (≈5 s). */
    public static final int PREVIEW_TICKS = 100;
    /** Half-extent of the box used to classify inside-vs-outside for a house with openings. Must be
     *  big enough to contain the whole house with a margin of outside air around it; 24 covers any
     *  rod-legal house (≤32 across) when the House Block sits anywhere reasonable, while keeping the
     *  one-shot exterior flood affordable. */
    public static final int OPEN_HOUSE_BOX_RADIUS = 24;

    /** The 26 neighbour offsets (3×3×3 minus centre) — same diagonal-aware adjacency the house
     *  connectivity check uses, so the extracted shell lines up with {@code isConnected}. */
    private static final int[][] OFFSETS_26 = build26();

    private HouseStructureDetector() {
    }

    /** Outcome of a detection run: the boxes to register on success, or a non-null {@link #failKey}
     *  naming the reason (a {@code bannerbound.house.detect.<failKey>} lang key) on failure.
     *  {@code diag} is a short human-readable trace (path taken + set sizes) shown in chat to make
     *  in-game debugging concrete — which flood ran and how big the interior/shell came out. */
    public record Result(List<BlockSelection> boxes, Set<BlockPos> shell, @Nullable String failKey,
                          String diag) {
        static Result fail(String key, String diag) { return new Result(List.of(), Set.of(), key, diag); }
    }

    /**
     * Runs detection for the House Block at {@code housePos}. Pure read of the world — never mutates
     * blocks or the registry; the caller registers {@link Result#boxes()} if {@code failKey} is null.
     */
    public static Result detect(LevelReader level, BlockPos housePos, Settlement owner, Home home,
                                 UUID playerId) {
        BlockPos seed = findInteriorSeed(level, housePos);
        if (seed == null) {
            return Result.fail("no_interior", "no air cell adjacent to the House Block");
        }
        // Fast path: a fully-enclosed house (any number of rooms, connected through interior
        // doorways) fills here, bounded only by its own walls. No opening sealing, so interior
        // doorways stay open and every room is captured. The common case, and cheap.
        Set<BlockPos> interior = floodInterior(level, housePos, seed);
        String path;
        // When present, the exterior air set (from the open-house path) lets the shell extraction
        // drop exterior ground that's only diagonally adjacent to the interior (the doorway floor
        // "spill"). Null for the fully-sealed fast path, where there's nothing to spill into.
        Set<BlockPos> exterior = null;
        if (interior != null) {
            path = "sealed";
        } else {
            // The flood leaked, so the house has openings to the outside (a door, windows). Classify
            // inside vs outside by flooding the EXTERIOR air — sealing wall holes so it can't sneak
            // in through a window/doorway — then keep everything the seed reaches that the outside
            // can't. Interior doorways are NOT sealed here, so multi-room houses stay whole.
            OpenHouse open = floodOpenHouse(level, housePos, seed);
            if (open != null) {
                interior = open.interior();
                exterior = open.exterior();
                path = "open";
            } else {
                path = "leaked";
            }
        }
        if (interior == null) {
            return Result.fail("open_wall", "path=leaked seed=" + shortPos(seed)
                + " (exterior reached the seed → a ≥2-wide opening / open roof)");
        }
        Set<BlockPos> shell = extractShell(level, interior, housePos, exterior);
        // No size gate here on purpose: an over-size house is still marked/buildable, it just
        // validates as BROKEN_TOO_BIG (no beds / no appeal) until research grows the limit. The
        // size check lives in HouseBlockEntity.runValidation so hand-marking and Detect agree.
        List<BlockSelection> boxes = decompose(shell, owner, home, housePos, playerId);
        String diag = "path=" + path + " interior=" + interior.size()
            + (exterior != null ? " exterior=" + exterior.size() : "")
            + " shell=" + shell.size() + " boxes=" + boxes.size() + " seed=" + shortPos(seed);
        return new Result(boxes, shell, null, diag);
    }

    private static String shortPos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** First air cell adjacent to the House Block (UP first — the usual interior), else null. */
    @Nullable
    private static BlockPos findInteriorSeed(LevelReader level, BlockPos housePos) {
        Direction[] order = { Direction.UP, Direction.NORTH, Direction.SOUTH,
                              Direction.EAST, Direction.WEST, Direction.DOWN };
        for (Direction d : order) {
            BlockPos n = housePos.relative(d);
            if (level.getBlockState(n).isAir()) return n;
        }
        return null;
    }

    /**
     * 6-connected BFS through air from {@code seed}, blocked by solids only, bounded by a Chebyshev
     * radius (reusing {@link HousingLimits#MAX_RADIUS}) and {@link #MAX_INTERIOR}.
     * No opening sealing, so a sealed multi-room house fills completely through its interior doorways.
     * Returns the interior air set, or {@code null} if it leaked (escaped the radius or exceeded the
     * volume cap → the house has openings to the outside; the caller falls back to {@link #floodOpenHouse}).
     */
    @Nullable
    private static Set<BlockPos> floodInterior(LevelReader level, BlockPos housePos, BlockPos seed) {
        Set<BlockPos> interior = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        interior.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (interior.contains(n)) continue;
                if (!isFloodPassable(level, n)) continue;                // wall blocks; doors pass
                if (chebyshev(n, housePos) > HousingLimits.MAX_RADIUS) {
                    return null;                                         // air leaking past radius
                }
                if (interior.size() >= MAX_INTERIOR) {
                    return null;                                         // leaking through open wall
                }
                interior.add(n);
                queue.add(n);
            }
        }
        return interior;
    }

    /**
     * Interior detection for a house that has openings to the outside. Works "outside-in":
     * <ol>
     *   <li>Take a box around the House Block (±{@link #OPEN_HOUSE_BOX_RADIUS}).</li>
     *   <li>Flood the EXTERIOR air from the box's boundary, sealing wall holes ({@link #isWallOpening})
     *       so the flood can't reach inside through a window/doorway.</li>
     *   <li>Interior = everything the {@code seed} reaches that is NOT exterior. Interior doorways are
     *       not sealed, so all rooms are captured; the flood simply can't step out into exterior air.</li>
     * </ol>
     * Returns {@code null} if the seed itself is reachable from the outside (a ≥2-wide opening / missing
     * wall — the house isn't enclosed).
     */
    /** Interior + exterior air sets for an open house, returned together so the shell extraction can
     *  use the exterior set to drop doorway floor spill. */
    private record OpenHouse(Set<BlockPos> interior, Set<BlockPos> exterior) {}

    @Nullable
    private static OpenHouse floodOpenHouse(LevelReader level, BlockPos housePos, BlockPos seed) {
        int r = OPEN_HOUSE_BOX_RADIUS;
        int minX = housePos.getX() - r, maxX = housePos.getX() + r;
        int minZ = housePos.getZ() - r, maxZ = housePos.getZ() + r;
        int minY = Math.max(level.getMinBuildHeight(), housePos.getY() - r);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, housePos.getY() + r);

        Set<BlockPos> exterior = floodExterior(level, minX, minY, minZ, maxX, maxY, maxZ);
        if (exterior.contains(seed)) {
            return null; // the seed is reachable from outside → not actually enclosed
        }
        Set<BlockPos> interior = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        interior.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (interior.contains(n)) continue;
                if (!inBox(n, minX, minY, minZ, maxX, maxY, maxZ)) continue;
                if (!isFloodPassable(level, n)) continue;                // wall blocks; doors pass
                if (exterior.contains(n)) continue;                     // can't leave the enclosure
                if (interior.size() >= MAX_INTERIOR) return null;       // pathological
                interior.add(n);
                queue.add(n);
            }
        }
        return new OpenHouse(interior, exterior);
    }

    /** Floods every air cell reachable from the box's 6 boundary faces, blocked by solids and by
     *  wall holes ({@link #isWallOpening}) — i.e. all "outside" air that can't sneak through a
     *  window/doorway. */
    private static Set<BlockPos> floodExterior(LevelReader level, int minX, int minY, int minZ,
                                                int maxX, int maxY, int maxZ) {
        Set<BlockPos> exterior = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                seedExterior(level, new BlockPos(x, minY, z), exterior, queue);
                seedExterior(level, new BlockPos(x, maxY, z), exterior, queue);
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                seedExterior(level, new BlockPos(x, y, minZ), exterior, queue);
                seedExterior(level, new BlockPos(x, y, maxZ), exterior, queue);
            }
        }
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                seedExterior(level, new BlockPos(minX, y, z), exterior, queue);
                seedExterior(level, new BlockPos(maxX, y, z), exterior, queue);
            }
        }
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (exterior.contains(n)) continue;
                if (!inBox(n, minX, minY, minZ, maxX, maxY, maxZ)) continue;
                if (isStructural(level, n)) continue;                   // walls/doors block; plants pass
                if (isWallOpening(level, n)) continue;                  // don't sneak through windows
                exterior.add(n);
                queue.add(n);
            }
        }
        return exterior;
    }

    private static void seedExterior(LevelReader level, BlockPos p, Set<BlockPos> exterior,
                                      ArrayDeque<BlockPos> queue) {
        if (exterior.contains(p)) return;
        if (isStructural(level, p)) return;
        if (isWallOpening(level, p)) return;
        exterior.add(p);
        queue.add(p);
    }

    private static boolean inBox(BlockPos p, int minX, int minY, int minZ,
                                  int maxX, int maxY, int maxZ) {
        return p.getX() >= minX && p.getX() <= maxX
            && p.getY() >= minY && p.getY() <= maxY
            && p.getZ() >= minZ && p.getZ() <= maxZ;
    }

    /** Shell = every solid block 26-adjacent to an interior air cell, plus the House Block itself.
     *  Walls, roof and the floor layer are included; sub-floor terrain (not adjacent to interior
     *  air) is excluded — that's what stops detection at the ground. When {@code exterior} is given
     *  (open-house path), exterior ground that only touches the interior diagonally — the doorway
     *  floor "spill" — is dropped via {@link #isExteriorSpill}; corner posts and roof are kept. */
    private static Set<BlockPos> extractShell(LevelReader level, Set<BlockPos> interior, BlockPos housePos,
                                              @Nullable Set<BlockPos> exterior) {
        // Pass 1: every structural block 26-adjacent to an interior air cell (plus doors the flood
        // passed through — they're in the interior set but are boundary blocks that must be marked,
        // else installed doors vanish from the detected shape).
        Set<BlockPos> shell = new HashSet<>();
        for (BlockPos air : interior) {
            if (isDoorLike(level.getBlockState(air))) shell.add(air);
            for (int[] o : OFFSETS_26) {
                BlockPos n = air.offset(o[0], o[1], o[2]);
                if (interior.contains(n)) continue;
                if (!isStructural(level, n)) continue;                  // skip air + plants/decoration
                shell.add(n);
            }
        }
        // Pass 2: drop doorway floor "spill" — exterior ground that's only diagonally next to the
        // interior, leaked in through a doorway. Done AFTER pass 1 so the spill test can ask whether
        // a block stands on the rest of the shell (a real wall/post does; a lone ground flap doesn't).
        if (exterior != null) {
            shell.removeIf(solid -> isExteriorSpill(solid, interior, exterior, shell));
        }
        shell.add(housePos.immutable());
        return shell;
    }

    /** A solid is "spill" — exterior ground that leaked into the shell through a doorway — when ALL
     *  of these hold: its top is open exterior air, no face of it touches the interior (only diagonal),
     *  AND it does NOT stand on another shell block. That last clause is the key discriminator: a real
     *  wall's top course stands on the wall below it (which is in the shell), so it's kept; a grass
     *  flap on the ground outside a doorway stands on raw un-detected terrain, so it's dropped. Without
     *  it, the old rule wrongly dropped every wall top-course (sky above, interior only diagonally
     *  below) — the missing-edges bug. Blocks that face the interior directly (floor/wall/roof) or that
     *  have a non-sky block above them are kept by the earlier clauses. */
    private static boolean isExteriorSpill(BlockPos solid, Set<BlockPos> interior, Set<BlockPos> exterior,
                                            Set<BlockPos> shell) {
        if (!exterior.contains(solid.above())) return false;       // top isn't open sky → keep
        for (Direction d : Direction.values()) {
            if (interior.contains(solid.relative(d))) return false; // shares a face with interior → keep
        }
        if (shell.contains(solid.below())) return false;           // stands on the shell → wall/post, keep
        return true;
    }

    /**
     * A block that counts as part of the house's solid shell — anything with a non-empty collision
     * box: walls, glass, fences, slabs, stairs, logs, leaves, plus doors (boundary blocks, special-
     * cased true). Air and non-collidable <i>decoration</i> — flowers, tall grass, crops, saplings,
     * torches, carpets — are NOT structural, so a flower bed beside the house is neither a wall the
     * flood stops at nor a block grabbed into the shell.
     *
     * <p>This is the fix for "{@code !isAir()}" over-reach: plants are not air, so the old check
     * treated a flower as a wall — blocking the exterior flood (trapping air that then read as
     * interior, spilling boxes into the garden) and grabbing the flower itself into the shell, which
     * also distorted the per-layer rectangle decomposition of the real walls.
     */
    private static boolean isStructural(BlockGetter level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.isAir()) return false;
        if (isDoorLike(s)) return true;
        return !s.getCollisionShape(level, pos).isEmpty();
    }

    /** Cells the interior flood may pass through: air, water, non-collidable decoration (flowers,
     *  grass, crops), plus doors / trapdoors / fence gates. Doors are {@link #isStructural structural}
     *  (so the EXTERIOR flood still can't enter through a closed one — it's a wall hole and gets
     *  sealed), but the interior flood treats them as passable so a doorway with a door installed
     *  still connects the rooms on either side. They're still <i>marked</i> as part of the house —
     *  see the door pass in {@link #extractShell}. */
    private static boolean isFloodPassable(BlockGetter level, BlockPos pos) {
        return isDoorLike(level.getBlockState(pos)) || !isStructural(level, pos);
    }

    /** Doors / trapdoors / fence gates — structural boundary blocks the interior flood passes
     *  through but that are still part of the house and must be marked. */
    private static boolean isDoorLike(BlockState state) {
        return state.getBlock() instanceof DoorBlock
            || state.getBlock() instanceof TrapDoorBlock
            || state.getBlock() instanceof FenceGateBlock;
    }

    /**
     * Decomposes the shell voxels into AABB HOME boxes via per-Y-layer greedy maximal rectangles
     * (each box is 1 block tall). A typical hut yields ~10–20 boxes; capped at {@link #MAX_BOXES}.
     * Every rectangle is fully contained in the shell, so the box volumes contain no stray air.
     */
    private static List<BlockSelection> decompose(Set<BlockPos> shell, Settlement owner, Home home,
                                                   BlockPos housePos, UUID playerId) {
        // Bucket shell cells by Y; within a layer track the (x,z) cells as packed longs.
        Map<Integer, Set<Long>> layers = new HashMap<>();
        for (BlockPos p : shell) {
            layers.computeIfAbsent(p.getY(), k -> new HashSet<>()).add(packXZ(p.getX(), p.getZ()));
        }
        List<BlockSelection> boxes = new ArrayList<>();
        for (Map.Entry<Integer, Set<Long>> e : layers.entrySet()) {
            int y = e.getKey();
            Set<Long> cells = e.getValue();
            while (!cells.isEmpty() && boxes.size() < MAX_BOXES) {
                long start = minCell(cells);
                int x0 = unpackX(start), z0 = unpackZ(start);
                // Grow east along +X while cells are present.
                int x1 = x0;
                while (cells.contains(packXZ(x1 + 1, z0))) x1++;
                // Grow south along +Z while the entire [x0..x1] row is present.
                int z1 = z0;
                grow:
                while (true) {
                    int nz = z1 + 1;
                    for (int x = x0; x <= x1; x++) {
                        if (!cells.contains(packXZ(x, nz))) break grow;
                    }
                    z1 = nz;
                }
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) cells.remove(packXZ(x, z));
                }
                boxes.add(BlockSelection.home(UUID.randomUUID(), owner.id(), owner.color().ordinal(),
                    new BlockPos(x0, y, z0), new BlockPos(x1, y, z1), home.id(), housePos.immutable(),
                    playerId));
            }
        }
        return boxes;
    }

    /**
     * True if the air cell {@code pos} is a hole in a wall (window / 1-wide doorway / skylight)
     * rather than open room space — judged by how many of its 6 face-neighbours are solid.
     *
     * <p>A hole punched through a wall is ringed by the wall on the faces in the wall plane and open
     * on the through-axis, so a 1×1 window has 4 solid faces and a 1-wide doorway cell has 3; open
     * room space (and wide ≥2-block openings) has ≤2. Counting <i>faces</i> — not framing within a
     * radius — is what lets a small enclosed room flood normally: a cosy 3×3×2 interior's cells have
     * ≤2 solid faces, so they're never mistaken for windows. (The only side effect is that a room's
     * own corner cells, with 3 solid faces, get treated as wall; harmless — the flood routes around
     * them and the 26-adjacent shell still picks up the corner blocks.)
     *
     * <p><b>Diagonal walls.</b> A wall built on an angle braces its gaps with EDGE/CORNER neighbours
     * rather than full faces, so a face-only count leaks an angled-walled house ("not enclosed"). So a
     * gap also seals when it has ≥2 solid faces AND enough solids over the 18-neighbourhood (6 faces +
     * 12 edges) to read as an angled wall ({@link #DIAG_SEAL_MIN}). A genuine ≥2-wide opening keeps
     * open (air) edge-neighbours on its long side, staying under the threshold, so it still leaks.
     * Connectivity already counts diagonal adjacency (26-neighbour); this brings enclosure in line.
     *
     * <p>Used by the detector's second flood pass and the validator's enclosure check, so a hut with
     * hole-windows both auto-detects and validates, while a genuine ≥2-wide missing wall still leaks.
     */
    public static boolean isWallOpening(BlockGetter level, BlockPos pos) {
        int solidFaces = 0;
        for (Direction d : Direction.values()) {
            if (isStructural(level, pos.relative(d))) solidFaces++;
        }
        if (solidFaces >= OPENING_MIN_SOLID_FACES) return true;
        if (solidFaces < 2) return false; // too open to be a braced gap — keep wide holes leaking
        int solidEdges = 0;
        for (int[] o : EDGE_OFFSETS_12) {
            if (isStructural(level, pos.offset(o[0], o[1], o[2]))) solidEdges++;
        }
        return solidFaces + solidEdges >= DIAG_SEAL_MIN;
    }

    /** Seal threshold (solid faces + edges, of 18) for a gap braced by a diagonal/angled wall — the
     *  enclosure twin of {@link #OPENING_MIN_SOLID_FACES}. Set above a plain interior corner cell
     *  (2 solid faces + a wall/floor's ~7 solid edges = 9) so those aren't mistaken for wall, while an
     *  angled-wall gap (braced on faces AND edges) still seals; a ≥2-wide flat-wall hole keeps open
     *  edge-neighbours on its long side and stays under it, so it still leaks. EMPIRICAL — tune in-game
     *  against the real angled-wall house (see the BROKEN_NOT_ENCLOSED repro). */
    public static final int DIAG_SEAL_MIN = 10;

    /** The 12 edge-neighbour offsets (exactly two non-zero unit components). */
    private static final int[][] EDGE_OFFSETS_12 = buildEdgeOffsets();

    private static int[][] buildEdgeOffsets() {
        java.util.List<int[]> offs = new java.util.ArrayList<>(12);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 2) offs.add(new int[]{dx, dy, dz});
                }
            }
        }
        return offs.toArray(new int[0][]);
    }

    private static int chebyshev(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()),
            Math.max(Math.abs(a.getY() - b.getY()), Math.abs(a.getZ() - b.getZ())));
    }

    // ── (x,z) long packing for the per-layer rectangle cover; int casts preserve negatives. ──
    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long v) { return (int) (v >> 32); }

    private static int unpackZ(long v) { return (int) v; }

    /** Smallest cell by (x, then z) — deterministic anchor so rectangles grow east-then-south. */
    private static long minCell(Set<Long> cells) {
        long best = Long.MAX_VALUE;
        int bestX = Integer.MAX_VALUE, bestZ = Integer.MAX_VALUE;
        for (long c : cells) {
            int x = unpackX(c), z = unpackZ(c);
            if (x < bestX || (x == bestX && z < bestZ)) {
                bestX = x; bestZ = z; best = c;
            }
        }
        return best;
    }

    private static int[][] build26() {
        List<int[]> offs = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) offs.add(new int[]{dx, dy, dz});
                }
            }
        }
        return offs.toArray(new int[0][]);
    }
}
