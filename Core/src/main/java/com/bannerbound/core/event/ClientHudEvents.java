package com.bannerbound.core.event;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.client.ChronicleToastLayer;
import com.bannerbound.core.client.DiplomacyHudLayer;
import com.bannerbound.core.client.EraYearHudLayer;
import com.bannerbound.core.client.JournalHudLayer;
import com.bannerbound.core.client.SettlementIndicatorLayer;
import com.bannerbound.core.client.SimulationHudLayer;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * Client-only registration of every Bannerbound HUD overlay via RegisterGuiLayersEvent. Each
 * layer is registered above all vanilla layers so the settlement indicators, era/year readout,
 * pantheon, warnings, journal, chronicle toasts, and debug/profiler overlays paint on top.
 * This is the single place a new HUD layer gets wired in. Must stay Dist.CLIENT - these layer
 * instances are client classes. onLoggingOut wipes the static warning-banner holds (food, raid,
 * town-hall unrest warnings) so a banner from one world can never leak into the next one the
 * client joins; the server re-sends the real level on login.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class ClientHudEvents {
    private ClientHudEvents() {
    }

    @SubscribeEvent
    public static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "settlement_indicator"),
            SettlementIndicatorLayer.INSTANCE
        );
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "era_year"),
            EraYearHudLayer.INSTANCE
        );
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "pantheon_hud"),
            com.bannerbound.core.client.sky.PantheonHudLayer.INSTANCE
        );
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "simulation_hud"),
            SimulationHudLayer.INSTANCE
        );
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "diplomacy_objective"),
            DiplomacyHudLayer.INSTANCE
        );
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "food_warning"),
            com.bannerbound.core.client.SettlementFoodWarningHudLayer.INSTANCE
        );
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "raid_warning"),
            com.bannerbound.core.client.RaidWarningHudLayer.INSTANCE
        );
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "barbarian_waypoint"),
            com.bannerbound.core.client.BarbarianWaypointRenderer.INSTANCE
        );
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "journal"),
            JournalHudLayer.INSTANCE
        );
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "chronicle_toast"),
            ChronicleToastLayer.INSTANCE
        );
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "citizen_ai_profiler"),
            com.bannerbound.core.client.CitizenAiProfilerHudLayer.INSTANCE
        );
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        com.bannerbound.core.client.ClientFoodWarningState.clear();
        com.bannerbound.core.client.ClientRaidWarningState.clear();
        com.bannerbound.core.client.ClientSettlementWarningsState.clear();
    }
}
