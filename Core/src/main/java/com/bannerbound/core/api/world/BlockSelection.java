package com.bannerbound.core.api.world;

import com.bannerbound.core.api.settlement.SettlementColor;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * A rectangular block region claimed by some rod for some purpose. Two flavours:
 * <ul>
 *   <li>{@link Kind#WORKSTATION} — committed by the Foreman's Rod. Carries a non-empty
 *       {@link #workstationType()} (e.g. {@code "digger"}). Overlap is forbidden across all
 *       selections.</li>
 *   <li>{@link Kind#HOME} — committed by the Home Marker Rod. Carries a non-zero
 *       {@link #homeId()}. Overlap is allowed between two HOME selections sharing the same
 *       {@code homeId} (the rod's "Super Glue" union-of-boxes behaviour); forbidden against
 *       all other selections.</li>
 * </ul>
 * Lives in {@link BlockSelectionRegistry} on the server; mirrored to clients for rendering.
 *
 * @param rodId           per-selection id (the registry's map key; not actually a rod id —
 *                        legacy field name, kept to avoid an NBT migration)
 * @param settlementId    settlement that owns this selection
 * @param colorIndex      {@link SettlementColor#ordinal()} — used by the client renderer
 * @param a               first corner
 * @param b               second corner
 * @param kind            see class docs
 * @param workstationType type id for {@code WORKSTATION} kind; {@code ""} for {@code HOME}
 * @param homeId          home id for {@code HOME} kind; {@link #NO_HOME} for {@code WORKSTATION}
 * @param completed       true after the assigned workstation finished its job (workstation only)
 * @param creatorId       UUID of the player who committed point B (or {@link #NO_CREATOR})
 * @param seedItemId      seed item id for farmer selections (workstation only); {@code ""} else
 * @param assignedCitizenId for workstation selections, the single citizen this job is bound to, or
 *                        {@link #NO_CITIZEN} for "any worker of this type" (e.g. all diggers)
 */
public record BlockSelection(UUID rodId, UUID settlementId, int colorIndex,
                              BlockPos a, BlockPos b,
                              Kind kind,
                              String workstationType,
                              UUID homeId,
                              BlockPos homePos,
                              boolean completed,
                              UUID creatorId, String seedItemId,
                              UUID assignedCitizenId) {

    /** Sentinel for "no creator" — legacy selections from pre-creator-field saves. */
    public static final UUID NO_CREATOR = new UUID(0L, 0L);
    /** Sentinel for "not bound to a specific citizen" — the job is open to all workers of its type. */
    public static final UUID NO_CITIZEN = new UUID(0L, 0L);
    /** Sentinel home id for {@link Kind#WORKSTATION} selections (they have no home). */
    public static final UUID NO_HOME = new UUID(0L, 0L);
    /** Sentinel home pos for {@link Kind#WORKSTATION} selections and pre-housing-v2 loads. */
    public static final BlockPos NO_HOME_POS = BlockPos.ZERO;

    /** Discriminator between Foreman's Rod (WORKSTATION), Home Marker Rod (HOME) and Workshop
     *  Orders rod (WORKSHOP) selections. Drives overlap rules in {@link BlockSelectionRegistry}
     *  and the render path in {@code SelectionRenderer}. WORKSHOP reuses the HOME slots:
     *  {@link #homeId()} holds the workshop id (the union-of-boxes key) and {@code homePos} stays
     *  at the sentinel (workshops have no anchor block — the rod binds by id). */
    public enum Kind {
        WORKSTATION,
        HOME,
        WORKSHOP;

        public static Kind fromOrdinalOrDefault(int ord) {
            Kind[] v = values();
            return (ord >= 0 && ord < v.length) ? v[ord] : WORKSTATION;
        }
    }

    /** Factory for a Foreman's Rod commit. Workstation-type required; home fields default
     *  to the sentinels so the caller never sees the HOME slots. */
    public static BlockSelection workstation(UUID rodId, UUID settlementId, int colorIndex,
                                              BlockPos a, BlockPos b, String workstationType,
                                              UUID creatorId, String seedItemId) {
        return new BlockSelection(rodId, settlementId, colorIndex, a, b,
            Kind.WORKSTATION, workstationType, NO_HOME, NO_HOME_POS, false, creatorId, seedItemId,
            NO_CITIZEN);
    }

    /** Factory for a Home Marker Rod commit. Home-id + home-pos both required. The pos is what
     *  the client uses to gate selection rendering on the player's rod binding (the rod stores
     *  {@code BOUND_HOME_POS}, which is matched against this field to decide whether to draw
     *  this home's selections). Multiple HOME selections with the same {@code homeId} compose
     *  into the home's appeal-region union — see {@link BlockSelectionRegistry}. */
    public static BlockSelection home(UUID selectionId, UUID settlementId, int colorIndex,
                                       BlockPos a, BlockPos b, UUID homeId, BlockPos homePos,
                                       UUID creatorId) {
        return new BlockSelection(selectionId, settlementId, colorIndex, a, b,
            Kind.HOME, "", homeId, homePos, false, creatorId, "", NO_CITIZEN);
    }

    /** Factory for a Workshop Orders rod commit. The workshop id rides in the {@code homeId} slot
     *  (it plays the identical union-of-boxes role); {@code homePos} stays at the sentinel because
     *  workshops have no anchor block — the rod binds to the workshop id directly. */
    public static BlockSelection workshop(UUID selectionId, UUID settlementId, int colorIndex,
                                           BlockPos a, BlockPos b, UUID workshopId,
                                           UUID creatorId) {
        return new BlockSelection(selectionId, settlementId, colorIndex, a, b,
            Kind.WORKSHOP, "", workshopId, NO_HOME_POS, false, creatorId, "", NO_CITIZEN);
    }

    /** Bounding-box width (a ↔ b inclusive). */
    public int sizeX() { return Math.abs(b.getX() - a.getX()) + 1; }
    public int sizeY() { return Math.abs(b.getY() - a.getY()) + 1; }
    public int sizeZ() { return Math.abs(b.getZ() - a.getZ()) + 1; }
    public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

    /** Block-volume of an A↔B pair without constructing a BlockSelection. */
    public static long volumeOf(BlockPos a, BlockPos b) {
        long sx = Math.abs(b.getX() - a.getX()) + 1L;
        long sy = Math.abs(b.getY() - a.getY()) + 1L;
        long sz = Math.abs(b.getZ() - a.getZ()) + 1L;
        return sx * sy * sz;
    }

    public int minX() { return Math.min(a.getX(), b.getX()); }
    public int minY() { return Math.min(a.getY(), b.getY()); }
    public int minZ() { return Math.min(a.getZ(), b.getZ()); }
    public int maxX() { return Math.max(a.getX(), b.getX()); }
    public int maxY() { return Math.max(a.getY(), b.getY()); }
    public int maxZ() { return Math.max(a.getZ(), b.getZ()); }

    public boolean contains(BlockPos p) {
        return p.getX() >= minX() && p.getX() <= maxX()
            && p.getY() >= minY() && p.getY() <= maxY()
            && p.getZ() >= minZ() && p.getZ() <= maxZ();
    }

    /** AABB intersection of two selections (any single shared block counts). */
    public boolean intersects(BlockSelection other) {
        return this.minX() <= other.maxX() && this.maxX() >= other.minX()
            && this.minY() <= other.maxY() && this.maxY() >= other.minY()
            && this.minZ() <= other.maxZ() && this.maxZ() >= other.minZ();
    }

    /** True iff this and {@code other} are both {@link Kind#HOME} selections owned by the same
     *  {@link #homeId()}. The "same-home overlap is allowed" predicate for the registry. */
    public boolean sameHomeAs(BlockSelection other) {
        return this.kind == Kind.HOME && other.kind == Kind.HOME
            && !NO_HOME.equals(this.homeId) && this.homeId.equals(other.homeId);
    }

    /** True iff this and {@code other} are both {@link Kind#WORKSHOP} selections owned by the same
     *  workshop (id in the {@code homeId} slot). The workshop twin of {@link #sameHomeAs}. */
    public boolean sameWorkshopAs(BlockSelection other) {
        return this.kind == Kind.WORKSHOP && other.kind == Kind.WORKSHOP
            && !NO_HOME.equals(this.homeId) && this.homeId.equals(other.homeId);
    }

    /** Replaces {@link #completed} with the given value. Other fields unchanged. */
    public BlockSelection withCompleted(boolean newCompleted) {
        return new BlockSelection(rodId, settlementId, colorIndex, a, b,
            kind, workstationType, homeId, homePos, newCompleted, creatorId, seedItemId,
            assignedCitizenId);
    }

    /** Returns a copy with the seed assignment replaced. Used by the seed-picker handler. */
    public BlockSelection withSeed(String newSeedItemId) {
        return new BlockSelection(rodId, settlementId, colorIndex, a, b,
            kind, workstationType, homeId, homePos, completed, creatorId,
            newSeedItemId == null ? "" : newSeedItemId, assignedCitizenId);
    }

    /** Returns a copy with the bounding-box corners replaced (everything else unchanged). Used by
     *  the Foreman's Rod "expand field" flow to grow an existing field to the union of its old box
     *  and a newly drawn one. */
    public BlockSelection withBounds(BlockPos newA, BlockPos newB) {
        return new BlockSelection(rodId, settlementId, colorIndex, newA, newB,
            kind, workstationType, homeId, homePos, completed, creatorId, seedItemId,
            assignedCitizenId);
    }

    /** Returns a copy bound to a specific citizen, or {@link #NO_CITIZEN} for "all workers". */
    public BlockSelection withAssignedCitizen(UUID citizenId) {
        return new BlockSelection(rodId, settlementId, colorIndex, a, b,
            kind, workstationType, homeId, homePos, completed, creatorId, seedItemId,
            citizenId == null ? NO_CITIZEN : citizenId);
    }

    /** True if this selection is open to any worker of its type (not bound to one citizen). */
    public boolean targetsAllWorkers() { return NO_CITIZEN.equals(assignedCitizenId); }

    /** True if {@code citizenId} may work this selection (it's open to all, or bound to them). */
    public boolean targetsCitizen(UUID citizenId) {
        return targetsAllWorkers() || assignedCitizenId.equals(citizenId);
    }

    // ─── NBT serialization ─────────────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("RodId", rodId);
        tag.putUUID("SettlementId", settlementId);
        tag.putInt("Color", colorIndex);
        tag.putInt("Ax", a.getX()); tag.putInt("Ay", a.getY()); tag.putInt("Az", a.getZ());
        tag.putInt("Bx", b.getX()); tag.putInt("By", b.getY()); tag.putInt("Bz", b.getZ());
        tag.putString("Type", workstationType);
        tag.putBoolean("Done", completed);
        if (!NO_CREATOR.equals(creatorId)) tag.putUUID("Creator", creatorId);
        if (!seedItemId.isEmpty()) tag.putString("Seed", seedItemId);
        if (!NO_CITIZEN.equals(assignedCitizenId)) tag.putUUID("Citizen", assignedCitizenId);
        // New v2 fields. Kind defaults to WORKSTATION on load so old saves stay valid.
        if (kind != Kind.WORKSTATION) tag.putInt("Kind", kind.ordinal());
        if (!NO_HOME.equals(homeId)) tag.putUUID("Home", homeId);
        if (!NO_HOME_POS.equals(homePos)) {
            tag.putInt("HpX", homePos.getX());
            tag.putInt("HpY", homePos.getY());
            tag.putInt("HpZ", homePos.getZ());
        }
        return tag;
    }

    public static BlockSelection load(CompoundTag tag) {
        UUID rodId = tag.getUUID("RodId");
        UUID settlementId = tag.getUUID("SettlementId");
        int color = tag.getInt("Color");
        BlockPos a = new BlockPos(tag.getInt("Ax"), tag.getInt("Ay"), tag.getInt("Az"));
        BlockPos b = new BlockPos(tag.getInt("Bx"), tag.getInt("By"), tag.getInt("Bz"));
        String type = tag.getString("Type");
        boolean done = tag.getBoolean("Done");
        UUID creator = tag.contains("Creator") ? tag.getUUID("Creator") : NO_CREATOR;
        String seed = tag.contains("Seed") ? tag.getString("Seed") : "";
        Kind kind = tag.contains("Kind") ? Kind.fromOrdinalOrDefault(tag.getInt("Kind")) : Kind.WORKSTATION;
        UUID home = tag.contains("Home") ? tag.getUUID("Home") : NO_HOME;
        BlockPos homePos = tag.contains("HpX")
            ? new BlockPos(tag.getInt("HpX"), tag.getInt("HpY"), tag.getInt("HpZ"))
            : NO_HOME_POS;
        UUID citizen = tag.contains("Citizen") ? tag.getUUID("Citizen") : NO_CITIZEN;
        return new BlockSelection(rodId, settlementId, color, a, b, kind, type, home, homePos, done,
            creator, seed, citizen);
    }
}
