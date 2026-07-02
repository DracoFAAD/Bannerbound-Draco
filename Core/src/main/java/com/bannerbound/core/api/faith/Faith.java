package com.bannerbound.core.api.faith;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * One player-founded faith (FAITH_PLAN.md Part 2) — the cross-faction unit: multiple
 * settlements (and therefore multiple factions) can be members of the same faith.
 * Lives in {@link FaithData}, persisted with the overworld. The pantheon
 * (deities/constellations) arrives with the astrology milestone; M1 carries identity +
 * membership only.
 */
public final class Faith {
    private final UUID id;
    private String name;
    private final FaithPath path;
    private final UUID founderSettlement;
    private final Set<UUID> memberSettlements = new HashSet<>();
    // ── Faith tree (FAITH_PLAN Part 2.5): progress is PER-FAITH, shared — every member
    // settlement's devotion rate pools into the active node, and completed nodes apply to
    // ALL members. Adopting an established faith inherits its climbed tree.
    private final Set<String> completedResearches = new HashSet<>();
    private String activeResearch = null;
    private final java.util.Map<String, Double> researchProgress = new java.util.HashMap<>();
    private final java.util.List<String> researchQueue = new java.util.ArrayList<>();
    private final java.util.Map<String, Integer> insightCounters = new java.util.HashMap<>();
    private final Set<String> firedInsights = new HashSet<>();
    // The pantheon (FAITH_PLAN Part 3): drawn gods. Antiquity cap lives in FaithManager.
    private final java.util.List<Constellation> constellations = new java.util.ArrayList<>();

    public Faith(UUID id, String name, FaithPath path, UUID founderSettlement) {
        this.id = id;
        this.name = name;
        this.path = path;
        this.founderSettlement = founderSettlement;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public FaithPath path() {
        return path;
    }

    public UUID founderSettlement() {
        return founderSettlement;
    }

    public Set<UUID> memberSettlements() {
        return Collections.unmodifiableSet(memberSettlements);
    }

    public void addMember(UUID settlementId) {
        memberSettlements.add(settlementId);
    }

    public void removeMember(UUID settlementId) {
        memberSettlements.remove(settlementId);
    }

    /** Live mutable set — FaithManager mutates it directly (forceUnresearch pattern). */
    public Set<String> completedResearches() {
        return completedResearches;
    }

    public String activeResearch() {
        return activeResearch;
    }

    public void setActiveResearch(String id) {
        this.activeResearch = id;
    }

    /** Live mutable map of node id → accumulated devotion points. */
    public java.util.Map<String, Double> researchProgress() {
        return researchProgress;
    }

    /** Live mutable queue of node ids, promoted in order as the active slot frees up. */
    public java.util.List<String> researchQueue() {
        return researchQueue;
    }

    public int insightCount(String key) { return insightCounters.getOrDefault(key, 0); }
    public void setInsightCount(String key, int value) { insightCounters.put(key, Math.max(0, value)); }
    public boolean hasFiredInsight(String key) { return firedInsights.contains(key); }
    public void markInsightFired(String key) { firedInsights.add(key); }
    public Set<String> firedInsights() { return Collections.unmodifiableSet(firedInsights); }

    /** Live mutable pantheon list — mutate through FaithManager only. */
    public java.util.List<Constellation> constellations() {
        return constellations;
    }

    /** Star exclusivity is PER-FAITH (FAITH_PLAN): true if any of this faith's
     *  constellations already claims {@code starId}. */
    public boolean starUsed(int starId) {
        for (Constellation c : constellations) {
            if (c.usesStar(starId)) return true;
        }
        return false;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putInt("Path", path.ordinal());
        tag.putUUID("Founder", founderSettlement);
        ListTag members = new ListTag();
        for (UUID member : memberSettlements) {
            members.add(NbtUtils.createUUID(member));
        }
        tag.put("Members", members);
        ListTag completed = new ListTag();
        for (String researchId : completedResearches) {
            completed.add(net.minecraft.nbt.StringTag.valueOf(researchId));
        }
        tag.put("CompletedResearches", completed);
        if (activeResearch != null) {
            tag.putString("ActiveResearch", activeResearch);
        }
        CompoundTag progress = new CompoundTag();
        for (java.util.Map.Entry<String, Double> e : researchProgress.entrySet()) {
            progress.putDouble(e.getKey(), e.getValue());
        }
        tag.put("ResearchProgress", progress);
        ListTag queue = new ListTag();
        for (String researchId : researchQueue) {
            queue.add(net.minecraft.nbt.StringTag.valueOf(researchId));
        }
        tag.put("ResearchQueue", queue);
        CompoundTag insightCounts = new CompoundTag();
        for (java.util.Map.Entry<String, Integer> e : insightCounters.entrySet()) {
            insightCounts.putInt(e.getKey(), e.getValue());
        }
        tag.put("InsightCounters", insightCounts);
        ListTag firedInsightList = new ListTag();
        for (String key : firedInsights) firedInsightList.add(net.minecraft.nbt.StringTag.valueOf(key));
        tag.put("FiredInsights", firedInsightList);
        ListTag pantheon = new ListTag();
        for (Constellation c : constellations) {
            pantheon.add(c.save());
        }
        tag.put("Constellations", pantheon);
        return tag;
    }

    public static Faith load(CompoundTag tag) {
        Faith faith = new Faith(
            tag.getUUID("Id"),
            tag.getString("Name"),
            FaithPath.fromOrdinal(tag.getInt("Path")),
            tag.getUUID("Founder"));
        ListTag members = tag.getList("Members", Tag.TAG_INT_ARRAY);
        for (Tag member : members) {
            faith.memberSettlements.add(NbtUtils.loadUUID(member));
        }
        ListTag completed = tag.getList("CompletedResearches", Tag.TAG_STRING);
        for (int i = 0; i < completed.size(); i++) {
            faith.completedResearches.add(completed.getString(i));
        }
        if (tag.contains("ActiveResearch")) {
            faith.activeResearch = tag.getString("ActiveResearch");
        }
        CompoundTag progress = tag.getCompound("ResearchProgress");
        for (String key : progress.getAllKeys()) {
            faith.researchProgress.put(key, progress.getDouble(key));
        }
        ListTag queue = tag.getList("ResearchQueue", Tag.TAG_STRING);
        for (int i = 0; i < queue.size(); i++) {
            faith.researchQueue.add(queue.getString(i));
        }
        CompoundTag insightCounts = tag.getCompound("InsightCounters");
        for (String key : insightCounts.getAllKeys()) {
            faith.insightCounters.put(key, insightCounts.getInt(key));
        }
        ListTag firedInsightList = tag.getList("FiredInsights", Tag.TAG_STRING);
        for (int i = 0; i < firedInsightList.size(); i++) {
            faith.firedInsights.add(firedInsightList.getString(i));
        }
        ListTag pantheon = tag.getList("Constellations", Tag.TAG_COMPOUND);
        for (int i = 0; i < pantheon.size(); i++) {
            faith.constellations.add(Constellation.load(pantheon.getCompound(i)));
        }
        return faith;
    }
}
