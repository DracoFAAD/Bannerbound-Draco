package com.bannerbound.core.codex;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.journal.JournalEntry;
import com.bannerbound.core.journal.JournalEntryType;
import com.bannerbound.core.journal.JournalManager;
import com.bannerbound.core.journal.JournalObjective;
import com.bannerbound.core.journal.JournalPlayerData;
import com.bannerbound.core.network.CodexSyncPayload;
import com.bannerbound.core.network.CodexToastPayload;
import com.bannerbound.core.network.OpenCodexScreenPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-side Chronicle unlock, sync, and trigger coordinator. */
public final class CodexManager {
    public static final String JOURNAL_PIN_SOURCE = "codex_pin";

    private CodexManager() {
    }

    public static void sendTo(ServerPlayer player) {
        if (player == null || player.getServer() == null) return;
        PacketDistributor.sendToPlayer(player, buildPayload(player));
    }

    public static void open(ServerPlayer player, String entryId) {
        if (player == null) return;
        reconcile(player, false);
        PacketDistributor.sendToPlayer(player, new OpenCodexScreenPayload(entryId == null ? "" : entryId));
    }

    public static boolean unlock(ServerPlayer player, String entryId) {
        return unlock(player, entryId, true);
    }

    public static boolean unlock(ServerPlayer player, String entryId, boolean notify) {
        if (player == null || player.getServer() == null || entryId == null || entryId.isBlank()) return false;
        CodexEntry entry = CodexEntryLoader.get(entryId);
        if (entry == null) return false;
        CodexPlayerData data = CodexPlayerData.get(player.getServer().overworld());
        CodexPlayerData.PlayerState state = data.state(player.getUUID());
        if (!state.unlock(entryId)) return false;
        data.setDirty();
        sendTo(player);
        if (notify) sendToast(player, List.of(entry));
        return true;
    }

    public static boolean lock(ServerPlayer player, String entryId) {
        if (player == null || player.getServer() == null || entryId == null || entryId.isBlank()) return false;
        CodexPlayerData data = CodexPlayerData.get(player.getServer().overworld());
        boolean changed = data.state(player.getUUID()).lock(entryId);
        if (changed) {
            data.setDirty();
            sendTo(player);
        }
        return changed;
    }

    public static boolean reset(ServerPlayer player) {
        if (player == null || player.getServer() == null) return false;
        CodexPlayerData data = CodexPlayerData.get(player.getServer().overworld());
        boolean changed = data.state(player.getUUID()).reset();
        if (changed) data.setDirty();
        reconcile(player, false);
        return changed;
    }

    public static void markSeen(ServerPlayer player, String entryId) {
        if (player == null || player.getServer() == null || entryId == null || entryId.isBlank()) return;
        CodexPlayerData data = CodexPlayerData.get(player.getServer().overworld());
        if (data.state(player.getUUID()).markSeen(entryId)) {
            data.setDirty();
            sendTo(player);
        }
    }

    public static void togglePinnedJournalEntry(ServerPlayer player, String entryId) {
        if (player == null || player.getServer() == null || entryId == null || entryId.isBlank()) return;
        CodexEntry entry = CodexEntryLoader.get(entryId);
        if (entry == null) return;
        CodexPlayerData data = CodexPlayerData.get(player.getServer().overworld());
        if (!data.state(player.getUUID()).isUnlocked(entryId)) return;

        JournalPlayerData journalData = JournalPlayerData.get(player.getServer().overworld());
        List<JournalEntry> entries = journalData.entriesFor(player.getUUID());
        boolean removedActive = entries.removeIf(existing -> isPinnedChronicle(existing, entryId) && !existing.resolved());
        if (removedActive) {
            journalData.setDirty();
            JournalManager.sendTo(player);
            return;
        }
        entries.removeIf(existing -> isPinnedChronicle(existing, entryId));
        entries.add(buildPinnedJournalEntry(player, entry));
        journalData.setDirty();
        JournalManager.sendTo(player);
    }

