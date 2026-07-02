package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.network.PolicyStateSyncPayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the player's settlement's policy state, fed by {@link PolicyStateSyncPayload}
 * and read by the town hall's Policies tab. Static singleton, same shape as
 * {@link ClientResearchState} / {@link ClientSuggestionState}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientPolicyState {
    private static volatile List<String> available = List.of();
    private static volatile List<String> active = List.of();
    private static volatile List<String> slotTypes = List.of();
    private static volatile int pendingSlot = -1;
    private static volatile String pendingAddId = "";
    private static volatile String pendingRemoveId = "";
    private static volatile int onlineMemberCount = 0;
    private static volatile Map<UUID, Boolean> confirmVotes = Map.of();
    private static volatile Map<String, List<UUID>> suggestions = Map.of();

    private ClientPolicyState() {}

    public static void replace(PolicyStateSyncPayload p) {
        available = List.copyOf(p.availablePolicyIds());
        active = List.copyOf(p.activePolicyIds());
        slotTypes = List.copyOf(p.slotTypes());
        pendingSlot = p.pendingSlot();
        pendingAddId = p.pendingAddId();
        pendingRemoveId = p.pendingRemoveId();
        onlineMemberCount = p.onlineMemberCount();
        Map<UUID, Boolean> votes = new HashMap<>();
        for (int i = 0; i < p.confirmVoterIds().size(); i++) {
            votes.put(p.confirmVoterIds().get(i), p.confirmVoteAgrees().get(i));
        }
        confirmVotes = Map.copyOf(votes);
        Map<String, List<UUID>> sug = new HashMap<>();
        for (int i = 0; i < p.suggestionPolicyIds().size(); i++) {
            sug.put(p.suggestionPolicyIds().get(i), List.copyOf(p.suggestionVoters().get(i)));
        }
        suggestions = Map.copyOf(sug);
    }

    public static List<String> getAvailable() { return available; }
    public static List<String> getActive() { return active; }
    /** Ordered slot-type names: each typed slot's {@code PolicyType.name()}, then {@code "SIGNATURE"}
     *  if the government has a signature slot. The UI renders one slot per entry. */
    public static List<String> getSlotTypes() { return slotTypes; }
    public static int getSlotCount() { return slotTypes.size(); }
    public static boolean hasPending() { return pendingSlot >= 0; }
    public static int getPendingSlot() { return pendingSlot; }
    public static String getPendingAddId() { return pendingAddId; }
    public static String getPendingRemoveId() { return pendingRemoveId; }
    public static int getOnlineMemberCount() { return onlineMemberCount; }

    /** This player's recorded confirm vote: null = hasn't voted, TRUE = agree, FALSE = disagree. */
    @Nullable
    public static Boolean getOwnConfirmVote(UUID self) {
        return confirmVotes.get(self);
    }
    public static int countAgrees() {
        int n = 0;
        for (Boolean v : confirmVotes.values()) if (Boolean.TRUE.equals(v)) n++;
        return n;
    }
    public static int countVotesCast() { return confirmVotes.size(); }

    public static List<UUID> getSuggesters(String policyId) {
        List<UUID> s = suggestions.get(policyId);
        return s == null ? List.of() : s;
    }

    /** Full suggestion map — the Suggestions tab aggregates it into its row list. */
    public static Map<String, List<UUID>> getAllSuggestions() { return suggestions; }

    /** Available policies that aren't already active — the set shown in the right-hand list. */
    public static List<String> getAvailableNotActive() {
        List<String> out = new ArrayList<>();
        for (String id : available) if (!active.contains(id)) out.add(id);
        return out;
    }
}
