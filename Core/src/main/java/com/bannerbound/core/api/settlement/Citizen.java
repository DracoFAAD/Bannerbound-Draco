package com.bannerbound.core.api.settlement;

import com.bannerbound.core.entity.CitizenEntity;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

/**
 * A single citizen on a settlement's roster. The {@link #entityId} identifies the in-world
 * {@link com.bannerbound.core.entity.CitizenEntity} that represents this citizen — kept here
 * so the settlement can persist its population independent of whether the entity's chunk is
 * currently loaded.
 */
public record Citizen(UUID entityId, String name) {

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("EntityId", entityId);
        tag.putString("Name", name);
        return tag;
    }

    public static Citizen load(CompoundTag tag) {
        UUID id = tag.hasUUID("EntityId") ? tag.getUUID("EntityId") : UUID.randomUUID();
        String name = tag.contains("Name") ? tag.getString("Name") : "Citizen";
        return new Citizen(id, name);
    }
}