    private static JournalEntry buildPinnedJournalEntry(ServerPlayer player, CodexEntry entry) {
        long now = player.getServer().overworld().getGameTime();
        UUID instanceId = UUID.nameUUIDFromBytes(
            ("bannerbound:codex_pin:" + player.getUUID() + ":" + entry.id()).getBytes(StandardCharsets.UTF_8));
        CodexTutorial tutorial = entry.tutorial();
        return new JournalEntry(
            instanceId,
            entry.id(),
            JournalEntryType.TUTORIAL,
            tutorial.title().isBlank() ? entry.title() : tutorial.title(),
            tutorial.subtitle().isBlank()
                ? (entry.subtitle().isBlank() ? "Pinned Chronicle tutorial" : entry.subtitle())
                : tutorial.subtitle(),
            tutorial.priority(),
            now,
            0L,
            JOURNAL_PIN_SOURCE,
            entry.id(),
            entry.id(),
            buildPinnedObjectives(player, entry)
        );
    }

    private static List<JournalObjective> buildPinnedObjectives(ServerPlayer player, CodexEntry entry) {
        CodexTutorial tutorial = entry.tutorial();
        if (tutorial.isEmpty()) {
            return List.of(new JournalObjective("read", "Read in the Chronicle", "Press J", false,
                List.of("Open the Chronicle with J.", "Unpin this entry when you are done.")));
        }
        // Pre-check state-based objectives: if the player already satisfied a step before the
        // tutorial was pinned (e.g. the same research that unlocks this entry also completes its
        // "Research X" step), start it checked instead of showing a stale "Not done" line.
        Settlement settlement = SettlementData.get(player.getServer().overworld()).getByPlayer(player.getUUID());
        List<JournalObjective> objectives = new ArrayList<>();
        for (CodexTutorial.Objective objective : tutorial.objectives()) {
            boolean satisfied = objective.trigger().isSatisfied(player, settlement, null);
            objectives.add(new JournalObjective(objective.id(), objective.label(),
                satisfied ? objective.completeText() : objective.progressText(), satisfied, objective.subSteps()));
        }
        return objectives;
    }

    /** Grants default/state-based entries, then syncs. Login/reload uses notify=false. */
    public static void reconcile(ServerPlayer player, boolean notify) {
        if (player == null || player.getServer() == null) return;
        MinecraftServer server = player.getServer();
        CodexPlayerData data = CodexPlayerData.get(server.overworld());
        CodexPlayerData.PlayerState state = data.state(player.getUUID());
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        List<CodexEntry> newlyUnlocked = new ArrayList<>();
        for (CodexEntry entry : CodexEntryLoader.sorted()) {
            if (state.isUnlocked(entry.id())) continue;
            CodexUnlockRule rule = entry.unlock();
            if ((rule.startsUnlocked() || rule.canReconcileFromState())
                    && rule.isSatisfied(player, settlement, null)) {
                state.unlock(entry.id());
                newlyUnlocked.add(entry);
            }
        }
        if (!newlyUnlocked.isEmpty()) {
            data.setDirty();
        }
        sendTo(player);
        if (notify && !newlyUnlocked.isEmpty()) sendToast(player, newlyUnlocked);
    }

