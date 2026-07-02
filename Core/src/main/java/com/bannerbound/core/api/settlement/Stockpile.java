package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * A registered community-storage building — a Stockpile Block plus the container blocks enclosed
 * with it inside a fence/wall + roof. Parallel to {@link Home}: same per-settlement
 * {@code Map<Long, Stockpile>} storage shape, same per-pos validity flag, same NBT round-trip.
 *
 * <p><b>Auto-scanned, not rod-marked.</b> Unlike a home (whose region is drawn with the Selection
 * Orders tool), a stockpile's enclosure is flooded outward from the block to the surrounding fence
 * ring by {@code StockpileEnclosure.scan(...)}. Validation writes the result here: {@link #valid},
 * the enclosed {@link #containers} (capped at {@link #MAX_CONTAINERS}), and a {@link #status} the
 * right-click terminal surfaces. The Selection Orders override (refine an imperfect auto-scan) is
 * layered on later, the same way the House Block has both Detect and rod marking.
 *
 * <p>The container count is the cap, mirroring a home's bed count. The enclosed inventories are the
 * settlement's town storage — see {@code StockpileService}, which resolves these positions to live
 * {@code IItemHandler}s on demand.
 */
public final class Stockpile {
    /** Max container blocks aggregated into one stockpile. Single source of truth (mirrors a
     *  home's bed cap); {@code StockpileEnclosure.MAX_STORAGE} reads this. */
    public static final int MAX_CONTAINERS = 8;

    /** Lifecycle/validity state, surfaced in the terminal header. Appended-only so saved ordinals
     *  never shift. */
    public enum Status {
        UNMARKED,        // placed in unclaimed territory, or no enclosure scanned yet
        NOT_ENCLOSED,    // a solid block breaks the fence ring
        NO_GATE,         // no fence gate in the perimeter (citizens couldn't walk in)
        NO_ROOF,         // an interior tile is open to the sky
        TOO_LARGE,       // enclosure escaped / exceeds the span cap
        NO_CONTAINERS,   // valid shell, but no storage blocks inside
        VALID,
        TOO_MANY;        // more than MAX_CONTAINERS containers inside (only the first 8 are served)

        public static Status fromOrdinalOrDefault(int ord) {
            Status[] v = values();
            return (ord >= 0 && ord < v.length) ? v[ord] : UNMARKED;
        }
    }

    private final UUID id;
    private final BlockPos pos;
    private boolean valid;
    private Status status;
    /** The enclosed container block positions, capped at {@link #MAX_CONTAINERS}. Positions are the
     *  persisted source of truth; live inventories are resolved on demand by {@code StockpileService}
     *  and the worker drop-off bridge. */
    private final List<BlockPos> containers;
    /** Per-stockpile worker access toggles (default open). The settlement storage pool reads these:
     *  a worker may DEPOSIT its yield into this stockpile only when {@link #allowWorkerDeposit}, and
     *  TAKE inputs/seeds/tools from it only when {@link #allowWorkerTake}. Set from the rack terminal;
     *  governs autonomous workers only (the player can always hand-move via the screen). */
    private boolean allowWorkerDeposit = true;
    private boolean allowWorkerTake = true;
    /** Whether this stockpile's contents are offered in settlement-to-settlement trade (the pool a
     *  trade partner sees and deals draw from / deliver into). Default CLOSED — trading is opt-in
     *  per stockpile so the town's whole store is never exposed by accident. */
    private boolean showForTrading = false;

    public Stockpile(UUID id, BlockPos pos) {
        this.id = id;
        this.pos = pos;
        this.valid = false;
        this.status = Status.UNMARKED;
        this.containers = new ArrayList<>();
    }

    public UUID id() { return id; }
    public BlockPos pos() { return pos; }
    public boolean valid() { return valid; }
    public Status status() { return status; }
    public List<BlockPos> containers() { return containers; }
    public int containerCount() { return containers.size(); }
    public boolean allowWorkerDeposit() { return allowWorkerDeposit; }
    public boolean allowWorkerTake() { return allowWorkerTake; }
    public boolean showForTrading() { return showForTrading; }

    public void setValid(boolean v) { this.valid = v; }
    public void setStatus(Status s) { this.status = s; }
    public void setAllowWorkerDeposit(boolean v) { this.allowWorkerDeposit = v; }
    public void setAllowWorkerTake(boolean v) { this.allowWorkerTake = v; }
    public void setShowForTrading(boolean v) { this.showForTrading = v; }

    /** Replaces the enclosed-container list with {@code next} (already capped by the scanner). */
    public void setContainers(List<BlockPos> next) {
        containers.clear();
        for (BlockPos p : next) containers.add(p.immutable());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        tag.putBoolean("Valid", valid);
        tag.putInt("Status", status.ordinal());
        tag.putBoolean("AllowDeposit", allowWorkerDeposit);
        tag.putBoolean("AllowTake", allowWorkerTake);
        tag.putBoolean("ShowTrade", showForTrading);
        if (!containers.isEmpty()) {
            ListTag list = new ListTag();
            for (BlockPos p : containers) {
                CompoundTag c = new CompoundTag();
                c.putInt("X", p.getX());
                c.putInt("Y", p.getY());
                c.putInt("Z", p.getZ());
                list.add(c);
            }
            tag.put("Containers", list);
        }
        return tag;
    }

    public static Stockpile load(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        BlockPos pos = new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
        Stockpile s = new Stockpile(id, pos);
        if (tag.contains("Valid")) s.valid = tag.getBoolean("Valid");
        if (tag.contains("Status")) s.status = Status.fromOrdinalOrDefault(tag.getInt("Status"));
        // Default OPEN when absent so existing stockpiles keep serving workers after the upgrade.
        s.allowWorkerDeposit = !tag.contains("AllowDeposit") || tag.getBoolean("AllowDeposit");
        s.allowWorkerTake = !tag.contains("AllowTake") || tag.getBoolean("AllowTake");
        s.showForTrading = tag.getBoolean("ShowTrade"); // default CLOSED on old saves
        if (tag.contains("Containers")) {
            ListTag list = tag.getList("Containers", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.getCompound(i);
                s.containers.add(new BlockPos(c.getInt("X"), c.getInt("Y"), c.getInt("Z")));
            }
        }
        return s;
    }
}
