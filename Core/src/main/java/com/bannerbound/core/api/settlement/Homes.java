package com.bannerbound.core.api.settlement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.api.world.HouseStructureDetector;
import com.bannerbound.core.entity.HousingEvictionHook;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * Server-side service for homes — the residence twin of {@link com.bannerbound.core.api.workshop.Workshops}.
 * Homes have no anchor block: a home is defined purely by the Housing Orders rod's
 * {@link BlockSelection.Kind#HOME} selections (keyed by {@link Home#id()} in the registry), and
 * validated on commit, on panel open, and from the background {@code HomeRevalidator} sweep.
 *
 * <p>Validation keeps the houses-only rules the old House Block entity ran — connectivity,
 * <b>enclosure</b> (walls + roof), a per-era size cap, and a <b>bed</b> count — but measures the
 * size cap as a union span (no House Block to anchor a radius to) and seeds the enclosure flood
 * from any interior air (no House Block to start beside). The marked-region geometry helpers
 * ({@link #collectMarkedSolids}, {@link #isConnected}) live here and are shared with the workshop
 * code, which used to call them on the now-deleted {@code HouseBlockEntity}.
 */
public final class Homes {

    private Homes() {
    }

    // ─── Validation ─────────────────────────────────────────────────────────────────────────────

    /**
     * Full validation of {@code home} against its current HOME-selection union: connectivity,
     * enclosure, size limit, and bed count. Updates the home's status / validity / bed count /
     * appeal cache / anchor pos in place, evicts surplus residents if the bed count dropped, and
     * marks settlement data dirty.
     */
    public static Home.Status validate(ServerLevel sl, Home home) {
        MinecraftServer server = sl.getServer();
        if (server == null) return home.status();
        SettlementData data = SettlementData.get(server.overworld());
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByHome(home.id());

        Home.Status status;
        int bedCount = 0;
        int interiorAir = 0;
        BlockPos anchor = null;
        Settlement owner = null;
        Set<BlockPos> marked = null;

        if (boxes.isEmpty()) {
            status = Home.Status.UNMARKED;
        } else {
            owner = data.getById(boxes.get(0).settlementId());
            marked = collectMarkedSolids(sl, boxes);
            if (marked.isEmpty()) {
                status = Home.Status.UNMARKED;
            } else if (!isConnected(marked)) {
                status = Home.Status.BROKEN_DISCONNECTED;
            } else if (exceedsSizeLimit(marked, HousingLimits.maxRadius(owner))) {
                // Checked before the enclosure flood so an oversized home never runs the full-bbox scan.
                status = Home.Status.BROKEN_TOO_BIG;
            } else {
                int air = enclosedAirCount(sl, marked); // ≥0 = sealed interior cells, −1 = not enclosed
                if (air < 0) {
                    status = Home.Status.BROKEN_NOT_ENCLOSED;
                } else {
                    interiorAir = air;
                    bedCount = countBedHeadsInBoxes(sl, boxes);
                    anchor = firstBedHead(sl, boxes);
                    status = bedCount > 0 ? Home.Status.VALID : Home.Status.NO_BEDS;
                }
            }
            if (anchor == null) anchor = centroid(marked);
        }

        home.setStatus(status);
        home.setValid(status == Home.Status.VALID);
        home.setBedCount(bedCount);
        home.setCachedInteriorVolume(interiorAir);
        if (anchor != null) home.setPos(anchor);
        java.util.List<UUID> evicted = home.trimToBedCount();
        if (!evicted.isEmpty()) {
            HousingEvictionHook.onEvict(sl, evicted);
        }
        // Always rescore appeal — cheap (one pass over the union) so the panel never shows stale.
        if (owner != null) {
            HouseAppealData.scoreOf(sl, owner, home);
        }

        // Demands → home happiness. Evaluated only for enclosed homes (VALID / NO_BEDS); broken or
        // unmarked homes show no demands and fall back to appeal-only happiness. Reuses the marked
        // set already collected for validation. Demands are SOFT — they never change the status.
        // Space per bed (enclosed air cells / beds) feeds the crowding ceiling; bedless/broken homes
        // have no crowding so they pass NO_CROWDING.
        int spacePerBed = (bedCount > 0 && interiorAir > 0)
            ? interiorAir / bedCount : HomeDemand.NO_CROWDING;
        if (owner != null && marked != null
                && (status == Home.Status.VALID || status == Home.Status.NO_BEDS)) {
            List<HomeDemand.DemandState> demands = HomeDemand.evaluate(sl, owner, marked);
            int met = 0;
            for (HomeDemand.DemandState d : demands) if (d.met()) met++;
            home.setCachedDemands(demands);
            home.setCachedHomeHappiness(
                HomeDemand.computeHappiness(home.cachedBeauty(), met, demands.size() - met, spacePerBed));
        } else {
            home.setCachedDemands(List.of());
            home.setCachedHomeHappiness(
                HomeDemand.computeHappiness(home.cachedBeauty(), 0, 0, spacePerBed));
        }

        data.setDirty();
        return status;
    }

    /** Throttled validation for hot paths (the auto-assignment poll / revalidator): re-runs the
     *  full validation at most every {@code maxAgeTicks}, else returns the cached status. The rod
     *  commit and panel open still validate eagerly. */
    public static Home.Status validateCached(ServerLevel sl, Home home, long maxAgeTicks) {
        long now = sl.getGameTime();
        if (now - home.lastValidatedTick() < maxAgeTicks) {
            return home.status();
        }
        home.setLastValidatedTick(now);
        return validate(sl, home);
    }

    /** Dev diagnostic: the validation breakdown for {@code home} as a one-line string. Printed to
     *  chat when the player opens the home panel, so a "why is this broken?" case can be read off
     *  directly (which check failed + the raw counts) instead of guessed from a screenshot. */
    public static String diagnose(ServerLevel sl, Home home) {
        List<BlockSelection> boxes = BlockSelectionRegistry.get(sl).findByHome(home.id());
        if (boxes.isEmpty()) return "no boxes";
        Set<BlockPos> marked = collectMarkedSolids(sl, boxes);
        boolean connected = isConnected(marked);
        int air = enclosedAirCount(sl, marked); // -1 = leaked / no interior
        int beds = countBedHeadsInBoxes(sl, boxes);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : marked) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        int spanX = marked.isEmpty() ? 0 : maxX - minX + 1;
        int spanY = marked.isEmpty() ? 0 : maxY - minY + 1;
        int spanZ = marked.isEmpty() ? 0 : maxZ - minZ + 1;
        return String.format("status=%s boxes=%d marked=%d connected=%b air=%d beds=%d span=%dx%dx%d",
            home.status(), boxes.size(), marked.size(), connected, air, beds, spanX, spanY, spanZ);
    }

    /** Deliverable container positions inside {@code home} (chests / Antiquity baskets a stocker can
     *  fill) — the home's private "pantry". Used by the stocker home-supply pass to deliver luxuries
     *  and to exclude these chests from settlement drain (so a delivered luxury stays put). */
    public static List<BlockPos> deliverableContainers(ServerLevel sl, Home home) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : collectMarkedSolids(sl, BlockSelectionRegistry.get(sl).findByHome(home.id()))) {
            if (com.bannerbound.core.entity.DropOffContainers.isDropOffBlock(sl, p)) out.add(p);
        }
        return out;
    }

    // ─── Lookup ─────────────────────────────────────────────────────────────────────────────────

    /** A home + its owning settlement, resolved from a block position. */
    public record Hit(Settlement settlement, Home home) {
    }

    /** The home whose box union contains {@code pos}, or null. Walks the selection registry
     *  (kind HOME) then resolves the owner settlement + home record. Mirrors
     *  {@code Workshops.findAt}. */
    @Nullable
    public static Hit findAt(ServerLevel sl, BlockPos pos) {
        MinecraftServer server = sl.getServer();
        if (server == null) return null;
        for (BlockSelection s : BlockSelectionRegistry.get(sl).getAll()) {
            if (s.kind() != BlockSelection.Kind.HOME || !s.contains(pos)) continue;
            Settlement owner = SettlementData.get(server.overworld()).getById(s.settlementId());
            if (owner == null) continue;
            Home h = owner.getHomeById(s.homeId());
            if (h != null) return new Hit(owner, h);
        }
        return null;
    }

    /** Resolves a home by id across all settlements (the rod stores only the id). */
    @Nullable
    public static Hit findById(ServerLevel sl, UUID homeId) {
        if (homeId == null) return null;
        MinecraftServer server = sl.getServer();
        if (server == null) return null;
        for (Settlement s : SettlementData.get(server.overworld()).all()) {
            Home h = s.getHomeById(homeId);
            if (h != null) return new Hit(s, h);
        }
        return null;
    }

    // ─── Shared marked-region geometry (moved off the deleted HouseBlockEntity) ──────────────────

    /** Walks the union of {@code boxes}, deduped, and returns the positions whose world block is
     *  non-air. Shared by home validation and the workshop code. */
    public static Set<BlockPos> collectMarkedSolids(ServerLevel sl, List<BlockSelection> boxes) {
        Set<BlockPos> marked = new HashSet<>();
        Set<BlockPos> seenInBox = new HashSet<>();
        for (BlockSelection box : boxes) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!seenInBox.add(p)) continue;
                        if (!sl.getBlockState(p).isAir()) marked.add(p);
                    }
                }
            }
        }
        return marked;
    }

    /** The 26 neighbour offsets (the 3×3×3 cube minus the centre). Connectivity counts diagonal
     *  adjacency so naturally-built stepped/cornered walls form one building. */
    private static final int[][] CONNECT_OFFSETS_26 = buildConnectOffsets();

    private static int[][] buildConnectOffsets() {
        java.util.List<int[]> offs = new java.util.ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) offs.add(new int[]{dx, dy, dz});
                }
            }
        }
        return offs.toArray(new int[0][]);
    }

    /** True iff every block in {@code marked} is reachable from any one of them through other
     *  marked blocks (26-neighbour adjacency). Shared with the workshop code. */
    public static boolean isConnected(Set<BlockPos> marked) {
        if (marked.size() <= 1) return true;
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = marked.iterator().next();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (int[] o : CONNECT_OFFSETS_26) {
                BlockPos n = p.offset(o[0], o[1], o[2]);
                if (marked.contains(n) && visited.add(n)) queue.add(n);
            }
        }
        return visited.size() == marked.size();
    }

    /** True iff at least one bed HEAD half sits in {@code marked}. The home analog of the
     *  workshop's "contains a work block" rule — the Housing Orders rod needs a bed in the first
     *  committed box before it will create a home. */
    public static boolean containsBedHead(ServerLevel sl, Set<BlockPos> marked) {
        for (BlockPos p : marked) {
            BlockState s = sl.getBlockState(p);
            if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) {
                return true;
            }
        }
        return false;
    }

    /** True if the marked region's bounding box spans more than {@code 2·maxRadius + 1} on any
     *  axis — i.e. the home is bigger than the settlement's era/research allows. */
    private static boolean exceedsSizeLimit(Set<BlockPos> marked, int maxRadius) {
        int maxSpan = 2 * maxRadius + 1;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : marked) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        return (maxX - minX + 1) > maxSpan || (maxY - minY + 1) > maxSpan || (maxZ - minZ + 1) > maxSpan;
    }

    /**
     * Enclosure check + interior volume, done <b>outside-in</b> so it's shape-agnostic. The old
     * "seed any air in the bbox and see if the flood escapes" approach false-failed every
     * non-rectangular house (stepped roof, diagonal/L wall): the bbox of a non-cuboid house
     * contains EXTERIOR dead-zone air, the seed landed there, and the flood "escaped" — reporting a
     * perfectly sealed house as not enclosed.
     *
     * <p>Instead: flood air inward from the boundary of the marked bbox expanded by one cell (that
     * ring is guaranteed outside the walls), blocked by solids and by {@link
     * HouseStructureDetector#isWallOpening wall holes} (so the outside can't sneak in through a
     * window/door). The INTERIOR is any air the outside couldn't reach. The house is enclosed iff
     * that interior is non-empty — true for any wall shape, while a ≥2-wide gap lets the outside in
     * and collapses the interior to zero.
     *
     * @return the number of enclosed interior air cells when sealed, or {@code -1} if not enclosed.
     */
    private static int enclosedAirCount(ServerLevel sl, Set<BlockPos> marked) {
        if (marked.isEmpty()) return -1;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : marked) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        // Expanded bbox: the +1 ring around the house is the seed shell for the exterior flood.
        int eMinX = minX - 1, eMinY = minY - 1, eMinZ = minZ - 1;
        int eMaxX = maxX + 1, eMaxY = maxY + 1, eMaxZ = maxZ + 1;

        Set<BlockPos> exterior = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        // Seed every air cell on the 6 faces of the expanded box.
        for (int x = eMinX; x <= eMaxX; x++) {
            for (int y = eMinY; y <= eMaxY; y++) {
                for (int z = eMinZ; z <= eMaxZ; z++) {
                    if (x != eMinX && x != eMaxX && y != eMinY && y != eMaxY && z != eMinZ && z != eMaxZ) {
                        continue; // interior of the expanded box — not a boundary cell
                    }
                    BlockPos p = new BlockPos(x, y, z);
                    if (sl.getBlockState(p).isAir() && exterior.add(p)) queue.add(p);
                }
            }
        }
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos n = p.relative(dir);
                if (n.getX() < eMinX || n.getX() > eMaxX
                    || n.getY() < eMinY || n.getY() > eMaxY
                    || n.getZ() < eMinZ || n.getZ() > eMaxZ) continue;
                if (exterior.contains(n)) continue;
                if (!sl.getBlockState(n).isAir()) continue;            // solids block the outside
                if (HouseStructureDetector.isWallOpening(sl, n)) continue; // window/door = wall, no entry
                exterior.add(n);
                queue.add(n);
            }
        }

        // Interior = air in the ORIGINAL bbox the outside couldn't reach (and not itself a wall hole,
        // so a lone windowed wall with no room doesn't read as a 1-cell "interior").
        int interior = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!sl.getBlockState(p).isAir()) continue;
                    if (exterior.contains(p)) continue;
                    if (HouseStructureDetector.isWallOpening(sl, p)) continue;
                    interior++;
                }
            }
        }
        return interior > 0 ? interior : -1;
    }

    /** Counts bed HEAD halves across the union of {@code boxes}, deduped by position. */
    private static int countBedHeadsInBoxes(ServerLevel sl, List<BlockSelection> boxes) {
        Set<BlockPos> seen = new HashSet<>();
        int n = 0;
        for (BlockSelection box : boxes) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!seen.add(p)) continue;
                        BlockState s = sl.getBlockState(p);
                        if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) {
                            n++;
                        }
                    }
                }
            }
        }
        return n;
    }

    /** The first bed HEAD position in the union of {@code boxes} (deduped scan order), or null. */
    @Nullable
    private static BlockPos firstBedHead(ServerLevel sl, List<BlockSelection> boxes) {
        for (BlockSelection box : boxes) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState s = sl.getBlockState(p);
                        if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) {
                            return p.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Centroid of the marked-solids bbox — the fallback home anchor when there's no bed yet. */
    private static BlockPos centroid(Set<BlockPos> marked) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : marked) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        return new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
    }
}
