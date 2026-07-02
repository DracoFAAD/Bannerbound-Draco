package com.bannerbound.core.api.faith;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-level faith storage (FAITH_PLAN.md Part 2) — the cross-faction layer, attached
 * to the overworld like {@link com.bannerbound.core.api.settlement.SettlementData}.
 * <p>
 * Holds the SKY SEED (the one random long every client's sky is generated from — see
 * {@link com.bannerbound.core.celestial.SkyField}; deliberately NOT the world seed) and
 * the registry of all founded {@link Faith}s. Settlements reference faiths by UUID
 * ({@code Settlement.faithId}); membership lives on BOTH sides for cheap lookup either way.
 */
public class FaithData extends SavedData {
    private static final String DATA_NAME = "bannerbound_faiths";

    private long skySeed;
    private boolean hasSkySeed;
    private final Map<UUID, Faith> faiths = new HashMap<>();

    public FaithData() {
    }

    // ── Faith registry ───────────────────────────────────────────────────────────

    public Faith createFaith(String name, FaithPath path, UUID founderSettlement) {
        Faith faith = new Faith(UUID.randomUUID(), name, path, founderSettlement);
        faith.addMember(founderSettlement);
        faiths.put(faith.id(), faith);
        setDirty();
        return faith;
    }

    @Nullable
    public Faith byId(@Nullable UUID id) {
        return id == null ? null : faiths.get(id);
    }

    public Collection<Faith> all() {
        return Collections.unmodifiableCollection(faiths.values());
    }

    /** Removes a faith with no members left (the gods fade when no one believes). */
    public void removeIfEmpty(UUID faithId) {
        Faith faith = faiths.get(faithId);
        if (faith != null && faith.memberSettlements().isEmpty()) {
            faiths.remove(faithId);
            setDirty();
        }
    }

    public static FaithData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<FaithData> factory() {
        return new Factory<>(FaithData::new, FaithData::load);
    }

    /** The world's sky seed, rolled once on first access and persisted forever after. */
    public long skySeed() {
        if (!hasSkySeed) {
            skySeed = new java.util.Random().nextLong();
            hasSkySeed = true;
            setDirty();
        }
        return skySeed;
    }

    /** Debug only (/bannerbound sky reroll): a renamed planet must never survive this. */
    public long rerollSkySeed() {
        skySeed = new java.util.Random().nextLong();
        hasSkySeed = true;
        setDirty();
        return skySeed;
    }

    public static FaithData load(CompoundTag tag, HolderLookup.Provider provider) {
        FaithData data = new FaithData();
        if (tag.contains("SkySeed")) {
            data.skySeed = tag.getLong("SkySeed");
            data.hasSkySeed = true;
        }
        ListTag faithList = tag.getList("Faiths", Tag.TAG_COMPOUND);
        for (int i = 0; i < faithList.size(); i++) {
            Faith faith = Faith.load(faithList.getCompound(i));
            data.faiths.put(faith.id(), faith);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        if (hasSkySeed) {
            tag.putLong("SkySeed", skySeed);
        }
        ListTag faithList = new ListTag();
        for (Faith faith : faiths.values()) {
            faithList.add(faith.save());
        }
        tag.put("Faiths", faithList);
        return tag;
    }
}