    public static void reconcileAll(MinecraftServer server, boolean notify) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            reconcile(player, notify);
        }
    }

    public static void onResearchCompleted(MinecraftServer server, Settlement settlement, String researchId, boolean culture) {
        fireForSettlement(server, settlement, CodexTriggerContext.research(researchId, culture));
    }

    public static void onItemObtained(ServerPlayer player, String itemId) {
        fireForPlayer(player, CodexTriggerContext.item(itemId));
    }

    public static void onBlockUsed(ServerPlayer player, String blockId) {
        fireForPlayer(player, CodexTriggerContext.block("block_used", blockId));
    }

    public static void onBlockPlaced(ServerPlayer player, String blockId) {
        fireForPlayer(player, CodexTriggerContext.block("block_placed", blockId));
    }

    public static void onBlockFormed(ServerPlayer player, String blockId) {
        fireForPlayer(player, CodexTriggerContext.block("block_formed", blockId));
    }

    public static void onEraReached(MinecraftServer server, Settlement settlement) {
        if (settlement != null) fireForSettlement(server, settlement, CodexTriggerContext.era(settlement.age().key()));
    }

    public static void onFlagGained(MinecraftServer server, Settlement settlement, String flag) {
        fireForSettlement(server, settlement, CodexTriggerContext.flag(flag));
    }

    public static void onAdvancement(ServerPlayer player, String advancementId) {
        fireForPlayer(player, CodexTriggerContext.advancement(advancementId));
    }

    public static void onCustom(ServerPlayer player, String type, String id) {
        fireForPlayer(player, CodexTriggerContext.custom(type, id));
    }

    public static void onCustom(MinecraftServer server, Settlement settlement, String type, String id) {
        fireForSettlement(server, settlement, CodexTriggerContext.custom(type, id));
    }

    private static void fireForSettlement(MinecraftServer server, Settlement settlement, CodexTriggerContext event) {
        if (server == null || settlement == null) return;
        for (UUID memberId : settlement.members()) {
            ServerPlayer player = server.getPlayerList().getPlayer(memberId);
            if (player != null) fireForPlayer(player, event);
        }
    }

    private static void fireForPlayer(ServerPlayer player, CodexTriggerContext event) {
        if (player == null || player.getServer() == null || event == null) return;
        updatePinnedTutorialProgress(player, event);
        MinecraftServer server = player.getServer();
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        CodexPlayerData data = CodexPlayerData.get(server.overworld());
        CodexPlayerData.PlayerState state = data.state(player.getUUID());
        List<CodexEntry> newlyUnlocked = new ArrayList<>();
        for (CodexEntry entry : CodexEntryLoader.sorted()) {
            if (state.isUnlocked(entry.id())) continue;
            CodexUnlockRule rule = entry.unlock();
            if (rule.mayMatchEvent(event) && rule.isSatisfied(player, settlement, event)) {
                state.unlock(entry.id());
                newlyUnlocked.add(entry);
            }
        }
        if (newlyUnlocked.isEmpty()) return;
        data.setDirty();
        sendTo(player);
        sendToast(player, newlyUnlocked);
        // Auto-pin: if enabled, pin the tutorial of each entry the player just unlocked (e.g. by
        // researching a node), so its checklist shows up in the side journal automatically. Fires
        // only on first unlock, so a later manual unpin sticks.
        if (state.autoPinTutorial()) {
            for (CodexEntry entry : newlyUnlocked) autoPinTutorialEntry(player, entry);
        }
    }

    /** Pins {@code entry}'s tutorial if it has one and is not already actively pinned. */
    private static void autoPinTutorialEntry(ServerPlayer player, CodexEntry entry) {
        if (entry == null || entry.tutorial().isEmpty()) return;
        JournalPlayerData journalData = JournalPlayerData.get(player.getServer().overworld());
        List<JournalEntry> entries = journalData.entriesFor(player.getUUID());
        for (JournalEntry existing : entries) {
            if (isPinnedChronicle(existing, entry.id()) && !existing.resolved()) return; // already pinned
        }
        JournalEntry pinned = buildPinnedJournalEntry(player, entry);
        // Don't auto-pin a tutorial that's already fully done — the player satisfied every step
        // (often the very unlock event) before it could be pinned, so it would only show a
        // completed checklist with nothing left to do.
        if (pinned.objectives().stream().allMatch(JournalObjective::complete)) return;
        entries.removeIf(existing -> isPinnedChronicle(existing, entry.id()));
        entries.add(pinned);
        journalData.setDirty();
        JournalManager.sendTo(player);
    }

    /** Sets the player's auto-pin-tutorial preference and re-syncs the Chronicle state. */
    public static void setAutoPinTutorial(ServerPlayer player, boolean enabled) {
        if (player == null || player.getServer() == null) return;
        CodexPlayerData data = CodexPlayerData.get(player.getServer().overworld());
        if (data.state(player.getUUID()).setAutoPinTutorial(enabled)) {
            data.setDirty();
            sendTo(player);
        }
    }

    private static void updatePinnedTutorialProgress(ServerPlayer player, CodexTriggerContext event) {
        JournalPlayerData journalData = JournalPlayerData.get(player.getServer().overworld());
        List<JournalEntry> entries = journalData.entriesFor(player.getUUID());
        boolean changed = false;
        long now = player.getServer().overworld().getGameTime();
        for (JournalEntry pinned : entries) {
            if (!JOURNAL_PIN_SOURCE.equals(pinned.sourceType()) || pinned.resolved()) continue;
            CodexEntry codex = CodexEntryLoader.get(pinned.sourceId());
            if (codex == null || codex.tutorial().isEmpty()) continue;
            List<JournalObjective> objectives = pinned.objectives();
            List<JournalObjective> updated = new ArrayList<>(objectives.size());
            boolean entryChanged = false;
            for (JournalObjective objective : objectives) {
                CodexTutorial.Objective definition = codex.tutorial().objective(objective.id());
                if (!objective.complete() && definition != null && definition.trigger().matchesEvent(event)) {
                    updated.add(new JournalObjective(objective.id(), objective.label(),
                        definition.completeText(), true, objective.subSteps()));
                    entryChanged = true;
                } else {
                    updated.add(objective);
                }
            }
            if (!entryChanged) continue;
            pinned.setObjectives(updated);
            if (updated.stream().allMatch(JournalObjective::complete)) {
                pinned.resolve(now, false);
            }
            changed = true;
        }
        if (changed) {
            journalData.setDirty();
            JournalManager.sendTo(player);
        }
    }

    private static boolean isPinnedChronicle(JournalEntry entry, String entryId) {
        return entry != null
            && JOURNAL_PIN_SOURCE.equals(entry.sourceType())
            && entryId.equals(entry.sourceId());
    }

    private static CodexSyncPayload buildPayload(ServerPlayer player) {
        CodexPlayerData.PlayerState state = CodexPlayerData.get(player.getServer().overworld()).state(player.getUUID());
        List<CodexSyncPayload.Category> categories = new ArrayList<>();
        for (CodexCategory category : CodexCategoryLoader.sorted()) {
            categories.add(CodexSyncPayload.Category.from(category));
        }
        List<CodexSyncPayload.Entry> entries = new ArrayList<>();
        for (CodexEntry entry : CodexEntryLoader.sorted()) {
            entries.add(CodexSyncPayload.Entry.from(entry));
        }
        Set<String> unlocked = state.unlocked();
        Set<String> seen = state.seen();
        return new CodexSyncPayload(categories, entries,
            unlocked.stream().sorted().toList(),
            seen.stream().sorted().toList(),
            state.autoPinTutorial());
    }

    private static void sendToast(ServerPlayer player, List<CodexEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        entries = entries.stream()
            .sorted(Comparator.comparingInt(CodexEntry::order).thenComparing(CodexEntry::title))
            .toList();
        CodexEntry first = entries.get(0);
        PacketDistributor.sendToPlayer(player, new CodexToastPayload(entries.size(), first.title(), first.icon()));
        player.sendSystemMessage(Component.literal(entries.size() == 1
                ? "New Chronicle entry: " + first.title()
                : entries.size() + " new Chronicle entries unlocked")
            .withStyle(ChatFormatting.GOLD));
    }
}
