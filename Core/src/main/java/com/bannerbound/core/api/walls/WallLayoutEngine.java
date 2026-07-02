package com.bannerbound.core.api.walls;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.walls.DefaultWallDesigns.WallDesignSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Computes a {@link WallPlan} from a settlement's claimed-chunk set, the terrain under the
 * border, and an active design set. Pure derivation — deterministic for (claims, terrain,
 * designs); callers freeze the result into {@link WallData} when the player commits.
 *
 * <p>Pipeline (WALLS_PLAN.md §B):
 * <ol>
 *   <li><b>Border tracing</b> — chunk edges whose 4-neighbour is unclaimed, chained into closed
 *       loops walked with the interior on the RIGHT (so loops run clockwise and a right turn =
 *       convex corner, left = concave). Outposts ({@code workingClaims}) are excluded.</li>
 *   <li><b>Corners</b> — every loop vertex stamps the corner design into the N×N square inside
 *       the territory diagonal to the vertex's open side (one rule covers convex AND concave;
 *       the design's outward faces point at the missing/diagonal quadrant).</li>
 *   <li><b>Run fill</b> — straight runs (outermost block ring INSIDE the claim, clipped where
 *       corner squares overlap) tile with the segment design; a remainder that doesn't fit a
 *       full instance emits a truncated piece, never a hole.</li>
 *   <li><b>Terrain</b> — per-column ground via a walk-down from the motion-blocking heightmap
 *       through vegetation and water (all server-side; clients never compute Y). Slope ≤
 *       {@value #DRAPE_MAX_SLOPE} across the piece → DRAPE, else STEPPED at median ground + 1
 *       with foundation fill.</li>
 *   <li><b>Obstacles</b> — vegetation in the footprint is counted for the clear list; a piece
 *       where more than half the outer-row columns sit on ≥{@value #DEEP_WATER_DEPTH} water
 *       becomes a recorded water gap (water IS the wall there).</li>
 * </ol>
 */
public final class WallLayoutEngine {

    /**
     * Blocks the wall footprint treats as removable decor (cleared by builders / marked red),
     * never as ground — on top of the built-in heuristics (vegetation tags, replaceables, and
     * anything with an empty collision shape, which catches the Antiquity surface rocks).
     * Data-driven so expansions tag their own minor blocks without touching Core.
     */
    public static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> WALL_CLEARABLE =
        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bannerbound", "wall_clearable"));

    /**
     * What counts as TERRAIN for the wall's ground walk. Ground is defined by this data tag —
     * never by mere solidity — so the existing wall, a house straddling the border, or any
     * player build is an OBSTACLE the plan reds-out at true ground level instead of a surface
     * to stack the wall on (playtest lesson 2026-06-11: solidity-based ground stacked walls on
     * walls). Vanilla terrain + ores + terracottas ship in Core's
     * {@code data/bannerbound/tags/block/wall_ground.json}; expansions append their own.
     */
    public static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> WALL_GROUND =
        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bannerbound", "wall_ground"));

    /** Max ground-Y spread across a piece that still drapes (hybrid-by-steepness rule). */
    public static final int DRAPE_MAX_SLOPE = 1;
    /** Water at least this deep turns a piece slot into a water gap instead of a wall. */
    public static final int DEEP_WATER_DEPTH = 4;
    /** Cap on the ground walk-down, so a wall over a jungle canopy can't scan to bedrock. */
    private static final int GROUND_SCAN_CAP = 48;

    private WallLayoutEngine() {
    }

    // ─── Result types ───────────────────────────────────────────────────────────────────────

    public record Stats(int loops, int convexCorners, int concaveCorners,
                        int segments, int truncatedSegments, int gates, int waterGaps,
                        int draped, int stepped, int foundationBlocks, int clearBlocks,
                        int obstacleBlocks, int perimeterColumns) {
    }

    public record LayoutResult(WallPlan plan, Stats stats, List<String> warnings) {
    }

    // ─── Internal geometry types ──────────────────────────────────────────────────────────────

    /** A directed border edge between chunk-lattice vertices, interior on the right. */
    private record Edge(int vx, int vz, Direction dir) {
        int endVx() { return vx + dir.getStepX(); }
        int endVz() { return vz + dir.getStepZ(); }
    }

    /** A loop vertex where direction changes: {@code in} arrives, {@code out} leaves. */
    private record Corner(int vx, int vz, Direction in, Direction out) {
        boolean convex() {
            // Interior-on-right walking: clockwise loops, so a clockwise turn is convex.
            return out == in.getClockWise();
        }
    }

    /** A straight border run between two corners, in chunk-lattice vertex coords. */
    private record Run(int vx, int vz, Direction dir, int lengthChunks, Corner startCorner, Corner endCorner) {
    }

    /** Per-column terrain sample. {@code obstacleBlocks} = non-terrain structures in the
     *  column (player builds, old walls) — red-marked, never built on, never auto-cleared. */
    private record Ground(int groundY, int waterDepth, int clearBlocks, int obstacleBlocks) {
    }

    // ─── Entry point ──────────────────────────────────────────────────────────────────────────

    /** Convenience overload for callers with no existing committed plan and no gates. */
    public static LayoutResult compute(ServerLevel level, Settlement settlement, WallDesignSet designs) {
        return compute(level, settlement, designs, it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET,
            it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET);
    }

    /**
     * @param existingWall packed positions the settlement considers wall (blueprint ∪ obsolete
     *        ∪ builtWall). The ground walk passes through these as if they were air —
     *        otherwise re-running the layout would read the half-built wall as terrain and
     *        stack a second wall on top of it. Makes re-construct idempotent.
     * @param gateAnchors packed (x, 0, z) piece-start positions the player marked as gates in
     *        the preview. The run fill emits the GATE design at a slot whose start matches an
     *        anchor — anchors are recorded from this same deterministic tiling, so they're
     *        stable across recomputes; anchors that no longer match any slot (border moved)
     *        are silently ignored.
     */
    public static LayoutResult compute(ServerLevel level, Settlement settlement, WallDesignSet designs,
                                       it.unimi.dsi.fastutil.longs.LongSet existingWall,
                                       it.unimi.dsi.fastutil.longs.LongSet gateAnchors) {
        List<String> warnings = new ArrayList<>();
        if (settlement.claimedChunks().isEmpty()) {
            return new LayoutResult(new WallPlan(new ArrayList<>()),
                new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of("Settlement has no claimed territory."));
        }

        List<List<Edge>> loops = traceLoops(settlement.claimedChunks());
        if (loops.size() > 1) {
            warnings.add(loops.size() + " separate border loops (disjoint territory or interior hole) — all walled.");
        }

        List<WallPiece> pieces = new ArrayList<>();
        int convex = 0;
        int concave = 0;
        int segments = 0;
        int truncated = 0;
        int gates = 0;
        int waterGaps = 0;
        int draped = 0;
        int stepped = 0;
        int foundation = 0;
        int clears = 0;
        int obstacles = 0;
        int perimeter = 0;

        Map<BlockPos, Ground> groundCache = new HashMap<>();
        Sampler sampler = new Sampler(level, existingWall, gateAnchors, groundCache);

        for (List<Edge> loop : loops) {
            List<Corner> corners = cornersOf(loop);
            List<Run> runs = runsOf(corners);

            for (Corner corner : corners) {
                if (corner.convex()) convex++;
                else concave++;
                WallPiece piece = placeCorner(sampler, corner, designs.corner());
                if (piece.waterGap()) waterGaps++;
                pieces.add(piece);
                perimeter += piece.length() * piece.depth();
            }

            for (Run run : runs) {
                for (WallPiece piece : placeRun(sampler, run, designs)) {
                    pieces.add(piece);
                    perimeter += piece.length() * piece.depth();
                    if (piece.waterGap()) {
                        waterGaps++;
                    } else if (piece.kind() == WallDesign.Kind.GATE) {
                        gates++;
                    } else {
                        segments++;
                        WallDesign design = designs.byId(piece.designId());
                        if (design != null && piece.length() < design.length()) truncated++;
                    }
                }
            }
        }

        // Stats that need the sampled terrain: mode split, foundation volume, clear volume.
        for (WallPiece piece : pieces) {
            if (piece.waterGap()) continue;
            if (piece.mode() == WallPiece.Mode.DRAPE) draped++;
            else stepped++;
            WallDesign design = designs.byId(piece.designId());
            if (design == null) continue;
            int[] counts = countFoundationAndClears(piece, design, groundCache);
            foundation += counts[0];
            clears += counts[1];
            obstacles += counts[2];
        }

        Stats stats = new Stats(loops.size(), convex, concave, segments, truncated, gates,
            waterGaps, draped, stepped, foundation, clears, obstacles, perimeter);
        return new LayoutResult(new WallPlan(pieces), stats, warnings);
    }

    // ─── 1. Border tracing ────────────────────────────────────────────────────────────────────

    /**
     * Directed edges per claimed chunk with an unclaimed 4-neighbour, chained into closed
     * loops. Edge directions keep the interior on the right (north side → EAST, east → SOUTH,
     * south → WEST, west → NORTH). At ambiguous lattice vertices (two claims touching
     * diagonally) the walk prefers the sharpest clockwise turn, which keeps each claim blob's
     * loop tight instead of fusing figure-eights.
     */
    private static List<List<Edge>> traceLoops(java.util.Set<Long> claimed) {
        Map<Long, List<Edge>> outgoing = new HashMap<>();
        int edgeCount = 0;
        for (long packed : claimed) {
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            if (!claimed.contains(ChunkPos.asLong(cx, cz - 1))) {
                addEdge(outgoing, new Edge(cx, cz, Direction.EAST));
                edgeCount++;
            }
            if (!claimed.contains(ChunkPos.asLong(cx + 1, cz))) {
                addEdge(outgoing, new Edge(cx + 1, cz, Direction.SOUTH));
                edgeCount++;
            }
            if (!claimed.contains(ChunkPos.asLong(cx, cz + 1))) {
                addEdge(outgoing, new Edge(cx + 1, cz + 1, Direction.WEST));
                edgeCount++;
            }
            if (!claimed.contains(ChunkPos.asLong(cx - 1, cz))) {
                addEdge(outgoing, new Edge(cx, cz + 1, Direction.NORTH));
                edgeCount++;
            }
        }

        List<List<Edge>> loops = new ArrayList<>();
        int consumed = 0;
        while (consumed < edgeCount) {
            Edge start = null;
            for (List<Edge> list : outgoing.values()) {
                if (!list.isEmpty()) {
                    start = list.get(0);
                    break;
                }
            }
            if (start == null) break;

            List<Edge> loop = new ArrayList<>();
            Edge current = start;
            while (true) {
                outgoing.get(vertexKey(current.vx(), current.vz())).remove(current);
                consumed++;
                loop.add(current);
                Edge next = nextEdge(outgoing, current, start);
                if (next == null || next.equals(start)) break; // loop closed
                current = next;
            }
            loops.add(loop);
        }
        return loops;
    }

    private static void addEdge(Map<Long, List<Edge>> outgoing, Edge edge) {
        outgoing.computeIfAbsent(vertexKey(edge.vx(), edge.vz()), k -> new ArrayList<>()).add(edge);
    }

    private static long vertexKey(int vx, int vz) {
        return ((long) vx << 32) ^ (vz & 0xFFFFFFFFL);
    }

    /**
     * Picks the walk's continuation at {@code current}'s end vertex: sharpest clockwise turn
     * first, then straight, then counterclockwise (tight right turns keep each blob's loop
     * separate at diagonal-pinch vertices). The already-consumed {@code start} edge competes
     * as a virtual candidate at its preference rank — if it wins, the loop has closed; without
     * this, a walk returning to a pinched start vertex would wrongly continue into the other
     * loop's edges.
     */
    private static Edge nextEdge(Map<Long, List<Edge>> outgoing, Edge current, Edge start) {
        List<Edge> candidates = outgoing.get(vertexKey(current.endVx(), current.endVz()));
        boolean atStartVertex = current.endVx() == start.vx() && current.endVz() == start.vz();
        Direction[] preference = {
            current.dir().getClockWise(), current.dir(), current.dir().getCounterClockWise()
        };
        for (Direction dir : preference) {
            if (atStartVertex && start.dir() == dir) return start;
            if (candidates != null) {
                for (Edge candidate : candidates) {
                    if (candidate.dir() == dir) return candidate;
                }
            }
        }
        return candidates == null || candidates.isEmpty() ? null : candidates.get(0);
    }

    private static List<Corner> cornersOf(List<Edge> loop) {
        List<Corner> corners = new ArrayList<>();
        for (int i = 0; i < loop.size(); i++) {
            Edge edge = loop.get(i);
            Edge next = loop.get((i + 1) % loop.size());
            if (edge.dir() != next.dir()) {
                corners.add(new Corner(next.vx(), next.vz(), edge.dir(), next.dir()));
            }
        }
        return corners;
    }

    private static List<Run> runsOf(List<Corner> corners) {
        if (corners.isEmpty()) return List.of();
        // Corner list is in loop order (cornersOf walks the loop), so the run between
        // consecutive corners is a straight edge chain; length = manhattan vertex distance.
        List<Run> runs = new ArrayList<>();
        for (int i = 0; i < corners.size(); i++) {
            Corner from = corners.get(i);
            Corner to = corners.get((i + 1) % corners.size());
            Direction dir = from.out();
            int lengthChunks = Math.abs(to.vx() - from.vx()) + Math.abs(to.vz() - from.vz());
            runs.add(new Run(from.vx(), from.vz(), dir, lengthChunks, from, to));
        }
        return runs;
    }

    // ─── 2. Corner placement ──────────────────────────────────────────────────────────────────

    /**
     * The corner footprint is the N×N block square INSIDE the territory touching the vertex —
     * the quadrant given by (interior side of the incoming edge) ∩ (interior side of the
     * outgoing edge). Its representative outward direction (for block rotation) points at the
     * diagonally opposite quadrant, which is exactly where the open/unclaimed side is for both
     * convex and concave corners.
     */
    private static WallPiece placeCorner(Sampler sampler, Corner corner, WallDesign design) {
        int n = design.length();
        int vx = corner.vx() * 16;
        int vz = corner.vz() * 16;

        boolean xEast = interiorSignX(corner) > 0;  // footprint east of the vertex?
        boolean zSouth = interiorSignZ(corner) > 0; // footprint south of the vertex?
        int minX = xEast ? vx : vx - n;
        int minZ = zSouth ? vz : vz - n;
        int maxX = minX + n - 1;
        int maxZ = minZ + n - 1;

        // Outward = diagonal opposite of the footprint quadrant; representative direction picks
        // the design rotation (authored as the land's NW corner → outward NORTH).
        Direction outward;
        int startX;
        int startZ;
        if (xEast && zSouth) {           // footprint SE of vertex → land NW corner
            outward = Direction.NORTH;
            startX = minX;
            startZ = minZ;
        } else if (!xEast && zSouth) {   // footprint SW → land NE corner
            outward = Direction.EAST;
            startX = maxX;
            startZ = minZ;
        } else if (!xEast && !zSouth) {  // footprint NW → land SE corner
            outward = Direction.SOUTH;
            startX = maxX;
            startZ = maxZ;
        } else {                         // footprint NE → land SW corner
            outward = Direction.WEST;
            startX = minX;
            startZ = maxZ;
        }

        return samplePiece(sampler, design, design.id(), WallDesign.Kind.CORNER, outward,
            startX, startZ, n, n);
    }

    /** Sign of the interior X half-plane at the corner (+1 = interior east, -1 = west, 0 = unconstrained). */
    private static int interiorSignX(Corner corner) {
        int sign = interiorSignX(corner.in());
        return sign != 0 ? sign : interiorSignX(corner.out());
    }

    private static int interiorSignZ(Corner corner) {
        int sign = interiorSignZ(corner.in());
        return sign != 0 ? sign : interiorSignZ(corner.out());
    }

    // Interior is on the RIGHT of travel: EAST → south(+z), SOUTH → west(-x),
    // WEST → north(-z), NORTH → east(+x).
    private static int interiorSignX(Direction dir) {
        return switch (dir) {
            case SOUTH -> -1;
            case NORTH -> 1;
            default -> 0;
        };
    }

    private static int interiorSignZ(Direction dir) {
        return switch (dir) {
            case EAST -> 1;
            case WEST -> -1;
            default -> 0;
        };
    }

    // ─── 3. Run fill ──────────────────────────────────────────────────────────────────────────

    /**
     * Expands a corner-to-corner run into segment pieces. The strip is the outermost block
     * ring inside the claim along this border edge; the along-axis interval is clipped
     * wherever the two adjacent corner squares actually overlap it (a corner whose footprint
     * sits on the far side of the vertex doesn't eat into this run).
     */
    private static List<WallPiece> placeRun(Sampler sampler, Run run, WallDesignSet designs) {
        Direction dir = run.dir();
        int vx = run.vx() * 16;
        int vz = run.vz() * 16;
        int lengthBlocks = run.lengthChunks() * 16;
        int cornerN = designs.corner().length();

        // Strip geometry: outward = interior's opposite... outward is the unclaimed side.
        // For a run travelling EAST the interior is south, so outward = NORTH and the wall row
        // is the first row inside: z = vz. Equivalents for the other three travel directions.
        Direction outward;
        int rowX = 0;
        int rowZ = 0;     // the fixed cross-axis coordinate of the strip's outer row
        int alongMin;     // inclusive block interval along the run axis
        int alongMax;
        boolean alongIsX;
        switch (dir) {
            case EAST -> {
                outward = Direction.NORTH;
                rowZ = vz;
                alongIsX = true;
                alongMin = vx;
                alongMax = vx + lengthBlocks - 1;
            }
            case SOUTH -> {
                outward = Direction.EAST;
                rowX = vx - 1;
                alongIsX = false;
                alongMin = vz;
                alongMax = vz + lengthBlocks - 1;
            }
            case WEST -> {
                outward = Direction.SOUTH;
                rowZ = vz - 1;
                alongIsX = true;
                alongMin = vx - lengthBlocks;
                alongMax = vx - 1;
            }
            case NORTH -> {
                outward = Direction.WEST;
                rowX = vx;
                alongIsX = false;
                alongMin = vz - lengthBlocks;
                alongMax = vz - 1;
            }
            default -> throw new IllegalStateException("Vertical border direction");
        }

        // Clip the interval where the adjacent corner squares overlap this strip. Apply BOTH
        // bounds from BOTH corners: the canonical along direction can be FLIPPED relative to
        // travel (west/north sides), putting the travel-start corner at the interval's MAX
        // end — taking only [0] from the start clip and [1] from the end clip left those
        // sides unclipped and buried the corner pieces under segments (playtest 2026-06-12).
        int[] clipped = clipForCorner(run.startCorner(), cornerN, alongIsX, alongMin, alongMax);
        alongMin = clipped[0];
        alongMax = clipped[1];
        clipped = clipForCorner(run.endCorner(), cornerN, alongIsX, alongMin, alongMax);
        alongMin = clipped[0];
        alongMax = clipped[1];
        if (alongMax < alongMin) return List.of();

        // Tile the interval with segment pieces in the canonical along direction (outward
        // rotated clockwise) so design orientation is consistent regardless of travel direction.
        Direction along = outward.getClockWise();
        boolean canonicalAscending = along.getStepX() + along.getStepZ() > 0;
        WallDesign wall = designs.wall();
        WallDesign gate = designs.gate();

        // Gate anchors anywhere on this run, as walk-order offsets — 1-BLOCK granularity.
        // Gates used to appear only when an anchor coincided with the segment tiling's slot
        // starts, which quantized placement to a wall-length grid hung off each side's corner
        // ("it's like I can't place them in the middle", playtest 2026-06-12). Now the walk
        // truncates the wall filler INTO the next gate start, so a gate can sit anywhere;
        // anchors overlapped by an earlier gate are passed by (and toggleGate's recompute
        // validation rejects them, since no piece start matches).
        it.unimi.dsi.fastutil.ints.IntArrayList gateOffsets = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.longs.LongIterator anchorIt = sampler.gateAnchors().iterator();
        while (anchorIt.hasNext()) {
            long anchor = anchorIt.nextLong();
            int ax = BlockPos.getX(anchor);
            int az = BlockPos.getZ(anchor);
            if (alongIsX ? az != rowZ : ax != rowX) continue; // other run / other row
            int a = alongIsX ? ax : az;
            if (a < alongMin || a > alongMax) continue;
            gateOffsets.add(canonicalAscending ? a - alongMin : alongMax - a);
        }
        gateOffsets.sort(it.unimi.dsi.fastutil.ints.IntComparators.NATURAL_COMPARATOR);

        List<WallPiece> pieces = new ArrayList<>();
        int remaining = alongMax - alongMin + 1;
        int cursor = canonicalAscending ? alongMin : alongMax;
        int walked = 0;
        int gateIdx = 0;
        while (remaining > 0) {
            while (gateIdx < gateOffsets.size() && gateOffsets.getInt(gateIdx) < walked) gateIdx++;
            boolean isGate = gateIdx < gateOffsets.size() && gateOffsets.getInt(gateIdx) == walked
                // A gate must fit WHOLE: a span truncated by the run end (the corner's
                // footprint) would lose its opening — "you shouldn't be able to place gates
                // at corners" (playtest 2026-06-12). Unexpressed anchors fail toggleGate's
                // recompute validation, so the client gets a clean rejection.
                && remaining >= gate.length();
            WallDesign design = isGate ? gate : wall;
            int take = Math.min(design.length(), remaining);
            if (!isGate && gateIdx < gateOffsets.size()) {
                take = Math.min(take, gateOffsets.getInt(gateIdx) - walked);
            }
            int startX = alongIsX ? cursor : rowX;
            int startZ = alongIsX ? rowZ : cursor;
            pieces.add(samplePiece(sampler, design, design.id(),
                isGate ? WallDesign.Kind.GATE : WallDesign.Kind.SEGMENT, outward,
                startX, startZ, take, design.depth()));
            cursor += (canonicalAscending ? 1 : -1) * take;
            walked += take;
            remaining -= take;
            if (isGate) gateIdx++;
        }
        return assignRunTops(pieces, designs);
    }

    /**
     * Returns the run interval with the given corner's footprint excluded, when that footprint
     * actually overlaps this strip's along-axis range (a corner whose square sits on the far
     * side of its vertex doesn't eat into this run — concave junctions stay flush).
     */
    private static int[] clipForCorner(Corner corner, int cornerN, boolean alongIsX,
                                       int alongMin, int alongMax) {
        int v = (alongIsX ? corner.vx() : corner.vz()) * 16;
        boolean positiveSide = alongIsX ? interiorSignX(corner) > 0 : interiorSignZ(corner) > 0;
        int squareMin = positiveSide ? v : v - cornerN;
        int squareMax = squareMin + cornerN - 1;
        int newMin = alongMin;
        int newMax = alongMax;
        if (squareMax >= alongMin && squareMin <= alongMax) {
            if (squareMin <= alongMin) {
                newMin = squareMax + 1;
            }
            if (squareMax >= alongMax) {
                newMax = squareMin - 1;
            }
        }
        return new int[]{newMin, newMax};
    }

    // ─── 4 + 5. Terrain sampling, mode pick, water gaps ───────────────────────────────────────

    /** Bundles what terrain sampling needs: the level, the committed plan's blueprint
     *  positions (passed through as air so walls never stack on themselves), and the
     *  per-compute column cache. */
    private record Sampler(ServerLevel level, it.unimi.dsi.fastutil.longs.LongSet existingWall,
                           it.unimi.dsi.fastutil.longs.LongSet gateAnchors,
                           Map<BlockPos, Ground> groundCache) {
    }

    private static WallPiece samplePiece(Sampler sampler, WallDesign design, String designId,
                                         WallDesign.Kind kind, Direction outward,
                                         int startX, int startZ, int length, int depth) {
        Direction along = outward.getClockWise();
        Direction inward = outward.getOpposite();
        int[] groundY = new int[length * depth];
        int deepWaterOuterColumns = 0;
        int minGround = Integer.MAX_VALUE;
        List<Integer> grounds = new ArrayList<>(length * depth);
        for (int l = 0; l < length; l++) {
            for (int d = 0; d < depth; d++) {
                int x = startX + along.getStepX() * l + inward.getStepX() * d;
                int z = startZ + along.getStepZ() * l + inward.getStepZ() * d;
                Ground ground = sampleGround(sampler, x, z);
                groundY[l * depth + d] = ground.groundY();
                grounds.add(ground.groundY());
                minGround = Math.min(minGround, ground.groundY());
                if (d == 0 && ground.waterDepth() >= DEEP_WATER_DEPTH) {
                    deepWaterOuterColumns++;
                }
            }
        }

        boolean waterGap = deepWaterOuterColumns > length / 2;

        // TOP-ALIGNED placement (user decision 2026-06-12): every piece is level; the
        // placeholder anchors the full design on the piece's own crest (top = maxGround +
        // height). Run pieces get re-chained by assignRunTops so a whole run shares one
        // walkable top wherever the terrain fits within the design height.
        int baseY = (minGround == Integer.MAX_VALUE ? 0 : maxOf(groundY)) + 1;
        return new WallPiece(designId, kind, outward, startX, startZ, length, depth,
            WallPiece.Mode.STEPPED, baseY, groundY, waterGap);
    }

    private static int maxOf(int[] values) {
        int max = Integer.MIN_VALUE;
        for (int v : values) max = Math.max(max, v);
        return max;
    }

    /** Maximum foundation courses under the lowest column before the chain re-anchors. */
    private static final int MAX_FOUNDATION_COURSES = 4;

    /** How many base courses may be buried before the chain re-anchors UP: a third of the
     *  design height (min 1). The old rule (keep 2 visible) let most of a wall sink into
     *  rising ground before lifting — playtest 2026-06-12: bury a LITTLE, lift EARLY. */
    private static int maxBuriedCourses(int height) {
        return Math.max(1, height / 3);
    }

    /**
     * Top-alignment chain (user decision 2026-06-12): a run keeps ONE level wall top for as
     * long as the terrain fits — high ground BURIES base courses (omitted from the blueprint,
     * so cheaper), dips get foundation fill (≤ {@value #MAX_FOUNDATION_COURSES} courses) —
     * and only re-anchors when the design height can't absorb the change. This keeps
     * wall-top walkways level across slopes instead of stepping every piece independently.
     */
    private static List<WallPiece> assignRunTops(List<WallPiece> pieces, WallDesignSet designs) {
        List<WallPiece> out = new ArrayList<>(pieces.size());
        int currentTop = Integer.MIN_VALUE;
        for (WallPiece piece : pieces) {
            WallDesign design = designs.byId(piece.designId());
            if (piece.waterGap() || design == null) {
                out.add(piece);
                currentTop = Integer.MIN_VALUE; // a gap breaks the level chain
                continue;
            }
            int height = design.height();
            // A gate's PASSAGE rows must never be buried by the level chain — force a
            // re-anchor so the full gate design sits on its own crest, then chain onward.
            if (piece.kind() == WallDesign.Kind.GATE) {
                currentTop = Integer.MIN_VALUE;
            }
            int minTop = piece.maxGround() + height - maxBuriedCourses(height);
            int maxTop = piece.minGround() + height + MAX_FOUNDATION_COURSES;
            if (currentTop < minTop || currentTop > maxTop) {
                currentTop = piece.maxGround() + height; // re-anchor: full design on the crest
            }
            out.add(piece.withBaseY(currentTop - height + 1));
        }
        return out;
    }

    /**
     * Walks down from the motion-blocking heightmap through air, water and clearable
     * vegetation to the first real ground block. Server-side only — clients must never run
     * this (their heightmaps beyond WORLD_SURFACE/MOTION_BLOCKING are garbage; established
     * rule). Forces the chunk if needed, which is fine: the border is the player's own claim,
     * always generated, and this is command/apply-time work, not a tick path.
     */
    private static Ground sampleGround(Sampler sampler, int x, int z) {
        BlockPos key = new BlockPos(x, 0, z);
        Ground cached = sampler.groundCache().get(key);
        if (cached != null) return cached;

        ServerLevel level = sampler.level();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        int water = 0;
        int clears = 0;
        int obstacles = 0;
        int scanned = 0;
        int firstSolid = Integer.MIN_VALUE;
        boolean groundFound = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (y > level.getMinBuildHeight() + 1 && scanned++ < GROUND_SCAN_CAP) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            // Committed-plan blocks pass through BEFORE the terrain check: a wall DESIGNED
            // out of terrain blocks (dirt, sand, stone) must read as wall, not as a hill —
            // checking WALL_GROUND first would resurrect the stack-on-wall bug for exactly
            // those designs.
            if (sampler.existingWall().contains(cursor.asLong())) {
                y--;
                continue;
            }
            if (state.is(WALL_GROUND)) {
                groundFound = true;
                break;
            }
            if (state.isAir()) {
                y--;
            } else if (state.getFluidState().is(FluidTags.WATER)) {
                water++;
                y--;
            } else if (isClearable(level, cursor, state)) {
                clears++;
                y--;
            } else {
                // Non-terrain structure: an old wall, a house edge, any player build. Track
                // the topmost one as the fallback surface, keep descending toward real ground.
                if (firstSolid == Integer.MIN_VALUE) {
                    firstSolid = y;
                }
                obstacles++;
                y--;
            }
        }
        if (!groundFound && firstSolid != Integer.MIN_VALUE) {
            // No tagged terrain within the scan (huge artificial platform, modded surface...) —
            // fall back to the topmost solid so the wall still lands somewhere sane.
            y = firstSolid;
            obstacles = 0;
        }
        Ground ground = new Ground(y, water, clears, obstacles);
        sampler.groundCache().put(key, ground);
        return ground;
    }

    /**
     * Vegetation and minor decor the builder clears from the footprint (logs go to the
     * stockpile, Phase 4): vegetation tags, replaceables, the {@link #WALL_CLEARABLE} data
     * tag, and anything with no collision shape — surface decor like the Antiquity rocks is
     * an obstacle to remove, never ground to build on.
     */
    public static boolean isClearableBlock(ServerLevel level, BlockPos pos, BlockState state) {
        return isClearable(level, pos, state);
    }

    private static boolean isClearable(ServerLevel level, BlockPos pos, BlockState state) {
        return state.is(BlockTags.LEAVES)
            || state.is(BlockTags.LOGS)
            || state.is(BlockTags.SAPLINGS)
            || state.is(BlockTags.FLOWERS)
            || state.is(WALL_CLEARABLE)
            || state.canBeReplaced()
            || state.getCollisionShape(level, pos).isEmpty();
    }

    /** [foundationBlocks, clearBlocks, obstacleBlocks] for one sampled piece. */
    private static int[] countFoundationAndClears(WallPiece piece, WallDesign design,
                                                  Map<BlockPos, Ground> groundCache) {
        int foundation = 0;
        int clears = 0;
        int obstacles = 0;
        Direction along = piece.along();
        Direction inward = piece.inward();
        for (int l = 0; l < piece.length(); l++) {
            for (int d = 0; d < piece.depth(); d++) {
                int x = piece.startX() + along.getStepX() * l + inward.getStepX() * d;
                int z = piece.startZ() + along.getStepZ() * l + inward.getStepZ() * d;
                Ground ground = groundCache.get(new BlockPos(x, 0, z));
                if (ground == null) continue;
                clears += ground.clearBlocks();
                obstacles += ground.obstacleBlocks();
                if (piece.mode() == WallPiece.Mode.STEPPED) {
                    foundation += Math.max(0, piece.baseY() - (ground.groundY() + 1));
                }
            }
        }
        return new int[]{foundation, clears, obstacles};
    }

    // ─── Debug helpers (used by /bannerbound walls layout) ──────────────────────────────────

    /** Per-kind piece counts for the dump line. */
    public static Map<WallDesign.Kind, Integer> countByKind(WallPlan plan) {
        Map<WallDesign.Kind, Integer> counts = new EnumMap<>(WallDesign.Kind.class);
        for (WallPiece piece : plan.pieces()) {
            counts.merge(piece.kind(), 1, Integer::sum);
        }
        return counts;
    }

    /** Stable, display-ordered copy of a required-items map (largest counts first). */
    public static Map<net.minecraft.world.item.Item, Integer> sortedRequired(Map<net.minecraft.world.item.Item, Integer> required) {
        List<Map.Entry<net.minecraft.world.item.Item, Integer>> entries = new ArrayList<>(required.entrySet());
        entries.sort(Map.Entry.<net.minecraft.world.item.Item, Integer>comparingByValue().reversed());
        Map<net.minecraft.world.item.Item, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<net.minecraft.world.item.Item, Integer> entry : entries) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }
}
