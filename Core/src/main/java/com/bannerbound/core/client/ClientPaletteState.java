package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.network.PaletteStateSyncPayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the player's settlement's palette state, fed by {@link PaletteStateSyncPayload}
 * and read by the town hall's Palettes tab. Twin of {@link ClientPolicyState}, plus a cache of the
 * synced palette <b>definitions</b> (name + ordered block-id/bonus lists) so the tab can render
 * each palette's block icons and per-block bonus tooltips.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientPaletteState {
    /** One palette's renderable definition: display name + parallel block-id / bonus lists. */
    public record Def(String name, List<String> blockIds, List<Float> bonuses) {
        /** This palette's bonus for {@code blockId}, or {@code 0} when it doesn't list it. */
        public float bonusOf(String blockId) {
            int i = blockIds.indexOf(blockId);
            return i < 0 ? 0f : bonuses.get(i);
        }
    }

    private static volatile List<String> available = List.of();
    private static volatile List<String> active = List.of();
    private static volatile int slots = 1;
    private static volatile int pendingSlot = -1;
    private static volatile String pendingAddId = "";
    private static volatile String pendingRemoveId = "";
    private static volatile int onlineMemberCount = 0;
    private static volatile Map<UUID, Boolean> confirmVotes = Map.of();
    private static volatile Map<String, List<UUID>> suggestions = Map.of();
    private static volatile Map<String, Def> defs = Map.of();

    private ClientPaletteState() {}

    public static void replace(PaletteStateSyncPayload p) {
        available = List.copyOf(p.availablePaletteIds());
        active = List.copyOf(p.activePaletteIds());
        slots = p.activePaletteSlots();
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
        for (int i = 0; i < p.suggestionPaletteIds().size(); i++) {
            sug.put(p.suggestionPaletteIds().get(i), List.copyOf(p.suggestionVoters().get(i)));
        }
        suggestions = Map.copyOf(sug);
        Map<String, Def> d = new HashMap<>();
        for (int i = 0; i < p.defIds().size(); i++) {
            d.put(p.defIds().get(i), new Def(
                p.defNames().get(i),
                List.copyOf(p.defBlockIds().get(i)),
                List.copyOf(p.defBonuses().get(i))));
        }
        defs = Map.copyOf(d);
    }

    public static List<String> getAvailable() { return available; }
    public static List<String> getActive() { return active; }
    public static int getSlots() { return slots; }
    public static boolean hasPending() { return pendingSlot >= 0; }
    public static int getPendingSlot() { return pendingSlot; }
    public static String getPendingAddId() { return pendingAddId; }
    public static String getPendingRemoveId() { return pendingRemoveId; }
    public static int getOnlineMemberCount() { return onlineMemberCount; }

    @Nullable
    public static Def getDef(String paletteId) { return defs.get(paletteId); }
    /** Display name for a palette id, falling back to the id when its def isn't synced. */
    public static String nameOf(String paletteId) {
        Def def = defs.get(paletteId);
        return def == null ? paletteId : def.name();
    }

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

    public static List<UUID> getSuggesters(String paletteId) {
        List<UUID> s = suggestions.get(paletteId);
        return s == null ? List.of() : s;
    }

    /** Full suggestion map — the Suggestions tab aggregates it into its row list. */
    public static Map<String, List<UUID>> getAllSuggestions() { return suggestions; }

    /** Available palettes that aren't already active — the right-hand list. */
    public static List<String> getAvailableNotActive() {
        List<String> out = new ArrayList<>();
        for (String id : available) if (!active.contains(id)) out.add(id);
        return out;
    }
}
