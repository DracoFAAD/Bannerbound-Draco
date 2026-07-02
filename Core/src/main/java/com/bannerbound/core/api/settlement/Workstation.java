package com.bannerbound.core.api.settlement;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * A workstation registered on a settlement: a placed workstation block (Forester's Log etc.) plus
 * the citizen assigned to work it. Inventory lives on the block entity — this record is the
 * "assignment" half so we can enumerate workstations for the assignment-picker GUIs even when
 * their chunks aren't loaded.
 */
public final class Workstation {
    private final BlockPos pos;
    private final String type;
    private UUID assignedCitizenId;
    /** Cached BuildingValidator result. Re-evaluated periodically by ImmigrationManager. */
    private boolean buildingValid;
    /** Player-controlled toggle. When false, all gatherer goals using this workstation yield —
     *  the assigned worker behaves as if unemployed (patrols + regens stamina) without losing
     *  the assignment. Lets the player pause a station without unassigning + reassigning. */
    private boolean active;
    /** Farmer-only: when true, the assigned farmer applies bone meal to immature crops inside
     *  its selections (sourced from the granary or a settlement stockpile). Opt-in, and only
     *  honoured when the settlement has researched Fertilization — the work goal re-checks the
     *  research flag, and the network handler refuses to set it true without the research. */
    private boolean useFertilizer;

    public Workstation(BlockPos pos, String type, UUID assignedCitizenId) {
        this.pos = pos;
        this.type = type;
        this.assignedCitizenId = assignedCitizenId;
        this.buildingValid = true;
        this.active = true;
        this.useFertilizer = false;
    }

    public BlockPos pos() { return pos; }
    public String type() { return type; }
    public UUID assignedCitizenId() { return assignedCitizenId; }
    public void setAssignedCitizenId(UUID id) { this.assignedCitizenId = id; }
    public boolean hasWorker() { return assignedCitizenId != null; }

    public boolean buildingValid() { return buildingValid; }
    public void setBuildingValid(boolean v) { this.buildingValid = v; }

    public boolean active() { return active; }
    public void setActive(boolean v) { this.active = v; }

    public boolean useFertilizer() { return useFertilizer; }
    public void setUseFertilizer(boolean v) { this.useFertilizer = v; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        tag.putString("Type", type);
        if (assignedCitizenId != null) {
            tag.putUUID("AssignedCitizen", assignedCitizenId);
        }
        tag.putBoolean("BuildingValid", buildingValid);
        tag.putBoolean("Active", active);
        tag.putBoolean("UseFertilizer", useFertilizer);
        return tag;
    }

    public static Workstation load(CompoundTag tag) {
        BlockPos pos = new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
        String type = tag.contains("Type") ? tag.getString("Type") : "unknown";
        UUID assigned = tag.hasUUID("AssignedCitizen") ? tag.getUUID("AssignedCitizen") : null;
        Workstation ws = new Workstation(pos, type, assigned);
        if (tag.contains("BuildingValid")) {
            ws.setBuildingValid(tag.getBoolean("BuildingValid"));
        }
        // Older saves default to active. The toggle is opt-out, not opt-in.
        ws.setActive(!tag.contains("Active") || tag.getBoolean("Active"));
        // Fertilizing is opt-in; absent key (older saves, non-farmer stations) defaults to off.
        ws.setUseFertilizer(tag.getBoolean("UseFertilizer"));
        return ws;
    }
}
