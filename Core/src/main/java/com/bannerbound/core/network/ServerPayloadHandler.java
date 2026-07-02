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

@ApiStatus.Internal
public final class ServerPayloadHandler {
    private ServerPayloadHandler() {
    }

    /** Helper: returns true (after sending a red toast) if {@code player} is a non-Chief in a
     *  Chiefdom and therefore can't act as Chief for routine research actions. Returns false
     *  in Council / NONE governments — those have no Chief gate. Used to defend the
     *  start-research + enqueue-research endpoints against spoofed packets. */
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

    /** Client asked for the starting-items set once it was fully in-world — (re)send it. A reliable
     *  pull to back up the {@code OnDatapackSyncEvent} push, which can miss during the join handshake
     *  and leave the client's known-set empty (JEI then shows a wall of "?"). */
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
            // Chiefdom gate: only the seated Chief may even *initiate* the disband vote.
            // Council + NONE governments are unaffected (canActWeighty returns true for any
            // member in those cases).
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
            // Decode ordinal → enum; reject NONE / out-of-range silently (a tampered packet
            // shouldn't be able to "vote for anarchy" or crash the server).
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
                    // Structurally open but cooling down after apostasy.
                    player.sendSystemMessage(Component.translatable("bannerbound.faith.cooldown")
                        .withStyle(ChatFormatting.YELLOW));
                }
                return;
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                com.bannerbound.core.api.faith.FaithManager.buildScreenPayload(server, settlement, player));
        });
    }

    /** Non-Chief member of a Chiefdom clicked a research node. Instead of starting/queueing
     *  the research, broadcast a suggestion chat to the seated Chief so they can decide.
     *  No state mutation here — the Chief still has to act on the suggestion. */
    public static void handleSuggestResearch(SuggestResearchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            SettlementData data = SettlementData.get(server.overworld());
            Settlement settlement = data.getByPlayer(player.getUUID());
            if (settlement == null) return;
            // Only meaningful in CHIEFDOM with an actual seated Chief. Council members can
            // start research themselves, so a suggestion makes no sense there; silently drop.
            if (settlement.governmentType() != Settlement.Government.CHIEFDOM
                    || settlement.chiefPlayerId() == null) {
                return;
            }
            String researchId = payload.researchId();
            if (researchId == null || researchId.isBlank()) return;
            // Toggle: if the player already had this node suggested, the click RETRACTS it.
            // Per design: no chat broadcasts on vote/suggest actions — the on-screen marker
            // update (broadcast below) IS the feedback. Avoids "X suggested Y" spam.
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
            // Chiefdom gate: non-Chiefs cannot enqueue, must use suggest. Defense in depth —
            // the client routes their click as a suggestion, but a spoofed packet would skip.
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

    /** Step 9 polish: re-send compliance + resentment for an open citizen screen so values
     *  tick live. The client polls this every ~20 ticks while a CitizenScreen is open;
     *  server resolves the entity, validates the player can view it (member of same
     *  settlement), then sends a CitizenLiveStatePayload. */
    public static void handleRequestCitizenLiveState(RequestCitizenLiveStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            net.minecraft.world.entity.Entity ent = player.serverLevel().getEntity(payload.entityId());
            if (!(ent instanceof com.bannerbound.core.entity.CitizenEntity c)) return;
            // Access: the requester must be in the same settlement OR be an admin (creative).
            SettlementData data = SettlementData.get(server.overworld());
            Settlement viewer = data.getByPlayer(player.getUUID());
            if (viewer == null || c.getSettlementId() == null
                    || !viewer.id().equals(c.getSettlementId())) {
                return;
            }
            // Filter to the viewer's own resentment value — same privacy rule as the
            // open payload (citizens don't expose how they feel about OTHER players).
            int viewerResentment = c.getResentment(player.getUUID());
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new com.bannerbound.core.network.CitizenLiveStatePayload(
                    payload.entityId(), c.getCompliance(), viewerResentment));
            // Piggyback the Job-tab state on the same 1 Hz poll so it stays fresh while open.
            sendJobState(player, c);
        });
    }

    /** Step 15: the seated Chief is stepping down. Clears {@code chiefPlayerId} (which
     *  re-opens the chief-election window), removes them from the chief scoreboard team,
     *  and triggers a regent recompute. */
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
            // Only the actual seated Chief may quit — a regent stepping down has no effect
            // on the seat (they were never IN the seat); a non-Chief click here is a spoof.
            if (!player.getUUID().equals(settlement.chiefPlayerId())) {
                player.sendSystemMessage(Component.translatable(
                        "bannerbound.chief.quit.not_chief")
                    .withStyle(ChatFormatting.RED));
                return;
            }
            // Anti-cheese: a freshly-elected chief must serve a minimum term before resigning.
            // The button greys out with a live countdown; this re-check stops a spoofed packet.
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
            // Demote the ex-chief from the chief scoreboard team — back to the regular
            // settlement team. The scoreboard team itself can stick around (empty) until the
            // next chief is elected; vanilla cleans it via removeSettlementTeams on disband.
            com.bannerbound.core.api.settlement.SettlementManager
                .applyScoreboardTeam(server, player, settlement);
            data.setDirty();
            com.bannerbound.core.api.settlement.SettlementManager.broadcastToSettlement(
                server, settlement,
                Component.translatable("bannerbound.chief.quit.broadcast", player.getName())
                    .withStyle(ChatFormatting.GOLD));
            // Regent recompute: with the seat vacant, an online member can step in until a
            // new chief is elected. Even the just-resigned ex-chief might end up as their
            // own settlement's regent if they're the least-resented member online.
            com.bannerbound.core.api.settlement.SettlementManager.recomputeRegent(server, settlement);
            // Push refreshed town-hall state to every member so the choose-chief flow opens
            // and the seated-Chief button (which we just clicked) clears.
            com.bannerbound.core.api.settlement.ImmigrationManager.broadcastState(server, settlement);
        });
    }

    /**
     * An individual member leaving their settlement (Town Hall "Leave Settlement" button). A seated
     * Chief is refused — they must Step Down first (and serve the minimum term); the UI shows them
     * Step Down in this slot instead of Leave, and this server check stops a spoofed packet.
     * Everyone else runs {@link com.bannerbound.core.api.settlement.SettlementManager#tryLeave},
     * which sends its own success message and collapses the settlement if they were the last member.
     */
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
            // Only respond if the workstation is actually owned by this player's settlement.
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

            // Clear any *other* station this citizen used to work — one job per citizen.
            java.util.UUID assigning = payload.citizenId();
            if (assigning != null && assigning.getMostSignificantBits() != 0L) {
                // Anarchy gate: with no code of laws, citizens don't obey assignments yet.
                // The freelance AnarchyWorkGoal handles their behaviour during this window.
                if (settlement.governmentType() == com.bannerbound.core.api.settlement.Settlement.Government.NONE) {
                    player.displayClientMessage(Component.translatable(
                        "bannerbound.workstation.anarchy_rejection")
                        .withStyle(ChatFormatting.RED), true);
                    return;
                }
                // Chiefdom: assigning jobs is a chief-only (or regent) action — UNLESS the
                // Workload Share policy is active, which opens assignment to every member.
                if (settlement.governmentType() == com.bannerbound.core.api.settlement.Settlement.Government.CHIEFDOM
                        && !settlement.canActAsChief(player.getUUID())
                        && !settlement.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.WORKLOAD_SHARE)) {
                    player.displayClientMessage(Component.translatable(
                        "bannerbound.workstation.chief_only")
                        .withStyle(ChatFormatting.RED), true);
                    return;
                }
                // Reject child assignment server-side — the client picker shouldn't list them,
                // but a tampered packet shouldn't be able to put a kid at a workstation either.
                if (player.serverLevel().getEntity(assigning)
                        instanceof com.bannerbound.core.entity.CitizenEntity ce && ce.isChild()) {
                    player.displayClientMessage(Component.translatable(
                        "bannerbound.workstation.child_cannot_work")
                        .withStyle(ChatFormatting.RED), true);
                    return;
                }
                // Step 12: compliance-driven refusal. Look up the live citizen entity to
                // read compliance + roll the refuseWorkstation table; on hit, stamp a
                // NO_WORK_AS_JOB thought keyed by the workstation's job id so the citizen
                // visibly refuses for a minute and the leader sees the rejection.
                if (player.serverLevel().getEntity(assigning)
                        instanceof com.bannerbound.core.entity.CitizenEntity refusingCitizen) {
                    int compliance = refusingCitizen.getCompliance();
                    double refuseChance = com.bannerbound.core.api.settlement.ComplianceTables
                        .refuseWorkstation(compliance);
                    if (refuseChance > 0 && refusingCitizen.getRandom().nextDouble() < refuseChance) {
                        // Per-job key so refusing a Forester assignment doesn't poison Farmer
                        // assignments — the per-partner UUID is derived from the workstation
                        // type/id string for stable identity.
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

    /** Exile routing by government: anarchy = not allowed (no authority exists to banish anyone);
     *  council = clickable chat-vote (majority of online); chiefdom = the seated chief exiles
     *  directly, any other member toggles a SUGGESTION the chief sees in the Suggestions tab. */
    /** A player's choice in a barbarian parley (accept demands / refuse / trade). */
    public static void handleBarbarianParleyAction(BarbarianParleyActionPayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.bannerbound.core.barbarian.MessengerManager.handleAction(player, payload);
            }
        });
    }

    /** A player's move in a barbarian barter (propose / decline / defer). */
    public static void handleBarterAction(BarterActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                com.bannerbound.core.barbarian.MessengerManager.handleBarter(player, payload);
            }
        });
    }

    /** The open barter screen polling for a fresh storage snapshot (for live grey-out). */
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
            // Only members of the citizen's settlement can exile.
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

    /** Actually removes a citizen from the settlement (chief direct-exile or a passed council
     *  vote). Resolves the entity by UUID when loaded (returns its tool, despawns it); the roster
     *  entry is removed either way so a vote passing on an unloaded citizen still sticks. */
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
            // Hand any held job tool back before the citizen is removed (drop-off, else feet).
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

    /** Chat ping to the seated chief and the acting regent (when online) about a new suggestion. */
    private static void pingChief(MinecraftServer server, Settlement settlement, Component msg) {
        java.util.Set<java.util.UUID> targets = new java.util.LinkedHashSet<>();
        if (settlement.chiefPlayerId() != null) targets.add(settlement.chiefPlayerId());
        if (settlement.regentPlayerId() != null) targets.add(settlement.regentPlayerId());
        for (java.util.UUID t : targets) {
            ServerPlayer p = server.getPlayerList().getPlayer(t);
            if (p != null) p.sendSystemMessage(msg);
        }
    }

    // ─── Job tab (per-citizen employment) ────────────────────────────────────────────────────────

    /** True if {@code player} may manage jobs in {@code settlement}: any council member or the
     *  chief/regent (via {@code canActAsChief}), or any member while the Workload Share policy is
     *  active. Casual non-chiefs in a chiefdom are denied.
     *  <p>In <b>anarchy</b> (no government) any settlement member may make the narrow refinements the
     *  self-organizing phase allows — set a drop-off, request a gatherer job switch, hand over a tool.
     *  The handlers themselves restrict what's possible there (no free assignment of ordered jobs, no
     *  Foreman's-Rod orders); this just opens the door for members so the Job tab is usable. */
    private static boolean canManageJobs(ServerPlayer player, Settlement settlement) {
        if (settlement == null) return false;
        if (settlement.governmentType() == Settlement.Government.NONE) {
            return settlement.members().contains(player.getUUID());
        }
        return settlement.canActAsChief(player.getUUID())
            || settlement.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.WORKLOAD_SHARE);
    }

    /** Implemented job type ids, in dropdown order. Add each here as it's migrated to the Job tab. */
    private static final String[] IMPLEMENTED_JOBS = {
        com.bannerbound.core.entity.ForesterWorkGoal.JOB_TYPE_ID,
        com.bannerbound.core.entity.DiggerWorkGoal.JOB_TYPE_ID,
        com.bannerbound.core.entity.FarmerWorkGoal.JOB_TYPE_ID,
        com.bannerbound.core.entity.FisherWorkGoal.JOB_TYPE_ID,
        com.bannerbound.core.entity.ForagerWorkGoal.JOB_TYPE_ID,
        com.bannerbound.core.entity.HerderWorkGoal.JOB_TYPE_ID,
    };

    /** Research-unlocked job type ids this settlement may run (gated by each job's unlock flag).
     *  Built-in jobs first, then {@link com.bannerbound.core.api.job.CitizenJobRegistry registry} jobs
     *  (expansion gatherers). A registry job that has been superseded by a researched successor
     *  ({@code obsoletedByUnit}, e.g. spear fisher once the rod fisher unlocks) is hidden so it can no
     *  longer be assigned. */
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

    /** Jobs the citizen Job tab may offer directly. Specific crafter specializations, such as
     *  Potter and Carpenter, stay registered for workshop auto-selection but are not picker rows. */
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

    /** Station type a citizen's generic-Crafter icon should show: its held station POSITION when
     *  one is set (so a fletcher in a mixed workshop reads as a fletcher, not the workshop's MIXED
     *  type), else the workshop's derived type. {@code null} when it has no workshop — JobIcons then
     *  defaults the crafter to the crafting stone. */
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
        // A single generic Crafter staffs every workshop; its specialties (fletcher, carpenter,
        // potter, …) are the crafter-profession units declared per workshop type in
        // WorkBlockRegistry. The job is offered once ANY one of them is researched.
        String genericFlag = com.bannerbound.core.api.settlement.WorkstationUnlocks
            .flagForWorkstation(com.bannerbound.core.entity.CrafterWorkGoal.JOB_TYPE_ID);
        if (genericFlag != null && ResearchManager.hasFlag(settlement, genericFlag)) return true;
        for (String unit : com.bannerbound.core.api.workshop.WorkBlockRegistry.crafterUnits()) {
            String flag = com.bannerbound.core.api.settlement.WorkstationUnlocks.flagForUnit(unit);
            if (ResearchManager.hasFlag(settlement, flag)) return true;
        }
        return false;
    }

    // Job-icon resolution — role mapping, the current-tool-age item, and the numeric icon id —
    // lives in com.bannerbound.core.social.JobIcons, shared with the citizen name-tag suffix glyph
    // so the JOB bubble and the name can never disagree about a job's icon (the herder-had-no-icon
    // gap came from two such lists drifting apart). The herder's ICON is a vanilla lead; its tool
    // SLOT accepts any item in #bannerbound:herder_rope (vanilla lead standalone, +fiber rope with
    // Antiquity — the same tag HerderWorkGoal gates on), resolved in allowedToolItemIds below.

    /** Item ids a job's tool slot accepts (current tool age or lower). Delegates to the shared
     *  {@link com.bannerbound.core.entity.JobTools#allowedToolsFor} so the Job-tab slot and the
     *  settlement's remote tool-provisioning never disagree about what counts as a valid tool. */
    private static java.util.List<Integer> allowedToolItemIds(Settlement settlement, String role) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (net.minecraft.world.item.Item tool
                : com.bannerbound.core.entity.JobTools.allowedToolsFor(settlement, role)) {
            out.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(tool));
        }
        return out;
    }

    /** Resolves the citizen + the viewer's settlement, verifies same-settlement, and sends the
     *  Job-tab state. Shared by the open path and the live poll. No-op if anything doesn't resolve. */
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
        // In anarchy the assign/switch list offers only the self-organizing gatherer jobs — ordered
        // and logistics jobs are locked until a government is enacted.
        boolean anarchy = settlement.governmentType() == Settlement.Government.NONE;
        java.util.List<String> unlocked = anarchy
            ? com.bannerbound.core.entity.AnarchyJobs.unlockedGathererJobs(settlement)
            : jobPickerJobTypeIds(settlement);
        java.util.List<Integer> unlockedIcons = new java.util.ArrayList<>(unlocked.size());
        for (String t : unlocked) unlockedIcons.add(com.bannerbound.core.social.JobIcons.iconItemId(settlement, t));
        // The citizen's own bubble: a generic Crafter shows the icon of the workshop it staffs, so
        // its specialty (fletchery/carpentry/pottery/…) reads even though all crafters share one id.
        int jobIcon = jobType.isEmpty() ? 0 : com.bannerbound.core.social.JobIcons.iconItemId(
            settlement, jobType, assignedWorkshopType(settlement, citizen));
        java.util.List<Integer> allowedTools =
            allowedToolItemIds(settlement, com.bannerbound.core.social.JobIcons.roleForJob(jobType));
        // Quarry research: gates the quarryworker's pickaxe slot AND the Digger→Quarryworker rename.
        // Settlement-level (not tied to this citizen's job) so the assign dropdown can show the upgraded
        // name too; the pickaxe slot itself is still drawn only for an actual digger citizen.
        boolean pickaxeUnlocked = ResearchManager.hasFlag(settlement, "bannerbound.unlock_quarry");
        int pickaxeItemId = citizen.hasJobPickaxe()
            ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(citizen.getJobPickaxe().getItem())
            : 0;
        java.util.List<Integer> allowedPickaxes = pickaxeUnlocked
            ? allowedToolItemIds(settlement, "pickaxe") : java.util.List.of();
        // Seed-cache contents for the farmer Job-tab display (non-empty slots only, as item id + count).
        java.util.List<Integer> cacheIds = new java.util.ArrayList<>();
        java.util.List<Integer> cacheCounts = new java.util.ArrayList<>();
        net.minecraft.world.SimpleContainer seedCache = citizen.getSeedCache();
        for (int i = 0; i < seedCache.getContainerSize(); i++) {
            ItemStack s = seedCache.getItem(i);
            if (s.isEmpty()) continue;
            cacheIds.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(s.getItem()));
            cacheCounts.add(s.getCount());
        }
        // Workshop extras: the bound workshop + profession XP for the Job tab's workshop/skill rows.
        // XP is keyed by the workshop's derived type (the profession — e.g. "fletchery"), so a
        // mixed workshop shows 0 until a single-family profession is read.
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
                // Skill follows the citizen's STATION POSITION when one is held (mixed workshops:
                // a positioned fletcher's bar tracks "fletchery", not the workshop's MIXED type).
                String position = workshop.positionOf(citizen.getUUID());
                String fixed = com.bannerbound.core.entity.CrafterWorkGoal.workshopTypeForJob(jobType);
                jobXp = (int) citizen.getJobXp(
                    fixed != null ? fixed : position != null ? position : workshop.derivedTypeId());
            }
        }
        // Stocker extras: the settlement task board, queue order, with each row's destination
        // name and claim state (this citizen's own haul renders highlighted).
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
        // Silviculture research: gates the forester's "Select plantation area" Options row (mirrors
        // the Quarry/pickaxe gate above). Settlement-level, like pickaxeUnlocked.
        boolean foresterPlantationUnlocked =
            ResearchManager.hasFlag(settlement, "bannerbound.unlock_forester_plantation");
        // Job-tab status headline. A goal with meaningful live sub-states (currently only the
        // plantation goal) publishes them on the citizen; for everything else we derive a verdict
        // from observable facts here — no per-goal instrumentation needed. Hard blockers
        // (stamina) win over a stale published value; the plantation goal clears itself to IDLE in
        // stop(), so a non-IDLE published value is always current.
        com.bannerbound.core.entity.CitizenWorkStatus workStatus;
        com.bannerbound.core.entity.CitizenWorkStatus published = citizen.getCurrentWorkStatus();
        if (jobType.isEmpty()) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.IDLE;
        } else if (citizen.isSleeping()) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.SLEEPING;
        } else if (!settlement.hasFactionBanner()) {
            // Banner down → ALL labor halts settlement-wide until it's raised again.
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.BANNER_DOWN;
        } else if (citizen.isPregnant()) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.EXPECTING;
        } else if (!anarchy && com.bannerbound.core.entity.WorkGoal.isAfternoonGathering(citizen)) {
            // The pre-bed social window (dusk): work yields so citizens gather and chat.
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.SOCIALIZING;
        } else if (citizen.isStaminaExhausted()) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.NO_STAMINA;
        } else if (!anarchy && com.bannerbound.core.entity.WorkGoal.hasRefusalThought(citizen)) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.ON_STRIKE;
        } else if (published != com.bannerbound.core.entity.CitizenWorkStatus.IDLE) {
            // A goal published a live sub-state (plantation states, crafter "nothing to craft", …).
            workStatus = published;
        } else if (com.bannerbound.core.entity.CrafterWorkGoal.isWorkshopJob(jobType)
                && workshopId.isEmpty()) {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.NO_WORKSHOP;
        } else if (!anarchy && com.bannerbound.core.social.JobIcons.requiresTool(jobType)
                && !citizen.hasJobTool()) {
            // Foragers (and any tool-free role) never read as NO_TOOL; nor does anyone in anarchy,
            // where gatherers work tool-free. Without this, tool-free workers showed "no tool".
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.NO_TOOL;
        } else if (!anarchy && isDropOffFull(citizen)) {
            // The worker's depot (its marked drop-off, or the settlement's preferred-storage fallback)
            // has no free slot → it can't deposit and has stopped (the same hasFreeSlot gate the
            // gatherers themselves use). Important to surface: from the Job tab the citizen otherwise
            // looks like it's "Working" when it's actually stalled.
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.STORAGE_FULL;
        } else {
            workStatus = com.bannerbound.core.entity.CitizenWorkStatus.WORKING;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new CitizenJobStatePayload(citizen.getId(), canManageJobs(player, settlement),
                jobType, jobIcon, citizen.hasJobTool(), toolItemId,
                logId == null ? "" : logId.toString(),
                // "has somewhere to deposit / take seeds": now reflects the settlement storage pool
                // (or an outpost/anarchy override), not a per-worker marked container.
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
                // Outpost-managed storage: the citizen's current work site is a working-claimed
                // chunk, so the outpost (not the Job tab) decides the drop-off.
                citizen.getOutpostSite() != null && settlement.workingClaims().contains(
                    new net.minecraft.world.level.ChunkPos(citizen.getOutpostSite()).toLong()),
                workStatus.ordinal(), foresterPlantationUnlocked,
                citizen.isTradingCourier()));
    }

    /** True when the citizen's depot — its marked drop-off, or the settlement's preferred-storage
     *  fallback when none is marked — resolves to a container with no free slot, so the worker can't
     *  deposit and is stalled. Mirrors the {@code hasFreeSlot} gate the gatherers use, so the Job-tab
     *  "Storage full" verdict matches their actual stop condition. */
    private static boolean isDropOffFull(com.bannerbound.core.entity.CitizenEntity citizen) {
        net.minecraft.world.Container c = com.bannerbound.core.entity.DropOffContainers
            .resolveOrPreferred(citizen, citizen.getDropOff());
        return c != null && !com.bannerbound.core.entity.DropOffContainers.hasFreeSlot(c);
    }

    /** Reopens the citizen screen after an in-world storage marking succeeds, so the player can
     *  continue configuring the same worker without walking back and right-clicking them again. */
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
            // Show the CURRENT (escalated) modifier so a festering grievance reads "-28" not
            // its day-one "-5". Matches what the happiness number actually reflects.
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

    /** Outpost Banner screen: appoint a specific miner to the outpost (binds the banner-owned
     *  deposit marker to them) or recall ({@code citizenUuid == ""} → marker removed). Validates
     *  membership, claim ownership, labor permission and the target's job, then replies with a
     *  fresh screen payload so the open UI live-updates. */
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
                    // Never fail silently — the candidate unloaded/died/changed job since the
                    // screen was sent; tell the player and refresh so the stale row disappears.
                    player.displayClientMessage(Component.translatable("bannerbound.outpost.candidate_gone")
                        .withStyle(ChatFormatting.RED), true);
                    com.bannerbound.core.api.settlement.Outpost.openScreen(sl, player, payload.bannerPos());
                    return;
                }
                // Appointment works regardless, but a too-soft pickaxe means the miner will idle
                // at the boulder (vanilla harvest-tier gate) — warn the player up front.
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

    /** "Establish outpost here" from the banner screen: validate + grant the working claim on the
     *  banner's chunk, then reply with the management screen (or, on failure, the establish screen
     *  again with the reason). The banner block itself is plain decoration until this confirms. */
    public static void handleEstablishOutpost(EstablishOutpostPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            net.minecraft.server.level.ServerLevel sl = player.serverLevel();
            if (!com.bannerbound.core.api.settlement.FactionBanner.isBanner(
                    sl.getBlockState(payload.bannerPos()))) {
                return; // the banner was removed between opening the screen and confirming
            }
            String failKey = com.bannerbound.core.api.settlement.Outpost.tryEstablish(
                sl, player, payload.bannerPos());
            if (failKey != null) {
                // Two args so every failure key formats: %1$s = range (too_far), %2$s = cap (limit).
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

    /** Resolves a citizen by entity id, requires the player to be in the citizen's settlement AND
     *  permitted to manage jobs; returns the citizen or null (after no-op) otherwise. */
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
                // Unassign: return the tool, then clear the job. Not permitted in anarchy — there's
                // no government to leave a citizen idle and they'd just auto-re-employ; the player can
                // only request a switch to another gatherer job, not make them jobless.
                if (anarchy) { sendJobState(player, citizen); return; }
                citizen.returnJobToolAndClear();
                citizen.setJobType(null);
            } else {
                // Children can't work; reject a tampered packet that tries to employ one.
                if (citizen.isChild()) return;
                // In anarchy only the self-directed gatherer jobs may be chosen — ordered (digger/
                // farmer) and logistics/herder jobs need a government and stay locked.
                if (anarchy && !com.bannerbound.core.entity.AnarchyJobs.isGathererJob(typeId)) return;
                if (!jobPickerJobTypeIds(settlement).contains(typeId)) return;
                // Compliance-driven assignment refusal — the same roll the old workstation handler
                // used, now applied to the citizen-menu jobs (forester/digger/farmer/fisher/forager).
                // A low-compliance citizen may reject being put to work: stamp a per-job
                // NO_WORK_AS_JOB thought (WorkGoal honours it so they visibly sit out for a minute),
                // tell the leader, and DON'T assign. The leader can try again — each attempt re-rolls.
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
                // Crafters bind to a WORKSHOP, not a drop-off — the job isn't assigned here.
                // Open the workshop picker instead; picking one sends AssignWorkshopWorkerPayload,
                // which performs the real assignment (job + binding + roster, capacity-checked).
                // The compliance roll above already ran, so the citizen has agreed to the work.
                if (com.bannerbound.core.entity.CrafterWorkGoal.isWorkshopJob(typeId)) {
                    WorkshopMenu.openPicker(player, player.serverLevel(), settlement, citizen, typeId);
                    return;
                }
                citizen.setJobType(typeId);
                // An explicit player assignment PINS the citizen so the settlement labor distributor
                // leaves them alone (a manual override). The "Auto" button releases it.
                citizen.setJobPinned(true);
                CodexManager.onCustom(player, "job_assigned", typeId);
            }
            sendJobState(player, citizen);
        });
    }

    /** "Auto" button: release a citizen back to settlement labor auto-distribution. */
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
            // Pickaxe (second) slot is gated by the Quarry research; primary uses the job's role.
            boolean pickaxe = payload.pickaxe();
            if (pickaxe && !(com.bannerbound.core.entity.DiggerWorkGoal.JOB_TYPE_ID.equals(citizen.getJobType())
                    && ResearchManager.hasFlag(settlement, "bannerbound.unlock_quarry"))) {
                return;
            }
            String role = pickaxe ? "pickaxe" : com.bannerbound.core.social.JobIcons.roleForJob(citizen.getJobType());
            // The tool must be the right role tool at/below the settlement's current tool age.
            if (!allowedToolItemIds(settlement, role).contains(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(inSlot.getItem()))) {
                player.displayClientMessage(Component.translatable(
                    "bannerbound.job.tool_too_advanced").withStyle(ChatFormatting.RED), true);
                return;
            }
            // Return any existing tool in that slot to the player first.
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
                // Prefer returning to the player's hands; fall back to drop-off / feet.
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
            // Whitelist actual logs, mirroring the old forester picker's filter.
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
            // A LOCKED category (not yet research-unlocked) can't be switched on — only off.
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
            // Only ids actually in the huntable tag are accepted — rejects tampered packets and
            // keeps the disabled set from accumulating junk after a datapack removes a species.
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

    /** The Foreman's-Rod unit string for an ORDERED job that marks work areas (digger/farmer), or
     *  {@code null} for jobs that don't use the rod. */
    public static String rodTypeForJob(String jobType) {
        if (com.bannerbound.core.entity.DiggerWorkGoal.JOB_TYPE_ID.equals(jobType))
            return com.bannerbound.core.entity.DiggerWorkGoal.SELECTION_TYPE;
        if (com.bannerbound.core.entity.FarmerWorkGoal.JOB_TYPE_ID.equals(jobType))
            return com.bannerbound.core.entity.FarmerWorkGoal.SELECTION_TYPE;
        if (com.bannerbound.core.entity.HerderWorkGoal.JOB_TYPE_ID.equals(jobType))
            return com.bannerbound.core.entity.HerderWorkGoal.SELECTION_TYPE;
        if (com.bannerbound.core.entity.MinerWorkGoal.JOB_TYPE_ID.equals(jobType))
            return com.bannerbound.core.entity.MinerWorkGoal.SELECTION_TYPE;
        // Forester plantation area (Silviculture-gated; the gate is enforced at bind time so the
        // type can still resolve here for the Job-tab "Select plantation area" affordance).
        if (com.bannerbound.core.entity.ForesterWorkGoal.JOB_TYPE_ID.equals(jobType))
            return com.bannerbound.core.entity.ForesterWorkGoal.SELECTION_TYPE;
        // Guard → guard-post markers (shift-right-click a guard to pin posts to just them).
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
            if (rodType == null) return;   // only the rod-driven ordered workers bind to a citizen
            // Forester plantations are research-gated: refuse the bind (and tell the player) until
            // Silviculture is done. Other rod jobs have no such gate.
            if (com.bannerbound.core.entity.ForesterWorkGoal.JOB_TYPE_ID.equals(citizen.getJobType())) {
                Settlement fs = citizen.getSettlement();
                if (fs == null || !ResearchManager.hasFlag(fs, "bannerbound.unlock_forester_plantation")) {
                    player.displayClientMessage(Component.translatable(
                        "bannerbound.citizen.job.plantation_locked").withStyle(ChatFormatting.RED), true);
                    return;
                }
            }
            // Find a Foreman's Rod in the player's inventory and point it at this worker.
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
            // Track edit mode server-side so DropLocationServerGuard suppresses the chest-open on
            // the server thread too (matters for the single-player integrated server).
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

    /**
     * Marks {@code pos} as the drop-off for the citizen, server-authoritatively. Called from
     * {@link com.bannerbound.core.event.DropLocationServerGuard} when the editing player right-clicks
     * a block. Returns {@code true} if the mark succeeded (edit mode should end), {@code false} if
     * the block was rejected (a non-storage / out-of-claim block — the player stays in edit mode and
     * can try again). Always sends the player the appropriate green / red action-bar feedback.
     */
    public static boolean markDropOff(ServerPlayer player, int citizenEntityId, BlockPos pos) {
        return markStorage(player, citizenEntityId, pos, false);
    }

    /**
     * Marks {@code pos} as either the citizen's harvest drop-off ({@code seed == false}) or the
     * farmer's seed source ({@code seed == true}), server-authoritatively. Returns {@code true} if the
     * mark succeeded or is moot (edit mode should end), {@code false} if the block was rejected (the
     * player stays in edit mode and can try another block). Sends green / red action-bar feedback.
     */
    /** True only if the storage block at {@code pos} is a container type the settlement has actually
     *  unlocked. Stops a player pointing workers at an un-researched container (stockpile, chest,
     *  barrel, basket, …). Sends red feedback and returns false when it isn't known yet. */
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
        if (!(raw instanceof com.bannerbound.core.entity.CitizenEntity citizen)) return true; // citizen gone → end
        if (citizen.getSettlementId() == null) return true;
        MinecraftServer server = player.getServer();
        if (server == null) return true;
        Settlement settlement = SettlementData.get(server.overworld()).getById(citizen.getSettlementId());
        if (settlement == null || !canManageJobs(player, settlement)) return true;
        // Working claims (outpost chunks) count as valid drop-off territory — that's how a remote
        // miner's chest at the outpost gets marked.
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

    /** Town Hall Labor-tab "Preferred storage" button: drop the player into the same click-a-block
     *  edit mode the per-citizen drop-off uses, but with the settlement-level sentinel target. */
    public static void handleBeginEditPreferredStorage(BeginEditPreferredStoragePayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
            // Government-only, and only for a member who may edit labor (chief/regent/council/Workload Share).
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

    /** Sets the settlement's preferred-storage depot from the editing player's block click — the
     *  settlement-level analogue of {@link #markStorage}. Returns true to end edit mode, false to let
     *  the player retry on a rejected block. Gated by {@code canEditLabor}. */
    public static boolean markPreferredStorage(ServerPlayer player, BlockPos pos) {
        MinecraftServer server = player.getServer();
        if (server == null) return true;
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        if (settlement == null || settlement.governmentType() == Settlement.Government.NONE
                || !SettlementManager.canEditLabor(player, settlement)) {
            return true;   // not allowed / not applicable → just leave edit mode
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
        // No longer force-assigns a per-worker drop-off: that would override the settlement storage
        // pool. Preferred storage is just one (optional) pooled container now.
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

    /** Tablet routing by government: anarchy = direct (the requester just takes one); council =
     *  clickable chat-vote (majority of online; the document goes to the initiator on pass);
     *  chiefdom = chief/regent issue directly, any other member toggles a SUGGESTION instead. */
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

    /** Creates and hands a registration document to {@code recipient} (chief direct-issue, anarchy
     *  self-issue, or a passed council vote). Re-checks capacity — a vote may pass after the last
     *  slot was used. Antiquity issues a stone Registration Tablet; Medieval and every later age
     *  issue a Registration Paper instead (identical behaviour, three charges either way). */
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

    /** Votes-tab Yes/No button — same server path as the chat [Yes]/[No] command. */
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

    /** Suggestions-tab [Ignore]: chief/regent dismisses a suggestion. Clears it, tells each
     *  suggester it was ignored (with what it was about), and re-syncs the matching state. */
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

    /**
     * C→S retract: a member pulls their OWN suggestion (vs. the chief's Ignore which clears the
     * whole set). Removes ONLY the calling player's UUID from the suggestion's suggester set, then
     * re-broadcasts the matching state so the chief's Suggestions tab updates live. Any settlement
     * member may retract their own — no chief gate (unlike Ignore). Uses the existing toggle methods,
     * which remove a player when present; guarded by a membership check so a stray packet for a
     * suggestion the player never made is a no-op (a toggle would otherwise ADD them).
     */
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

    /**
     * Looks up the requesting player's settlement, walks its citizen roster, samples live
     * health + stamina for any entity currently loaded, and ships the roster + snapshots back
     * for the Citizens screen. Citizens whose entity isn't loaded contribute name only with
     * 0-filled stats — they still appear so the player can see they exist.
     */
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
                // Job is only known from the loaded entity; an unloaded citizen reads as
                // unemployed in the roster (same lean-low convention as its 0-filled stats).
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
                // Happiness has no live system yet — ship the same 10/10 placeholder
                // CitizenEntity.mobInteract uses, so the screen can render the icon now.
                entries.add(new SettlementCitizensListPayload.Entry(
                    c.entityId(), displayName, health, maxHealth, stamina, maxStamina, 10, 10,
                    jobTypeId, jobIconItemId));
            }
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new SettlementCitizensListPayload(settlement.color(), settlement.age(), entries));
        });
    }

    /**
     * Teleports the named citizen to its settlement's town hall via
     * {@link com.bannerbound.core.entity.CitizenEntity#recallToTownHall}. Requester must be
     * a member of the citizen's settlement.
     */
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

    /**
     * Player picked a workstation type in the rod picker (or chose "Clear"). Writes the choice
     * to whichever stack is in the player's main hand if it's a Foreman's Rod. An empty type
     * string just resets the rod's workstation type + in-progress A/B; committed selections in
     * the {@link com.bannerbound.core.api.world.BlockSelectionRegistry registry} are NOT touched
     * — the player removes those individually via shift-left-click on a block inside one.
     * Whitelist on workstation type — only known types are accepted; unknown values silently
     * ignored so a misbehaving client can't junk-write component data.
     */
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
                // Clear path: just reset rod state. Committed selections persist in the registry.
                stack.remove(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get());
                stack.remove(BannerboundCore.FOREMAN_POINT_A.get());
                stack.remove(BannerboundCore.FOREMAN_POINT_B.get());
                return;
            }

            // Allow-list of supported workstation types.
            if (!"digger".equals(chosen) && !"farmer".equals(chosen) && !"herder".equals(chosen)
                && !"miner".equals(chosen) && !"guard".equals(chosen)) return;

            // Require settlement membership so commits later know which color to draw with.
            Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
            if (settlement == null) {
                player.displayClientMessage(Component.translatable(
                    "bannerbound.foremans_rod.not_in_settlement")
                    .withStyle(ChatFormatting.RED), true);
                return;
            }
            // Research gate — the chosen unit must be unlocked for the player's settlement.
            if (!com.bannerbound.core.api.research.ResearchManager.hasFlag(settlement,
                    com.bannerbound.core.api.settlement.WorkstationUnlocks.flagForUnit(chosen))) {
                player.displayClientMessage(Component.translatable(
                    "bannerbound.foremans_rod.not_researched").withStyle(ChatFormatting.RED), true);
                return;
            }
            stack.set(BannerboundCore.FOREMAN_WORKSTATION_TYPE.get(), chosen);
        });
    }

    /** Commit a herder pen with the animal the player chose in {@code PenAnimalPickerScreen}. Re-validates
     *  the pen, territory, and that the animal was actually offered, then registers a point selection whose
     *  packed seedItemId stores {@code "<animalId>|0"} (kill counter starts at 0). */
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
            // Animal must be one the server offered: the basics anywhere, horse only on a horse chunk.
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
                } catch (IllegalArgumentException ignored) { /* malformed → open to all herders */ }
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

    /** Player set a pen's "keep how many adults alive" harvest threshold in {@code PenKeepScreen}. Writes it
     *  into the pen marker's packed seedItemId (animalId|kills|keep) and re-broadcasts. */
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
                // Clamp to the pen's live capacity so a stale or spoofed packet can't poison the marker
                // (adultKeepTarget clamps at runtime anyway, but keep the stored data honest).
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

    // ─── Chunk-claim expansion ────────────────────────────────────────────────────────────────

    /** Client pressed "Expand Territory" in the Town Hall screen. Server checks the player is in
     *  a settlement with a Town Hall and replies with the bundled birdseye state so the screen
     *  can render. No state mutation here. */
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
            // No gate on opening the screen — every member needs to be able to view it
            // (Chiefdom non-chief to suggest chunks, Council member to cast a vote, anarchy
            // members to claim outright). The gate now lives inside TerritoryService.tryClaim
            // which dispatches by government type.
            OpenExpandTerritoryScreenPayload screen = com.bannerbound.core.api.territory.TerritoryService
                .buildScreenPayload(overworld, settlement, player);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, screen);
        });
    }

    /** Client clicked a chunk in the ExpandTerritoryScreen. Server re-validates everything from
     *  scratch (adjacency, unclaimed, era cap, tier afford, settlement membership) and either
     *  performs the claim or replies with a red action-bar error. On success: consumes items,
     *  claims chunk, force-loads it, increments expansion counter, broadcasts the claim sync to
     *  all clients, and announces in chat. */
    public static void handleExpandTerritoryClaim(ExpandTerritoryClaimPayload payload,
                                                   IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // No upfront gate — TerritoryService.tryClaim itself dispatches by government:
            // Chiefdom non-chief becomes a suggestion, Council member becomes a vote, Chief
            // (or anarchy member) becomes a direct claim. The old canActWeighty wrap blocked
            // non-chiefs from suggesting at all.
            com.bannerbound.core.api.territory.TerritoryService.tryClaim(player, payload.packedChunkPos());
        });
    }

    /** Player chose a seed (or skip) in the seed-picker. Validates ownership, writes the seed to
     *  the selection, and broadcasts the updated registry so the floating-seed marker renders. */
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
            // Only the creator can answer the prompt.
            if (!player.getUUID().equals(sel.creatorId())) return;
            if (!"farmer".equals(sel.workstationType())) return;

            String chosen = payload.seedItemId();
            if (chosen == null || chosen.isEmpty()) {
                // Skip / Esc: erase the selection entirely. The popup is one-shot — the player
                // commits to a seed or they lose the selection.
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

    /** Player confirmed edits from the field-edit screen (shift-right-click a farmer field with the
     *  Foreman's Rod). Re-points the field's crop and assigned worker. Unlike the seed popup this is
     *  NOT one-shot — Cancel/Esc sends nothing, so we always have a valid crop here and never delete
     *  the field. Validates the player owns the field, the crop is a real seed, and the worker is an
     *  actual farmer in the settlement (else falls back to "all farmers" rather than rejecting). */
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
            // The field must belong to the editing player's own settlement.
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

    /** Client opened the resident picker for a House Block. Builds the three-bucket roster
     *  (current residents · homeless · residents of other homes in the same settlement) and
     *  ships it back as a {@link HomeCitizenListPayload}. Only the player's own settlement is
     *  surveyed — neighbour faction citizens aren't visible. */
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
                    // Chebyshev distance — same metric the home size cap uses, so the number the
                    // player sees in the picker matches the units they think in.
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

    /** Player clicked Assign / Unassign in the resident picker. One-home-per-citizen rule:
     *  on assign, drop the citizen from whatever home they currently live in first, then add
     *  them to the target home (bed cap enforced). On unassign, remove from the target home
     *  only — citizen becomes homeless and the auto-assignment poll picks the next bed for them.
     *  Refreshes the picker by re-sending the list on success. */
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
            // Citizen must belong to this settlement — guards against a tampered client trying
            // to assign citizens from another faction.
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
                // Drop the citizen from any prior home so the one-home invariant holds. Wake
                // them up if they were asleep there — the bed they were lying in belongs to a
                // home they no longer live in.
                com.bannerbound.core.api.settlement.Home prior = settlement.getHomeFor(cid);
                if (prior != null && prior != home) {
                    wakeIfSleepingInHome(player.serverLevel(), cid, prior);
                    prior.removeResident(cid);
                }
                home.addResident(cid);
            } else {
                // Same wake-up reason on a plain unassign — the resident loses access to the
                // bed they're currently lying in.
                wakeIfSleepingInHome(player.serverLevel(), cid, home);
                home.removeResident(cid);
            }
            sd.setDirty();

            // The unassign control lives on two screens. Refresh whichever one the click came
            // from so it updates in place instead of bouncing the player to the other screen.
            if (payload.fromHousePanel()) {
                com.bannerbound.core.item.HousingOrdersItem.refreshStatusPanel(
                    player, player.serverLevel(), home);
                return;
            }

            // Refresh the open picker. Same payload shape as the initial request reply.
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

    /** If the citizen is currently sleeping inside the given home's selection union, wake them
     *  and clear the bed's OCCUPIED state. Called from the assign/unassign handler so a kicked-
     *  out resident gets out of bed instead of lingering on a bed that no longer belongs to them.
     *  No-op when the entity isn't loaded, isn't sleeping, or is sleeping somewhere else. */
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

    /**
     * Beauty-debug overlay query: reports how many of the block-at-{@code pos}'s type are
     * counted in the diminishing-returns queue, so the client can show the marginal appeal of
     * this specific block.
     *
     * <p>Two scopes — if {@code pos} falls inside a home selection of the settlement that
     * OWNS the block's chunk, we answer from that home's per-type queue (the home is the
     * citizen's happiness scope, so its math should match what the overlay shows). Otherwise
     * we fall back to the chunk-scan queue the overlay used before the housing system shipped.
     *
     * <p>Scope resolution is by block LOCATION, never by the requester's own settlement, so
     * the same block reports the same appeal to every viewer. (Previously each player saw the
     * home value only for their own faction's homes and the chunk value for everyone else's,
     * which desynced the overlay between clients looking at the same block.)
     */
    public static void handleRequestBlockAppeal(RequestBlockAppealPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            BlockPos pos = payload.pos();
            // Ignore positions absurdly far from the player — debug queries are always close.
            if (pos.distSqr(player.blockPosition()) > 128 * 128) return;

            // Multi-block objects (two-tall plants/doors, beds) are tallied at one anchor half
            // (LOWER / HEAD — see AppealResolver). If the query lands on the other half, resolve to
            // the anchor so the overlay shows the value the scorer used, not 0.
            net.minecraft.server.level.ServerLevel overworld = server.overworld();
            pos = com.bannerbound.core.api.settlement.AppealResolver
                .appealAnchor(overworld.getBlockState(pos), pos);

            // Home-scope first, resolved by the block's location: the settlement that owns the
            // block's chunk is the only one whose homes can contain it (territories don't
            // overlap). This is viewer-independent — the appeal is a property of the block.
            SettlementData sd = SettlementData.get(overworld);
            net.minecraft.world.level.block.Block block = overworld.getBlockState(pos).getBlock();
            Settlement owner = sd.getByChunk(new net.minecraft.world.level.ChunkPos(pos).toLong());
            // Appeal is culture-relative; resolve it against the OWNING settlement's styles (not
            // the requester's) so the value is identical for every viewer of this block.
            java.util.List<String> styles =
                owner != null ? owner.cultureStyles() : java.util.List.of();
            java.util.List<String> palettes =
                owner != null ? owner.activePalettes() : java.util.List.of();
            float base = com.bannerbound.core.api.settlement.AppealResolver.appealOf(block, styles, palettes);
            if (owner != null) {
                for (com.bannerbound.core.api.settlement.Home home : owner.homes().values()) {
                    if (!com.bannerbound.core.api.settlement.HouseAppealData.unionContains(overworld, home, pos)) {
                        continue;
                    }
                    int homeQueuePos = com.bannerbound.core.api.settlement.HouseAppealData
                        .queuePositionOf(overworld, home, pos);
                    float homeAppeal = homeQueuePos > 0
                        ? (float) (base * Math.pow(0.9, homeQueuePos - 1)) : base;
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new BlockAppealDebugPayload(pos, homeQueuePos, true, true, homeAppeal));
                    return;
                }
            }

            long chunkKey = new net.minecraft.world.level.ChunkPos(pos).toLong();
            com.bannerbound.core.api.settlement.ChunkAppealData cad =
                com.bannerbound.core.api.settlement.ChunkBeautyData.get(overworld).get(chunkKey);
            int queuePosition = 0;
            boolean tracked = false;
            if (cad != null && cad.isScanned()) {
                tracked = true;
                queuePosition = cad.queuePositionOf(pos);
            }
            // queuePos>0: counted block, apply its diminishing slot. Tracked but slot 0:
            // underground / not in the surface tally → 0. Untracked chunk: show the raw base.
            float chunkAppeal = queuePosition > 0
                ? (float) (base * Math.pow(0.9, queuePosition - 1))
                : (tracked ? 0f : base);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new BlockAppealDebugPayload(pos, queuePosition, tracked, false, chunkAppeal));
        });
    }

    // ─── Heraldry banner editor ────────────────────────────────────────────────────────────

    /** The flag the Heraldry culture research grants; gates the banner editor both here and
     *  on the TownHallScreen button. */
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

    /** Hands the player a cosmetic copy of the faction banner (base color + Heraldry design),
     *  paid for from settlement storage: one dye of every color the banner is made of (base
     *  cloth + each pattern layer). Placed copies are decoration while the main banner stands;
     *  if it's down, the next one placed is promoted to THE banner by FactionBannerEvents (the
     *  recovery flow). */
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
            // The dyes the design is made of: the base cloth color plus every pattern layer's
            // color, one of each DISTINCT dye. Sourced from settlement storage only (no voters
            // passed → stockpile + workstation inventories, never the player's own pockets).
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
            // Inventory first; drop at the player's feet if it's full so the copy is never lost.
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
            // Hard validation — the client proposal is never authority. Size mismatch or an
            // unknown pattern id means a buggy/forged packet: drop silently. A point shortfall
            // can happen legitimately (an era regression un-completing researches while the
            // editor was open), so that one gets a message.
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
            // The banner decides who you are: every dye holding ≥5% of the cloth, ranked —
            // as many identity colors as the design has. Empty design = founding fallback.
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
            // The flag in the plaza updates live (no-op while its chunk is unloaded).
            com.bannerbound.core.api.settlement.FactionBanner.applyDesignToBlock(
                server.overworld(), mine);
            // The identity change is settlement-WIDE: re-tint scoreboard teams (member name
            // colors), restyle every loaded citizen's name tag, and push the fresh color
            // table so the territory overlay, wireframes and HUD recolor everywhere
            // immediately — the banner IS the settlement's color.
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
            // Refresh the open editor so the saved state + points line are authoritative.
            sendBannerEditor(player, mine);
        });
    }
}
