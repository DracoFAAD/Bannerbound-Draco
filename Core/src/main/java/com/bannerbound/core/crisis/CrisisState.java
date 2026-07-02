package com.bannerbound.core.crisis;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Persisted per-settlement state for the currently active crisis. */
public final class CrisisState {
    private final String crisisId;
    private final UUID instanceId;
    private final long startedTick;
    private String choiceId;
    private UUID chooserId;
    private long choiceTick;
    private boolean resolved;
    private boolean failed;
    private long resolvedTick;
    private final Map<UUID, String> votes = new HashMap<>();
    /** Per-source lifetime food-production totals snapshotted when the player picked a response, so a
     *  "produce N from your fields/fishing/herds" objective measures production SINCE the choice (not
     *  whatever the settlement already produced beforehand — e.g. anarchy fishers). */
    private final Map<String, Double> producedBaseline = new HashMap<>();

    public CrisisState(String crisisId, UUID instanceId, long startedTick) {
        this.crisisId = crisisId == null ? "" : crisisId;
        this.instanceId = instanceId == null ? UUID.randomUUID() : instanceId;
        this.startedTick = startedTick;
        this.choiceId = "";
        this.chooserId = null;
    }

    public String crisisId() { return crisisId; }
    public UUID instanceId() { return instanceId; }
    public long startedTick() { return startedTick; }
    public String choiceId() { return choiceId; }
    public UUID chooserId() { return chooserId; }
    public long choiceTick() { return choiceTick; }
    public boolean resolved() { return resolved; }
    public boolean failed() { return failed; }
    public long resolvedTick() { return resolvedTick; }
    public boolean awaitingChoice() { return !resolved && choiceId.isBlank(); }
    public boolean hasChoice() { return !choiceId.isBlank(); }
    public Map<UUID, String> votes() { return Collections.unmodifiableMap(votes); }

    public void choose(String choiceId, UUID chooserId, long gameTick) {
        if (resolved || !this.choiceId.isBlank()) return;
        this.choiceId = choiceId == null ? "" : choiceId;
        this.chooserId = chooserId;
        this.choiceTick = gameTick;
        this.votes.clear();
    }

    /** Capture the settlement's current per-source production totals as the baseline for "produce N
     *  since the choice" objectives. Called once, right after {@link #choose}. Idempotent-ish: only the
     *  first non-empty snapshot sticks (a reload re-loads the saved baseline, never re-snapshots). */
    public void snapshotProducedBaseline(Map<String, Double> totals) {
        if (totals == null || !producedBaseline.isEmpty()) return;
        producedBaseline.putAll(totals);
    }

    /** Production already on the books for {@code source} when the choice was made (0 if none). */
    public double producedBaselineFor(String source) {
        return source == null ? 0.0 : producedBaseline.getOrDefault(source, 0.0);
    }

    public void vote(UUID voterId, String choiceId) {
        if (resolved || !this.choiceId.isBlank() || voterId == null || choiceId == null || choiceId.isBlank()) return;
        votes.put(voterId, choiceId);
    }

    public int voteCount(String choiceId) {
        int count = 0;
        for (String vote : votes.values()) {
            if (vote.equals(choiceId)) count++;
        }
        return count;
    }

    public String voteOf(UUID voterId) {
        return voterId == null ? "" : votes.getOrDefault(voterId, "");
    }

    public void resolve(long gameTick, boolean failed) {
        if (resolved) return;
        this.resolved = true;
        this.failed = failed;
        this.resolvedTick = gameTick;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("CrisisId", crisisId);
        tag.putUUID("InstanceId", instanceId);
        tag.putLong("StartedTick", startedTick);
        tag.putString("ChoiceId", choiceId);
        if (chooserId != null) tag.putUUID("ChooserId", chooserId);
        tag.putLong("ChoiceTick", choiceTick);
        tag.putBoolean("Resolved", resolved);
        tag.putBoolean("Failed", failed);
        tag.putLong("ResolvedTick", resolvedTick);
        ListTag voteList = new ListTag();
        for (Map.Entry<UUID, String> vote : votes.entrySet()) {
            CompoundTag voteTag = new CompoundTag();
            voteTag.putUUID("Voter", vote.getKey());
            voteTag.putString("Choice", vote.getValue());
            voteList.add(voteTag);
        }
        tag.put("Votes", voteList);
        if (!producedBaseline.isEmpty()) {
            CompoundTag baseTag = new CompoundTag();
            for (Map.Entry<String, Double> e : producedBaseline.entrySet()) {
                baseTag.putDouble(e.getKey(), e.getValue());
            }
            tag.put("ProducedBaseline", baseTag);
        }
        return tag;
    }

    public static CrisisState load(CompoundTag tag) {
        CrisisState state = new CrisisState(
            tag.getString("CrisisId"),
            tag.hasUUID("InstanceId") ? tag.getUUID("InstanceId") : UUID.randomUUID(),
            tag.getLong("StartedTick")
        );
        state.choiceId = tag.getString("ChoiceId");
        state.chooserId = tag.hasUUID("ChooserId") ? tag.getUUID("ChooserId") : null;
        state.choiceTick = tag.getLong("ChoiceTick");
        state.resolved = tag.getBoolean("Resolved");
        state.failed = tag.getBoolean("Failed");
        state.resolvedTick = tag.getLong("ResolvedTick");
        if (tag.contains("Votes")) {
            ListTag votes = tag.getList("Votes", Tag.TAG_COMPOUND);
            for (int i = 0; i < votes.size(); i++) {
                CompoundTag voteTag = votes.getCompound(i);
                if (voteTag.hasUUID("Voter")) {
                    state.votes.put(voteTag.getUUID("Voter"), voteTag.getString("Choice"));
                }
            }
        }
        if (tag.contains("ProducedBaseline")) {
            CompoundTag baseTag = tag.getCompound("ProducedBaseline");
            for (String key : baseTag.getAllKeys()) {
                state.producedBaseline.put(key, baseTag.getDouble(key));
            }
        }
        return state;
    }
}
