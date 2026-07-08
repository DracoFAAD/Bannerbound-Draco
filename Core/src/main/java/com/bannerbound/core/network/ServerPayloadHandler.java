package com.bannerbound.core.network;

import com.bannerbound.core.api.farmer.AwaitingSeedRegistry;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Citizen;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.ImmigrationManager;
import com.bannerbound.core.api.territory.TerritoryService;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.codex.CodexManager;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-side hub for every client -> server payload in Core: one static handle*(payload, context)
 * method per packet, wired to the payloads at network registration time (elsewhere). Every handler
 * hops off the network thread with context.enqueueWork(...) before touching game state -- doing work
 * inline would race the server thread; this is the single non-negotiable rule for adding a handler.
 *
 * <p>The client is NEVER authority. Each handler independently re-resolves the acting player's
 * settlement and re-checks membership, permissions, research/era gates and target validity from
 * scratch, so a spoofed or stale packet cannot bypass the UI's own gating. That is why the checks
 * look redundant with what the client already enforces -- they are the real enforcement.
 *
 * <p>Weighty / issuing actions (exile, registration tablet/paper, territory claim, policy and
 * palette changes, disband) route by government type: anarchy = direct or disallowed (no authority
 * exists), COUNCIL = a clickable ChatVoteManager vote (majority of online members), CHIEFDOM = the
 * seated chief (or acting regent) acts directly while any other member instead toggles a SUGGESTION
 * the chief sees in the Suggestions tab. rejectIfChiefdomNonChief guards the research start/enqueue
 * endpoints the same way; non-chiefs there are routed to suggestions on the client.
 *
 * <p>Shared helpers: canManageJobs / resolveManageable centralize the Job-tab permission-plus-lookup
 * gate (chief/regent, any council member, or any member under the Workload Share policy; in anarchy
 * any member may make the narrow self-organizing tweaks). sendJobState builds the whole Job-tab
 * payload and is reused by the open path AND the ~1 Hz live poll (piggybacked on the citizen live
 * state poll). Compliance-driven refusal: assigning a low-compliance citizen may roll a rejection
 * that stamps a per-job NO_WORK_AS_JOB thought (job key derived stably from the job id) instead of
 * taking effect. markStorage / markPreferredStorage / markDropOff are called from
 * DropLocationServerGuard when the editing player clicks a block; they return true to END edit mode
 * and false to let the player retry on a rejected block. Foreman's-Rod commit handlers whitelist the
 * workstation-type string and re-validate territory/enclosure before writing component data.
 */
@ApiStatus.Internal
public final class ServerPayloadHandler {
    private ServerPayloadHandler() {
    }

    private static boolean rejectIfChiefdomNonChief(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement s = data.getByPlayer(player.getUUID());
        if (s == null) return false;
        if (s.governmentType() != Settlement.Government.CHIEFDOM) return false;
        if (s.canActAsChief(player.getUUID())) return false;
        player.sendSystemMessage(Component.translatable(
                "bannerbound.research.chief_only")
            .withStyle(ChatFormatting.RED));
        return true;
    }

