package com.bannerbound.core.api.settlement;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Top-level {@link SavedData} for all bannerbound state on the server. Owns the table of
 * {@link Settlement}s, the playerâ†’settlement and chunkâ†’settlement reverse indices, and the
 * world-wide era (max of all settlement ages, or admin-set via /bannerbound world set_age).
 * <p>
 * Attached to the overworld's data storage â€” call {@link #get(ServerLevel)} from anywhere
 * server-side. Mutators call {@link #setDirty()} so SavedData persists on next save.
 * <p>
 * To add world-level state (active wars, world events, etc.) put it on this class next to
 * {@code worldAge}, save/load it, and add accessors. Per-settlement state belongs on
 * {@link Settlement} instead.
 */
public class SettlementData extends SavedData {
    private static final String DATA_NAME = "bannerbound_settlements";

    private final Map<UUID, Settlement> settlements = new HashMap<>();
    private final Map<UUID, UUID> playerToSettlement = new HashMap<>();
    private final Map<Long, UUID> chunkToSettlement = new HashMap<>();
    /** Reverse index of outpost WORKING claims (exclusive, unprotected â€” see
     *  {@link Settlement#workingClaims()}). Rebuilt from settlements on load, like
     *  {@link #chunkToSettlement}. */
    private final Map<Long, UUID> workingChunkToSettlement = new HashMap<>();
    private Era worldAge = Era.ANCIENT;
    /** Set of every research id ever completed by any settlement on this world. Monotonically
     *  growing â€” entries are never removed by regular gameplay (settlement disband, era regression,
     *  or even {@code unresearch} commands) so the world-year HUD can keep moving forward. The
     *  {@code /bannerbound reset_world_age} command is the only thing that clears this. */
    private final Set<String> globalResearchedIds = new HashSet<>();
    /** First-completion ORDER of {@link #globalResearchedIds} â€” append-only, no duplicates, same
     *  monotonic lifetime (only {@code resetWorldAge} clears it). The last entry is the world's
     *  most-recently-discovered research; barbarian camps know "everything but the last" (see
     *  {@code com.bannerbound.core.barbarian.BarbarianData} / {@code campKnownTech}). */
    private final java.util.List<String> globalResearchOrder = new java.util.ArrayList<>();
    private final Map<String, DiplomacyRelation> diplomacyRelations = new HashMap<>();
    private final Map<UUID, StolenStandard> stolenStandards = new HashMap<>();
    private final Map<UUID, Long> winnerNoNewWarUntil = new HashMap<>();
    private final Set<UUID> rallyingSettlements = new HashSet<>();
    /** Per-player game-time tick until which a member may NOT leave their settlement. Set when a
     *  player joins or founds (see {@link SettlementManager#LEAVE_COOLDOWN_TICKS}) so they can't
     *  cheese rapid join/leave cycles. Cleared when they actually leave. */
    private final Map<UUID, Long> leaveCooldownUntil = new HashMap<>();

    public SettlementData() {
    }

    public static SettlementData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static Factory<SettlementData> factory() {
        return new Factory<>(SettlementData::new, SettlementData::load);
    }

    public Settlement getByPlayer(UUID playerId) {
        UUID settlementId = playerToSettlement.get(playerId);
        return settlementId == null ? null : settlements.get(settlementId);
    }

    public Settlement getById(UUID id) {
        return settlements.get(id);
    }

    public Collection<Settlement> all() {
        return Collections.unmodifiableCollection(settlements.values());
    }

    public Settlement getByChunk(long packedChunkPos) {
        UUID id = chunkToSettlement.get(packedChunkPos);
        return id == null ? null : settlements.get(id);
    }

    public boolean claimChunk(Settlement settlement, ChunkPos pos) {
        long packed = pos.toLong();
        if (chunkToSettlement.containsKey(packed)) {
            return false;
        }
        // Another settlement's WORKING claim blocks a full claim too (exclusivity is the whole
        // point of working claims); expanding onto your OWN outpost chunk upgrades it â€” the
        // now-redundant working claim is dropped.
        UUID workOwner = workingChunkToSettlement.get(packed);
        if (workOwner != null) {
            if (!workOwner.equals(settlement.id())) return false;
            unclaimWorkingChunk(settlement, pos);
        }
        chunkToSettlement.put(packed, settlement.id());
        settlement.addClaim(packed);
        setDirty();
        return true;
    }

    public void unclaimAllOf(Settlement settlement) {
        for (long packed : settlement.claimedChunks()) {
            chunkToSettlement.remove(packed);
        }
        settlement.claimedChunks().clear();
        for (long packed : settlement.workingClaims()) {
            workingChunkToSettlement.remove(packed);
        }
        settlement.workingClaims().clear();
        setDirty();
    }

    // â”€â”€â”€ Working claims (outposts): exclusive, unprotected, never territory expansions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** The settlement holding a WORKING claim on this chunk, or {@code null}. */
    public Settlement getByWorkingClaim(long packedChunkPos) {
        UUID id = workingChunkToSettlement.get(packedChunkPos);
        return id == null ? null : settlements.get(id);
    }

    /** Grants a working claim. Fails if the chunk is fully claimed by anyone or working-claimed
     *  by another settlement (a chunk has at most ONE holder of any claim kind). */
    public boolean claimWorkingChunk(Settlement settlement, ChunkPos pos) {
        long packed = pos.toLong();
        if (chunkToSettlement.containsKey(packed)) return false;
        UUID workOwner = workingChunkToSettlement.get(packed);
        if (workOwner != null && !workOwner.equals(settlement.id())) return false;
        workingChunkToSettlement.put(packed, settlement.id());
        settlement.workingClaims().add(packed);
        setDirty();
        return true;
    }

    /** Drops a working claim (outpost banner removed/conquered). No-op if not held. */
    public void unclaimWorkingChunk(Settlement settlement, ChunkPos pos) {
        long packed = pos.toLong();
        workingChunkToSettlement.remove(packed, settlement.id());
        settlement.workingClaims().remove(packed);
        settlement.removeOutpostBanner(packed);
        setDirty();
    }

    public boolean nameTaken(String name) {
        for (Settlement s : settlements.values()) {
            if (s.matchesName(name)) {
                return true;
            }
        }
        return false;
    }

    public void addSettlement(Settlement settlement) {
        settlements.put(settlement.id(), settlement);
        for (UUID member : settlement.members()) {
            playerToSettlement.put(member, settlement.id());
        }
        setDirty();
    }

    public void removeMember(Settlement settlement, UUID playerId) {
        settlement.removeMember(playerId);
        playerToSettlement.remove(playerId);
        leaveCooldownUntil.remove(playerId);
        setDirty();
    }

    /** Game-time tick before which {@code playerId} may not leave their settlement (0 if none). */
    public long leaveCooldownUntil(UUID playerId) {
        return leaveCooldownUntil.getOrDefault(playerId, 0L);
    }

    /** Records that {@code playerId} cannot leave until {@code untilGameTime}. Set on join/found. */
    public void setLeaveCooldownUntil(UUID playerId, long untilGameTime) {
        leaveCooldownUntil.put(playerId, untilGameTime);
        setDirty();
    }

    public void addMember(Settlement settlement, UUID playerId) {
        settlement.members().add(playerId);
        playerToSettlement.put(playerId, settlement.id());
        setDirty();
    }

    public Era getWorldAge() {
        return worldAge;
    }

    public void setWorldAge(Era era) {
        this.worldAge = era;
        setDirty();
    }

    /** Records that {@code researchId} has been completed by some settlement at some point.
     *  Returns true if this is the first time globally â€” callers can use that to trigger
     *  HUD updates only on genuinely new discoveries (other completions are no-ops for the
     *  world year). Marks the SavedData dirty when something actually changes. */
    public boolean markGloballyResearched(String researchId) {
        if (globalResearchedIds.add(researchId)) {
            globalResearchOrder.add(researchId); // first-time only â†’ ordered, dup-free, monotonic
            setDirty();
            return true;
        }
        return false;
    }

    /** Read-only view of the global discovered-research set. Used by the world-year formula. */
    public Set<String> getGlobalResearchedIds() {
        return Collections.unmodifiableSet(globalResearchedIds);
    }

    /** Read-only first-completion order of {@link #getGlobalResearchedIds()}. The last entry is the
     *  frontier (most-recently-discovered research across all settlements). */
    public java.util.List<String> getGlobalResearchOrder() {
        return Collections.unmodifiableList(globalResearchOrder);
    }

    public Collection<DiplomacyRelation> diplomacyRelations() {
        return Collections.unmodifiableCollection(diplomacyRelations.values());
    }

    public DiplomacyRelation relation(UUID first, UUID second) {
        if (first == null || second == null || first.equals(second)) return null;
        return diplomacyRelations.computeIfAbsent(diplomacyKey(first, second),
            key -> new DiplomacyRelation(first, second));
    }

    public DiplomacyRelation existingRelation(UUID first, UUID second) {
        if (first == null || second == null || first.equals(second)) return null;
        return diplomacyRelations.get(diplomacyKey(first, second));
    }

    /** Drops every diplomacy relation that touches {@code settlementId}. Called when a settlement
     *  is disbanded/razed so dead relations (whose endpoint no longer resolves) don't accumulate
     *  in the map forever. */
    public void removeRelationsInvolving(UUID settlementId) {
        if (settlementId == null) return;
        if (diplomacyRelations.values().removeIf(r -> r.involves(settlementId))) {
            setDirty();
        }
    }

    public static String diplomacyKey(UUID first, UUID second) {
        String a = first.toString();
        String b = second.toString();
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    public Map<UUID, StolenStandard> stolenStandards() {
        return stolenStandards;
    }

    public Map<UUID, Long> winnerNoNewWarUntil() {
        return winnerNoNewWarUntil;
    }

    public Set<UUID> rallyingSettlements() {
        return rallyingSettlements;
    }

    public boolean isRallying(UUID settlementId) {
        return rallyingSettlements.contains(settlementId);
    }

    public void setRallying(UUID settlementId, boolean rally) {
        if (settlementId == null) return;
        boolean changed = rally ? rallyingSettlements.add(settlementId)
            : rallyingSettlements.remove(settlementId);
        if (changed) setDirty();
    }

    /** Wipes the discovered set + drops {@code worldAge} back to {@link Era#ANCIENT}. Backs
     *  the {@code /bannerbound reset_world_age} command. Caller is responsible for broadcasting
     *  the new era state to clients after this returns. */
    public void resetWorldAge() {
        globalResearchedIds.clear();
        globalResearchOrder.clear();
        worldAge = Era.ANCIENT;
        setDirty();
    }

    /**
     * Checks whether any settlement has a claimed chunk within {@code minDistance} chunks of the
     * proposed new claim area. The new claim area is a (2*radius+1) x (2*radius+1) square centred
     * on {@code center}. Returns true if the rule is violated (too close to another settlement).
     */
    public boolean hasClaimsWithin(ChunkPos center, int radius, int minDistance) {
        for (Settlement s : settlements.values()) {
            for (long claim : s.claimedChunks()) {
                ChunkPos cp = new ChunkPos(claim);
                int chebyshev = Math.max(Math.abs(cp.x - center.x), Math.abs(cp.z - center.z));
                if (chebyshev - radius < minDistance) {
                    return true;
                }
            }
        }
        return false;
    }

    public void removeSettlement(Settlement settlement) {
        for (UUID member : settlement.members()) {
            playerToSettlement.remove(member);
        }
        settlements.remove(settlement.id());
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Settlement settlement : settlements.values()) {
            list.add(settlement.save());
        }
        tag.put("Settlements", list);
        tag.putInt("WorldAge", worldAge.ordinal());
        ListTag discovered = new ListTag();
        for (String id : globalResearchedIds) {
            discovered.add(StringTag.valueOf(id));
        }
        tag.put("GlobalResearchedIds", discovered);
        ListTag discoveredOrder = new ListTag();
        for (String id : globalResearchOrder) {
            discoveredOrder.add(StringTag.valueOf(id));
        }
        tag.put("GlobalResearchOrder", discoveredOrder);
        ListTag relations = new ListTag();
        for (DiplomacyRelation relation : diplomacyRelations.values()) {
            relations.add(relation.save());
        }
        tag.put("DiplomacyRelations", relations);
        ListTag stolen = new ListTag();
        for (StolenStandard standard : stolenStandards.values()) {
            stolen.add(standard.save());
        }
        tag.put("StolenStandards", stolen);
        ListTag winnerCooldowns = new ListTag();
        for (Map.Entry<UUID, Long> e : winnerNoNewWarUntil.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("Settlement", e.getKey());
            c.putLong("Until", e.getValue());
            winnerCooldowns.add(c);
        }
        tag.put("WinnerNoNewWarUntil", winnerCooldowns);
        ListTag leaveCooldowns = new ListTag();
        for (Map.Entry<UUID, Long> e : leaveCooldownUntil.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("Player", e.getKey());
            c.putLong("Until", e.getValue());
            leaveCooldowns.add(c);
        }
        tag.put("LeaveCooldownUntil", leaveCooldowns);
        ListTag rally = new ListTag();
        for (UUID id : rallyingSettlements) {
            rally.add(StringTag.valueOf(id.toString()));
        }
        tag.put("RallyingSettlements", rally);
        return tag;
    }

    public static SettlementData load(CompoundTag tag, HolderLookup.Provider provider) {
        SettlementData data = new SettlementData();
        ListTag list = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Settlement settlement = Settlement.load(list.getCompound(i));
            data.settlements.put(settlement.id(), settlement);
            for (UUID member : settlement.members()) {
                data.playerToSettlement.put(member, settlement.id());
            }
            for (long packed : settlement.claimedChunks()) {
                data.chunkToSettlement.put(packed, settlement.id());
            }
            for (long packed : settlement.workingClaims()) {
                data.workingChunkToSettlement.put(packed, settlement.id());
            }
        }
        if (tag.contains("WorldAge")) {
            data.worldAge = Era.fromOrdinalOrDefault(tag.getInt("WorldAge"));
        }
        if (tag.contains("GlobalResearchedIds")) {
            ListTag discovered = tag.getList("GlobalResearchedIds", Tag.TAG_STRING);
            for (int i = 0; i < discovered.size(); i++) {
                data.globalResearchedIds.add(discovered.getString(i));
            }
        }
        if (tag.contains("GlobalResearchOrder")) {
            ListTag order = tag.getList("GlobalResearchOrder", Tag.TAG_STRING);
            for (int i = 0; i < order.size(); i++) {
                data.globalResearchOrder.add(order.getString(i));
            }
        } else {
            // Pre-existing world saved before the order log existed: seed it from the set so camp
            // tech derivation works (relative order is unknown but the set is complete).
            data.globalResearchOrder.addAll(data.globalResearchedIds);
        }
        if (tag.contains("DiplomacyRelations")) {
            ListTag relations = tag.getList("DiplomacyRelations", Tag.TAG_COMPOUND);
            for (int i = 0; i < relations.size(); i++) {
                DiplomacyRelation relation = DiplomacyRelation.load(relations.getCompound(i));
                if (relation != null) {
                    data.diplomacyRelations.put(diplomacyKey(relation.first(), relation.second()), relation);
                }
            }
        }
        if (tag.contains("StolenStandards")) {
            ListTag stolen = tag.getList("StolenStandards", Tag.TAG_COMPOUND);
            for (int i = 0; i < stolen.size(); i++) {
                StolenStandard standard = StolenStandard.load(stolen.getCompound(i));
                if (standard != null) data.stolenStandards.put(standard.targetSettlementId(), standard);
            }
        }
        if (tag.contains("WinnerNoNewWarUntil")) {
            ListTag cooldowns = tag.getList("WinnerNoNewWarUntil", Tag.TAG_COMPOUND);
            for (int i = 0; i < cooldowns.size(); i++) {
                CompoundTag c = cooldowns.getCompound(i);
                if (c.hasUUID("Settlement")) {
                    data.winnerNoNewWarUntil.put(c.getUUID("Settlement"), c.getLong("Until"));
                }
            }
        }
        if (tag.contains("LeaveCooldownUntil")) {
            ListTag leaveCooldowns = tag.getList("LeaveCooldownUntil", Tag.TAG_COMPOUND);
            for (int i = 0; i < leaveCooldowns.size(); i++) {
                CompoundTag c = leaveCooldowns.getCompound(i);
                if (c.hasUUID("Player")) {
                    data.leaveCooldownUntil.put(c.getUUID("Player"), c.getLong("Until"));
                }
            }
        }
        if (tag.contains("RallyingSettlements")) {
            ListTag rally = tag.getList("RallyingSettlements", Tag.TAG_STRING);
            for (int i = 0; i < rally.size(); i++) {
                try {
                    data.rallyingSettlements.add(UUID.fromString(rally.getString(i)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return data;
    }

    public static final class DiplomacyRelation {
        private final UUID first;
        private final UUID second;
        public boolean discovered;
        public boolean warActive;
        public long warStartedAt;
        public UUID pendingDeclarer;
        public UUID pendingTarget;
        public int pendingTicksRemaining;
        public boolean peaceOfferedByFirst;
        public boolean peaceOfferedBySecond;
        public long redeclareAfter;
        public UUID capturedTarget;
        public UUID capturedBy;
        public long capturedAt;
        public boolean gloryUsedByFirst;
        public boolean gloryUsedBySecond;

        public DiplomacyRelation(UUID first, UUID second) {
            String a = first.toString();
            String b = second.toString();
            if (a.compareTo(b) <= 0) {
                this.first = first;
                this.second = second;
            } else {
                this.first = second;
                this.second = first;
            }
        }

        public UUID first() { return first; }
        public UUID second() { return second; }

        public boolean involves(UUID id) {
            return first.equals(id) || second.equals(id);
        }

        public UUID other(UUID id) {
            if (first.equals(id)) return second;
            if (second.equals(id)) return first;
            return null;
        }

        public boolean peaceOfferedBy(UUID id) {
            if (first.equals(id)) return peaceOfferedByFirst;
            if (second.equals(id)) return peaceOfferedBySecond;
            return false;
        }

        public void setPeaceOfferedBy(UUID id, boolean value) {
            if (first.equals(id)) peaceOfferedByFirst = value;
            if (second.equals(id)) peaceOfferedBySecond = value;
        }

        public boolean gloryUsedBy(UUID id) {
            return first.equals(id) ? gloryUsedByFirst : second.equals(id) && gloryUsedBySecond;
        }

        public void setGloryUsedBy(UUID id) {
            if (first.equals(id)) gloryUsedByFirst = true;
            if (second.equals(id)) gloryUsedBySecond = true;
        }

        public boolean pending() {
            return pendingDeclarer != null && pendingTarget != null && pendingTicksRemaining > 0;
        }

        public boolean capturedFinal() {
            return capturedTarget != null && capturedBy != null;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("First", first);
            tag.putUUID("Second", second);
            tag.putBoolean("Discovered", discovered);
            tag.putBoolean("WarActive", warActive);
            tag.putLong("WarStartedAt", warStartedAt);
            if (pendingDeclarer != null) tag.putUUID("PendingDeclarer", pendingDeclarer);
            if (pendingTarget != null) tag.putUUID("PendingTarget", pendingTarget);
            tag.putInt("PendingTicksRemaining", pendingTicksRemaining);
            tag.putBoolean("PeaceOfferedByFirst", peaceOfferedByFirst);
            tag.putBoolean("PeaceOfferedBySecond", peaceOfferedBySecond);
            tag.putLong("RedeclareAfter", redeclareAfter);
            if (capturedTarget != null) tag.putUUID("CapturedTarget", capturedTarget);
            if (capturedBy != null) tag.putUUID("CapturedBy", capturedBy);
            tag.putLong("CapturedAt", capturedAt);
            tag.putBoolean("GloryUsedByFirst", gloryUsedByFirst);
            tag.putBoolean("GloryUsedBySecond", gloryUsedBySecond);
            return tag;
        }

        static DiplomacyRelation load(CompoundTag tag) {
            if (!tag.hasUUID("First") || !tag.hasUUID("Second")) return null;
            DiplomacyRelation relation = new DiplomacyRelation(tag.getUUID("First"), tag.getUUID("Second"));
            relation.discovered = tag.getBoolean("Discovered");
            relation.warActive = tag.getBoolean("WarActive");
            relation.warStartedAt = tag.getLong("WarStartedAt");
            if (tag.hasUUID("PendingDeclarer")) relation.pendingDeclarer = tag.getUUID("PendingDeclarer");
            if (tag.hasUUID("PendingTarget")) relation.pendingTarget = tag.getUUID("PendingTarget");
            relation.pendingTicksRemaining = tag.getInt("PendingTicksRemaining");
            relation.peaceOfferedByFirst = tag.getBoolean("PeaceOfferedByFirst");
            relation.peaceOfferedBySecond = tag.getBoolean("PeaceOfferedBySecond");
            relation.redeclareAfter = tag.getLong("RedeclareAfter");
            if (tag.hasUUID("CapturedTarget")) relation.capturedTarget = tag.getUUID("CapturedTarget");
            if (tag.hasUUID("CapturedBy")) relation.capturedBy = tag.getUUID("CapturedBy");
            relation.capturedAt = tag.getLong("CapturedAt");
            relation.gloryUsedByFirst = tag.getBoolean("GloryUsedByFirst");
            relation.gloryUsedBySecond = tag.getBoolean("GloryUsedBySecond");
            return relation;
        }
    }

    public static final class StolenStandard {
        private final UUID targetSettlementId;
        public UUID carrierPlayerId;
        public UUID carrierSettlementId;
        public BlockPos droppedPos;
        public long droppedAt;
        public long autoReturnAt;

        public StolenStandard(UUID targetSettlementId) {
            this.targetSettlementId = targetSettlementId;
        }

        public UUID targetSettlementId() { return targetSettlementId; }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("TargetSettlement", targetSettlementId);
            if (carrierPlayerId != null) tag.putUUID("CarrierPlayer", carrierPlayerId);
            if (carrierSettlementId != null) tag.putUUID("CarrierSettlement", carrierSettlementId);
            if (droppedPos != null) tag.putLong("DroppedPos", droppedPos.asLong());
            tag.putLong("DroppedAt", droppedAt);
            tag.putLong("AutoReturnAt", autoReturnAt);
            return tag;
        }

        static StolenStandard load(CompoundTag tag) {
            if (!tag.hasUUID("TargetSettlement")) return null;
            StolenStandard standard = new StolenStandard(tag.getUUID("TargetSettlement"));
            if (tag.hasUUID("CarrierPlayer")) standard.carrierPlayerId = tag.getUUID("CarrierPlayer");
            if (tag.hasUUID("CarrierSettlement")) standard.carrierSettlementId = tag.getUUID("CarrierSettlement");
            if (tag.contains("DroppedPos")) standard.droppedPos = BlockPos.of(tag.getLong("DroppedPos"));
            standard.droppedAt = tag.getLong("DroppedAt");
            standard.autoReturnAt = tag.getLong("AutoReturnAt");
            return standard;
        }
    }
}
