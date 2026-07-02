package com.bannerbound.core.barbarian;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * One barbarian camp. Mutable record-style class persisted inside {@link BarbarianData} (mirrors the
 * {@code DiplomacyRelation}/{@code StolenStandard} inner-class save/load pattern in SettlementData).
 *
 * <p>Live entity handles ({@link #commanderIds}/{@link #rosterIds}) are NOT saved — camp NPCs are
 * {@code markSimulated()} and never serialize; they respawn from {@link #memberTarget} on approach.
 * Defeat progress survives via the persisted {@link #commandersKilled} counter instead of resolving
 * stale UUIDs offscreen. {@link #realized} is transient and forced false on load.
 */
public final class BarbarianCamp {
    public final UUID id;
    public CampType type;
    public BlockPos center;          // banner anchor
    public BlockPos bannerPos;       // the raze target (== center until stamped)
    public ResourceLocation biome;   // resolved once at seed time
    public long languageSeed;        // type-biased deterministic seed
    public String name = "";         // the camp's name in its own tongue (e.g. "Ratakusupria")
    public int memberTarget;         // fixed headcount (camps do NOT grow); respawn target on realize
    public int commanderCount;       // total commanders; defeat needs commandersKilled >= this
    public int commandersKilled;     // PERSISTED defeat progress (do not rely on UUIDs offscreen)
    public int raidDifficulty;       // escalation counter, ++ on failed raid
    public long lastRaidTick;
    public long nextScoutTick;
    public long nextDriftTick;
    public boolean razed;
    public boolean bannerRazed;      // the central standard has been broken (one defeat condition)
    public boolean structureStamped; // props placed at least once

    // Live-only (never serialized; cleared on ghostify / forced empty on load).
    public final transient Set<UUID> commanderIds = new HashSet<>();
    public final transient Set<UUID> rosterIds = new HashSet<>();
    public transient boolean realized;

    // Per player-settlement relationship.
    public final Map<UUID, Integer> relScore = new HashMap<>();
    public final Map<UUID, CampRelationState> relState = new HashMap<>();
    public final Set<UUID> discoveredBy = new HashSet<>();
    public final Set<UUID> reachedBy = new HashSet<>(); // settlements whose player has stood at the camp
    // Settlements that took the "we'll get it for you" grace on a demand → game-tick the tribute is due.
    public final Map<UUID, Long> graceUntil = new HashMap<>();

    public BarbarianCamp(UUID id, CampType type, BlockPos center, ResourceLocation biome) {
        this.id = id;
        this.type = type;
        this.center = center;
        this.bannerPos = center;
        this.biome = biome;
    }

    private BarbarianCamp(UUID id) {
        this.id = id;
    }

    /** Stance toward a settlement, defaulting to the type's baseline if first contact. */
    public CampRelationState relationToward(UUID settlementId) {
        return relState.getOrDefault(settlementId, type.defaultRelation());
    }

    /** Records that {@code settlementId} owes a deferred tribute, due at {@code deadlineTick}. */
    public void setGrace(UUID settlementId, long deadlineTick) {
        graceUntil.put(settlementId, deadlineTick);
    }

    public void clearGrace(UUID settlementId) {
        graceUntil.remove(settlementId);
    }

    /** Tick the settlement's deferred tribute is due, or 0 if none is outstanding. */
    public long graceDeadline(UUID settlementId) {
        return graceUntil.getOrDefault(settlementId, 0L);
    }

    /** All commanders have been killed (one of the two defeat conditions). */
    public boolean commandersDefeated() {
        return commandersKilled >= commanderCount && commanderCount > 0;
    }

    /** Ready for permanent clearing: every commander dead AND the standard razed. */
    public boolean clearable() {
        return commandersDefeated() && bannerRazed;
    }

    /** Commanders still to spawn on realize (finite — killed commanders never come back). */
    public int liveCommanderCount() {
        return Math.max(0, commanderCount - commandersKilled);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Type", type.name());
        tag.putLong("Center", center.asLong());
        if (bannerPos != null) tag.putLong("BannerPos", bannerPos.asLong());
        if (biome != null) tag.putString("Biome", biome.toString());
        tag.putLong("LanguageSeed", languageSeed);
        tag.putString("Name", name);
        tag.putInt("MemberTarget", memberTarget);
        tag.putInt("CommanderCount", commanderCount);
        tag.putInt("CommandersKilled", commandersKilled);
        tag.putInt("RaidDifficulty", raidDifficulty);
        tag.putLong("LastRaidTick", lastRaidTick);
        tag.putLong("NextScoutTick", nextScoutTick);
        tag.putLong("NextDriftTick", nextDriftTick);
        tag.putBoolean("Razed", razed);
        tag.putBoolean("BannerRazed", bannerRazed);
        tag.putBoolean("StructureStamped", structureStamped);

        ListTag rel = new ListTag();
        for (Map.Entry<UUID, Integer> e : relScore.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("S", e.getKey());
            c.putInt("V", e.getValue());
            CampRelationState st = relState.get(e.getKey());
            if (st != null) c.putString("R", st.name());
            rel.add(c);
        }
        tag.put("Relations", rel);

        ListTag discovered = new ListTag();
        for (UUID u : discoveredBy) {
            CompoundTag c = new CompoundTag();
            c.putUUID("S", u);
            discovered.add(c);
        }
        tag.put("DiscoveredBy", discovered);

        ListTag reached = new ListTag();
        for (UUID u : reachedBy) {
            CompoundTag c = new CompoundTag();
            c.putUUID("S", u);
            reached.add(c);
        }
        tag.put("ReachedBy", reached);

        ListTag grace = new ListTag();
        for (Map.Entry<UUID, Long> e : graceUntil.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("S", e.getKey());
            c.putLong("T", e.getValue());
            grace.add(c);
        }
        tag.put("Grace", grace);
        return tag;
    }

    static BarbarianCamp load(CompoundTag tag) {
        if (!tag.hasUUID("Id")) return null;
        BarbarianCamp camp = new BarbarianCamp(tag.getUUID("Id"));
        camp.type = CampType.fromName(tag.getString("Type"));
        if (camp.type == null) camp.type = CampType.MARAUDER;
        camp.center = BlockPos.of(tag.getLong("Center"));
        camp.bannerPos = tag.contains("BannerPos") ? BlockPos.of(tag.getLong("BannerPos")) : camp.center;
        if (tag.contains("Biome")) camp.biome = ResourceLocation.tryParse(tag.getString("Biome"));
        camp.languageSeed = tag.getLong("LanguageSeed");
        camp.name = tag.getString("Name");
        if (camp.name.isBlank()) camp.name = BarbarianNames.generate(camp.languageSeed); // backfill old saves
        camp.memberTarget = tag.getInt("MemberTarget");
        camp.commanderCount = tag.getInt("CommanderCount");
        camp.commandersKilled = tag.getInt("CommandersKilled");
        camp.raidDifficulty = tag.getInt("RaidDifficulty");
        camp.lastRaidTick = tag.getLong("LastRaidTick");
        camp.nextScoutTick = tag.getLong("NextScoutTick");
        camp.nextDriftTick = tag.getLong("NextDriftTick");
        camp.razed = tag.getBoolean("Razed");
        camp.bannerRazed = tag.getBoolean("BannerRazed");
        camp.structureStamped = tag.getBoolean("StructureStamped");
        camp.realized = false; // entities are never saved; respawn on approach

        ListTag rel = tag.getList("Relations", Tag.TAG_COMPOUND);
        for (int i = 0; i < rel.size(); i++) {
            CompoundTag c = rel.getCompound(i);
            if (!c.hasUUID("S")) continue;
            UUID s = c.getUUID("S");
            camp.relScore.put(s, c.getInt("V"));
            if (c.contains("R")) {
                CampRelationState st = CampRelationState.fromName(c.getString("R"));
                if (st != null) camp.relState.put(s, st);
            }
        }
        ListTag discovered = tag.getList("DiscoveredBy", Tag.TAG_COMPOUND);
        for (int i = 0; i < discovered.size(); i++) {
            CompoundTag c = discovered.getCompound(i);
            if (c.hasUUID("S")) camp.discoveredBy.add(c.getUUID("S"));
        }
        ListTag reached = tag.getList("ReachedBy", Tag.TAG_COMPOUND);
        for (int i = 0; i < reached.size(); i++) {
            CompoundTag c = reached.getCompound(i);
            if (c.hasUUID("S")) camp.reachedBy.add(c.getUUID("S"));
        }
        ListTag grace = tag.getList("Grace", Tag.TAG_COMPOUND);
        for (int i = 0; i < grace.size(); i++) {
            CompoundTag c = grace.getCompound(i);
            if (c.hasUUID("S")) camp.graceUntil.put(c.getUUID("S"), c.getLong("T"));
        }
        return camp;
    }
}
