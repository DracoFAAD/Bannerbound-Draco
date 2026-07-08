package com.bannerbound.antiquity.compat.jade;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.BloomeryBlock;
import com.bannerbound.antiquity.block.ChoppingStumpBlock;
import com.bannerbound.antiquity.block.ClayTankBlock;
import com.bannerbound.antiquity.block.CraftingStoneBlock;
import com.bannerbound.antiquity.block.CrucibleBlock;
import com.bannerbound.antiquity.block.DryingRackBlock;
import com.bannerbound.antiquity.block.FermentationTroughBlock;
import com.bannerbound.antiquity.block.FletchingStationBlock;
import com.bannerbound.antiquity.block.KilnBlock;
import com.bannerbound.antiquity.block.MasonsBenchBlock;
import com.bannerbound.antiquity.block.MortarAndPestleBlock;
import com.bannerbound.antiquity.block.PotterySlabBlock;
import com.bannerbound.antiquity.block.StoneAnvilBlock;
import com.bannerbound.antiquity.block.StoneCookingPotBlock;
import com.bannerbound.antiquity.block.TanningRackBlock;
import com.bannerbound.antiquity.block.WoodworkingTableBlock;
import com.bannerbound.antiquity.block.WormCrateBlock;
import com.bannerbound.core.block.StockpileBlock;
import com.bannerbound.core.block.entity.StockpileBlockEntity;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CropBlock;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade plugin entry for Bannerbound (covers Core and Antiquity blocks alike -- Antiquity is the
 * only mod with Jade on its classpath and already depends on Core). Wires three layers: the
 * knowledge mask (JadeKnowledgeMask: ore-disguise accessor swap on ray trace, unknown-block wipe
 * after collection -- registered as callbacks, deliberately NOT user-toggleable since spoiler
 * protection is game design, not preference); the client-read body providers
 * (AntiquityJadeProviders, one per machine plus the appeal line on every block); and the
 * server-data pairs for state that never reaches the client BE (stockpile record + contents via
 * the universal item-storage channel, citizen job/settlement, livestock tamed flag). Jade
 * discovers this class through the @WailaPlugin annotation scan, so nothing here loads unless
 * Jade is actually installed; registerClient only runs on the client dist. The tooltip-collected
 * mask registers at priority 10000 so it runs after every other collector and nothing can append
 * below the wipe.
 */
@WailaPlugin
public class BannerboundAntiquityJadePlugin implements IWailaPlugin {
    public static final ResourceLocation STOCKPILE = id("stockpile");
    public static final ResourceLocation STOCKPILE_STORAGE = id("stockpile_storage");
    public static final ResourceLocation CITIZEN = id("citizen");
    public static final ResourceLocation LIVESTOCK = id("livestock");
    public static final ResourceLocation CROP_CHUNK = id("crop_chunk");
    public static final ResourceLocation BANNER = id("banner");
    public static final ResourceLocation TOWN_HALL = id("town_hall");

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, path);
    }

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(StockpileJadeSupport.ServerData.INSTANCE, StockpileBlockEntity.class);
        registration.registerItemStorage(StockpileJadeSupport.StorageServer.INSTANCE, StockpileBlockEntity.class);
        registration.registerEntityDataProvider(EntityJadeSupport.CitizenData.INSTANCE, CitizenEntity.class);
        registration.registerEntityDataProvider(EntityJadeSupport.LivestockData.INSTANCE, Animal.class);
        registration.registerBlockDataProvider(TerritoryJadeSupport.CropData.INSTANCE, CropBlock.class);
        registration.registerBlockDataProvider(TerritoryJadeSupport.BannerData.INSTANCE, AbstractBannerBlock.class);
        registration.registerBlockDataProvider(TerritoryJadeSupport.TownHallData.INSTANCE, CampfireBlock.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        JadeKnowledgeMask mask = new JadeKnowledgeMask(registration);
        registration.addRayTraceCallback(mask);
        registration.addTooltipCollectedCallback(10000, mask);

        registration.registerBlockComponent(AntiquityJadeProviders.APPEAL, Block.class);
        registration.registerBlockComponent(AntiquityJadeProviders.TERRITORY, Block.class);
        registration.registerBlockComponent(TerritoryJadeSupport.CropDisplay.INSTANCE, CropBlock.class);
        registration.registerBlockComponent(TerritoryJadeSupport.BannerDisplay.INSTANCE, AbstractBannerBlock.class);
        registration.registerBlockComponent(TerritoryJadeSupport.TownHallDisplay.INSTANCE, CampfireBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.KILN, KilnBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.BLOOMERY, BloomeryBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.FERMENTATION_TROUGH, FermentationTroughBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.CLAY_TANK, ClayTankBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.TANNING_RACK, TanningRackBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.DRYING_RACK, DryingRackBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.COOKING_POT, StoneCookingPotBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.CRUCIBLE, CrucibleBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.MORTAR, MortarAndPestleBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.CHOPPING_STUMP, ChoppingStumpBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.WORM_CRATE, WormCrateBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.WORKSTATION, CraftingStoneBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.WORKSTATION, FletchingStationBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.WORKSTATION, MasonsBenchBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.WORKSTATION, PotterySlabBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.WORKSTATION, WoodworkingTableBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.WORKSTATION, StoneAnvilBlock.class);
        registration.registerBlockComponent(AntiquityJadeProviders.STONE_ANVIL, StoneAnvilBlock.class);

        registration.registerBlockComponent(StockpileJadeSupport.Display.INSTANCE, StockpileBlock.class);
        registration.registerItemStorageClient(StockpileJadeSupport.StorageClient.INSTANCE);

        registration.registerEntityComponent(EntityJadeSupport.CitizenDisplay.INSTANCE, CitizenEntity.class);
        registration.registerEntityComponent(EntityJadeSupport.LivestockDisplay.INSTANCE, Animal.class);
    }
}