    public static void handleRequestStartingItems(RequestStartingItemsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                SettlementManager.sendStartingItemsTo(player);
            }
        });
    }

    public static void handleSettleRequest(SettleRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            BlockPos townHallPos = SettlementManager.takePendingTownHall(player.getUUID());
            SettlementManager.Result result = SettlementManager.trySettle(
                player, payload.name(), payload.colorIndex(), payload.cultureStyle(), townHallPos);
            switch (result) {
                case ALREADY_IN_SETTLEMENT -> player.sendSystemMessage(
                    Component.translatable("bannerbound.settle.error.already").withStyle(ChatFormatting.RED));
                case NAME_TAKEN -> player.sendSystemMessage(
                    Component.translatable("bannerbound.settle.error.name_taken").withStyle(ChatFormatting.RED));
                case NAME_INVALID -> player.sendSystemMessage(
                    Component.translatable("bannerbound.settle.error.name_invalid").withStyle(ChatFormatting.RED));
                case TOO_CLOSE_TO_OTHER_SETTLEMENT -> player.sendSystemMessage(
                    Component.translatable("bannerbound.settle.error.too_close").withStyle(ChatFormatting.RED));
                case TOO_CLOSE_TO_CITY_STATE -> player.sendSystemMessage(
                    Component.translatable("bannerbound.settle.error.too_close_city_state").withStyle(ChatFormatting.RED));
                case MAX_FACTIONS -> player.sendSystemMessage(
                    Component.translatable("bannerbound.settle.error.max_factions").withStyle(ChatFormatting.RED));
                case COLOR_TAKEN -> player.sendSystemMessage(
                    Component.translatable("bannerbound.settle.error.color_taken").withStyle(ChatFormatting.RED));
                case OK -> { }
            }
        });
    }

    public static void handleDisbandSettlement(DisbandSettlementPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }

            net.minecraft.server.MinecraftServer server = player.getServer();
            if (server != null) {
                SettlementData data = SettlementData.get(server.overworld());
                Settlement s = data.getByPlayer(player.getUUID());
                if (s != null && !s.canActWeighty(player.getUUID())) {
                    player.sendSystemMessage(Component.translatable(
                            "bannerbound.townhall.chief_only_action")
                        .withStyle(ChatFormatting.RED));
                    return;
                }
            }
            SettlementManager.disband(player);
        });
    }

    public static void handleCastGovernmentVote(CastGovernmentVotePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            com.bannerbound.core.api.settlement.Settlement.Government[] vals =
                com.bannerbound.core.api.settlement.Settlement.Government.values();
            int ord = payload.governmentOrdinal();
            if (ord <= 0 || ord >= vals.length) return;
            SettlementManager.handleGovernmentVote(player, vals[ord]);
        });
    }

    public static void handleCastChiefNomination(CastChiefNominationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SettlementManager.handleChiefNomination(player, payload.candidate());
        });
    }

    public static void handleCastFaithVote(CastFaithVotePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.api.faith.FaithManager.handleFaithVote(
                player, payload.optionKey(), payload.proposedName());
        });
    }

    public static void handleCastCrisisChoice(CastCrisisChoicePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.crisis.CrisisManager.handleChoice(player, payload.choiceId());
        });
    }

    public static void handleMarkCodexSeen(MarkCodexSeenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.codex.CodexManager.markSeen(player, payload.entryId());
        });
    }

    public static void handleToggleCodexPin(ToggleCodexPinPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.codex.CodexManager.togglePinnedJournalEntry(player, payload.entryId());
        });
    }

    public static void handleSetAutoPinTutorial(SetAutoPinTutorialPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.codex.CodexManager.setAutoPinTutorial(player, payload.enabled());
        });
    }

    public static void handleSetTutorialPopups(SetTutorialPopupsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.codex.CodexManager.setTutorialPopupsEnabled(player, payload.enabled());
        });
    }

    public static void handleRequestTutorialPopup(RequestTutorialPopupPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.codex.CodexManager.showPopup(player, payload.popupId());
        });
    }

    public static void handleMenuOpened(MenuOpenedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.codex.CodexManager.onCustom(player, "menu_opened", payload.menuId());
        });
    }

    public static void handleRequestFaithScreen(RequestFaithScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var server = player.getServer();
            if (server == null) return;
            var data = com.bannerbound.core.api.settlement.SettlementData.get(server.overworld());
            var settlement = data.getByPlayer(player.getUUID());
            if (settlement == null) return;
            if (!com.bannerbound.core.api.faith.FaithManager.choiceWindowOpen(server, settlement)) {
                if (settlement.faithChoiceWindowOpen()) {
                    player.sendSystemMessage(Component.translatable("bannerbound.faith.cooldown")
                        .withStyle(ChatFormatting.YELLOW));
                }
                return;
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                com.bannerbound.core.api.faith.FaithManager.buildScreenPayload(server, settlement, player));
        });
    }

    public static void handleSuggestResearch(SuggestResearchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(player.getUUID());
            if (settlement == null) return;

            if (settlement.governmentType() != Settlement.Government.CHIEFDOM
                    || settlement.chiefPlayerId() == null) {
                return;
            }
            String researchId = payload.researchId();
            if (researchId == null || researchId.isBlank()) return;

            if (payload.treeType() == SuggestResearchPayload.TREE_CULTURE) {
                settlement.toggleCultureSuggestion(researchId, player.getUUID());
            } else {
                settlement.toggleScienceSuggestion(researchId, player.getUUID());
            }
            SettlementManager.broadcastSuggestionState(server, settlement);
        });
    }

    public static void handleProposePolicyChange(ProposePolicyChangePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SettlementManager.proposePolicyChange(
                player, payload.slotIndex(), payload.addPolicyId(), payload.removePolicyId());
        });
    }

    public static void handleProposeLaborPriorityChange(ProposeLaborPriorityChangePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SettlementManager.proposeLaborPriorityChange(player, payload);
        });
    }

    public static void handleCastPolicyVote(CastPolicyVotePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SettlementManager.castPolicyVote(player, payload.agree());
        });
    }

    public static void handleSuggestPolicy(SuggestPolicyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SettlementManager.suggestPolicy(player, payload.policyId());
        });
    }

    public static void handleRetractPolicyChange(RetractPolicyChangePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SettlementManager.retractPolicyChange(player);
        });
    }

    public static void handleProposePaletteChange(ProposePaletteChangePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SettlementManager.proposePaletteChange(
                player, payload.slotIndex(), payload.addPaletteId(), payload.removePaletteId());
        });
    }

    public static void handleCastPaletteVote(CastPaletteVotePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SettlementManager.castPaletteVote(player, payload.agree());
        });
    }

    public static void handleSuggestPalette(SuggestPalettePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SettlementManager.suggestPalette(player, payload.paletteId());
        });
    }

    public static void handleRetractPaletteChange(RetractPaletteChangePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SettlementManager.retractPaletteChange(player);
        });
    }

    public static void handleEnqueueResearch(EnqueueResearchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }

            if (rejectIfChiefdomNonChief(player)) return;
            com.bannerbound.core.api.research.ResearchManager.EnqueueResult result =
                com.bannerbound.core.api.research.ResearchManager.tryEnqueue(player, payload.researchId());
            switch (result) {
                case NOT_IN_SETTLEMENT -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.not_in_settlement").withStyle(ChatFormatting.RED));
                case UNKNOWN_RESEARCH -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.unknown").withStyle(ChatFormatting.RED));
                case ALREADY_COMPLETE -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.already_complete").withStyle(ChatFormatting.RED));
                case AGE_LOCKED -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.age_locked").withStyle(ChatFormatting.RED));
                case OK, OK_REMOVED -> { }
            }
        });
    }

    public static void handleStartFaithResearch(StartFaithResearchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (rejectIfChiefdomNonChief(player)) return;
            com.bannerbound.core.api.faith.FaithManager.ResearchManagerResult result =
                com.bannerbound.core.api.faith.FaithManager.tryStartFaithResearch(player, payload.researchId());
            switch (result) {
                case NOT_IN_SETTLEMENT -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.not_in_settlement").withStyle(ChatFormatting.RED));
                case UNKNOWN -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.unknown").withStyle(ChatFormatting.RED));
                case ALREADY_COMPLETE -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.already_complete").withStyle(ChatFormatting.RED));
                case PREREQ_MISSING -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.prereq_missing").withStyle(ChatFormatting.RED));
                case AGE_LOCKED -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.age_locked").withStyle(ChatFormatting.RED));
                case OK -> { }
            }
        });
    }

    public static void handleEnqueueFaithResearch(EnqueueFaithResearchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (rejectIfChiefdomNonChief(player)) return;
            com.bannerbound.core.api.research.ResearchManager.EnqueueResult result =
                com.bannerbound.core.api.faith.FaithManager.tryEnqueueFaithResearch(player, payload.researchId());
            switch (result) {
                case NOT_IN_SETTLEMENT -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.not_in_settlement").withStyle(ChatFormatting.RED));
                case UNKNOWN_RESEARCH -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.unknown").withStyle(ChatFormatting.RED));
                case ALREADY_COMPLETE -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.already_complete").withStyle(ChatFormatting.RED));
                case AGE_LOCKED -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.age_locked").withStyle(ChatFormatting.RED));
                case OK, OK_REMOVED -> { }
            }
        });
    }

    public static void handleAbandonFaith(AbandonFaithPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.api.faith.FaithManager.handleAbandonFaith(player);
        });
    }

    public static void handleSubmitConstellation(SubmitConstellationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.api.faith.FaithManager.submitConstellation(
                player, payload.name(), payload.deityName(), payload.starIds());
        });
    }

    public static void handleForgetConstellation(ForgetConstellationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.api.faith.FaithManager.forgetConstellation(player, payload.constellationId());
        });
    }

    public static void handleStartCultureResearch(StartCultureResearchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (rejectIfChiefdomNonChief(player)) return;
            com.bannerbound.core.api.research.ResearchManager.StartResult result =
                com.bannerbound.core.api.research.CultureManager.tryStart(player, payload.researchId());
            switch (result) {
                case NOT_IN_SETTLEMENT -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.not_in_settlement").withStyle(ChatFormatting.RED));
                case UNKNOWN_RESEARCH -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.unknown").withStyle(ChatFormatting.RED));
                case ALREADY_COMPLETE -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.already_complete").withStyle(ChatFormatting.RED));
                case AGE_LOCKED -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.age_locked").withStyle(ChatFormatting.RED));
                case PREREQ_MISSING -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.prereq_or_busy").withStyle(ChatFormatting.RED));
                case OK -> { }
            }
        });
    }

    public static void handleRequestCitizenLiveState(RequestCitizenLiveStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            net.minecraft.world.entity.Entity ent = player.serverLevel().getEntity(payload.entityId());
            if (!(ent instanceof com.bannerbound.core.entity.CitizenEntity c)) return;

            SettlementData data = SettlementData.get(server.overworld());
            Settlement viewer = data.getByPlayer(player.getUUID());
            if (viewer == null || c.getSettlementId() == null
                    || !viewer.id().equals(c.getSettlementId())) {
                return;
            }

            int viewerResentment = c.getResentment(player.getUUID());
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new com.bannerbound.core.network.CitizenLiveStatePayload(
                    payload.entityId(), c.getCompliance(), viewerResentment));

            sendJobState(player, c);
        });
    }

    public static void handleQuitChief(QuitChiefPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(player.getUUID());
            if (settlement == null) return;
            if (settlement.governmentType() != Settlement.Government.CHIEFDOM) {
                player.sendSystemMessage(Component.translatable(
                        "bannerbound.chief.quit.not_chiefdom")
                    .withStyle(ChatFormatting.RED));
                return;
            }

            if (!player.getUUID().equals(settlement.chiefPlayerId())) {
                player.sendSystemMessage(Component.translatable(
                        "bannerbound.chief.quit.not_chief")
                    .withStyle(ChatFormatting.RED));
                return;
            }

            long since = settlement.chiefSinceTick();
            long elapsed = server.overworld().getGameTime() - since;
            if (since >= 0 && elapsed < com.bannerbound.core.api.settlement.SettlementManager.CHIEF_STEP_DOWN_COOLDOWN_TICKS) {
                long remainingSec = (com.bannerbound.core.api.settlement.SettlementManager.CHIEF_STEP_DOWN_COOLDOWN_TICKS
                    - elapsed + 19L) / 20L;
                player.sendSystemMessage(Component.translatable("bannerbound.chief.quit.cooldown",
                        String.format("%d:%02d", remainingSec / 60, remainingSec % 60))
                    .withStyle(ChatFormatting.RED));
                return;
            }
            settlement.setChiefPlayerId(null);

            com.bannerbound.core.api.settlement.SettlementManager
                .applyScoreboardTeam(server, player, settlement);
            data.setDirty();
            com.bannerbound.core.api.settlement.SettlementManager.broadcastToSettlement(
                server, settlement,
                Component.translatable("bannerbound.chief.quit.broadcast", player.getName())
                    .withStyle(ChatFormatting.GOLD));

            com.bannerbound.core.api.settlement.SettlementManager.recomputeRegent(server, settlement);

            com.bannerbound.core.api.settlement.ImmigrationManager.broadcastState(server, settlement);
        });
    }

    public static void handleLeaveSettlement(LeaveSettlementPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(player.getUUID());
            if (settlement == null) {
                player.sendSystemMessage(Component.translatable(
                        "bannerbound.leave.error.not_in_settlement").withStyle(ChatFormatting.RED));
                return;
            }
            if (settlement.governmentType() == Settlement.Government.CHIEFDOM
                    && player.getUUID().equals(settlement.chiefPlayerId())) {
                player.sendSystemMessage(Component.translatable(
                        "bannerbound.leave.must_step_down").withStyle(ChatFormatting.RED));
                return;
            }
            com.bannerbound.core.api.settlement.SettlementManager.tryLeave(player);
        });
    }

    public static void handleEnqueueCultureResearch(EnqueueCultureResearchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (rejectIfChiefdomNonChief(player)) return;
            com.bannerbound.core.api.research.ResearchManager.EnqueueResult result =
                com.bannerbound.core.api.research.CultureManager.tryEnqueue(player, payload.researchId());
            switch (result) {
                case NOT_IN_SETTLEMENT -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.not_in_settlement").withStyle(ChatFormatting.RED));
                case UNKNOWN_RESEARCH -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.unknown").withStyle(ChatFormatting.RED));
                case ALREADY_COMPLETE -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.already_complete").withStyle(ChatFormatting.RED));
                case AGE_LOCKED -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.age_locked").withStyle(ChatFormatting.RED));
                case OK, OK_REMOVED -> { }
            }
        });
    }

    public static void handleStartResearch(StartResearchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (rejectIfChiefdomNonChief(player)) return;
            com.bannerbound.core.api.research.ResearchManager.StartResult result =
                com.bannerbound.core.api.research.ResearchManager.tryStart(player, payload.researchId());
            switch (result) {
                case NOT_IN_SETTLEMENT -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.not_in_settlement").withStyle(ChatFormatting.RED));
                case UNKNOWN_RESEARCH -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.unknown").withStyle(ChatFormatting.RED));
                case ALREADY_COMPLETE -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.already_complete").withStyle(ChatFormatting.RED));
                case PREREQ_MISSING -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.prereq_missing").withStyle(ChatFormatting.RED));
                case AGE_LOCKED -> player.sendSystemMessage(Component.translatable(
                    "bannerbound.research.error.age_locked").withStyle(ChatFormatting.RED));
                case OK -> { }
            }
        });
    }

    public static void handleRequestUnemployed(RequestUnemployedCitizensPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(player.getUUID());
            if (settlement == null) return;

            if (settlement.getWorkstation(payload.workstationPos()) == null) return;

            java.util.List<CitizenListPayload.Entry> entries = new java.util.ArrayList<>();
            for (com.bannerbound.core.api.settlement.Citizen c : settlement.unemployedCitizens()) {
                entries.add(new CitizenListPayload.Entry(c.entityId(), c.name()));
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new CitizenListPayload(payload.workstationPos(), entries));
        });
    }

    public static void handleRequestWorkstations(RequestWorkstationsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(player.getUUID());
            if (settlement == null) return;

            java.util.List<WorkstationListPayload.Entry> entries = new java.util.ArrayList<>();
            for (com.bannerbound.core.api.settlement.Workstation ws : settlement.workstations().values()) {
                String workerName = null;
                if (ws.assignedCitizenId() != null) {
                    for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
                        if (ws.assignedCitizenId().equals(c.entityId())) {
                            workerName = c.name();
                            break;
                        }
                    }
                }
                entries.add(new WorkstationListPayload.Entry(
                    ws.pos(), ws.type(), ws.assignedCitizenId(), workerName));
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new WorkstationListPayload(payload.citizenEntityId(), entries));
        });
    }

    public static void handleAssignWorkstation(AssignCitizenToWorkstationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(player.getUUID());
            if (settlement == null) return;
            com.bannerbound.core.api.settlement.Workstation ws = settlement.getWorkstation(payload.workstationPos());
            if (ws == null) return;

            java.util.UUID assigning = payload.citizenId();
            if (assigning != null && assigning.getMostSignificantBits() != 0L) {
                if (settlement.governmentType() == com.bannerbound.core.api.settlement.Settlement.Government.NONE) {
                    player.displayClientMessage(Component.translatable(
                        "bannerbound.workstation.anarchy_rejection")
                        .withStyle(ChatFormatting.RED), true);
                    return;
                }

                if (settlement.governmentType() == com.bannerbound.core.api.settlement.Settlement.Government.CHIEFDOM
                        && !settlement.canActAsChief(player.getUUID())
                        && !settlement.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.WORKLOAD_SHARE)) {
                    player.displayClientMessage(Component.translatable(
                        "bannerbound.workstation.chief_only")
                        .withStyle(ChatFormatting.RED), true);
                    return;
                }

                if (player.serverLevel().getEntity(assigning)
                        instanceof com.bannerbound.core.entity.CitizenEntity ce && ce.isChild()) {
                    player.displayClientMessage(Component.translatable(
                        "bannerbound.workstation.child_cannot_work")
                        .withStyle(ChatFormatting.RED), true);
                    return;
                }

                if (player.serverLevel().getEntity(assigning)
                        instanceof com.bannerbound.core.entity.CitizenEntity refusingCitizen) {
                    int compliance = refusingCitizen.getCompliance();
                    double refuseChance = com.bannerbound.core.api.settlement.ComplianceTables
                        .refuseWorkstation(compliance);
                    if (refuseChance > 0 && refusingCitizen.getRandom().nextDouble() < refuseChance) {
                        String jobId = ws.type() != null
                            ? ws.type().toString()
                            : "unknown";
                        java.util.UUID jobKey = java.util.UUID.nameUUIDFromBytes(
                            ("job:" + jobId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        net.minecraft.server.level.ServerLevel sl = player.serverLevel();
                        refusingCitizen.getThoughts().add(
                            com.bannerbound.core.social.ThoughtKind.NO_WORK_AS_JOB,
                            jobKey,
                            sl.getGameTime(),
                            sl.getRandom());
                        refusingCitizen.recomputeHappiness();
                        player.displayClientMessage(Component.translatable(
                            "bannerbound.workstation.refused",
                            refusingCitizen.getDisplayName())
                            .withStyle(ChatFormatting.RED), true);
                        return;
                    }
                }
                for (com.bannerbound.core.api.settlement.Workstation other : settlement.workstations().values()) {
                    if (other != ws && assigning.equals(other.assignedCitizenId())) {
                        other.setAssignedCitizenId(null);
                    }
                }
                ws.setAssignedCitizenId(assigning);
            } else {
                ws.setAssignedCitizenId(null);
            }
            data.setDirty();
        });
    }

    public static void handleBarbarianParleyAction(BarbarianParleyActionPayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.bannerbound.core.barbarian.MessengerManager.handleAction(player, payload);
            }
        });
    }

    public static void handleBarterAction(BarterActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.bannerbound.core.barbarian.MessengerManager.handleBarter(player, payload);
            }
        });
    }

    public static void handleRequestBarterStorage(RequestBarterStoragePayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.bannerbound.core.barbarian.MessengerManager.handleStorageRequest(player,
                    payload.messengerEntityId());
            }
        });
    }

    public static void handleExileCitizen(ExileCitizenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }
            net.minecraft.world.entity.Entity raw = player.serverLevel().getEntity(payload.entityId());
            if (!(raw instanceof com.bannerbound.core.entity.CitizenEntity citizen)) {
                return;
            }
            if (citizen.getSettlementId() == null) {
                return;
            }
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getById(citizen.getSettlementId());
            if (settlement == null) {
                return;
            }

            if (!settlement.members().contains(player.getUUID())) {
                player.sendSystemMessage(Component.translatable("bannerbound.citizen.exile.error.not_member")
                    .withStyle(ChatFormatting.RED));
                return;
            }
            String name = citizen.getCustomName() != null ? citizen.getCustomName().getString() : "Citizen";
            switch (settlement.governmentType()) {
                case NONE -> player.displayClientMessage(
                    Component.translatable("bannerbound.citizen.exile.error.anarchy")
                        .withStyle(ChatFormatting.RED), true);
                case COUNCIL -> com.bannerbound.core.api.settlement.ChatVoteManager.start(
                    server, settlement, com.bannerbound.core.api.settlement.ChatVoteManager.Kind.EXILE,
                    player, citizen.getUUID(), name);
                case CHIEFDOM -> {
                    if (settlement.canActWeighty(player.getUUID())) {
                        performExile(server, settlement, citizen.getUUID());
                    } else {
                        boolean added = settlement.toggleExileSuggestion(citizen.getUUID(), player.getUUID());
                        if (added) {
                            pingChief(server, settlement, Component.translatable(
                                "bannerbound.suggest.exile.ping",
                                player.getGameProfile().getName(), name)
                                .withStyle(ChatFormatting.GOLD));
                        }
                        player.displayClientMessage(Component.translatable(added
                            ? "bannerbound.suggest.sent" : "bannerbound.suggest.retracted")
                            .withStyle(ChatFormatting.GRAY), true);
                        SettlementManager.broadcastExtraSuggestions(server, settlement);
                    }
                }
            }
        });
    }

    public static void performExile(MinecraftServer server, Settlement settlement,
            java.util.UUID citizenUuid) {
        if (citizenUuid == null || settlement == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        String name = "Citizen";
        for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
            if (c.entityId().equals(citizenUuid)) { name = c.name(); break; }
        }
        net.minecraft.world.entity.Entity raw = server.overworld().getEntity(citizenUuid);
        if (raw instanceof com.bannerbound.core.entity.CitizenEntity citizen) {
            if (citizen.getCustomName() != null) name = citizen.getCustomName().getString();

            citizen.returnJobToolAndClear();
            citizen.discard();
        }
        settlement.removeCitizen(citizenUuid);
        settlement.clearExileSuggestions(citizenUuid);
        data.setDirty();

        com.bannerbound.core.api.settlement.ImmigrationManager.broadcastState(server, settlement);
        SettlementManager.broadcastExtraSuggestions(server, settlement);

        Component msg = Component.translatable("bannerbound.citizen.exile.broadcast",
                name, settlement.name())
            .withStyle(ChatFormatting.YELLOW);
        for (java.util.UUID memberId : settlement.members()) {
            ServerPlayer m = server.getPlayerList().getPlayer(memberId);
            if (m != null) {
                m.sendSystemMessage(msg);
            }
        }
    }

    private static void pingChief(MinecraftServer server, Settlement settlement, Component msg) {
        java.util.Set<java.util.UUID> targets = new java.util.LinkedHashSet<>();
        if (settlement.chiefPlayerId() != null) targets.add(settlement.chiefPlayerId());
        if (settlement.regentPlayerId() != null) targets.add(settlement.regentPlayerId());
        for (java.util.UUID t : targets) {
            ServerPlayer p = server.getPlayerList().getPlayer(t);
            if (p != null) p.sendSystemMessage(msg);
        }
    }

    private static boolean canManageJobs(ServerPlayer player, Settlement settlement) {
        if (settlement == null) return false;
        if (settlement.governmentType() == Settlement.Government.NONE) {
            return settlement.members().contains(player.getUUID());
        }
        return settlement.canActAsChief(player.getUUID())
            || settlement.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.WORKLOAD_SHARE);
    }

    private static final String[] IMPLEMENTED_JOBS = {
        com.bannerbound.core.entity.ForesterWorkGoal.JOB_TYPE_ID,
        com.bannerbound.core.entity.DiggerWorkGoal.JOB_TYPE_ID,
        com.bannerbound.core.entity.FarmerWorkGoal.JOB_TYPE_ID,
        com.bannerbound.core.entity.FisherWorkGoal.JOB_TYPE_ID,
        com.bannerbound.core.entity.ForagerWorkGoal.JOB_TYPE_ID,
        com.bannerbound.core.entity.HerderWorkGoal.JOB_TYPE_ID,
    };

    private static java.util.List<String> unlockedJobTypeIds(Settlement settlement) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String job : IMPLEMENTED_JOBS) {
            String flag = com.bannerbound.core.api.settlement.WorkstationUnlocks.flagForWorkstation(job);
            if (flag == null || ResearchManager.hasFlag(settlement, flag)) out.add(job);
        }
        for (com.bannerbound.core.api.job.CitizenJobRegistry.JobDef def
                : com.bannerbound.core.api.job.CitizenJobRegistry.all()) {
            String job = def.jobTypeId();
            if (out.contains(job)) continue;
            if (!isJobUnlocked(settlement, def)) continue;
            if (com.bannerbound.core.entity.AnarchyJobs.isObsoleted(settlement, job)) continue;
            out.add(job);
        }
        return out;
    }

    private static java.util.List<String> jobPickerJobTypeIds(Settlement settlement) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String job : unlockedJobTypeIds(settlement)) {
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef def =
                com.bannerbound.core.api.job.CitizenJobRegistry.byId(job);
            if (def == null || def.jobPickerVisible()) out.add(job);
        }
        return out;
    }

    private static boolean isJobUnlocked(Settlement settlement,
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef def) {
        if (com.bannerbound.core.entity.CrafterWorkGoal.JOB_TYPE_ID.equals(def.jobTypeId())
                && def.workshopBound() && def.workshopTypeId() == null) {
            return anyCrafterProfessionUnlocked(settlement);
        }
        String flag = com.bannerbound.core.api.settlement.WorkstationUnlocks
            .flagForWorkstation(def.jobTypeId());
        return flag == null || ResearchManager.hasFlag(settlement, flag);
    }

    private static String assignedWorkshopType(Settlement settlement,
            com.bannerbound.core.entity.CitizenEntity citizen) {
        if (settlement == null || citizen.getAssignedWorkshopId() == null) return null;
        com.bannerbound.core.api.settlement.Workshop w =
            settlement.getWorkshop(citizen.getAssignedWorkshopId());
        if (w == null) return null;
        String position = w.positionOf(citizen.getUUID());
        return position != null ? position : w.derivedTypeId();
    }

    private static boolean anyCrafterProfessionUnlocked(Settlement settlement) {
        String genericFlag = com.bannerbound.core.api.settlement.WorkstationUnlocks
            .flagForWorkstation(com.bannerbound.core.entity.CrafterWorkGoal.JOB_TYPE_ID);
        if (genericFlag != null && ResearchManager.hasFlag(settlement, genericFlag)) return true;
        for (String unit : com.bannerbound.core.api.workshop.WorkBlockRegistry.crafterUnits()) {
            String flag = com.bannerbound.core.api.settlement.WorkstationUnlocks.flagForUnit(unit);
            if (ResearchManager.hasFlag(settlement, flag)) return true;
        }
        return false;
    }

    private static java.util.List<Integer> allowedToolItemIds(Settlement settlement, String role) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (net.minecraft.world.item.Item tool
                : com.bannerbound.core.entity.JobTools.allowedToolsFor(settlement, role)) {
            out.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(tool));
        }
        return out;
    }

    public static void sendJobState(ServerPlayer player, com.bannerbound.core.entity.CitizenEntity citizen) {
        MinecraftServer server = player.getServer();
        if (server == null || citizen.getSettlementId() == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        Settlement settlement = data.getById(citizen.getSettlementId());
        if (settlement == null || !settlement.members().contains(player.getUUID())) return;
        String jobType = citizen.getJobType() == null ? "" : citizen.getJobType();
        int toolItemId = citizen.hasJobTool()
            ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(citizen.getJobTool().getItem())
            : 0;
        net.minecraft.resources.ResourceLocation logId =
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(citizen.getPreferredLog());

        boolean anarchy = settlement.governmentType() == Settlement.Government.NONE;
        java.util.List<String> unlocked = anarchy
            ? com.bannerbound.core.entity.AnarchyJobs.unlockedGathererJobs(settlement)
            : jobPickerJobTypeIds(settlement);
        java.util.List<Integer> unlockedIcons = new java.util.ArrayList<>(unlocked.size());
        for (String t : unlocked) unlockedIcons.add(com.bannerbound.core.social.JobIcons.iconItemId(settlement, t));

        int jobIcon = jobType.isEmpty() ? 0 : com.bannerbound.core.social.JobIcons.iconItemId(
            settlement, jobType, assignedWorkshopType(settlement, citizen));
        java.util.List<Integer> allowedTools =
            allowedToolItemIds(settlement, com.bannerbound.core.social.JobIcons.roleForJob(jobType));

        boolean pickaxeUnlocked = ResearchManager.hasFlag(settlement, "bannerbound.unlock_quarry");
        int pickaxeItemId = citizen.hasJobPickaxe()
            ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(citizen.getJobPickaxe().getItem())
            : 0;
        java.util.List<Integer> allowedPickaxes = pickaxeUnlocked
            ? allowedToolItemIds(settlement, "pickaxe") : java.util.List.of();

        java.util.List<Integer> cacheIds = new java.util.ArrayList<>();
        java.util.List<Integer> cacheCounts = new java.util.ArrayList<>();
        net.minecraft.world.SimpleContainer seedCache = citizen.getSeedCache();
        for (int i = 0; i < seedCache.getContainerSize(); i++) {
            ItemStack s = seedCache.getItem(i);
            if (s.isEmpty()) continue;
            cacheIds.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(s.getItem()));
            cacheCounts.add(s.getCount());
        }

        String workshopId = "", workshopName = "", workshopTypeId = "";
        int jobXp = jobType.isEmpty() ? 0 : (int) citizen.getJobXp(jobType);
        if (com.bannerbound.core.entity.CrafterWorkGoal.isWorkshopJob(jobType)
                && citizen.getAssignedWorkshopId() != null) {
            com.bannerbound.core.api.settlement.Workshop workshop =
                settlement.getWorkshop(citizen.getAssignedWorkshopId());
            if (workshop != null) {
                workshopId = workshop.id().toString();
                workshopName = workshop.customName();
                workshopTypeId = workshop.derivedTypeId();

                String position = workshop.positionOf(citizen.getUUID());
                String fixed = com.bannerbound.core.entity.CrafterWorkGoal.workshopTypeForJob(jobType);
                jobXp = (int) citizen.getJobXp(
                    fixed != null ? fixed : position != null ? position : workshop.derivedTypeId());
            }
        }

        java.util.List<Integer> taskItems = new java.util.ArrayList<>();
        java.util.List<Integer> taskCounts = new java.util.ArrayList<>();
        java.util.List<String> taskDests = new java.util.ArrayList<>();
        java.util.List<Integer> taskStates = new java.util.ArrayList<>();
        if (com.bannerbound.core.entity.StockerWorkGoal.JOB_TYPE_ID.equals(jobType)) {
            for (com.bannerbound.core.world.StockerTasks.Task t
                    : com.bannerbound.core.world.StockerTasks.snapshot(settlement.id())) {
                taskItems.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(t.item));
                taskCounts.add(t.count);
                String dest = "";
                if (t.destWorkshopId != null) {
                    com.bannerbound.core.api.settlement.Workshop dw =
                        settlement.getWorkshop(t.destWorkshopId);
                    dest = dw == null ? "?" : dw.customName().isEmpty()
                        ? net.minecraft.network.chat.Component.translatable(
                            com.bannerbound.core.api.workshop.WorkBlockRegistry
                                .displayKey(dw.derivedTypeId())).getString()
                        : dw.customName();
                }
                java.util.UUID claimer = com.bannerbound.core.world.StockerTasks.claimedBy(t);
                taskStates.add(claimer == null ? 0 : claimer.equals(citizen.getUUID()) ? 2 : 1);
                taskDests.add(dest);
            }
        }

        boolean foresterPlantationUnlocked =
            ResearchManager.hasFlag(settlement, "bannerbound.unlock_forester_plantation");

        com.bannerbound.core.entity.CitizenWorkStatus workStatus;
        com.bannerbound.core.entity.CitizenWorkStatus published = citizen.getCurrentWorkStatus();
        if (jobType.isEmpty()) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.IDLE;
        } else if (citizen.isSleeping()) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.SLEEPING;
        } else if (!settlement.hasFactionBanner()) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.BANNER_DOWN;
        } else if (citizen.isPregnant()) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.EXPECTING;
        } else if (!anarchy && com.bannerbound.core.entity.WorkGoal.isAfternoonGathering(citizen)) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.SOCIALIZING;
        } else if (citizen.isStaminaExhausted()) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.NO_STAMINA;
        } else if (!anarchy && com.bannerbound.core.entity.WorkGoal.hasRefusalThought(citizen)) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.ON_STRIKE;
        } else if (published != com.bannerbound.core.entity.CitizenWorkStatus.IDLE) {
            workStatus = published;
        } else if (com.bannerbound.core.entity.CrafterWorkGoal.isWorkshopJob(jobType)
                && workshopId.isEmpty()) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.NO_WORKSHOP;
        } else if (!anarchy && com.bannerbound.core.social.JobIcons.requiresTool(jobType)
                && !citizen.hasJobTool()) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.NO_TOOL;
        } else if (!anarchy && isDropOffFull(citizen)) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.STORAGE_FULL;
        } else {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.WORKING;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new CitizenJobStatePayload(citizen.getId(), canManageJobs(player, settlement),
                jobType, jobIcon, citizen.hasJobTool(), toolItemId,
                logId == null ? "" : logId.toString(),

                citizen.hasDropDepot(), unlocked, unlockedIcons,
                allowedTools, pickaxeUnlocked, citizen.hasJobPickaxe(), pickaxeItemId, allowedPickaxes,
                citizen.hasSeedDepot(),
                citizen.getForageTargetBits(),
                com.bannerbound.core.api.forager.ForageCategory.unlockedBits(settlement),
                citizen.getHunterPreyOffIds(),
                cacheIds, cacheCounts, anarchy, citizen.foresterKeepsExtras(), citizen.isJobPinned(),
                citizen.hasActiveJobRefusal(),
                workshopId, workshopName, workshopTypeId, jobXp,
                taskItems, taskCounts, taskDests, taskStates,

                citizen.getOutpostSite() != null && settlement.workingClaims().contains(
                    new net.minecraft.world.level.ChunkPos(citizen.getOutpostSite()).toLong()),
                workStatus.ordinal(), foresterPlantationUnlocked,
                citizen.isTradingCourier()));
    }

    private static boolean isDropOffFull(com.bannerbound.core.entity.CitizenEntity citizen) {
        net.minecraft.world.Container c = com.bannerbound.core.entity.DropOffContainers
            .resolveOrPreferred(citizen, citizen.getDropOff());
        return c != null && !com.bannerbound.core.entity.DropOffContainers.hasFreeSlot(c);
    }

    public static void reopenCitizenScreen(ServerPlayer player, int entityId) {
        com.bannerbound.core.entity.CitizenEntity citizen = resolveManageable(player, entityId);
        if (citizen == null) return;
        Settlement settlement = citizen.getSettlement();
        boolean canModify = settlement != null && settlement.members().contains(player.getUUID());
        Component displayName = citizen.getCustomName() != null
            ? citizen.getCustomName()
            : Component.literal("Citizen");

        java.util.List<com.bannerbound.core.network.RelationshipEntry> rels = new java.util.ArrayList<>();
        net.minecraft.server.level.ServerLevel sl = player.serverLevel();
        for (java.util.Map.Entry<java.util.UUID, com.bannerbound.core.social.Relationship> e
                : citizen.getRelationships().entries().entrySet()) {
            net.minecraft.world.entity.Entity ent = sl.getEntity(e.getKey());
            Component otherName = ent instanceof com.bannerbound.core.entity.CitizenEntity oc
                    && oc.getCustomName() != null
                ? oc.getCustomName()
                : Component.literal("Unknown Citizen");
            rels.add(new com.bannerbound.core.network.RelationshipEntry(
                otherName, e.getValue().score(), e.getValue().isFamily()));
        }

        java.util.List<com.bannerbound.core.network.ThoughtEntry> thoughtRows = new java.util.ArrayList<>();
        for (com.bannerbound.core.social.Thought t : citizen.getThoughts().entries()) {
            Component partnerName = null;
            if (t.otherUuid() != null) {
                net.minecraft.world.entity.Entity ent = sl.getEntity(t.otherUuid());
                if (ent instanceof com.bannerbound.core.entity.CitizenEntity oc && oc.getCustomName() != null) {
                    partnerName = oc.getCustomName();
                } else if (t.savedPartnerName() != null) {
                    partnerName = Component.literal(t.savedPartnerName());
                } else {
                    partnerName = Component.literal("Someone");
                }
            }
            Component label = partnerName != null
                ? Component.translatable(t.kind().labelKey(), partnerName)
                : Component.translatable(t.kind().labelKey());

            thoughtRows.add(new com.bannerbound.core.network.ThoughtEntry(
                label, t.effectiveModifier(sl.getGameTime()), t.expireGameTime(), t.totalDurationTicks(),
                t.kind().category().ordinal()));
        }

        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new OpenCitizenScreenPayload(
                citizen.getId(),
                displayName,
                citizen.getHealth(),
                citizen.getMaxHealth(),
                citizen.getHappiness(),
                citizen.getHappinessMax(),
                canModify,
                citizen.getStamina(),
                citizen.getStaminaMax(),
                rels,
                thoughtRows,
                citizen.getCompliance(),
                citizen.getResentment(player.getUUID())
            ));
        sendJobState(player, citizen);
    }

    public static void handleAssignOutpostWorker(AssignOutpostWorkerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            net.minecraft.server.level.ServerLevel sl = player.serverLevel();
            SettlementData data = SettlementData.get(server.overworld());
            net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(payload.bannerPos());
            Settlement owner = data.getByWorkingClaim(cp.toLong());
            if (owner == null) return;
            Settlement mine = data.getByPlayer(player.getUUID());
            if (mine == null || !mine.id().equals(owner.id())) return;
            if (!canManageJobs(player, owner)) return;
            java.util.UUID target = null;
            com.bannerbound.core.territory.ChunkResource type =
                com.bannerbound.core.territory.ChunkResources.typeAt(sl, cp);
            String expectedJob = com.bannerbound.core.api.settlement.Outpost.expectedJob(type);
            if (!payload.citizenUuid().isEmpty()) {
                try {
                    target = java.util.UUID.fromString(payload.citizenUuid());
                } catch (IllegalArgumentException malformed) {
                    return;
                }
                if (expectedJob == null
                    || !(sl.getEntity(target) instanceof com.bannerbound.core.entity.CitizenEntity c)
                    || !c.isAlive()
                    || !owner.id().equals(c.getSettlementId())
                    || !expectedJob.equals(c.getJobType())) {
                    player.displayClientMessage(Component.translatable("bannerbound.outpost.candidate_gone")
                        .withStyle(ChatFormatting.RED), true);
                    com.bannerbound.core.api.settlement.Outpost.openScreen(sl, player, payload.bannerPos());
                    return;
                }

                if (com.bannerbound.core.territory.BoulderLayout.isOreChunk(type)
                    && !c.getJobTool().isCorrectToolForDrops(
                        com.bannerbound.core.territory.BoulderLayout.oreBlock(type))) {
                    player.displayClientMessage(Component.translatable("bannerbound.outpost.tool_too_soft",
                            c.getCustomName() != null ? c.getCustomName().getString() : "The miner")
                        .withStyle(ChatFormatting.YELLOW), false);
                }
            }
            String failKey = com.bannerbound.core.api.settlement.Outpost.setOutpostWorker(
                sl, owner, payload.bannerPos(), target, player.getUUID());
            if (failKey != null) {
                player.displayClientMessage(Component.translatable(failKey)
                    .withStyle(ChatFormatting.RED), true);
            }
            com.bannerbound.core.api.settlement.Outpost.openScreen(sl, player, payload.bannerPos());
        });
    }

    public static void handleEstablishOutpost(EstablishOutpostPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            net.minecraft.server.level.ServerLevel sl = player.serverLevel();
            if (!com.bannerbound.core.api.settlement.FactionBanner.isBanner(
                    sl.getBlockState(payload.bannerPos()))) {
                return;
            }
            String failKey = com.bannerbound.core.api.settlement.Outpost.tryEstablish(
                sl, player, payload.bannerPos());
            if (failKey != null) {
                Settlement mine = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
                int max = mine != null ? com.bannerbound.core.api.settlement.Outpost.maxOutposts(mine)
                    : com.bannerbound.core.api.settlement.Outpost.BASE_OUTPOSTS;
                player.displayClientMessage(Component.translatable(failKey,
                        com.bannerbound.core.api.settlement.Outpost.OUTPOST_RANGE_CHUNKS, max)
                    .withStyle(ChatFormatting.RED), true);
                com.bannerbound.core.api.settlement.Outpost.openEstablishScreen(sl, player, payload.bannerPos());
                return;
            }
            com.bannerbound.core.api.settlement.Outpost.openScreen(sl, player, payload.bannerPos());
        });
    }

    private static com.bannerbound.core.entity.CitizenEntity resolveManageable(
            ServerPlayer player, int entityId) {
        net.minecraft.world.entity.Entity raw = player.serverLevel().getEntity(entityId);
        if (!(raw instanceof com.bannerbound.core.entity.CitizenEntity citizen)) return null;
        if (citizen.getSettlementId() == null) return null;
        MinecraftServer server = player.getServer();
        if (server == null) return null;
        Settlement settlement = SettlementData.get(server.overworld()).getById(citizen.getSettlementId());
        if (settlement == null || !settlement.members().contains(player.getUUID())) return null;
        if (!canManageJobs(player, settlement)) return null;
        return citizen;
    }

    public static void handleAssignCitizenJob(AssignCitizenJobPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.entity.CitizenEntity citizen = resolveManageable(player, payload.entityId());
            if (citizen == null) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            Settlement settlement = SettlementData.get(server.overworld()).getById(citizen.getSettlementId());
            if (settlement == null) return;
            boolean anarchy = settlement.governmentType() == Settlement.Government.NONE;
            String typeId = payload.typeId();
            if (typeId == null || typeId.isEmpty()) {
                if (anarchy) { sendJobState(player, citizen); return; }
                citizen.returnJobToolAndClear();
                citizen.setJobType(null);
            } else {
                if (citizen.isChild()) return;

                if (anarchy && !com.bannerbound.core.entity.AnarchyJobs.isGathererJob(typeId)) return;
                if (!jobPickerJobTypeIds(settlement).contains(typeId)) return;

                double refuseChance = com.bannerbound.core.api.settlement.ComplianceTables
                    .refuseWorkstation(citizen.getCompliance());
                if (refuseChance > 0 && citizen.getRandom().nextDouble() < refuseChance) {
                    java.util.UUID jobKey = java.util.UUID.nameUUIDFromBytes(
                        ("job:" + typeId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    net.minecraft.server.level.ServerLevel sl = player.serverLevel();
                    citizen.getThoughts().add(
                        com.bannerbound.core.social.ThoughtKind.NO_WORK_AS_JOB, jobKey,
                        sl.getGameTime(), sl.getRandom());
                    citizen.recomputeHappiness();
                    player.displayClientMessage(Component.translatable(
                        "bannerbound.workstation.refused", citizen.getDisplayName())
                        .withStyle(ChatFormatting.RED), true);
                    sendJobState(player, citizen);
                    return;
                }

                if (com.bannerbound.core.entity.CrafterWorkGoal.isWorkshopJob(typeId)) {
                    WorkshopMenu.openPicker(player, player.serverLevel(), settlement, citizen, typeId);
                    return;
                }
                citizen.setJobType(typeId);

                citizen.setJobPinned(true);
                CodexManager.onCustom(player, "job_assigned", typeId);
            }
            sendJobState(player, citizen);
        });
    }

    public static void handleSetJobAuto(SetJobAutoPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.entity.CitizenEntity citizen = resolveManageable(player, payload.entityId());
            if (citizen == null) return;
            citizen.setJobPinned(false);
            sendJobState(player, citizen);
        });
    }

    public static void handleSetCitizenTool(SetCitizenToolPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.entity.CitizenEntity citizen = resolveManageable(player, payload.entityId());
            if (citizen == null) return;
            if (!citizen.isEmployed()) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            Settlement settlement = SettlementData.get(server.overworld()).getById(citizen.getSettlementId());
            if (settlement == null) return;
            int slot = payload.playerInvSlot();
            net.minecraft.world.entity.player.Inventory inv = player.getInventory();
            if (slot < 0 || slot >= inv.getContainerSize()) return;
            ItemStack inSlot = inv.getItem(slot);

            boolean pickaxe = payload.pickaxe();
            if (pickaxe && !(com.bannerbound.core.entity.DiggerWorkGoal.JOB_TYPE_ID.equals(citizen.getJobType())
                    && ResearchManager.hasFlag(settlement, "bannerbound.unlock_quarry"))) {
                return;
            }
            String role = pickaxe ? "pickaxe" : com.bannerbound.core.social.JobIcons.roleForJob(citizen.getJobType());

            if (!allowedToolItemIds(settlement, role).contains(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(inSlot.getItem()))) {
                player.displayClientMessage(Component.translatable(
                    "bannerbound.job.tool_too_advanced").withStyle(ChatFormatting.RED), true);
                return;
            }

            ItemStack existing = pickaxe ? citizen.getJobPickaxe() : citizen.getJobTool();
            if (!existing.isEmpty()) {
                if (!player.getInventory().add(existing.copy())) {
                    citizen.spawnAtLocation(existing.copy());
                }
            }
            ItemStack one = inSlot.copy();
            one.setCount(1);
            inSlot.shrink(1);
            if (pickaxe) citizen.setJobPickaxe(one); else citizen.setJobTool(one);
            CodexManager.onCustom(player, pickaxe ? "job_pickaxe_set" : "job_tool_set",
                citizen.getJobType() == null ? "" : citizen.getJobType());
            sendJobState(player, citizen);
        });
    }

    public static void handleClearCitizenTool(ClearCitizenToolPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.entity.CitizenEntity citizen = resolveManageable(player, payload.entityId());
            if (citizen == null) return;
            boolean pickaxe = payload.pickaxe();
            ItemStack existing = pickaxe ? citizen.getJobPickaxe() : citizen.getJobTool();
            if (!existing.isEmpty()) {
                if (player.getInventory().add(existing.copy())) {
                    if (pickaxe) citizen.setJobPickaxe(ItemStack.EMPTY); else citizen.setJobTool(ItemStack.EMPTY);
                } else {
                    citizen.spawnAtLocation(existing.copy());
                    if (pickaxe) citizen.setJobPickaxe(ItemStack.EMPTY); else citizen.setJobTool(ItemStack.EMPTY);
                }
            }
            sendJobState(player, citizen);
        });
    }

    public static void handleSetCitizenPreferredLog(SetCitizenPreferredLogPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.entity.CitizenEntity citizen = resolveManageable(player, payload.entityId());
            if (citizen == null) return;
            net.minecraft.resources.ResourceLocation logId =
                net.minecraft.resources.ResourceLocation.tryParse(payload.logId());
            if (logId == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(logId)) return;
            net.minecraft.world.level.block.Block block =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(logId);

            if (!block.defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS)) return;
            citizen.setPreferredLog(block);
            sendJobState(player, citizen);
        });
    }

    public static void handleSetForageTarget(SetForageTargetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.entity.CitizenEntity citizen = resolveManageable(player, payload.entityId());
            if (citizen == null) return;
            com.bannerbound.core.api.forager.ForageCategory cat =
                com.bannerbound.core.api.forager.ForageCategory.byOrdinal(payload.categoryOrdinal());
            if (cat == null) return;

            if (payload.enabled()) {
                MinecraftServer server = player.getServer();
                if (server == null) return;
                Settlement settlement = SettlementData.get(server.overworld()).getById(citizen.getSettlementId());
                if (!cat.isUnlocked(settlement)) return;
            }
            citizen.setForageTarget(cat.ordinal(), payload.enabled());
            sendJobState(player, citizen);
        });
    }

    public static void handleSetHunterPrey(SetHunterPreyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.entity.CitizenEntity citizen = resolveManageable(player, payload.entityId());
            if (citizen == null) return;

            net.minecraft.resources.ResourceLocation id =
                net.minecraft.resources.ResourceLocation.tryParse(payload.entityTypeId());
            if (id == null) return;
            java.util.Optional<net.minecraft.world.entity.EntityType<?>> type =
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (type.isEmpty()
                    || !type.get().is(com.bannerbound.core.entity.HunterWorkGoal.HUNTABLE_TAG)) {
                return;
            }
            citizen.setHunterPreyEnabled(payload.entityTypeId(), payload.enabled());
            sendJobState(player, citizen);
        });
    }

    public static void handleSetForesterKeepExtras(SetForesterKeepExtrasPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.entity.CitizenEntity citizen = resolveManageable(player, payload.entityId());
            if (citizen == null) return;
            citizen.setForesterKeepExtras(payload.keep());
            sendJobState(player, citizen);
        });
    }

    public static String rodTypeForJob(String jobType) {
        if (com.bannerbound.core.entity.DiggerWorkGoal.JOB_TYPE_ID.equals(jobType))
            return com.bannerbound.core.entity.DiggerWorkGoal.SELECTION_TYPE;
        if (com.bannerbound.core.entity.FarmerWorkGoal.JOB_TYPE_ID.equals(jobType))
            return com.bannerbound.core.entity.FarmerWorkGoal.SELECTION_TYPE;
        if (com.bannerbound.core.entity.HerderWorkGoal.JOB_TYPE_ID.equals(jobType))
            return com.bannerbound.core.entity.HerderWorkGoal.SELECTION_TYPE;
        if (com.bannerbound.core.entity.MinerWorkGoal.JOB_TYPE_ID.equals(jobType))
            return com.bannerbound.core.entity.MinerWorkGoal.SELECTION_TYPE;

        if (com.bannerbound.core.entity.ForesterWorkGoal.JOB_TYPE_ID.equals(jobType))
            return com.bannerbound.core.entity.ForesterWorkGoal.SELECTION_TYPE;

        if (com.bannerbound.core.entity.GuardWorkGoal.JOB_TYPE_ID.equals(jobType))
            return com.bannerbound.core.entity.GuardWorkGoal.SELECTION_TYPE;
        return null;
    }

    public static void handleBindForemanToCitizen(BindForemanToCitizenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.entity.CitizenEntity citizen = resolveManageable(player, payload.entityId());
            if (citizen == null) return;
            String rodType = rodTypeForJob(citizen.getJobType());
            if (rodType == null) return;

            if (com.bannerbound.core.entity.ForesterWorkGoal.JOB_TYPE_ID.equals(citizen.getJobType())) {
                Settlement fs = citizen.getSettlement();
                if (fs == null || !ResearchManager.hasFlag(fs, "bannerbound.unlock_forester_plantation")) {
                    player.displayClientMessage(Component.translatable(
                        "bannerbound.citizen.job.plantation_locked").withStyle(ChatFormatting.RED), true);
                    return;
                }
            }

            net.minecraft.world.entity.player.Inventory inv = player.getInventory();
            ItemStack rod = ItemStack.EMPTY;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).is(BannerboundCore.FOREMANS_ROD.get())) { rod = inv.getItem(i); break; }
            }
            if (rod.isEmpty()) {
                player.displayClientMessage(Component.translatable(
                    "bannerbound.job.need_rod_msg").withStyle(ChatFormatting.RED), true);
                return;
            }
            rod.set(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get(), rodType);
            rod.set(BannerboundCore.FOREMAN_TARGET_CITIZEN.get(), citizen.getUUID().toString());
            rod.set(BannerboundCore.FOREMAN_TARGET_NAME.get(),
                citizen.getCustomName() != null ? citizen.getCustomName().getString() : "Worker");
            player.displayClientMessage(Component.translatable(
                "bannerbound.job.work_area_bound").withStyle(ChatFormatting.GREEN), true);
        });
    }

    public static void handleBeginEditDropLocation(BeginEditDropLocationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.entity.CitizenEntity citizen = resolveManageable(player, payload.entityId());
            if (citizen == null) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            Settlement settlement = SettlementData.get(server.overworld()).getById(citizen.getSettlementId());
            if (settlement == null) return;
            Component name = citizen.getCustomName() != null
                ? citizen.getCustomName() : Component.literal("Citizen");
            Component jobTitle = Component.translatable(
                "bannerbound.job." + (citizen.getJobType() == null ? "unemployed" : citizen.getJobType()));
            // Track edit mode server-side too: DropLocationServerGuard needs it to suppress the
            // chest-open on the integrated (single-player) server thread.
            com.bannerbound.core.event.DropLocationEditServer.begin(player.getUUID(), citizen.getId(), payload.seed());
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new OpenDropLocationEditPayload(citizen.getId(), name, jobTitle,
                    settlement.identityRgb(), payload.seed()));
        });
    }

    public static void handleCancelDropLocationEdit(CancelDropLocationEditPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.bannerbound.core.event.DropLocationEditServer.clear(player.getUUID());
            }
        });
    }

    public static boolean markDropOff(ServerPlayer player, int citizenEntityId, BlockPos pos) {
        return markStorage(player, citizenEntityId, pos, false);
    }

    private static boolean storageBlockResearched(ServerPlayer player, Settlement settlement, BlockPos pos) {
        net.minecraft.world.item.Item item = player.serverLevel().getBlockState(pos).getBlock().asItem();
        if (com.bannerbound.core.api.research.ItemKnowledge.isKnown(settlement, item)) {
            return true;
        }
        player.displayClientMessage(Component.translatable(
            "bannerbound.job.drop_not_researched").withStyle(ChatFormatting.RED), true);
        return false;
    }

    public static boolean markStorage(ServerPlayer player, int citizenEntityId, BlockPos pos, boolean seed) {
        net.minecraft.world.entity.Entity raw = player.serverLevel().getEntity(citizenEntityId);
        if (!(raw instanceof com.bannerbound.core.entity.CitizenEntity citizen)) return true;
        if (citizen.getSettlementId() == null) return true;
        MinecraftServer server = player.getServer();
        if (server == null) return true;
        Settlement settlement = SettlementData.get(server.overworld()).getById(citizen.getSettlementId());
        if (settlement == null || !canManageJobs(player, settlement)) return true;

        long packedChunk = new net.minecraft.world.level.ChunkPos(pos).toLong();
        boolean inClaim = settlement.claimedChunks().contains(packedChunk)
            || settlement.workingClaims().contains(packedChunk);
        boolean validStorage = com.bannerbound.core.entity.DropOffContainers.resolveDropOff(
            player.serverLevel(), pos) != null;
        if (!validStorage) {
            player.displayClientMessage(Component.translatable(
                "bannerbound.job.drop_not_storage").withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (!storageBlockResearched(player, settlement, pos)) {
            return false;
        }
        if (!inClaim) {
            player.displayClientMessage(Component.translatable(
                "bannerbound.job.drop_invalid").withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (seed) {
            citizen.setSeedSource(pos);
            CodexManager.onCustom(player, "seed_storage_marked",
                citizen.getJobType() == null ? "" : citizen.getJobType());
        } else {
            citizen.setDropOff(pos);
            CodexManager.onCustom(player, "dropoff_storage_marked",
                citizen.getJobType() == null ? "" : citizen.getJobType());
        }
        player.serverLevel().playSound(null, pos,
            net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(),
            net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
        player.displayClientMessage(Component.translatable(
            seed ? "bannerbound.job.seeds_marked" : "bannerbound.job.drop_marked")
            .withStyle(ChatFormatting.GREEN), true);
        sendJobState(player, citizen);
        return true;
    }

    public static void handleBeginEditPreferredStorage(BeginEditPreferredStoragePayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());

            if (settlement == null || settlement.governmentType() == Settlement.Government.NONE
                    || !SettlementManager.canEditLabor(player, settlement)) {
                return;
            }
            com.bannerbound.core.event.DropLocationEditServer.begin(player.getUUID(),
                OpenDropLocationEditPayload.PREFERRED_STORAGE_TARGET, false);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new OpenDropLocationEditPayload(OpenDropLocationEditPayload.PREFERRED_STORAGE_TARGET,
                    Component.translatable("bannerbound.townhall.labor.preferred_storage"),
                    Component.translatable("bannerbound.townhall.labor.preferred_storage_hint"),
                    settlement.identityRgb(), false));
        });
    }

    public static boolean markPreferredStorage(ServerPlayer player, BlockPos pos) {
        MinecraftServer server = player.getServer();
        if (server == null) return true;
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        if (settlement == null || settlement.governmentType() == Settlement.Government.NONE
                || !SettlementManager.canEditLabor(player, settlement)) {
            return true;
        }
        boolean validStorage = com.bannerbound.core.entity.DropOffContainers.resolveDropOff(
            player.serverLevel(), pos) != null;
        if (!validStorage) {
            player.displayClientMessage(Component.translatable(
                "bannerbound.job.drop_not_storage").withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (!storageBlockResearched(player, settlement, pos)) {
            return false;
        }
        if (!settlement.claimedChunks().contains(new net.minecraft.world.level.ChunkPos(pos).toLong())) {
            player.displayClientMessage(Component.translatable(
                "bannerbound.job.drop_invalid").withStyle(ChatFormatting.RED), true);
            return false;
        }
        settlement.setPreferredStoragePos(pos);

        SettlementData.get(server.overworld()).setDirty();
        CodexManager.onCustom(player, "preferred_storage_marked", "settlement");
        player.serverLevel().playSound(null, pos,
            net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(),
            net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 1.0f);
        player.displayClientMessage(Component.translatable(
            "bannerbound.townhall.labor.preferred_storage_set").withStyle(ChatFormatting.GREEN), true);
        SettlementManager.broadcastLaborState(server, settlement);
        return true;
    }

    public static void handleStockpileWithdraw(StockpileWithdrawPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player
                && player.containerMenu instanceof com.bannerbound.core.menu.StockpileMenu m
                && m.menuId() == payload.containerId()) {
                m.withdraw(player, payload.template(), payload.half());
            }
        });
    }

    public static void handleStockpileDeposit(StockpileDepositPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player
                && player.containerMenu instanceof com.bannerbound.core.menu.StockpileMenu m
                && m.menuId() == payload.containerId()) {
                m.deposit(player, payload.single());
            }
        });
    }

    public static void handleStockpileDetect(StockpileDetectPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player
                && player.level() instanceof net.minecraft.server.level.ServerLevel sl
                && player.containerMenu instanceof com.bannerbound.core.menu.StockpileMenu m
                && m.menuId() == payload.containerId()) {
                com.bannerbound.core.block.StockpileBlock.flashEnclosure(sl, m.pos(), player);
            }
        });
    }

    public static void handleSetCitizenTrading(SetCitizenTradingPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            com.bannerbound.core.entity.CitizenEntity citizen =
                resolveManageable(player, payload.entityId());
            if (citizen == null) return;
            Settlement settlement = citizen.getSettlement();
            if (settlement == null || !canManageJobs(player, settlement)) return;
            if (!com.bannerbound.core.entity.StockerWorkGoal.JOB_TYPE_ID.equals(citizen.getJobType())) {
                return;
            }
            citizen.setTradingCourier(payload.enabled());
            sendJobState(player, citizen);
        });
    }

    public static void handleRequestOpenTrade(RequestOpenTradePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            Settlement target = resolveTradeTarget(server, payload.targetId());
            if (target == null) return;
            OpenTradeScreenPayload open = com.bannerbound.core.trade.TradeManager
                .buildOpen(server.overworld(), player, target);
            if (open != null) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, open);
            }
        });
    }

    public static void handleTradeAction(TradeActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            Settlement target = resolveTradeTarget(server, payload.targetId());
            if (target == null) return;
            java.util.UUID dealId = null;
            try {
                if (!payload.dealId().isEmpty()) dealId = java.util.UUID.fromString(payload.dealId());
            } catch (IllegalArgumentException ignored) {
            }
            com.bannerbound.core.trade.TradeManager.handleAction(
                player, target, dealId, payload.action(), payload.give(), payload.get());
        });
    }

    public static void handleRequestTradeStorage(RequestTradeStoragePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            Settlement target = resolveTradeTarget(server, payload.targetId());
            Settlement mine = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
            if (target == null || mine == null) return;
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new TradeStoragePayload(payload.targetId(),
                    com.bannerbound.core.trade.TradeManager.livePool(server.overworld(), mine, mine),
                    com.bannerbound.core.trade.TradeManager.livePool(server.overworld(), target, mine)));
        });
    }

    @Nullable
    private static Settlement resolveTradeTarget(MinecraftServer server, String targetId) {
        try {
            return SettlementData.get(server.overworld()).getById(java.util.UUID.fromString(targetId));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static void handleStockpileToggle(StockpileTogglePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player
                && player.containerMenu instanceof com.bannerbound.core.menu.StockpileMenu m
                && m.menuId() == payload.containerId()) {
                m.setWorkerAccess(player, payload.toggle(), payload.value());
            }
        });
    }

    public static void handleGetRegistrationTablet(GetRegistrationTabletPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(player.getUUID());
            if (settlement == null) {
                player.sendSystemMessage(Component.translatable("bannerbound.tablet.error.no_settlement")
                    .withStyle(ChatFormatting.RED));
                return;
            }
            if (!settlement.canIssueTablet()) {
                player.sendSystemMessage(Component.translatable("bannerbound.tablet.error.already_issued")
                    .withStyle(ChatFormatting.RED));
                return;
            }
            switch (settlement.governmentType()) {
                case NONE -> issueTablet(player, settlement);
                case COUNCIL -> com.bannerbound.core.api.settlement.ChatVoteManager.start(
                    server, settlement, com.bannerbound.core.api.settlement.ChatVoteManager.Kind.TABLET,
                    player, null, "");
                case CHIEFDOM -> {
                    if (settlement.canActAsChief(player.getUUID())) {
                        issueTablet(player, settlement);
                    } else {
                        boolean added = settlement.toggleTabletSuggestion(player.getUUID());
                        if (added) {
                            boolean paper = settlement.age().ordinal() >= Era.MEDIEVAL.ordinal();
                            pingChief(server, settlement, Component.translatable(
                                paper ? "bannerbound.suggest.paper.ping" : "bannerbound.suggest.tablet.ping",
                                player.getGameProfile().getName())
                                .withStyle(ChatFormatting.GOLD));
                        }
                        player.displayClientMessage(Component.translatable(added
                            ? "bannerbound.suggest.sent" : "bannerbound.suggest.retracted")
                            .withStyle(ChatFormatting.GRAY), true);
                        SettlementManager.broadcastExtraSuggestions(server, settlement);
                    }
                }
            }
        });
    }

    public static void issueTablet(ServerPlayer recipient, Settlement settlement) {
        MinecraftServer server = recipient.getServer();
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        if (!settlement.canIssueTablet()) {
            recipient.sendSystemMessage(Component.translatable("bannerbound.tablet.error.already_issued")
                .withStyle(ChatFormatting.RED));
            return;
        }
        boolean paper = settlement.age().ordinal() >= Era.MEDIEVAL.ordinal();
        ItemStack document = new ItemStack(
            (paper ? BannerboundCore.REGISTRATION_PAPER : BannerboundCore.REGISTRATION_TABLET).get());
        document.set(BannerboundCore.SETTLEMENT_REF.get(), settlement.factionName());
        document.set(BannerboundCore.TABLET_CHARGES.get(), 3);
        document.set(BannerboundCore.TABLET_MAX_CHARGES.get(), 3);
        if (!recipient.getInventory().add(document)) {
            recipient.drop(document, false);
        }
        settlement.incrementTabletsIssued();
        settlement.clearTabletSuggestions();
        data.setDirty();
        recipient.sendSystemMessage(Component.translatable(
                paper ? "bannerbound.paper.received" : "bannerbound.tablet.received", settlement.factionName())
            .withStyle(settlement.identityFormatting()));
        SettlementManager.broadcastExtraSuggestions(server, settlement);
    }

    public static void handleCastChatVote(CastChatVotePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            com.bannerbound.core.api.settlement.ChatVoteManager.castVote(
                server, player, payload.voteId(), payload.yes());
        });
    }

    public static void handleDiplomacyAction(DiplomacyActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            java.util.UUID target = null;
            if (payload.targetSettlementId() != null && !payload.targetSettlementId().isBlank()) {
                try {
                    target = java.util.UUID.fromString(payload.targetSettlementId());
                } catch (IllegalArgumentException ignored) {
                    return;
                }
            }
            com.bannerbound.core.api.settlement.DiplomacyManager.routeAction(
                player, payload.action(), target);
        });
    }

    public static void handleIgnoreSuggestion(IgnoreSuggestionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData data = SettlementData.get(server.overworld());
            Settlement s = data.getByPlayer(player.getUUID());
            if (s == null || s.governmentType() != Settlement.Government.CHIEFDOM
                    || !s.canActAsChief(player.getUUID())) {
                return;
            }
            String id = payload.id();
            java.util.Collection<java.util.UUID> suggesters;
            Component subject;
            switch (payload.kind()) {
                case IgnoreSuggestionPayload.KIND_SCIENCE -> {
                    suggesters = s.scienceSuggesters(id);
                    com.bannerbound.core.api.research.ResearchDefinition def =
                        com.bannerbound.core.api.research.data.ResearchTreeLoader.get(id);
                    subject = Component.translatable("bannerbound.suggest.subject.research",
                        def == null ? id : def.name());
                    s.clearScienceSuggestions(id);
                    SettlementManager.broadcastSuggestionState(server, s);
                }
                case IgnoreSuggestionPayload.KIND_CULTURE -> {
                    suggesters = s.cultureSuggesters(id);
                    com.bannerbound.core.api.research.ResearchDefinition def =
                        com.bannerbound.core.api.research.data.CultureTreeLoader.get(id);
                    subject = Component.translatable("bannerbound.suggest.subject.research",
                        def == null ? id : def.name());
                    s.clearCultureSuggestions(id);
                    SettlementManager.broadcastSuggestionState(server, s);
                }
                case IgnoreSuggestionPayload.KIND_POLICY -> {
                    suggesters = s.policySuggesters(id);
                    com.bannerbound.core.api.settlement.PolicyRegistry.Policy p =
                        com.bannerbound.core.api.settlement.PolicyRegistry.get(id);
                    subject = Component.translatable("bannerbound.suggest.subject.policy",
                        p == null ? Component.literal(id) : Component.translatable(p.nameKey()));
                    s.clearPolicySuggestions(id);
                    SettlementManager.broadcastPolicyState(server, s);
                }
                case IgnoreSuggestionPayload.KIND_PALETTE -> {
                    suggesters = new java.util.ArrayList<>(
                        s.allPaletteSuggestions().getOrDefault(id, new java.util.LinkedHashSet<>()));
                    com.bannerbound.core.api.settlement.Palette palette =
                        com.bannerbound.core.api.settlement.data.PaletteLoader.get(id);
                    subject = Component.translatable("bannerbound.suggest.subject.palette",
                        palette == null ? id : palette.name());
                    s.clearPaletteSuggestions(id);
                    SettlementManager.broadcastPaletteState(server, s);
                }
                case IgnoreSuggestionPayload.KIND_EXILE -> {
                    java.util.UUID citizenUuid;
                    try {
                        citizenUuid = java.util.UUID.fromString(id);
                    } catch (IllegalArgumentException ex) {
                        return;
                    }
                    suggesters = new java.util.ArrayList<>(s.allExileSuggestions()
                        .getOrDefault(citizenUuid, new java.util.LinkedHashSet<>()));
                    String name = "Citizen";
                    for (com.bannerbound.core.api.settlement.Citizen c : s.citizens()) {
                        if (c.entityId().equals(citizenUuid)) { name = c.name(); break; }
                    }
                    subject = Component.translatable("bannerbound.suggest.subject.exile", name);
                    s.clearExileSuggestions(citizenUuid);
                    SettlementManager.broadcastExtraSuggestions(server, s);
                }
                case IgnoreSuggestionPayload.KIND_TABLET -> {
                    suggesters = s.tabletSuggesters();
                    boolean paper = s.age().ordinal() >= Era.MEDIEVAL.ordinal();
                    subject = Component.translatable(paper
                        ? "bannerbound.suggest.subject.paper" : "bannerbound.suggest.subject.tablet");
                    s.clearTabletSuggestions();
                    SettlementManager.broadcastExtraSuggestions(server, s);
                }
                default -> { return; }
            }
            Component msg = Component.translatable("bannerbound.suggest.ignored", subject)
                .withStyle(ChatFormatting.GRAY);
            for (java.util.UUID u : suggesters) {
                ServerPlayer sp = server.getPlayerList().getPlayer(u);
                if (sp != null) sp.sendSystemMessage(msg);
            }
        });
    }

    public static void handleRetractSuggestion(RetractSuggestionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData data = SettlementData.get(server.overworld());
            Settlement s = data.getByPlayer(player.getUUID());
            if (s == null) return;
            java.util.UUID me = player.getUUID();
            String id = payload.id();
            boolean changed = false;
            switch (payload.kind()) {
                case RetractSuggestionPayload.KIND_SCIENCE -> {
                    if (s.scienceSuggesters(id).contains(me)) {
                        s.toggleScienceSuggestion(id, me);
                        SettlementManager.broadcastSuggestionState(server, s);
                        changed = true;
                    }
                }
                case RetractSuggestionPayload.KIND_CULTURE -> {
                    if (s.cultureSuggesters(id).contains(me)) {
                        s.toggleCultureSuggestion(id, me);
                        SettlementManager.broadcastSuggestionState(server, s);
                        changed = true;
                    }
                }
                case RetractSuggestionPayload.KIND_POLICY -> {
                    if (s.policySuggesters(id).contains(me)) {
                        s.togglePolicySuggestion(id, me);
                        SettlementManager.broadcastPolicyState(server, s);
                        changed = true;
                    }
                }
                case RetractSuggestionPayload.KIND_PALETTE -> {
                    if (s.allPaletteSuggestions().getOrDefault(id, new java.util.LinkedHashSet<>())
                            .contains(me)) {
                        s.togglePaletteSuggestion(id, me);
                        SettlementManager.broadcastPaletteState(server, s);
                        changed = true;
                    }
                }
                case RetractSuggestionPayload.KIND_EXILE -> {
                    java.util.UUID citizenUuid;
                    try {
                        citizenUuid = java.util.UUID.fromString(id);
                    } catch (IllegalArgumentException ex) {
                        return;
                    }
                    if (s.allExileSuggestions().getOrDefault(citizenUuid, new java.util.LinkedHashSet<>())
                            .contains(me)) {
                        s.toggleExileSuggestion(citizenUuid, me);
                        SettlementManager.broadcastExtraSuggestions(server, s);
                        changed = true;
                    }
                }
                case RetractSuggestionPayload.KIND_TABLET -> {
                    if (s.tabletSuggesters().contains(me)) {
                        s.toggleTabletSuggestion(me);
                        SettlementManager.broadcastExtraSuggestions(server, s);
                        changed = true;
                    }
                }
                default -> { return; }
            }
            if (changed) {
                player.sendSystemMessage(Component.translatable("bannerbound.suggest.retracted")
                    .withStyle(ChatFormatting.GRAY));
            }
        });
    }

    public static void handleRequestSettlementCitizens(RequestSettlementCitizensPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(player.getUUID());
            if (settlement == null) return;

            java.util.List<SettlementCitizensListPayload.Entry> entries = new java.util.ArrayList<>();
            for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
                net.minecraft.world.entity.Entity raw = player.serverLevel().getEntity(c.entityId());
                float health = 0f, maxHealth = 20f;
                int stamina = 0, maxStamina = com.bannerbound.core.entity.CitizenEntity.MAX_STAMINA;
                String displayName = c.name();

                String jobTypeId = "";
                int jobIconItemId = 0;
                if (raw instanceof com.bannerbound.core.entity.CitizenEntity ce) {
                    health = ce.getHealth();
                    maxHealth = ce.getMaxHealth();
                    stamina = ce.getStamina();
                    maxStamina = ce.getStaminaMax();
                    displayName = ce.displayCitizenName();
                    String jt = ce.getJobType();
                    if (jt != null) {
                        jobTypeId = jt;
                        jobIconItemId = com.bannerbound.core.social.JobIcons.iconItemId(settlement, jt);
                    }
                }

                entries.add(new SettlementCitizensListPayload.Entry(
                    c.entityId(), displayName, health, maxHealth, stamina, maxStamina, 10, 10,
                    jobTypeId, jobIconItemId));
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new SettlementCitizensListPayload(settlement.color(), settlement.age(), entries));
        });
    }

    public static void handleToggleWorkstationActive(ToggleWorkstationActivePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(player.getUUID());
            if (settlement == null) return;
            com.bannerbound.core.api.settlement.Workstation ws = settlement.getWorkstation(payload.pos());
            if (ws == null) return;
            ws.setActive(payload.active());
            data.setDirty();
        });
    }

    public static void handleRecallCitizen(RecallCitizenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            net.minecraft.world.entity.Entity raw = player.serverLevel().getEntity(payload.citizenId());
            if (!(raw instanceof com.bannerbound.core.entity.CitizenEntity citizen)) return;
            if (citizen.getSettlementId() == null) return;
            Settlement settlement = SettlementData.get(server.overworld()).getById(citizen.getSettlementId());
            if (settlement == null) return;
            if (!settlement.members().contains(player.getUUID())) return;
            citizen.recallToTownHall();
        });
    }

    public static void handlePickForemansRodWorkstation(PickForemansRodWorkstationPayload payload,
                                                         IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ItemStack stack = player.getMainHandItem();
            if (stack.isEmpty() || !stack.is(BannerboundCore.FOREMANS_ROD.get())) return;

            String chosen = payload.workstationType();
            if (chosen == null) return;

            if (chosen.isEmpty()) {
                stack.remove(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
                stack.remove(BannerboundCore.FOREMAN_POINT_A.get());
                stack.remove(BannerboundCore.FOREMAN_POINT_B.get());
                return;
            }

            if (!"digger".equals(chosen) && !"farmer".equals(chosen) && !"herder".equals(chosen)
                && !"miner".equals(chosen) && !"guard".equals(chosen)) return;

            Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
            if (settlement == null) {
                player.displayClientMessage(Component.translatable(
                    "bannerbound.foremans_rod.not_in_settlement")
                    .withStyle(ChatFormatting.RED), true);
                return;
            }

            if (!com.bannerbound.core.api.research.ResearchManager.hasFlag(settlement,
                    com.bannerbound.core.api.settlement.WorkstationUnlocks.flagForUnit(chosen))) {
                player.displayClientMessage(Component.translatable(
                    "bannerbound.foremans_rod.not_researched").withStyle(ChatFormatting.RED), true);
                return;
            }
            stack.set(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get(), chosen);
        });
    }

    public static void handlePickPenAnimal(PickPenAnimalPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ItemStack stack = player.getMainHandItem();
            if (stack.isEmpty() || !stack.is(BannerboundCore.FOREMANS_ROD.get())) return;

            var clicked = payload.penPos();
            String animalId = payload.animalId();
            if (clicked == null || animalId == null || animalId.isEmpty()) return;

            var overworld = server.overworld();
            var level = player.serverLevel();
            Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
            if (settlement == null) {
                player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.not_in_settlement")
                    .withStyle(ChatFormatting.RED), true);
                return;
            }

            boolean allowed = com.bannerbound.core.item.ForemansRodItem.BASIC_PEN_ANIMALS.contains(animalId)
                || ("minecraft:horse".equals(animalId)
                    && com.bannerbound.core.territory.ChunkResources.typeAt(level,
                        new net.minecraft.world.level.ChunkPos(clicked))
                        == com.bannerbound.core.territory.ChunkResource.HORSES);
            if (!allowed) return;
            if (!com.bannerbound.core.item.ForemansRodItem.isFullyWithinTerritory(settlement, clicked, clicked)) {
                player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.outside_territory")
                    .withStyle(ChatFormatting.RED), true);
                return;
            }
            com.bannerbound.core.building.PenEnclosure.Result pen =
                com.bannerbound.core.building.PenEnclosure.scan(level, clicked);
            if (!pen.valid()) {
                player.displayClientMessage(Component.translatable(
                    com.bannerbound.core.item.ForemansRodItem.penFailKey(pen.reason()))
                    .withStyle(ChatFormatting.RED), true);
                return;
            }
            var selectionId = java.util.UUID.randomUUID();
            BlockSelection candidate = BlockSelection.workstation(selectionId, settlement.id(),
                settlement.color().ordinal(), clicked, clicked,
                com.bannerbound.core.item.ForemansRodItem.HERDER_TYPE, player.getUUID(),
                com.bannerbound.core.entity.HerderWorkGoal.packPen(animalId, 0));
            String targetStr = stack.get(BannerboundCore.FOREMAN_TARGET_CITIZEN.get());
            if (targetStr != null && !targetStr.isEmpty()) {
                try {
                    candidate = candidate.withAssignedCitizen(java.util.UUID.fromString(targetStr));
                } catch (IllegalArgumentException ignored) {  }
            }
            BlockSelectionRegistry registry = BlockSelectionRegistry.get(overworld);
            if (registry.anyOverlapExcluding(candidate, selectionId)) {
                player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.overlap")
                    .withStyle(ChatFormatting.RED), true);
                return;
            }
            registry.register(candidate);
            player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.pen_marked")
                .withStyle(ChatFormatting.GREEN), true);
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(server);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new ShowStockpileDebugPayload(
                new java.util.ArrayList<>(pen.interior()), java.util.List.of(), java.util.Optional.empty(), 200));
        });
    }

    public static void handleSetPenKeep(SetPenKeepPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ItemStack stack = player.getMainHandItem();
            if (stack.isEmpty() || !stack.is(BannerboundCore.FOREMANS_ROD.get())) return;
            var clicked = payload.penPos();
            if (clicked == null) return;
            var overworld = server.overworld();
            Settlement settlement = SettlementData.get(overworld).getByPlayer(player.getUUID());
            if (settlement == null) return;
            BlockSelectionRegistry registry = BlockSelectionRegistry.get(overworld);
            for (BlockSelection sel : registry.getForSettlement(settlement.id())) {
                if (sel.kind() != BlockSelection.Kind.WORKSTATION) continue;
                if (!com.bannerbound.core.item.ForemansRodItem.HERDER_TYPE.equals(sel.workstationType())) continue;
                if (sel.minX() != clicked.getX() || sel.minY() != clicked.getY() || sel.minZ() != clicked.getZ()) continue;
                String packed = sel.seedItemId();
                int keep = Math.max(0, payload.keep());

                var scan = com.bannerbound.core.building.PenEnclosure.scan(overworld, clicked);
                if (scan.valid()) {
                    var rl = net.minecraft.resources.ResourceLocation.tryParse(
                        com.bannerbound.core.entity.HerderWorkGoal.penAnimalId(packed));
                    net.minecraft.world.entity.EntityType<?> t = rl == null ? null
                        : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
                    int cap = com.bannerbound.core.building.PenEnclosure.stats(overworld, scan)
                        .capacity(com.bannerbound.core.entity.HerderWorkGoal.animalSize(t));
                    keep = Math.min(keep, cap);
                }
                registry.register(sel.withSeed(com.bannerbound.core.entity.HerderWorkGoal.packPen(
                    com.bannerbound.core.entity.HerderWorkGoal.penAnimalId(packed),
                    com.bannerbound.core.entity.HerderWorkGoal.penKills(packed), keep)));
                com.bannerbound.core.world.SelectionBroadcaster.broadcast(server);
                player.displayClientMessage(Component.translatable("bannerbound.foremans_rod.pen_keep_set",
                    keep == 0 ? Component.translatable("bannerbound.pen_keep.auto_short")
                              : Component.literal(String.valueOf(keep))).withStyle(ChatFormatting.GREEN), true);
                return;
            }
        });
    }

    public static void handleRequestExpandTerritory(RequestExpandTerritoryPayload payload,
                                                     IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            net.minecraft.server.level.ServerLevel overworld = server.overworld();
            SettlementData data = SettlementData.get(overworld);
            Settlement settlement = data.getByPlayer(player.getUUID());
            if (settlement == null || !settlement.hasTownHall()) {
                player.displayClientMessage(Component.translatable(
                    "bannerbound.territory.error.no_settlement")
                    .withStyle(ChatFormatting.RED), true);
                return;
            }

            OpenExpandTerritoryScreenPayload screen = com.bannerbound.core.api.territory.TerritoryService
                .buildScreenPayload(overworld, settlement, player);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, screen);
        });
    }

    public static void handleExpandTerritoryClaim(ExpandTerritoryClaimPayload payload,
                                                   IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            com.bannerbound.core.api.territory.TerritoryService.tryClaim(player, payload.packedChunkPos());
        });
    }

    public static void handlePickSeed(PickSeedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            net.minecraft.server.level.ServerLevel overworld = server.overworld();
            com.bannerbound.core.api.world.BlockSelectionRegistry registry =
                com.bannerbound.core.api.world.BlockSelectionRegistry.get(overworld);
            com.bannerbound.core.api.world.BlockSelection sel = registry.get(payload.rodId());
            if (sel == null) return;

            if (!player.getUUID().equals(sel.creatorId())) return;
            if (!"farmer".equals(sel.workstationType())) return;

            String chosen = payload.seedItemId();
            if (chosen == null || chosen.isEmpty()) {
                registry.unregister(sel.rodId());
                com.bannerbound.core.api.farmer.AwaitingSeedRegistry.unqueue(sel.rodId());
                com.bannerbound.core.world.SelectionBroadcaster.broadcast(overworld);
                return;
            }
            if (!com.bannerbound.core.farmer.SeedCandidates.isValid(chosen)) return;

            registry.register(sel.withSeed(chosen));
            com.bannerbound.core.api.farmer.AwaitingSeedRegistry.unqueue(sel.rodId());
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(overworld);
        });
    }

    public static void handleEditField(EditFieldPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            net.minecraft.server.level.ServerLevel overworld = server.overworld();
            com.bannerbound.core.api.world.BlockSelectionRegistry registry =
                com.bannerbound.core.api.world.BlockSelectionRegistry.get(overworld);
            com.bannerbound.core.api.world.BlockSelection sel = registry.get(payload.rodId());
            if (sel == null) return;
            if (sel.kind() != com.bannerbound.core.api.world.BlockSelection.Kind.WORKSTATION
                || !"farmer".equals(sel.workstationType())) return;

            com.bannerbound.core.api.settlement.Settlement settlement =
                com.bannerbound.core.api.settlement.SettlementData.get(overworld).getByPlayer(player.getUUID());
            if (settlement == null || !settlement.id().equals(sel.settlementId())) return;

            String seed = payload.seedItemId();
            if (seed == null || seed.isEmpty() || !com.bannerbound.core.farmer.SeedCandidates.isValid(seed)) return;

            java.util.UUID worker = payload.assignedCitizen();
            if (worker == null) worker = com.bannerbound.core.api.world.BlockSelection.NO_CITIZEN;
            if (!com.bannerbound.core.api.world.BlockSelection.NO_CITIZEN.equals(worker)) {
                boolean isFarmer = false;
                for (com.bannerbound.core.api.settlement.Citizen c : settlement.citizens()) {
                    if (!c.entityId().equals(worker)) continue;
                    if (overworld.getEntity(worker) instanceof com.bannerbound.core.entity.CitizenEntity ce
                        && com.bannerbound.core.entity.FarmerWorkGoal.JOB_TYPE_ID.equals(ce.getJobType())) {
                        isFarmer = true;
                    }
                    break;
                }
                if (!isFarmer) worker = com.bannerbound.core.api.world.BlockSelection.NO_CITIZEN;
            }

            registry.register(sel.withSeed(seed).withAssignedCitizen(worker));
            com.bannerbound.core.api.farmer.AwaitingSeedRegistry.unqueue(sel.rodId());
            com.bannerbound.core.world.SelectionBroadcaster.broadcast(overworld);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "bannerbound.foremans_rod.field_edited").withStyle(net.minecraft.ChatFormatting.GREEN), true);
        });
    }

    public static void handleRequestHomeCitizenList(RequestHomeCitizenListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData sd = SettlementData.get(server.overworld());
            Settlement settlement = sd.getByPlayer(player.getUUID());
            if (settlement == null) return;
            com.bannerbound.core.api.settlement.Home thisHome = settlement.getHomeById(payload.homeId());
            if (thisHome == null) return;

            java.util.List<HomeCitizenListPayload.Entry> entries = new java.util.ArrayList<>();
            for (Citizen c : settlement.citizens()) {
                com.bannerbound.core.api.settlement.Home current =
                    settlement.getHomeFor(c.entityId());
                HomeCitizenListPayload.Role role;
                int distance = 0;
                if (current == thisHome) {
                    role = HomeCitizenListPayload.Role.RESIDENT;
                } else if (current == null) {
                    role = HomeCitizenListPayload.Role.HOMELESS;
                } else {
                    role = HomeCitizenListPayload.Role.OTHER;

                    BlockPos a = current.pos();
                    BlockPos b = thisHome.pos();
                    distance = Math.max(Math.max(
                        Math.abs(a.getX() - b.getX()),
                        Math.abs(a.getY() - b.getY())),
                        Math.abs(a.getZ() - b.getZ()));
                }
                entries.add(new HomeCitizenListPayload.Entry(c.entityId(), c.name(), role, distance));
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new HomeCitizenListPayload(payload.homeId(), entries));
        });
    }

    public static void handleAssignCitizenToHome(AssignCitizenToHomePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData sd = SettlementData.get(server.overworld());
            Settlement settlement = sd.getByPlayer(player.getUUID());
            if (settlement == null) return;
            com.bannerbound.core.api.settlement.Home home = settlement.getHomeById(payload.homeId());
            if (home == null) return;

            java.util.UUID cid = payload.citizenId();
            if (cid == null) return;

            boolean inRoster = false;
            for (Citizen c : settlement.citizens()) {
                if (cid.equals(c.entityId())) { inRoster = true; break; }
            }
            if (!inRoster) return;

            if (payload.assign()) {
                if (!home.valid() || home.bedCount() <= 0) {
                    player.displayClientMessage(Component.translatable(
                        "bannerbound.house.assign.not_valid")
                        .withStyle(ChatFormatting.RED), true);
                    return;
                }
                if (home.residents().size() >= home.bedCount()
                    && !home.residents().contains(cid)) {
                    player.displayClientMessage(Component.translatable(
                        "bannerbound.house.assign.full")
                        .withStyle(ChatFormatting.RED), true);
                    return;
                }

                com.bannerbound.core.api.settlement.Home prior = settlement.getHomeFor(cid);
                if (prior != null && prior != home) {
                    wakeIfSleepingInHome(player.serverLevel(), cid, prior);
                    prior.removeResident(cid);
                }
                home.addResident(cid);
            } else {
                wakeIfSleepingInHome(player.serverLevel(), cid, home);
                home.removeResident(cid);
            }
            sd.setDirty();

            if (payload.fromHousePanel()) {
                com.bannerbound.core.item.HousingOrdersItem.refreshStatusPanel(
                    player, player.serverLevel(), home);
                return;
            }

            java.util.List<HomeCitizenListPayload.Entry> entries = new java.util.ArrayList<>();
            for (Citizen c : settlement.citizens()) {
                com.bannerbound.core.api.settlement.Home current =
                    settlement.getHomeFor(c.entityId());
                HomeCitizenListPayload.Role role;
                int distance = 0;
                if (current == home) {
                    role = HomeCitizenListPayload.Role.RESIDENT;
                } else if (current == null) {
                    role = HomeCitizenListPayload.Role.HOMELESS;
                } else {
                    role = HomeCitizenListPayload.Role.OTHER;
                    BlockPos a = current.pos();
                    BlockPos b = home.pos();
                    distance = Math.max(Math.max(
                        Math.abs(a.getX() - b.getX()),
                        Math.abs(a.getY() - b.getY())),
                        Math.abs(a.getZ() - b.getZ()));
                }
                entries.add(new HomeCitizenListPayload.Entry(c.entityId(), c.name(), role, distance));
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new HomeCitizenListPayload(home.id(), entries));
        });
    }

    private static void wakeIfSleepingInHome(net.minecraft.server.level.ServerLevel sl,
                                              java.util.UUID citizenId,
                                              com.bannerbound.core.api.settlement.Home home) {
        net.minecraft.world.entity.Entity raw = sl.getEntity(citizenId);
        if (!(raw instanceof com.bannerbound.core.entity.CitizenEntity citizen)) return;
        if (!citizen.isSleeping()) return;
        java.util.Optional<BlockPos> sleepingPos = citizen.getSleepingPos();
        if (sleepingPos.isEmpty()) return;
        BlockPos pos = sleepingPos.get();
        if (!com.bannerbound.core.api.settlement.HouseAppealData.unionContains(sl, home, pos)) return;
        citizen.stopSleeping();
        net.minecraft.world.level.block.state.BlockState bs = sl.getBlockState(pos);
        if (bs.getBlock() instanceof net.minecraft.world.level.block.BedBlock
            && bs.getValue(net.minecraft.world.level.block.BedBlock.PART)
                == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
            sl.setBlock(pos,
                bs.setValue(net.minecraft.world.level.block.BedBlock.OCCUPIED, false),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }

    public static final String HERALDRY_FLAG = "bannerbound.unlock.heraldry";

    public static void handleRequestBannerEditor(RequestBannerEditorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            Settlement mine = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
            if (mine == null) return;
            if (!ResearchManager.hasFlagEitherTree(mine, HERALDRY_FLAG)) {
                player.sendSystemMessage(Component.translatable("bannerbound.banner.editor.locked")
                    .withStyle(ChatFormatting.RED));
                return;
            }
            sendBannerEditor(player, mine);
        });
    }

    private static void sendBannerEditor(ServerPlayer player, Settlement settlement) {
        java.util.List<String> patterns = new java.util.ArrayList<>();
        java.util.List<Integer> colors = new java.util.ArrayList<>();
        for (Settlement.BannerLayer layer : settlement.bannerDesign()) {
            patterns.add(layer.patternId());
            colors.add(layer.colorId());
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new OpenBannerEditorPayload(settlement.color().ordinal(),
                ResearchManager.heraldryPointsEarned(settlement), patterns, colors));
    }

    public static void handleRequestBannerCopy(RequestBannerCopyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            net.minecraft.server.level.ServerLevel level = server.overworld();
            Settlement mine = SettlementData.get(level).getByPlayer(player.getUUID());
            if (mine == null) return;
            if (!ResearchManager.hasFlagEitherTree(mine, HERALDRY_FLAG)) {
                player.sendSystemMessage(Component.translatable("bannerbound.banner.editor.locked")
                    .withStyle(ChatFormatting.RED));
                return;
            }

            java.util.LinkedHashSet<net.minecraft.world.item.DyeColor> colors =
                new java.util.LinkedHashSet<>();
            colors.add(com.bannerbound.core.api.settlement.FactionBanner.dyeFor(mine.color()));
            for (Settlement.BannerLayer layer : mine.bannerDesign()) {
                colors.add(net.minecraft.world.item.DyeColor.byId(layer.colorId()));
            }
            java.util.List<ServerPlayer> noVoters = java.util.List.of();
            java.util.List<com.bannerbound.core.api.territory.ChunkClaimCost.ItemCost> costs =
                new java.util.ArrayList<>(colors.size());
            java.util.List<Component> missing = new java.util.ArrayList<>();
            for (net.minecraft.world.item.DyeColor dye : colors) {
                com.bannerbound.core.api.territory.ChunkClaimCost.ItemCost cost =
                    new com.bannerbound.core.api.territory.ChunkClaimCost.ItemCost(
                        net.minecraft.world.item.DyeItem.byColor(dye), 1);
                costs.add(cost);
                if (!com.bannerbound.core.territory.SettlementInventoryHelper.hasAll(
                        level, mine, noVoters, java.util.List.of(cost))) {
                    missing.add(Component.translatable("color.minecraft." + dye.getName()));
                }
            }
            if (!missing.isEmpty()) {
                player.sendSystemMessage(Component.translatable("bannerbound.banner.editor.copy_no_dyes",
                        net.minecraft.network.chat.ComponentUtils.formatList(
                            missing, Component.literal(", ")))
                    .withStyle(ChatFormatting.RED));
                return;
            }
            com.bannerbound.core.territory.SettlementInventoryHelper.consume(level, mine, noVoters, costs);
            net.minecraft.world.item.ItemStack copy =
                com.bannerbound.core.api.settlement.FactionBanner.designedItem(
                    mine, server.registryAccess(), 1);

            if (!player.getInventory().add(copy)) {
                player.drop(copy, false);
            }
            player.sendSystemMessage(Component.translatable("bannerbound.banner.editor.copy_given")
                .withStyle(mine.identityFormatting()));
        });
    }

    public static void handleSaveBannerDesign(SaveBannerDesignPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData data = SettlementData.get(server.overworld());
            Settlement mine = data.getByPlayer(player.getUUID());
            if (mine == null) return;
            if (!ResearchManager.hasFlagEitherTree(mine, HERALDRY_FLAG)) return;
            if (!canManageJobs(player, mine)) {
                player.sendSystemMessage(Component.translatable("bannerbound.banner.editor.no_permission")
                    .withStyle(ChatFormatting.RED));
                return;
            }
            java.util.List<String> patterns = payload.patterns();
            java.util.List<Integer> colors = payload.colors();

            if (patterns.size() != colors.size() || patterns.size() > 6) return;
            if (patterns.size() > ResearchManager.heraldryPointsEarned(mine)) {
                player.sendSystemMessage(Component.translatable("bannerbound.banner.editor.no_points")
                    .withStyle(ChatFormatting.RED));
                return;
            }
            net.minecraft.core.Registry<net.minecraft.world.level.block.entity.BannerPattern> reg =
                server.overworld().registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.BANNER_PATTERN);
            java.util.List<Settlement.BannerLayer> layers = new java.util.ArrayList<>();
            for (int i = 0; i < patterns.size(); i++) {
                net.minecraft.resources.ResourceLocation rl =
                    net.minecraft.resources.ResourceLocation.tryParse(patterns.get(i));
                if (rl == null || reg.getOptional(rl).isEmpty()) return;
                int colorId = colors.get(i);
                if (colorId < 0 || colorId > 15) return;
                layers.add(new Settlement.BannerLayer(rl.toString(), colorId));
            }
            mine.setBannerDesign(layers);

            if (layers.isEmpty()) {
                mine.setIdentityDyes(java.util.List.of());
            } else {
                java.util.List<Integer> dyeIds = new java.util.ArrayList<>();
                for (net.minecraft.world.item.DyeColor dye :
                        com.bannerbound.core.api.settlement.FactionBanner.identityDyes(
                            com.bannerbound.core.api.settlement.FactionBanner.dyeFor(mine.color()),
                            layers)) {
                    dyeIds.add(dye.getId());
                }
                mine.setIdentityDyes(dyeIds);
            }
            data.setDirty();

            com.bannerbound.core.api.settlement.FactionBanner.applyDesignToBlock(
                server.overworld(), mine);

            for (java.util.UUID memberId : mine.members()) {
                ServerPlayer member = server.getPlayerList().getPlayer(memberId);
                if (member != null) {
                    SettlementManager.applyScoreboardTeam(server, member, mine);
                }
            }
            for (com.bannerbound.core.api.settlement.Citizen citizen : mine.citizens()) {
                if (server.overworld().getEntity(citizen.entityId())
                        instanceof com.bannerbound.core.entity.CitizenEntity entity) {
                    entity.refreshNameColor();
                }
            }
            SettlementManager.broadcastIdentity(server);
            player.sendSystemMessage(Component.translatable("bannerbound.banner.editor.saved")
                .withStyle(mine.identityFormatting()));

            sendBannerEditor(player, mine);
        });
    }
}
