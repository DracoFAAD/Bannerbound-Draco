package com.bannerbound.core.network;

import com.bannerbound.core.api.research.ResearchDefinition;

import org.jetbrains.annotations.ApiStatus;

import java.util.HashSet;

import com.bannerbound.core.client.ClientClaimState;
import com.bannerbound.core.client.ClientEraState;
import com.bannerbound.core.client.ClientStartingItems;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.SettlementColor;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-side dispatch target for every S->C Bannerbound payload: network registration maps each
 * payload type to one static handleXxx method here. Every handler body runs inside
 * context.enqueueWork(...) so client-state mutation and Minecraft.setScreen happen on the render
 * thread, never the netty thread - do not touch client state outside that lambda.
 *
 * Two recurring patterns to preserve when editing:
 *  - Refresh-in-place: open-screen handlers check whether the target screen is already the current
 *    Minecraft.screen and refresh it (keeping camera / scroll / tab / half-typed text) rather than
 *    constructing a new screen on top; skipping this ejects or resets the player on every server push.
 *  - Parent back-target: a screen opened from the Town Hall stashes it as its parent so Escape
 *    returns there instead of dumping to the world.
 *
 * Era note: every era uses the flat default screens now (TownHallScreen, SettlementCitizensScreen);
 * the old per-era reskins are no longer dispatched (AncientTownHallScreen still exists in the tree).
 * reopenCitizenJobTabEntityId is a one-shot handshake: set when the citizen screen closes to enter
 * drop-location edit, consumed on the next citizen-screen open to reopen straight to the Job tab.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientPayloadHandler {
    private static int reopenCitizenJobTabEntityId = -1;

    private ClientPayloadHandler() {
    }

    public static void handleOpenSettleScreen(OpenSettleScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new com.bannerbound.core.client.SettleScreen(payload.siteWarningMask()));
        });
    }

    public static void handleOpenAncientGuiPreview(OpenAncientGuiPreviewPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            Minecraft.getInstance().setScreen(new com.bannerbound.core.client.AncientWorldBoxScreen()));
    }

    public static void handleClaimSync(ClaimSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientClaimState.replaceAll(payload.claims()));
    }

    public static void handleSkySeed(SkySeedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.sky.ClientSkyState.set(
            payload.skySeed(), payload.celestialSpeed(), payload.meteorAmount(), payload.monthDays()));
    }

    public static void handleFaithTreeSync(FaithTreeSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            java.util.Map<String, ResearchDefinition> map = new java.util.HashMap<>();
            for (ResearchDefinition def : payload.definitions()) {
                map.put(def.id(), def);
            }
            com.bannerbound.core.client.ClientFaithTreeState.replaceTree(map);
        });
    }

    public static void handleFaithResearchState(FaithResearchStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientFaithTreeState.replaceState(payload));
    }

    public static void handleConstellationsSync(ConstellationsSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.sky.ClientConstellationState.replace(payload));
    }

    public static void handleFaithState(FaithStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            com.bannerbound.core.client.ClientFaithState.replace(payload);
            if (Minecraft.getInstance().screen instanceof com.bannerbound.core.client.ChooseFaithScreen
                    && (payload.hasFaith() || !payload.choiceWindowOpen())) {
                Minecraft.getInstance().setScreen(null);
            }
        });
    }

    public static void handleOpenChooseFaithScreen(OpenChooseFaithScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            Minecraft.getInstance().setScreen(new com.bannerbound.core.client.ChooseFaithScreen(payload)));
    }

    public static void handleJournalSync(JournalSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientJournalState.replace(payload));
    }

    public static void handleCodexSync(CodexSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientChronicleState.replace(payload));
    }

    public static void handleOpenCodex(OpenCodexScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            Minecraft.getInstance().setScreen(new com.bannerbound.core.client.ChronicleScreen(payload.entryId())));
    }

    public static void handleCodexToast(CodexToastPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientChronicleState.enqueueToast(payload));
    }

    public static void handleShowTutorialPopup(ShowTutorialPopupPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientTutorialPopups.enqueue(payload));
    }

    public static void handleCrisisState(CrisisStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientCrisisState.replace(payload));
    }

    public static void handleOpenCrisisScreen(OpenCrisisScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (payload.forceOpen()) {
                mc.setScreen(new com.bannerbound.core.client.CrisisScreen(payload));
            } else if (mc.screen instanceof com.bannerbound.core.client.CrisisScreen current) {
                current.refresh(payload);
            }
        });
    }

    public static void handleIdentitySync(IdentitySyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientIdentityState.replace(payload));
    }

    public static void handleLaborState(LaborStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            com.bannerbound.core.client.ClientLaborState.replace(payload);
            if (Minecraft.getInstance().screen instanceof com.bannerbound.core.client.TownHallScreen townHall) {
                townHall.onLaborStateSynced();
            }
        });
    }

    public static void handleChatVotesState(ChatVotesStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            com.bannerbound.core.client.ClientChatVotesState.replace(payload);
            if (Minecraft.getInstance().screen instanceof com.bannerbound.core.client.TownHallScreen townHall) {
                townHall.onChatVotesSynced();
            }
        });
    }

    public static void handleExtraSuggestions(ExtraSuggestionsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            com.bannerbound.core.client.ClientExtraSuggestionsState.replace(payload);
            if (Minecraft.getInstance().screen instanceof com.bannerbound.core.client.TownHallScreen townHall) {
                townHall.onSuggestionsSynced();
            }
        });
    }

    public static void handleDiplomacyState(DiplomacyStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            com.bannerbound.core.client.ClientDiplomacyState.replace(payload);
            if (Minecraft.getInstance().screen instanceof com.bannerbound.core.client.TownHallScreen townHall) {
                townHall.onDiplomacySynced();
            }
        });
    }

    public static void handleDiplomacyObjective(DiplomacyObjectivePayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.bannerbound.core.client.ClientDiplomacyState.objective(payload));
    }

    public static void handleOpenTownHallScreen(OpenTownHallScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            SettlementColor color = SettlementColor.byIndex(payload.colorOrdinal());
            Era era = Era.fromOrdinalOrDefault(payload.eraOrdinal());
            if (payload.governmentChoiceWindowOpen()) {
                mc.setScreen(new com.bannerbound.core.client.ChooseGovernmentScreen(
                    null,
                    payload.councilVoteCount(), payload.chiefdomVoteCount(),
                    payload.onlineMembers(), payload.playerGovernmentVote()));
                return;
            }
            if (payload.chiefdomElectionActive()) {
                mc.setScreen(new com.bannerbound.core.client.NominateChiefScreen(
                    null,
                    payload.chiefCandidates(),
                    payload.chiefCandidateNames(),
                    payload.chiefCandidateVotes(),
                    payload.onlineMembers(),
                    payload.playerChiefNomination()));
                return;
            }
            boolean isChief = payload.playerIsChief();
            boolean isRegent = payload.playerIsRegent();
            com.bannerbound.core.client.ClientPopulationState.setChiefState(
                payload.governmentOrdinal(), isChief || isRegent);
            mc.setScreen(new com.bannerbound.core.client.TownHallScreen(
                payload.settlementName(), color, era,
                payload.tabletsIssued(), payload.tabletCapacity(),
                payload.disbandVoteCount(), payload.disbandTotalMembers(),
                payload.playerHasVotedToDisband(), payload.disbandVoteActive(),
                payload.governmentOrdinal(), isChief, payload.chiefStepDownReadyTick(),
                payload.leaveReadyTick(),
                payload.identityRgbs()));
        });
    }

    public static void handleSettlementWarnings(SettlementWarningsPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.bannerbound.core.client.ClientSettlementWarningsState.set(payload.warnings()));
    }

    public static void handleEraState(EraStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientEraState.setEras(payload.playerEra(), payload.worldEra(), payload.worldYear()));
    }

    public static void handleLanguageState(LanguageStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientLanguageState.replace(
            payload.enabled(), payload.seed(), payload.conceptOverrides()));
    }

    public static void handleStartingItemsSync(StartingItemsSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientStartingItems.replace(new HashSet<>(payload.itemIds())));
    }

    public static void handleCultureStyleSync(CultureStyleSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientCultureStyleState.replace(
            payload.ids(), payload.nameKeys(), payload.images()));
    }

    public static void handleBlockAppealSync(BlockAppealSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientBlockAppealState.replace(
            payload.blockIds(), payload.appeals()));
    }

    public static void handleFoodValueSync(FoodValueSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientFoodValueState.replace(
            payload.itemIds(), payload.values()));
    }

    public static void handleSettlementFoodWarning(SettlementFoodWarningPayload payload,
            IPayloadContext context) {
        context.enqueueWork(() ->
            com.bannerbound.core.client.ClientFoodWarningState.set(payload.level()));
    }

    public static void handleRaidWarning(RaidWarningPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.bannerbound.core.client.ClientRaidWarningState.set(payload.active()));
    }

    public static void handleOpenBarbarianParley(OpenBarbarianParleyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> net.minecraft.client.Minecraft.getInstance().setScreen(
            new com.bannerbound.core.client.BarbarianParleyScreen(payload)));
    }

    public static void handleOpenBarter(OpenBarterPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> net.minecraft.client.Minecraft.getInstance().setScreen(
            new com.bannerbound.core.client.BarbarianBarterScreen(payload)));
    }

    public static void handleBarterStorage(BarterStoragePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof com.bannerbound.core.client.BarbarianBarterScreen s) {
                s.applyStorageUpdate(payload);
            }
        });
    }

    public static void handleOpenTradeScreen(OpenTradeScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> net.minecraft.client.Minecraft.getInstance().setScreen(
            new com.bannerbound.core.client.TradeScreen(payload)));
    }

    public static void handleTradeStorage(TradeStoragePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof com.bannerbound.core.client.TradeScreen s) {
                s.applyStorageUpdate(payload);
            }
        });
    }

    public static void handleResearchTreeSync(ResearchTreeSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            java.util.Map<String, com.bannerbound.core.api.research.ResearchDefinition> map = new java.util.HashMap<>();
            for (com.bannerbound.core.api.research.ResearchDefinition d : payload.definitions()) {
                map.put(d.id(), d);
            }
            com.bannerbound.core.client.ClientResearchState.replaceTree(map);
        });
    }

    public static void handleCultureTreeSync(CultureTreeSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            java.util.Map<String, com.bannerbound.core.api.research.ResearchDefinition> map = new java.util.HashMap<>();
            for (com.bannerbound.core.api.research.ResearchDefinition d : payload.definitions()) {
                map.put(d.id(), d);
            }
            com.bannerbound.core.client.ClientCultureState.replaceTree(map);
        });
    }

    public static void handleSuggestionStateSync(SuggestionStateSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.bannerbound.core.client.ClientSuggestionState.replace(payload));
    }

    public static void handlePolicyStateSync(PolicyStateSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            com.bannerbound.core.client.ClientPolicyState.replace(payload);
            if (Minecraft.getInstance().screen
                    instanceof com.bannerbound.core.client.TownHallScreen townHall) {
                townHall.onPolicyStateSynced();
            }
        });
    }

    public static void handlePaletteStateSync(PaletteStateSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            com.bannerbound.core.client.ClientPaletteState.replace(payload);
            if (Minecraft.getInstance().screen
                    instanceof com.bannerbound.core.client.TownHallScreen townHall) {
                townHall.onPaletteStateSynced();
            }
        });
    }

    public static void handleCloseSettlementScreens(CloseSettlementScreensPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.client.gui.screens.Screen s = mc.screen;
            if (s == null) return;
            // Explicit list (not package-scan) so an unrelated future screen isn't collateral-closed.
            boolean isSettlementScreen =
                s instanceof com.bannerbound.core.client.TownHallScreen
                || s instanceof com.bannerbound.core.client.AncientTownHallScreen
                || s instanceof com.bannerbound.core.client.CitizenScreen
                || s instanceof com.bannerbound.core.client.ExpandTerritoryScreen
                || s instanceof com.bannerbound.core.client.ResearchScreen
                || s instanceof com.bannerbound.core.client.ChooseGovernmentScreen
                || s instanceof com.bannerbound.core.client.NominateChiefScreen
                || s instanceof com.bannerbound.core.client.TribeVoteScreen
                || s instanceof com.bannerbound.core.client.CrisisScreen
                || s instanceof com.bannerbound.core.client.SettlementCitizensScreen
                || s instanceof com.bannerbound.core.client.WorkerPickerScreen
                || s instanceof com.bannerbound.core.client.WorkstationPickerScreen
                || s instanceof com.bannerbound.core.client.HouseStatusScreen
                || s instanceof com.bannerbound.core.client.HomeResidentPickerScreen
                || s instanceof com.bannerbound.core.client.OutpostScreen;
            if (isSettlementScreen) mc.setScreen(null);
        });
    }

    public static void handleOpenBannerEditor(OpenBannerEditorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.bannerbound.core.client.BannerEditorScreen editor) {
                editor.applyServerState(payload);
            } else {
                mc.setScreen(new com.bannerbound.core.client.BannerEditorScreen(payload));
            }
        });
    }

    public static void handleProximityChat(ProximityChatPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui == null) {
                return;
            }
            // mc.gui.getChat() is mixin'd to implement ProximityChatSink; alpha fades distant chatter.
            ((com.bannerbound.core.client.ProximityChatSink) mc.gui.getChat())
                .bannerbound$addProximityMessage(payload.message(), payload.alpha());
        });
    }

    public static void handleCloseSettleScreen(CloseSettleScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.bannerbound.core.client.SettleScreen) {
                mc.setScreen(null);
            }
        });
    }

    public static void handleCitizenLiveState(CitizenLiveStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.bannerbound.core.client.CitizenScreen cs) {
                cs.applyLiveState(payload);
            }
        });
    }

    public static void handleCultureStateSync(CultureStateSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            java.util.Set<String> completed = new java.util.HashSet<>(payload.completed());
            java.util.Map<String, Double> progress = new java.util.HashMap<>();
            for (ResearchStateSyncPayload.ProgressEntry e : payload.progress()) {
                progress.put(e.researchId(), e.progress());
            }
            java.util.Map<String, Double> insightProgress = new java.util.HashMap<>();
            for (ResearchStateSyncPayload.ProgressEntry e : payload.insightProgress()) {
                insightProgress.put(e.researchId(), e.progress());
            }
            com.bannerbound.core.client.ClientCultureState.replaceState(
                completed, payload.activeResearch(), progress,
                payload.culturePerSecond(), payload.capacity(),
                payload.queue(), insightProgress, new java.util.HashSet<>(payload.firedInsights()));
        });
    }

    public static void handleOreDisguisesSync(OreDisguisesSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientOreState.replaceDisguises(payload.disguises()));
    }

    public static void handleCitizenList(CitizenListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new com.bannerbound.core.client.WorkerPickerScreen(
                payload.workstationPos(), payload.entries()));
        });
    }

    public static void handleWorkstationList(WorkstationListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.world.entity.Entity entity = mc.level == null ? null
                : mc.level.getEntity(payload.citizenEntityId());
            if (entity == null) return;
            mc.setScreen(new com.bannerbound.core.client.WorkstationPickerScreen(
                entity.getUUID(), payload.entries()));
        });
    }

    public static void handleOpenCitizenScreen(OpenCitizenScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            boolean openJobTab = reopenCitizenJobTabEntityId == payload.entityId();
            reopenCitizenJobTabEntityId = -1;
            mc.setScreen(new com.bannerbound.core.client.CitizenScreen(payload, openJobTab));
        });
    }

    public static void handleOpenHouseStatus(OpenHouseStatusPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.bannerbound.core.client.HouseStatusScreen existing) {
                existing.refresh(payload);
            } else {
                mc.setScreen(new com.bannerbound.core.client.HouseStatusScreen(payload));
            }
        });
    }

    public static void handleCitizenJobState(CitizenJobStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.bannerbound.core.client.CitizenScreen screen) {
                screen.applyJobState(payload);
            }
        });
    }

    public static void handleOpenDropLocationEdit(OpenDropLocationEditPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.bannerbound.core.client.CitizenScreen screen
                    && screen.jobTabActive()) {
                reopenCitizenJobTabEntityId = screen.entityId();
            } else {
                reopenCitizenJobTabEntityId = -1;
            }
            mc.setScreen(null);
            com.bannerbound.core.client.DropLocationEditState.begin(
                payload.entityId(), payload.name(), payload.jobTitle(), payload.settlementRgb(), payload.seed());
        });
    }

    public static void handleEndDropLocationEdit(EndDropLocationEditPayload payload, IPayloadContext context) {
        context.enqueueWork(com.bannerbound.core.client.DropLocationEditState::clear);
    }

    public static void handleHomeCitizenList(HomeCitizenListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.bannerbound.core.client.HomeResidentPickerScreen existing) {
                existing.refresh(payload);
            } else {
                mc.setScreen(new com.bannerbound.core.client.HomeResidentPickerScreen(payload));
            }
        });
    }

    public static void handleOpenTribeVoteScreen(OpenTribeVoteScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new com.bannerbound.core.client.TribeVoteScreen(
                payload.voterNames(), payload.candidateNames()));
        });
    }

    public static void handlePopulationState(PopulationStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientPopulationState.update(
            payload.settlementId(),
            payload.population(),
            payload.populationMax(),
            payload.foodPerSecond(),
            payload.culturePerSecond(),
            payload.foodStored(),
            payload.cultureStored(),
            payload.storedFoodValue(),
            payload.storedFoodPerSecond(),
            payload.nextFoodCost(),
            payload.nextCultureCost(),
            payload.foodCap(),
            payload.cultureCap(),
            payload.governmentOrdinal(),
            payload.members(),
            payload.foodConsumptionPerSecond(),
            payload.foodSourceRates(),
            payload.appealCulturePerSecond()
        ));
    }

    public static void handleWorkforceStats(WorkforceStatsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientWorkforceState.update(payload.entries()));
    }

    public static void handleWorkshopStats(WorkshopStatsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientWorkshopState.update(payload.entries()));
    }

    public static void handleSimulationState(SimulationStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientSimulationState.update(payload));
    }

    public static void handleStatusEffectList(StatusEffectListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            java.util.List<com.bannerbound.core.client.ClientStatusState.Entry> mapped =
                new java.util.ArrayList<>(payload.effects().size());
            for (com.bannerbound.core.network.StatusEffectListPayload.Entry e : payload.effects()) {
                mapped.add(new com.bannerbound.core.client.ClientStatusState.Entry(
                    e.instanceId(),
                    e.translationKey(),
                    e.args(),
                    com.bannerbound.core.api.settlement.StatusEffectIcon.fromOrdinalOrFood(e.iconOrdinal()),
                    e.iconValue(),
                    e.totalDurationTicks(),
                    e.remainingTicks()
                ));
            }
            com.bannerbound.core.client.ClientStatusState.setAll(mapped);
        });
    }

    public static void handleOpenForemansRodPicker(OpenForemansRodPickerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new com.bannerbound.core.client.ForemansRodPickerScreen());
        });
    }

    public static void handleOpenPenAnimalPicker(OpenPenAnimalPickerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new com.bannerbound.core.client.PenAnimalPickerScreen(payload.penPos(), payload.animalIds()));
        });
    }

    public static void handleOpenPenKeep(OpenPenKeepPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().setScreen(
            new com.bannerbound.core.client.PenKeepScreen(payload.penPos(), payload.animalId(),
                payload.mature(), payload.capacity(), payload.kills(), payload.keep())));
    }

    public static void handleOpenOutpostScreen(OpenOutpostScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().setScreen(
            new com.bannerbound.core.client.OutpostScreen(payload)));
    }

    public static void handleOpenWorkshopMenu(OpenWorkshopMenuPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            var next = new com.bannerbound.core.client.WorkshopScreen(payload);
            if (mc.screen instanceof com.bannerbound.core.client.WorkshopScreen prev
                    && prev.showsWorkshop(payload.workshopId())) {
                next.carryUiStateFrom(prev);
            }
            mc.setScreen(next);
        });
    }

    public static void handleOpenWorkshopPicker(OpenWorkshopPickerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().setScreen(
            new com.bannerbound.core.client.WorkshopPickerScreen(payload)));
    }

    public static void handleWorkshopSummarySync(WorkshopSummarySyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.bannerbound.core.client.ClientWorkshopSummaries.replace(payload));
    }

    public static void handleSelectionSync(SelectionSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.bannerbound.core.client.ClientSelectionState.replace(payload.selections()));
    }

    public static void handleShowDetectPreview(ShowDetectPreviewPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.bannerbound.core.client.DetectPreviewState.show(payload.housePos(), payload.durationTicks()));
    }

    public static void handleShowStockpileDebug(ShowStockpileDebugPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.bannerbound.core.client.StockpileDebugState.show(
                payload.interior(), payload.containers(), payload.failPos(), payload.durationTicks()));
    }

    public static void handleShowChunkTypes(ShowChunkTypesPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.bannerbound.core.client.ChunkTypeOverlayState.show(
                payload.centerX(), payload.centerZ(), payload.radius(),
                payload.ordinals(), payload.durationTicks()));
    }

    public static void handleStockpileContents(StockpileContentsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null
                && mc.player.containerMenu instanceof com.bannerbound.core.menu.StockpileMenu m
                && m.menuId() == payload.containerId()) {
                m.setSnapshot(payload.statusOrdinal(), payload.containerCount(),
                    payload.usedSlots(), payload.totalSlots(),
                    payload.allowDeposit(), payload.allowTake(), payload.showTrade(),
                    payload.entries());
            }
        });
    }

    public static void handleSettlementCitizensList(SettlementCitizensListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.client.gui.screens.Screen parent = null;
            if (mc.screen instanceof com.bannerbound.core.client.TownHallScreen townHall) {
                parent = townHall;
            } else if (mc.screen instanceof com.bannerbound.core.client.SettlementCitizensScreen existing) {
                parent = existing.parentScreen();
            }
            mc.setScreen(new com.bannerbound.core.client.SettlementCitizensScreen(payload.entries(), parent));
        });
    }

    public static void handleResearchStateSync(ResearchStateSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            java.util.Set<String> completed = new java.util.HashSet<>(payload.completed());
            java.util.Map<String, Double> progress = new java.util.HashMap<>();
            for (ResearchStateSyncPayload.ProgressEntry e : payload.progress()) {
                progress.put(e.researchId(), e.progress());
            }
            java.util.Map<String, Double> insightProgress = new java.util.HashMap<>();
            for (ResearchStateSyncPayload.ProgressEntry e : payload.insightProgress()) {
                insightProgress.put(e.researchId(), e.progress());
            }
            java.util.Set<String> unlocked = new java.util.HashSet<>(payload.unlockedItemIds());
            com.bannerbound.core.client.ClientResearchState.replaceState(
                completed, payload.activeResearch(), progress,
                payload.sciencePerSecond(), payload.capacity(), unlocked,
                payload.queue(), insightProgress, new java.util.HashSet<>(payload.firedInsights()));
        });
    }

    public static void handleOpenExpandTerritoryScreen(OpenExpandTerritoryScreenPayload payload,
                                                        IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.bannerbound.core.client.ExpandTerritoryScreen existing) {
                existing.refreshData(payload);
            } else {
                com.bannerbound.core.client.ExpandTerritoryScreen screen =
                    new com.bannerbound.core.client.ExpandTerritoryScreen(payload);
                if (mc.screen instanceof com.bannerbound.core.client.TownHallScreen townHall) {
                    screen.setParent(townHall);
                }
                mc.setScreen(screen);
            }
        });
    }

    public static void handleOpenSeedPicker(OpenSeedPickerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new com.bannerbound.core.client.SeedPickerScreen(
                payload.rodId(), payload.candidateSeeds(), payload.bonusSeeds()));
        });
    }

    public static void handleOpenFieldEdit(OpenFieldEditPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new com.bannerbound.core.client.FieldEditScreen(
                payload.rodId(), payload.candidateSeeds(), payload.currentSeed(),
                payload.farmerIds(), payload.farmerNames(), payload.currentWorker(), payload.bonusSeeds()));
        });
    }

    public static void handleWallBlueprintSync(WallBlueprintSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.bannerbound.core.client.ClientWallBlueprint.set(payload.positions(), payload.stateIds()));
    }

    public static void handleWallStatus(WallScreenPayloads.WallStatus payload,
                                        IPayloadContext context) {
        context.enqueueWork(() ->
            com.bannerbound.core.client.ClientWallStatus.set(payload.message(), payload.error()));
    }

    public static void handleOpenWallDesigner(WallScreenPayloads.OpenWallDesigner payload,
                                              IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.bannerbound.core.client.WallDesignerScreen open) {
                open.refreshLibrary(payload);
                return;
            }
            com.bannerbound.core.client.WallDesignerScreen screen =
                new com.bannerbound.core.client.WallDesignerScreen(payload);
            if (mc.screen instanceof com.bannerbound.core.client.TownHallScreen
                || mc.screen instanceof com.bannerbound.core.client.WallPreviewScreen) {
                screen.setParentScreen(mc.screen);
            }
            mc.setScreen(screen);
        });
    }

    public static void handleOpenWallPreview(WallScreenPayloads.OpenWallPreview payload,
                                             IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.bannerbound.core.client.WallRefineScreen refine) {
                refine.refreshWalls(payload);
            } else if (mc.screen instanceof com.bannerbound.core.client.WallPreviewScreen open) {
                open.refreshWalls(payload);
            } else if (payload.openRefine()) {
                mc.setScreen(new com.bannerbound.core.client.WallRefineScreen(payload));
            } else {
                com.bannerbound.core.client.WallPreviewScreen wallScreen =
                    new com.bannerbound.core.client.WallPreviewScreen(payload);
                if (mc.screen instanceof com.bannerbound.core.client.TownHallScreen townHall) {
                    wallScreen.setParent(townHall);
                }
                mc.setScreen(wallScreen);
            }
        });
    }
}
