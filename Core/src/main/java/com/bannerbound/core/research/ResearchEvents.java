package com.bannerbound.core.research;

import com.bannerbound.core.api.research.data.OreDisguiseLoader;
import com.bannerbound.core.api.research.data.StartingItemsLoader;
import com.bannerbound.core.api.research.data.ToolAgeLoader;
import com.bannerbound.core.api.settlement.ImmigrationManager;
import com.bannerbound.core.api.territory.data.ChunkClaimCostLoader;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.research.data.ResearchTreeLoader;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

/**
 * Wires the research subsystem (and, by extension, most per-settlement server tickers) into the
 * server lifecycle via three events.
 *
 * <p>{@code AddReloadListenerEvent} registers every datapack reload listener (research/culture/
 * faith trees, starting items, ore disguises, drop overrides, tool ages, chunk/city-state/era/
 * appeal/food/palette/language/crisis/codex/barbarian data).
 *
 * <p>{@code OnDatapackSyncEvent} re-runs auto-unlocks after the tree (re)loads - this fixes existing
 * saves and picks up newly added auto_unlock nodes on /reload - then pushes era, items, trees,
 * disguises and per-manager state to the joining player (single) or to everyone after a global
 * /reload.
 *
 * <p>{@code ServerTickEvent.Post} drives every per-settlement ticker (research, culture, faith,
 * immigration, crises, barbarians, city-states, ruination, trade, ...). Post is used so entities
 * have already ticked. Two ordering constraints matter and are flagged inline below: the dormancy
 * pre-pass must run before any per-settlement ticker, and chunk-beauty must refresh before
 * immigration.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class ResearchEvents {
    private ResearchEvents() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new StartingItemsLoader());
        event.addListener(new ResearchTreeLoader());
        event.addListener(new com.bannerbound.core.api.research.data.CultureTreeLoader());
        event.addListener(new com.bannerbound.core.api.research.data.FaithTreeLoader());
        event.addListener(new OreDisguiseLoader());
        event.addListener(new com.bannerbound.core.api.research.data.DropOverrideLoader());
        event.addListener(new ToolAgeLoader());
        event.addListener(new com.bannerbound.core.api.territory.data.ChunkClaimCostLoader());
        event.addListener(new com.bannerbound.core.api.territory.data.ChunkResourceLoader());
        event.addListener(new com.bannerbound.core.api.citystate.data.CityStateGoodsLoader());
        event.addListener(new com.bannerbound.core.api.citystate.data.CityStateWantsLoader());
        event.addListener(new com.bannerbound.core.api.settlement.data.EraTimelineLoader());
        event.addListener(new com.bannerbound.core.api.settlement.data.CitizenNameLoader());
        event.addListener(new com.bannerbound.core.api.settlement.data.BlockAppealLoader());
        event.addListener(new com.bannerbound.core.api.settlement.data.CultureStyleLoader());
        event.addListener(new com.bannerbound.core.api.settlement.data.FoodValueLoader());
        event.addListener(new com.bannerbound.core.api.settlement.data.PaletteLoader());
        event.addListener(new com.bannerbound.core.language.LanguageConceptOverrideLoader());
        event.addListener(new com.bannerbound.core.crisis.CrisisDefinitionLoader());
        event.addListener(new com.bannerbound.core.codex.CodexCategoryLoader());
        event.addListener(new com.bannerbound.core.codex.CodexEntryLoader());
        event.addListener(new com.bannerbound.core.codex.TutorialPopupLoader());
        event.addListener(new com.bannerbound.core.barbarian.BarbarianLoadoutLoader());
        event.addListener(new com.bannerbound.core.barbarian.ParleyLoader());
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        ResearchManager.applyAllAutoUnlocks(event.getPlayerList().getServer());
        com.bannerbound.core.api.research.CultureManager.applyAllAutoUnlocks(event.getPlayerList().getServer());
        com.bannerbound.core.api.research.InsightManager.rebuildIndex();
        com.bannerbound.core.barbarian.CampPieces.clearCache();
        assignDefaultCultureStyles(event.getPlayerList().getServer());
        com.bannerbound.core.language.CustomLanguageSync.refreshLoadedCitizenNames(
            event.getPlayerList().getServer());

        ServerPlayer single = event.getPlayer();
        if (single != null) {
            SettlementManager.sendEraStateTo(single);
            SettlementManager.sendStartingItemsTo(single);
            SettlementManager.sendCultureStylesTo(single);
            SettlementManager.sendBlockAppealTo(single);
            SettlementManager.sendFoodValuesTo(single);
            SettlementManager.sendResearchTreeTo(single);
            SettlementManager.sendCultureTreeTo(single);
            SettlementManager.sendFaithTreeTo(single);
            SettlementManager.sendOreDisguisesTo(single);
            ResearchManager.sendStateTo(single);
            com.bannerbound.core.api.research.CultureManager.sendStateTo(single);
            com.bannerbound.core.api.faith.FaithManager.sendTreeStateTo(
                event.getPlayerList().getServer(), single);
            com.bannerbound.core.api.settlement.ImmigrationManager.sendStateTo(single);
            SettlementManager.sendStatusEffectsTo(single);
            com.bannerbound.core.language.CustomLanguageSync.sendTo(single);
            com.bannerbound.core.journal.JournalManager.sendTo(single);
            com.bannerbound.core.crisis.CrisisManager.sendStateTo(single);
            com.bannerbound.core.codex.CodexManager.reconcile(single, false);
        } else {
            for (ServerPlayer p : event.getPlayerList().getPlayers()) {
                SettlementManager.sendEraStateTo(p);
                SettlementManager.sendStartingItemsTo(p);
                SettlementManager.sendCultureStylesTo(p);
                SettlementManager.sendBlockAppealTo(p);
                SettlementManager.sendFoodValuesTo(p);
                SettlementManager.sendResearchTreeTo(p);
                SettlementManager.sendCultureTreeTo(p);
                SettlementManager.sendFaithTreeTo(p);
                SettlementManager.sendOreDisguisesTo(p);
                ResearchManager.sendStateTo(p);
                com.bannerbound.core.api.research.CultureManager.sendStateTo(p);
                com.bannerbound.core.api.faith.FaithManager.sendTreeStateTo(
                    event.getPlayerList().getServer(), p);
                com.bannerbound.core.api.settlement.ImmigrationManager.sendStateTo(p);
                SettlementManager.sendStatusEffectsTo(p);
                com.bannerbound.core.language.CustomLanguageSync.sendTo(p);
                com.bannerbound.core.journal.JournalManager.sendTo(p);
                com.bannerbound.core.crisis.CrisisManager.sendStateTo(p);
                com.bannerbound.core.codex.CodexManager.reconcile(p, false);
            }
        }
    }

    private static void assignDefaultCultureStyles(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        java.util.List<String> styleIds =
            com.bannerbound.core.api.settlement.data.CultureStyleLoader.ids();
        if (styleIds.isEmpty()) return;
        String defaultStyle = styleIds.get(0);
        com.bannerbound.core.api.settlement.SettlementData data =
            com.bannerbound.core.api.settlement.SettlementData.get(server.overworld());
        boolean changed = false;
        for (com.bannerbound.core.api.settlement.Settlement s : data.all()) {
            if (s.cultureStyles().isEmpty()) {
                s.setCultureStyle(defaultStyle);
                changed = true;
            }
        }
        if (changed) data.setDirty();
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        com.bannerbound.core.sim.CitizenAiProfiler.endTick();
        // Dormancy pre-pass: must run before every per-settlement ticker below so they read one fresh flag.
        SettlementManager.refreshDormancy(event.getServer());
        ResearchManager.tickAll(event.getServer());
        com.bannerbound.core.api.research.CultureManager.tickAll(event.getServer());
        com.bannerbound.core.api.faith.FaithManager.tickAll(event.getServer());
        com.bannerbound.core.api.research.InsightManager.tickLevels(event.getServer());
        // Must refresh chunk beauty before immigration: the culture accumulator reads these tags.
        com.bannerbound.core.api.settlement.ChunkBeautyManager.tickAll(event.getServer());
        com.bannerbound.core.api.settlement.ImmigrationManager.tickAll(event.getServer());
        com.bannerbound.core.crisis.CrisisManager.tickAll(event.getServer());
        com.bannerbound.core.social.BabyMakingManager.tickAll(event.getServer());
        com.bannerbound.core.sim.SimulationManager.tickAll(event.getServer());
        com.bannerbound.core.sim.TraderSimManager.tickAll(event.getServer());
        com.bannerbound.core.barbarian.BarbarianCampManager.tickAll(event.getServer());
        com.bannerbound.core.citystate.CityStateManager.tickAll(event.getServer());
        com.bannerbound.core.citystate.CityStateWarManager.tickAll(event.getServer());
        com.bannerbound.core.ruin.RuinManager.tickAll(event.getServer());
        com.bannerbound.core.trade.TradeManager.tickAll(event.getServer());
    }
}
