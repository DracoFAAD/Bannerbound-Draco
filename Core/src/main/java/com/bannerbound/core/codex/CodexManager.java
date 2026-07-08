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
import com.bannerbound.core.network.ShowTutorialPopupPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side coordinator for the Chronicle: unlocking entries, syncing state to clients, and
 * routing gameplay events into unlock and tutorial-progress checks. reconcile() grants every
 * entry whose rule starts unlocked or is state-satisfiable and re-syncs (login/reload pass
 * notify=false to stay quiet); the onXxx and fireForXxx paths translate a live event into a
 * CodexTriggerContext and unlock any entry whose rule may match it. The same two paths evaluate
 * tutorial popups (checkPopups): a satisfied popup marks fired, quietly unlocks its linked entry
 * for re-reading, and pushes a ShowTutorialPopupPayload (interrupt) or a plain entry toast
 * (toast priority). Newly unlocked entries can
 * auto-pin their tutorial to the side journal: this fires only on the first unlock (so a later
 * manual unpin sticks) and skips a tutorial whose every step is already complete (the unlock
 * event often satisfies the steps itself, which would otherwise pin a done checklist with nothing
 * left to do). Pinned objectives are pre-checked against current state when built, so a step the
 * player already met starts checked instead of showing a stale "not done" line.
 */
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
        Settlement settlement = SettlementData.get(player.getServer().overworld()).getByPlayer(player.getUUID());
        List<JournalObjective> objectives = new ArrayList<>();
        for (CodexTutorial.Objective objective : tutorial.objectives()) {
            boolean satisfied = objective.trigger().isSatisfied(player, settlement, null);
            objectives.add(new JournalObjective(objective.id(), objective.label(),
                satisfied ? objective.completeText() : objective.progressText(), satisfied, objective.subSteps()));
        }
        return objectives;
    }

    public static void reconcile(ServerPlayer player, boolean notify) {
        if (player == null || player.getServer() == null) return;
        MinecraftServer server = player.getServer();
        prunePinsForDeletedEntries(player);
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
        checkPopups(player, settlement, data, state, null);
    }

    /** A pinned tutorial whose Chronicle entry was removed from the datapack would otherwise be
     *  stuck on the HUD forever (no entry row to unpin from), so login/reload drops such pins. */
    private static void prunePinsForDeletedEntries(ServerPlayer player) {
        JournalPlayerData journalData = JournalPlayerData.get(player.getServer().overworld());
        List<JournalEntry> entries = journalData.entriesFor(player.getUUID());
        boolean changed = entries.removeIf(pinned -> JOURNAL_PIN_SOURCE.equals(pinned.sourceType())
            && CodexEntryLoader.get(pinned.sourceId()) == null);
        if (changed) {
            journalData.setDirty();
            JournalManager.sendTo(player);
        }
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

    /** Once-per-second population probe (ImmigrationManager broadcast tick): fires a
     *  population_reached event only when a settlement's population actually changed. */
    public static void onPopulationTick(MinecraftServer server, Settlement settlement) {
        if (server == null || settlement == null) return;
        Integer last = LAST_POPULATION.put(settlement.id(), settlement.population());
        if (last == null || settlement.population() > last) {
            fireForSettlement(server, settlement, CodexTriggerContext.custom(
                "population_reached", String.valueOf(settlement.population())));
        }
    }

    private static final java.util.Map<UUID, Integer> LAST_POPULATION = new java.util.HashMap<>();

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
        if (!newlyUnlocked.isEmpty()) {
            data.setDirty();
            sendTo(player);
            sendToast(player, newlyUnlocked);
            if (state.autoPinTutorial()) {
                for (CodexEntry entry : newlyUnlocked) autoPinTutorialEntry(player, entry);
            }
        }
        checkPopups(player, settlement, data, state, event);
    }

    private static void checkPopups(ServerPlayer player, Settlement settlement,
                                    CodexPlayerData data, CodexPlayerData.PlayerState state,
                                    CodexTriggerContext event) {
        for (TutorialPopup popup : TutorialPopupLoader.sorted()) {
            if (popup.once() && state.isPopupFired(popup.id())) continue;
            CodexUnlockRule rule = popup.trigger();
            // Null event = the reconcile pass: only always-on or state-recheckable triggers may
            // fire there, mirroring how entry unlocks reconcile on login.
            boolean satisfied = event == null
                ? (rule.startsUnlocked() || rule.canReconcileFromState())
                    && rule.isSatisfied(player, settlement, null)
                : rule.mayMatchEvent(event) && rule.isSatisfied(player, settlement, event);
            if (!satisfied) continue;
            state.firePopup(popup.id());
            data.setDirty();
            boolean interrupt = popup.isInterrupt() && state.popupsEnabled();
            unlockPopupEntry(player, data, state, popup, !interrupt);
            if (interrupt) {
                PacketDistributor.sendToPlayer(player, new ShowTutorialPopupPayload(
                    popup.id(), popup.entry(), popup.order(), popup.pages(), false));
            }
        }
    }

    /** Unlocks the Chronicle entry archiving a fired popup: the authored entry when popup.entry
     *  names a real one, else the popup's own id (rendered as a synthesized Tutorials entry). */
    private static void unlockPopupEntry(ServerPlayer player, CodexPlayerData data,
                                         CodexPlayerData.PlayerState state, TutorialPopup popup,
                                         boolean notifyToast) {
        String entryId = resolvePopupEntryId(popup);
        if (CodexEntryLoader.get(entryId) != null) {
            unlock(player, entryId, notifyToast);
            return;
        }
        if (!state.unlock(entryId)) return;
        data.setDirty();
        sendTo(player);
        if (notifyToast && !popup.pages().isEmpty()) {
            PacketDistributor.sendToPlayer(player,
                new CodexToastPayload(1, popup.pages().get(0).title(), ""));
        }
    }

    static String resolvePopupEntryId(TutorialPopup popup) {
        return !popup.entry().isBlank() && CodexEntryLoader.get(popup.entry()) != null
            ? popup.entry() : popup.id();
    }

    public static boolean showPopup(ServerPlayer player, String popupId) {
        TutorialPopup popup = TutorialPopupLoader.get(popupId);
        if (player == null || popup == null) return false;
        PacketDistributor.sendToPlayer(player, new ShowTutorialPopupPayload(
            popup.id(), popup.entry(), popup.order(), popup.pages(), true));
        return true;
    }

    public static void setTutorialPopupsEnabled(ServerPlayer player, boolean enabled) {
        if (player == null || player.getServer() == null) return;
        CodexPlayerData data = CodexPlayerData.get(player.getServer().overworld());
        if (data.state(player.getUUID()).setPopupsEnabled(enabled)) {
            data.setDirty();
            sendTo(player);
        }
    }

    public static boolean resetPopups(ServerPlayer player) {
        if (player == null || player.getServer() == null) return false;
        CodexPlayerData data = CodexPlayerData.get(player.getServer().overworld());
        boolean changed = data.state(player.getUUID()).resetPopups();
        if (changed) data.setDirty();
        return changed;
    }

    private static void autoPinTutorialEntry(ServerPlayer player, CodexEntry entry) {
        if (entry == null || entry.tutorial().isEmpty()) return;
        JournalPlayerData journalData = JournalPlayerData.get(player.getServer().overworld());
        List<JournalEntry> entries = journalData.entriesFor(player.getUUID());
        for (JournalEntry existing : entries) {
            if (isPinnedChronicle(existing, entry.id()) && !existing.resolved()) return;
        }
        JournalEntry pinned = buildPinnedJournalEntry(player, entry);
        if (pinned.objectives().stream().allMatch(JournalObjective::complete)) return;
        entries.removeIf(existing -> isPinnedChronicle(existing, entry.id()));
        entries.add(pinned);
        journalData.setDirty();
        JournalManager.sendTo(player);
    }

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

        // Popups double as Chronicle content: a popup naming a real entry links onto it (and
        // donates its pages when the entry has none of its own); any other popup becomes a
        // synthesized read-only entry in the Tutorials category under the popup's id.
        java.util.Map<String, TutorialPopup> popupByEntry = new java.util.HashMap<>();
        List<TutorialPopup> virtualPopups = new ArrayList<>();
        for (TutorialPopup popup : TutorialPopupLoader.sorted()) {
            if (popup.pages().isEmpty()) continue;
            String entryId = resolvePopupEntryId(popup);
            if (CodexEntryLoader.get(entryId) != null) popupByEntry.put(entryId, popup);
            else virtualPopups.add(popup);
        }

        List<CodexSyncPayload.Entry> entries = new ArrayList<>();
        for (CodexEntry entry : CodexEntryLoader.sorted()) {
            CodexSyncPayload.Entry built = CodexSyncPayload.Entry.from(entry);
            TutorialPopup linked = popupByEntry.get(entry.id());
            if (linked != null) {
                entries.add(new CodexSyncPayload.Entry(built.id(), built.category(), built.title(),
                    built.subtitle(), built.icon(), built.order(), built.secret(), built.ponder(),
                    built.pages().isEmpty() ? popupPages(linked) : built.pages(),
                    built.searchableText() + " " + popupSearchText(linked),
                    linked.id()));
            } else {
                entries.add(built);
            }
        }
        for (TutorialPopup popup : virtualPopups) {
            entries.add(new CodexSyncPayload.Entry(popup.id(), "bannerbound:tutorials",
                popup.pages().get(0).title(), "", "", popup.order(), true, "",
                popupPages(popup), popupSearchText(popup), popup.id()));
        }

        Set<String> unlocked = state.unlocked();
        Set<String> seen = state.seen();
        return new CodexSyncPayload(categories, entries,
            unlocked.stream().sorted().toList(),
            seen.stream().sorted().toList(),
            state.autoPinTutorial(),
            state.popupsEnabled());
    }

    private static List<CodexSyncPayload.PageElement> popupPages(TutorialPopup popup) {
        List<CodexSyncPayload.PageElement> out = new ArrayList<>();
        for (TutorialPopup.Page page : popup.pages()) {
            if (!page.title().isBlank()) {
                out.add(new CodexSyncPayload.PageElement("heading", page.title(), "", "", "", "", "", List.of()));
            }
            if (!page.clip().isBlank()) {
                out.add(new CodexSyncPayload.PageElement("clip", "", "", "", page.clip(), "", "", List.of()));
            } else if (!page.image().isBlank()) {
                out.add(new CodexSyncPayload.PageElement("image", "", "", "", "",
                    normalizedImagePath(page.image()), "", List.of()));
            }
            if (!page.text().isBlank()) {
                out.add(new CodexSyncPayload.PageElement("text", page.text(), "", "", "", "", "", List.of()));
            }
        }
        return out;
    }

    private static String popupSearchText(TutorialPopup popup) {
        StringBuilder search = new StringBuilder();
        for (TutorialPopup.Page page : popup.pages()) {
            search.append(page.title()).append(' ').append(page.text()).append(' ');
        }
        return search.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /** Bare image ids resolve into textures/codex/images; full texture paths pass through. */
    private static String normalizedImagePath(String image) {
        net.minecraft.resources.ResourceLocation raw =
            net.minecraft.resources.ResourceLocation.tryParse(image);
        if (raw == null || raw.getPath().contains("/")) return image;
        return raw.getNamespace() + ":textures/codex/images/" + raw.getPath() + ".png";
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
