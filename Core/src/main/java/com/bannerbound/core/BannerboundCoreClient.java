package com.bannerbound.core;

import com.bannerbound.core.api.settlement.Citizen;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.client.CitizenRenderer;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = BannerboundCore.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public class BannerboundCoreClient {
    public BannerboundCoreClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        BannerboundCore.LOGGER.info("HELLO FROM CLIENT SETUP");
        BannerboundCore.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        // Extend the vanilla fishing-rod "cast" item predicate so a citizen who's currently
        // fishing also renders the bent (cast) rod variant.
        event.enqueueWork(com.bannerbound.core.client.FishingRodCastOverride::register);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Citizen uses vanilla ModelLayers.PLAYER (+ PLAYER_INNER/OUTER_ARMOR), which Mojang
        // pre-registers — no layer-definition registration needed on our side.
        event.registerEntityRenderer(BannerboundCore.CITIZEN.get(), CitizenRenderer::new);
        // Barbarians reuse the citizen renderer (same model/skin) — they're only a distinct logical type.
        event.registerEntityRenderer(BannerboundCore.BARBARIAN.get(),
            ctx -> new CitizenRenderer(ctx));
        // Mercenaries reuse the citizen renderer too (distinct logical type, same body/skin).
        event.registerEntityRenderer(BannerboundCore.MERCENARY.get(),
            ctx -> new CitizenRenderer(ctx));
        event.registerEntityRenderer(BannerboundCore.FISHER_BOBBER.get(),
            com.bannerbound.core.client.FisherBobberRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
        event.register(BannerboundCore.STOCKPILE_MENU.get(),
            com.bannerbound.core.client.StockpileScreen::new);
    }
}
