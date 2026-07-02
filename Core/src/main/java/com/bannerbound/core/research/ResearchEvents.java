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
 * Wires the research subsystem into the server lifecycle:
 * <ul>
 *   <li>{@code AddReloadListenerEvent} — registers {@link StartingItemsLoader},
 *       {@link ResearchTreeLoader}, {@link OreDisguiseLoader} as datapack reload listeners.</li>
 *   <li>{@code OnDatapackSyncEvent} — runs auto-unlocks (fixes existing saves on first load)
 *       and pushes era/items/tree/disguises/state to the joining player or to everyone after
 *       a global /reload.</li>
 *   <li>{@code ServerTickEvent.Post} — drives {@link ResearchManager#tickAll} every tick.</li>
 * </ul>
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
        event.addListener(new com.bannerbound.core.barbarian.BarbarianLoadoutLoader());
        event.addListener(new com.bannerbound.core.barbarian.ParleyLoader());
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        // After the tree (re)loads, sweep settlements for any auto_unlock nodes they're missing.
        // Covers existing saves and new auto_unlock nodes added via /reload.
        ResearchManager.applyAllAutoUnlocks(event.getPlayerList().getServer());
        com.bannerbound.core.api.research.CultureManager.applyAllAutoUnlocks(event.getPlayerList().getServer());
        com.bannerbound.core.api.research.InsightManager.rebuildIndex();
        com.bannerbound.core.barbarian.CampPieces.clearCache(); // re-scan camp .nbt pieces after /reload
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

    /** Gives a default culture style to any settlement saved before the culture-style feature
     *  existed, so its block appeal isn't stuck on base values forever. */
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
        // Snapshot the citizen-AI profiler for this tick (entities already ticked before Post).
        com.bannerbound.core.sim.CitizenAiProfiler.endTick();
        // Dormancy pre-pass: refresh every settlement's transient dormant flag BEFORE any
        // per-settlement ticker below (research, culture, crises, immigration, ...) so they all
        // read the same fresh value within this tick.
        SettlementManager.refreshDormancy(event.getServer());
        ResearchManager.tickAll(event.getServer());
        com.bannerbound.core.api.research.CultureManager.tickAll(event.getServer());
        // Devotion accrual + per-second faith state broadcast (FAITH_PLAN.md).
        com.bannerbound.core.api.faith.FaithManager.tickAll(event.getServer());
        com.bannerbound.core.api.research.InsightManager.tickLevels(event.getServer());
        // Refresh chunk beauty before immigration so the culture accumulator reads fresh tags.
        com.bannerbound.core.api.settlement.ChunkBeautyManager.tickAll(event.getServer());
        com.bannerbound.core.api.settlement.ImmigrationManager.tickAll(event.getServer());
        com.bannerbound.core.crisis.CrisisManager.tickAll(event.getServer());
        // Procreation loop: nightly mate-scan + active lovemaking-session tick. Cheap when no
        // one is sleeping in a home with eligible pairs (most of every day).
        com.bannerbound.core.social.BabyMakingManager.tickAll(event.getServer());
        // Throwaway crowd-LOD stress test (/bannerbound simulate). No-op unless a session is running.
        com.bannerbound.core.sim.SimulationManager.tickAll(event.getServer());
        // Throwaway long-distance traversal proof (/bannerbound trader_simulate). No-op unless running.
        com.bannerbound.core.sim.TraderSimManager.tickAll(event.getServer());
        // Barbarian camp seeding (and, in later phases, realize/clocks/raids). Self-gated to a scan
        // every N ticks; cheap no-op otherwise.
        com.bannerbound.core.barbarian.BarbarianCampManager.tickAll(event.getServer());
        // AI city-states: detect villages near players, run their abstract economy/evolution clock.
        // Self-gated to periodic passes; cheap no-op when none are near / none exist.
        com.bannerbound.core.citystate.CityStateManager.tickAll(event.getServer());
        // City-state war: pending countdowns, mercenary garrisons, capture timeouts. Cheap no-op
        // when no city-state is at war.
        com.bannerbound.core.citystate.CityStateWarManager.tickAll(event.getServer());
        // Ruination: razed/disbanded areas slowly crumble to ruins. No-op when nothing's crumbling.
        com.bannerbound.core.ruin.RuinManager.tickAll(event.getServer());
        // Settlement-to-settlement trade: offer expiry, war-cancel, escrow gathering, clock transit,
        // delivery/refund deposits. Self-gated to a sweep every 20 ticks; no-op with no deals.
        com.bannerbound.core.trade.TradeManager.tickAll(event.getServer());
    }
}
