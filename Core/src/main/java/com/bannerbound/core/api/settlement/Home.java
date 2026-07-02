package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * A registered residence — a House Block plus the citizens who sleep there. Parallel to
 * {@link Workstation}: same per-settlement {@code Map<Long, Home>} storage shape, same
 * per-pos validity flag, same NBT round-trip pattern. Kept as a distinct type (instead of
 * a {@code Workstation} typed as "house") because residence and employment are orthogonal
 * gameplay concepts that should evolve independently.
 *
 * <p><b>Selection set lives elsewhere.</b> A home's appeal-region boxes are stored in
 * {@link com.bannerbound.core.api.world.BlockSelectionRegistry} (HOME-kind selections with
 * matching {@link #id()}), not on this record. Keeping the registry as the single source of
 * truth avoids the drift trap that a duplicated list would invite.
 *
 * <p><b>Bed count is the resident cap.</b> Recomputed on every successful validation from
 * the freshly flood-filled interior — count of {@code BedBlock} HEAD halves. Persisted only
 * as a snapshot so a save mid-day reloads with the same count.
 */
public final class Home {
    /**
     * Lifecycle/validation state of the home, mirroring {@link Workshop.Status}. A home has no
     * anchor block now (it is defined purely by the Housing Orders rod's HOME selections), so
     * validation runs from {@link Homes#validate} on commit, on panel open, and from the
     * background {@code HomeRevalidator} sweep. Persisted by ordinal — append, never reorder.
     */
    public enum Status {
        /** No HOME selections drawn for this home (or all removed / only air captured). */
        UNMARKED,
        /** Marked solids form more than one connected cluster (multiple buildings marked as one). */
        BROKEN_DISCONNECTED,
        /** Marked region exists but the interior isn't sealed (missing wall or roof). */
        BROKEN_NOT_ENCLOSED,
        /** Enclosed, but contains zero bed HEAD halves — no one can move in. */
        NO_BEDS,
        /** Enclosed AND has at least one bed. Fully usable. */
        VALID,
        /** Buildable, but the marked region is larger than this era/research allows. */
        BROKEN_TOO_BIG;

        public static Status fromOrdinalOrDefault(int ord) {
            Status[] v = values();
            return (ord >= 0 && ord < v.length) ? v[ord] : UNMARKED;
        }
    }

    private final UUID id;
    /** Representative position of the home — a contained bed HEAD when one exists, else the
     *  centroid of the marked region. Recomputed on each validation; used for nearest-home
     *  auto-assignment and the resident picker's distance readout. No longer a House Block pos. */
    private BlockPos pos;
    /** Insertion-ordered list of residents — used for least-recently-assigned eviction when the
     *  bed count drops below the resident count. */
    private final List<UUID> residents;
    private int bedCount;
    /** Last validation result (walls + roof). False also signals "no beds" — citizens won't
     *  auto-assign here while invalid, and existing residents get evicted on a {@code valid→invalid}
     *  flip in {@link Homes#validate}. */
    private boolean valid;
    /** Cached score from the most recent {@code HouseAppealData.scoreOf(home)} call. -1 sentinel
     *  marks "never scored." */
    private double cachedScore;
    /** Cached beauty tier matching {@link #cachedScore}. Null until first score. */
    private ChunkBeauty cachedBeauty;
    /** Game-tick of the last score recomputation. Used by the periodic rescan to skip homes
     *  that were just scored. */
    private long lastScoredTick;
    /** Last validation result. Mirrors {@link Workshop#status()}. */
    private Status status = Status.UNMARKED;
    /** Game tick of the last full validation (transient — {@code Homes.validateCached} throttles
     *  the enclosure flood-fill on this, same as {@code Workshop.lastValidatedTick}). */
    private transient long lastValidatedTick = Long.MIN_VALUE;

    public Home(UUID id, BlockPos pos) {
        this.id = id;
        this.pos = pos;
        this.residents = new ArrayList<>();
        this.bedCount = 0;
        this.valid = false;
        this.cachedScore = 0.0;
        this.cachedBeauty = null;
        this.lastScoredTick = -1L;
    }

    /** Creates a home with a placeholder anchor (origin) — {@link Homes#validate} sets the real
     *  anchor from the marked region. Mirrors {@code new Workshop(id)}; used by the Housing Orders
     *  rod when the first box creates the home. */
    public Home(UUID id) {
        this(id, BlockPos.ZERO);
    }

    public UUID id() { return id; }
    public BlockPos pos() { return pos; }
    public void setPos(BlockPos p) { if (p != null) this.pos = p.immutable(); }
    public Status status() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public long lastValidatedTick() { return lastValidatedTick; }
    public void setLastValidatedTick(long tick) { this.lastValidatedTick = tick; }
    public List<UUID> residents() { return residents; }
    public int bedCount() { return bedCount; }
    public boolean valid() { return valid; }
    public double cachedScore() { return cachedScore; }
    public ChunkBeauty cachedBeauty() { return cachedBeauty; }
    public long lastScoredTick() { return lastScoredTick; }

    /** Enclosed interior air volume (cells) from the last validation — the raw input to the
     *  crowdedness readout (space per bed). Transient: recomputed every validation, kept fresh by
     *  the {@code HomeRevalidator} sweep, so the status panel always reads a current value. */
    private transient int cachedInteriorVolume;
    public int cachedInteriorVolume() { return cachedInteriorVolume; }
    public void setCachedInteriorVolume(int v) { this.cachedInteriorVolume = Math.max(0, v); }

    /** Home happiness (0–100): appeal combined with demand satisfaction
     *  ({@link HomeDemand#computeHappiness}). Drives the resident mood thought AND the nightly
     *  reproduction chance. Transient — recomputed every validation, kept fresh by the revalidator. */
    private transient double cachedHomeHappiness = 50.0;
    public double cachedHomeHappiness() { return cachedHomeHappiness; }
    public void setCachedHomeHappiness(double v) { this.cachedHomeHappiness = Math.max(0.0, Math.min(100.0, v)); }

    /** Per-demand met/unmet snapshot from the last validation, for the status panel. Transient. */
    private transient List<HomeDemand.DemandState> cachedDemands = List.of();
    public List<HomeDemand.DemandState> cachedDemands() { return cachedDemands; }
    public void setCachedDemands(List<HomeDemand.DemandState> demands) {
        this.cachedDemands = demands == null ? List.of() : demands;
    }

    /** Hash of the (styles, palettes) the cached score was computed under — transient. The
     *  periodic home rescore in {@code ChunkBeautyManager.tickAll} compares it so a global
     *  culture style/palette change refreshes home scores promptly (they used to stay stale
     *  until the next block edit inside the home — the audited cache-invalidation bug). */
    private transient int lastScoredStyleHash;
    public int lastScoredStyleHash() { return lastScoredStyleHash; }
    public void setLastScoredStyleHash(int hash) { this.lastScoredStyleHash = hash; }

    public void setBedCount(int v) { this.bedCount = Math.max(0, v); }
    public void setValid(boolean v) { this.valid = v; }

    public void setCachedScore(double score, ChunkBeauty beauty, long now) {
        this.cachedScore = score;
        this.cachedBeauty = beauty;
        this.lastScoredTick = now;
    }

    /** True iff the home has room for at least one more resident (valid + has beds + under cap). */
    public boolean hasVacancy() {
        return valid && bedCount > 0 && residents.size() < bedCount;
    }

    /** Adds {@code citizenId} as a resident if there's room. Returns true on add, false if the
     *  home is full or the citizen is already a resident. */
    public boolean addResident(UUID citizenId) {
        if (citizenId == null) return false;
        if (residents.contains(citizenId)) return false;
        if (residents.size() >= bedCount) return false;
        residents.add(citizenId);
        return true;
    }

    /** Drops {@code citizenId} if present. Returns true iff anything changed. */
    public boolean removeResident(UUID citizenId) {
        return citizenId != null && residents.remove(citizenId);
    }

    /** Evicts residents from the end of the list (least-recently-assigned) until
     *  {@code residents.size() <= bedCount}. Returns the evicted UUIDs so the caller can
     *  notify the affected citizens (clear their {@code *_HOME} thoughts, etc.). */
    public List<UUID> trimToBedCount() {
        List<UUID> evicted = new ArrayList<>();
        while (residents.size() > bedCount && !residents.isEmpty()) {
            evicted.add(residents.remove(residents.size() - 1));
        }
        return evicted;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        tag.putInt("BedCount", bedCount);
        tag.putBoolean("Valid", valid);
        tag.putInt("Status", status.ordinal());
        // Score cache is persisted so post-load happiness reads don't need a full recompute
        // before the next scheduled rescan fires.
        tag.putDouble("Score", cachedScore);
        if (cachedBeauty != null) tag.putInt("Beauty", cachedBeauty.ordinal());
        tag.putLong("LastScoredTick", lastScoredTick);
        if (!residents.isEmpty()) {
            ListTag list = new ListTag();
            for (UUID r : residents) list.add(NbtUtils.createUUID(r));
            tag.put("Residents", list);
        }
        return tag;
    }

    public static Home load(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        BlockPos pos = new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
        Home h = new Home(id, pos);
        if (tag.contains("BedCount")) h.bedCount = tag.getInt("BedCount");
        if (tag.contains("Valid")) h.valid = tag.getBoolean("Valid");
        if (tag.contains("Status")) h.status = Status.fromOrdinalOrDefault(tag.getInt("Status"));
        if (tag.contains("Score")) h.cachedScore = tag.getDouble("Score");
        if (tag.contains("Beauty")) {
            int ord = tag.getInt("Beauty");
            ChunkBeauty[] v = ChunkBeauty.values();
            if (ord >= 0 && ord < v.length) h.cachedBeauty = v[ord];
        }
        if (tag.contains("LastScoredTick")) h.lastScoredTick = tag.getLong("LastScoredTick");
        if (tag.contains("Residents")) {
            ListTag list = tag.getList("Residents", Tag.TAG_INT_ARRAY);
            for (int i = 0; i < list.size(); i++) {
                h.residents.add(NbtUtils.loadUUID(list.get(i)));
            }
        }
        return h;
    }
}
