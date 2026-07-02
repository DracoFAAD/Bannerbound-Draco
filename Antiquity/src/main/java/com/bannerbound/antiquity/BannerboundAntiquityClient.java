package com.bannerbound.antiquity;

import com.bannerbound.antiquity.client.BasketRenderer;
import com.bannerbound.antiquity.client.BasketScreen;
import com.bannerbound.antiquity.client.BloomeryModel;
import com.bannerbound.antiquity.client.BloomeryRenderer;
import com.bannerbound.antiquity.client.ChoppingStumpRenderer;
import com.bannerbound.antiquity.client.CraftingStoneRenderer;
import com.bannerbound.antiquity.client.KilnRenderer;
import com.bannerbound.antiquity.client.MortarAndPestleModel;
import com.bannerbound.antiquity.client.MortarAndPestleRenderer;
import com.bannerbound.antiquity.client.RaftModel;
import com.bannerbound.antiquity.client.RaftRenderer;
import com.bannerbound.antiquity.client.RopedPostKnotRenderer;
import com.bannerbound.antiquity.client.RopeFenceGateRenderer;
import com.bannerbound.antiquity.client.RopeFencePostRenderer;
import com.bannerbound.antiquity.client.BloodDropParticle;
import com.bannerbound.antiquity.client.GroundDecalRenderer;
import com.bannerbound.antiquity.client.SpearHandFlipModel;
import com.bannerbound.antiquity.client.SpearProjectileRenderer;
import com.bannerbound.antiquity.client.SpearedFishEntityRenderer;
import com.bannerbound.antiquity.client.StuckSpearLayer;

import java.util.Map;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = BannerboundAntiquity.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public class BannerboundAntiquityClient {
    public BannerboundAntiquityClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        // Custom-recipe managers are SERVER-data reload listeners, so on a remote client their static
        // lists never fill (no server in this JVM) — JEI shows no custom recipes and ghost previews go
        // blank for everyone but the host. This loader reads the same JSON from the mod's own file to
        // fill them client-side; it no-ops when an integrated server is present (it owns that data).
        event.registerReloadListener(new com.bannerbound.antiquity.client.ClientDatapackRecipes());
    }

    /** The poison screen overlay (wolfsbane: cold wash + tunnel vignette), deepening with stage —
     *  reads the synced POISON_STATE attachment (no custom payload, no Core import). */
    @SubscribeEvent
    static void onRegisterGuiLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        event.registerAboveAll(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                BannerboundAntiquity.MODID, "poison_overlay"),
            com.bannerbound.antiquity.client.PoisonHudOverlay::render);
        // Bloomery temperature readout below the crosshair (METALWORKING_PLAN.md Part 1).
        event.registerAboveAll(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                BannerboundAntiquity.MODID, "bloomery_state"),
            com.bannerbound.antiquity.client.BloomeryStateHudLayer.INSTANCE);
        event.registerAboveAll(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                BannerboundAntiquity.MODID, "crucible_state"),
            com.bannerbound.antiquity.client.CrucibleStateHudLayer.INSTANCE);
        // Cooking pot "what next" hint below the crosshair (fill → fire → add food → cooking → servings).
        event.registerAboveAll(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                BannerboundAntiquity.MODID, "cooking_pot_state"),
            com.bannerbound.antiquity.client.CookingPotStateHudLayer.INSTANCE);
    }

    /** The poison-vision post-process shader (desaturate / blur / vignette of the rendered scene). */
    @SubscribeEvent
    static void onRegisterShaders(net.neoforged.neoforge.client.event.RegisterShadersEvent event) {
        try {
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(
                    event.getResourceProvider(),
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        BannerboundAntiquity.MODID, "poison_vision"),
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX),
                com.bannerbound.antiquity.client.PoisonPostProcessor::setShader);
        } catch (java.io.IOException e) {
            BannerboundAntiquity.LOGGER.warn("poison_vision shader failed to load", e);
        }
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        BannerboundAntiquity.LOGGER.info("Bannerbound: Antiquity client setup.");
        // The green "Settlement food value" tooltip line reflects freshness: bland food shows half
        // (the client mirror of FoodSpoilage.BLAND_FOOD_MULTIPLIER; the synced component drives it).
        com.bannerbound.core.client.ClientFoodValueState.addModifier(stack -> {
            com.bannerbound.antiquity.item.FoodSpoilage fs =
                stack.get(BannerboundAntiquity.FOOD_SPOILAGE.get());
            return fs == null ? 1.0 : fs.foodMultiplier();
        });
        // Client mirror of the arrow knowledge gate: a modular arrow shows as ??? unless the civ knows
        // all its part materials (matches the server's ItemKnowledge gate, using client-side knowledge).
        com.bannerbound.core.client.UnknownItemHelper.registerStackGate(
            stack -> com.bannerbound.antiquity.item.ArrowParts.partsKnown(
                stack, com.bannerbound.core.client.UnknownItemHelper::isKnown));
        // The primitive bow's draw animation: vanilla only registers the pull/pulling model
        // predicates for minecraft:bow, so a custom bow must register its own (same lambdas as
        // vanilla's). ItemProperties.register isn't thread-safe → enqueueWork.
        event.enqueueWork(() -> {
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.PRIMITIVE_BOW.get(),
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("pull"),
                (stack, level, entity, seed) -> {
                    if (entity == null || entity.getUseItem() != stack) return 0.0F;
                    return (stack.getUseDuration(entity) - entity.getUseItemRemainingTicks()) / 20.0F;
                });
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.PRIMITIVE_BOW.get(),
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("pulling"),
                (stack, level, entity, seed) ->
                    entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1.0F : 0.0F);
            // 1.0 when the nocked ammo is a flint-tipped arrow → the draw frames swap to the _flint
            // sprites (a flint head on the string instead of the vanilla arrow's iron one).
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.PRIMITIVE_BOW.get(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    BannerboundAntiquity.MODID, "flint_ammo"),
                (stack, level, entity, seed) -> {
                    if (entity == null) return 0.0F;
                    net.minecraft.world.item.ItemStack ammo = entity.getProjectile(stack);
                    return ammo.getItem() instanceof com.bannerbound.antiquity.item.CompositeArrowItem
                        && com.bannerbound.antiquity.item.ArrowParts.tip(ammo).equals("flint")
                        ? 1.0F : 0.0F;
                });
            // Slingshot: the same pull/pulling draw predicates as the bow — pull ramps 0→1 over the
            // draw, pulling is 1 while held — so the item model swaps through the stretch-frame sprites.
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.SLINGSHOT.get(),
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("pull"),
                (stack, level, entity, seed) -> {
                    if (entity == null || entity.getUseItem() != stack) return 0.0F;
                    return (stack.getUseDuration(entity) - entity.getUseItemRemainingTicks()) / 20.0F;
                });
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.SLINGSHOT.get(),
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("pulling"),
                (stack, level, entity, seed) ->
                    entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1.0F : 0.0F);
            // Crucible: 1.0 once the stack carries molten metal → the item model's override swaps the
            // dry clay_crucible model for the molten one (clay_crucible_with_molten_metal).
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.CRUCIBLE.get(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    BannerboundAntiquity.MODID, "filled"),
                (stack, level, entity, seed) -> {
                    com.bannerbound.antiquity.item.CrucibleContents c =
                        stack.get(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
                    return c != null && c.molten() ? 1.0F : 0.0F; // only molten swaps to the molten model
                });
            // Blowgun: 1.0 while the player is drawing a breath (using the item) → the item model's
            // override swaps blowgun for the blowgun_draw "raised to the mouth" pose.
            net.minecraft.client.renderer.item.ItemProperties.register(
                BannerboundAntiquity.BLOWGUN.get(),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    BannerboundAntiquity.MODID, "drawing"),
                (stack, level, entity, seed) ->
                    entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1.0F : 0.0F);
            // Grog vessels: 1.0 once filled (has GROG_CONTENTS) → the model override swaps the empty
            // mug/horn for the *_full model (which carries the tinted alcohol layer).
            net.minecraft.resources.ResourceLocation grogFilled =
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "filled");
            for (net.minecraft.world.item.Item vessel : new net.minecraft.world.item.Item[] {
                    BannerboundAntiquity.MUG.get(), BannerboundAntiquity.GOAT_HORN.get() }) {
                net.minecraft.client.renderer.item.ItemProperties.register(vessel, grogFilled,
                    (stack, level, entity, seed) ->
                        stack.has(BannerboundAntiquity.GROG_CONTENTS.get()) ? 1.0F : 0.0F);
            }
        });
        // Create is a SOFT dependency. Only touch the Ponder bridge when Create is present —
        // the PonderBootstrap class imports net.createmod.ponder.* and would NoClassDefFoundError
        // on systems without it. Routing through the lambda here keeps the JVM from resolving
        // PonderBootstrap unless we actually call it.
        if (ModList.get().isLoaded("create")) {
            com.bannerbound.antiquity.client.ponder.PonderBootstrap.init();
        }
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Mortar and Pestle: a block entity renderer draws the body + the animated liquid.
        event.registerBlockEntityRenderer(BannerboundAntiquity.MORTAR_AND_PESTLE_BE.get(),
            MortarAndPestleRenderer::new);
        // Drying Rack: draws the items hung on the line + their drying cross-fade.
        event.registerBlockEntityRenderer(BannerboundAntiquity.DRYING_RACK_BE.get(),
            com.bannerbound.antiquity.client.DryingRackRenderer::new);
        // Fermentation Trough: draws the liquid surface (water now; tinted grog + bubbles in Phase 2).
        event.registerBlockEntityRenderer(BannerboundAntiquity.FERMENTATION_TROUGH_BE.get(),
            com.bannerbound.antiquity.client.FermentationTroughRenderer::new);
        // Stone Anvil: draws the placed mold + a floating preview of the casting once it cools.
        event.registerBlockEntityRenderer(BannerboundAntiquity.STONE_ANVIL_BE.get(),
            com.bannerbound.antiquity.client.StoneAnvilRenderer::new);
        // Bellows: animated block — plays the "Push" model animation on each jump.
        event.registerBlockEntityRenderer(BannerboundAntiquity.BELLOWS_BLOCK_BE.get(),
            com.bannerbound.antiquity.client.BellowsRenderer::new);
        // Crucible: draws the charged items sitting inside the bowl.
        event.registerBlockEntityRenderer(BannerboundAntiquity.CRUCIBLE_BLOCK_BE.get(),
            com.bannerbound.antiquity.client.CrucibleRenderer::new);
        // Stone Cooking Pot: draws the raw ingredients floating on the water before they cook to stew.
        event.registerBlockEntityRenderer(BannerboundAntiquity.STONE_COOKING_POT_BE.get(),
            com.bannerbound.antiquity.client.StoneCookingPotRenderer::new);
        // Basket: a block entity renderer draws the contents of the first slot on top.
        event.registerBlockEntityRenderer(BannerboundAntiquity.BASKET_BE.get(),
            BasketRenderer::new);
        // Bloomery: a block entity renderer draws the 1×1×2 model + door animation.
        event.registerBlockEntityRenderer(BannerboundAntiquity.BLOOMERY_BE.get(),
            BloomeryRenderer::new);
        // Kiln: a block entity renderer draws the 2×2×2 dome model, rotated to face the player.
        event.registerBlockEntityRenderer(BannerboundAntiquity.KILN_BE.get(),
            KilnRenderer::new);
        // Clay Tank: draws the curing-liquid surface inside the pillar (TANNERY plan).
        event.registerBlockEntityRenderer(BannerboundAntiquity.CLAY_TANK_BE.get(),
            com.bannerbound.antiquity.client.ClayTankRenderer::new);
        // Tanning Rack: draws the hide/leather lying on the rack (TANNERY plan).
        event.registerBlockEntityRenderer(BannerboundAntiquity.TANNING_RACK_BE.get(),
            com.bannerbound.antiquity.client.TanningRackRenderer::new);
        // Crafting Stone: draws the placed item pile + the floating spinning recipe result.
        event.registerBlockEntityRenderer(BannerboundAntiquity.CRAFTING_STONE_BE.get(),
            CraftingStoneRenderer::new);
        // Fletching Station: draws the placed item pile + the floating spinning result preview.
        event.registerBlockEntityRenderer(BannerboundAntiquity.FLETCHING_STATION_BE.get(),
            com.bannerbound.antiquity.client.FletchingStationRenderer::new);
        // Pottery Slab: draws the clay pile, cycleable recipe preview, and spinning clay.
        event.registerBlockEntityRenderer(BannerboundAntiquity.POTTERY_SLAB_BE.get(),
            com.bannerbound.antiquity.client.PotterySlabRenderer::new);
        // Carpenter's Table: draws the log budget, the build-list rows, the picker preview, and the
        // in-world saw minigame animation.
        event.registerBlockEntityRenderer(BannerboundAntiquity.WOODWORKING_TABLE_BE.get(),
            com.bannerbound.antiquity.client.WoodworkingTableRenderer::new);
        // Mason's Bench: deposited stone pile, picker ghost, and the in-world chisel-strike animation.
        event.registerBlockEntityRenderer(BannerboundAntiquity.MASONS_BENCH_BE.get(),
            com.bannerbound.antiquity.client.MasonsBenchRenderer::new);
        // Chopping Stump: draws the logs deposited on top, waiting to be chopped.
        event.registerBlockEntityRenderer(BannerboundAntiquity.CHOPPING_STUMP_BE.get(),
            ChoppingStumpRenderer::new);
        // Rope Fence Post: draws the leash-style rope to each connected tie point.
        event.registerBlockEntityRenderer(BannerboundAntiquity.ROPE_FENCE_POST_BE.get(),
            RopeFencePostRenderer::new);
        // Rope Fence Gate: draws the ropes tied to its two uprights.
        event.registerBlockEntityRenderer(BannerboundAntiquity.ROPE_FENCE_GATE_BE.get(),
            RopeFenceGateRenderer::new);
        // Thrown spear: draws the spear's 3D model oriented along flight.
        event.registerEntityRenderer(BannerboundAntiquity.SPEAR_PROJECTILE.get(),
            SpearProjectileRenderer::new);
        // Modular arrow in flight: ONE renderer draws the back/shaft/tip projectile layers from the
        // parts on the entity's pickup stack (covers every material combination).
        event.registerEntityRenderer(BannerboundAntiquity.ARROW_ENTITY.get(),
            com.bannerbound.antiquity.client.CompositeArrowRenderer::new);
        // Blowdart in flight: vanilla arrow rendering with a small dart texture.
        event.registerEntityRenderer(BannerboundAntiquity.BLOWDART_PROJECTILE.get(),
            com.bannerbound.antiquity.client.BlowdartRenderer::new);
        // Thrown rock: render the carried rock item, like a snowball.
        event.registerEntityRenderer(BannerboundAntiquity.THROWN_ROCK.get(),
            net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        // Ground decals: blood splats + footprint tracks (the hunting tracker).
        event.registerEntityRenderer(BannerboundAntiquity.GROUND_DECAL.get(),
            GroundDecalRenderer::new);
        // Spear-fishing catch: the spear angled in the water with the fish impaled on its tip.
        event.registerEntityRenderer(BannerboundAntiquity.SPEARED_FISH.get(),
            SpearedFishEntityRenderer::new);
        // Raft: a Boat-derived vehicle drawn with the raft model + keyframe paddle animations.
        event.registerEntityRenderer(BannerboundAntiquity.RAFT.get(),
            RaftRenderer::new);
        // Replace the vanilla leash-knot renderer with one that hides the knot on a "roped" rope-fence
        // post (a fiber-tied raft shows the post's with-rope model instead of a knot).
        event.registerEntityRenderer(net.minecraft.world.entity.EntityType.LEASH_KNOT,
            RopedPostKnotRenderer::new);
    }

    /**
     * Add the stuck-spear render layer to every living-entity renderer (all mobs) and both player
     * skins, so spears embedded in any mob (stored in the STUCK_SPEARS attachment) draw as part of
     * that mob — the vanilla arrows-in-a-body approach. The raw cast is the standard AddLayers idiom:
     * {@code getRenderer}/{@code getSkin} return a wildcard renderer, and {@code addLayer} just
     * appends to the renderer's own typed layer list (runtime-safe).
     */
    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        ItemRenderer itemRenderer = event.getContext().getItemRenderer();
        for (EntityType<?> type : event.getEntityTypes()) {
            EntityRenderer<?> renderer = event.getRenderer(type);
            if (renderer instanceof LivingEntityRenderer ler) {
                ler.addLayer(new StuckSpearLayer(ler, itemRenderer));
            }
        }
        for (PlayerSkin.Model skin : event.getSkins()) {
            EntityRenderer<?> renderer = event.getSkin(skin);
            if (renderer instanceof LivingEntityRenderer ler) {
                ler.addLayer(new StuckSpearLayer(ler, itemRenderer));
            }
        }
    }

    /**
     * Wrap each spear's baked inventory model with {@link SpearHandFlipModel}, which flips the held
     * model 180° only during the SPEAR throw wind-up. Re-applied on every resource reload (this event
     * fires each time); the wrap is idempotent via the instanceof guard.
     */
    @SubscribeEvent
    static void onRegisterParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(BannerboundAntiquity.BLOOD_DROP.get(), BloodDropParticle.Provider::new);
    }

    @SubscribeEvent
    static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        // Standalone 3D models for the transparent-cavity placed molds (drawn by StoneAnvilRenderer).
        for (String shape : com.bannerbound.antiquity.metalworking.MetalworkingItems.MOLD_SHAPES) {
            event.register(com.bannerbound.antiquity.client.StoneAnvilRenderer.placedMoldModel(shape));
        }
    }

    @SubscribeEvent
    static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        wrapSpearModel(models, BannerboundAntiquity.WOODEN_SPEAR.get());
        wrapSpearModel(models, BannerboundAntiquity.BONE_SPEAR.get());
        wrapSpearModel(models, BannerboundAntiquity.STONE_SPEAR.get());
    }

    private static void wrapSpearModel(Map<ModelResourceLocation, BakedModel> models, Item item) {
        ModelResourceLocation key = ModelResourceLocation.inventory(BuiltInRegistries.ITEM.getKey(item));
        BakedModel original = models.get(key);
        if (original != null && !(original instanceof SpearHandFlipModel)) {
            models.put(key, new SpearHandFlipModel(original));
        }
    }

    @SubscribeEvent
    static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(
            MortarAndPestleModel.LAYER_LOCATION, MortarAndPestleModel::createBodyLayer);
        event.registerLayerDefinition(
            BloomeryModel.LAYER_LOCATION, BloomeryModel::createBodyLayer);
        event.registerLayerDefinition(
            RaftModel.LAYER_LOCATION, RaftModel::createBodyLayer);
        event.registerLayerDefinition(
            com.bannerbound.antiquity.client.BellowsModel.LAYER_LOCATION,
            com.bannerbound.antiquity.client.BellowsModel::createBodyLayer);
        // Player-designed armor: the designed helmet's 3D geometry (ARMOR_PLAN.md), baked for the
        // Armorer's Workbench preview now and for worn rendering later.
        event.registerLayerDefinition(
            com.bannerbound.antiquity.client.HelmetModel.LAYER,
            com.bannerbound.antiquity.client.HelmetModel::createBodyLayer);
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(BannerboundAntiquity.BASKET_MENU.get(), BasketScreen::new);
    }

    /** Renders a picked-up basket's stored contents as a bundle-style slot grid on its tooltip. */
    @SubscribeEvent
    static void onRegisterTooltipComponents(
            net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(com.bannerbound.antiquity.item.BasketTooltip.class,
            com.bannerbound.antiquity.client.ClientBasketTooltip::new);
    }

    /** Screen shake for the cold-hammer minigame — rolls the camera on each strike (Iris-safe; it's
     *  just a view-angle nudge, not a post chain). */
    @SubscribeEvent
    static void onComputeCameraAngles(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        float roll = com.bannerbound.antiquity.client.HammerScreen.cameraShake();
        if (roll != 0F) {
            event.setRoll(event.getRoll() + roll);
        }
    }

    /** FOV pull-in during the cold-hammer swing — narrows as the head accelerates, then eases back. */
    @SubscribeEvent
    static void onHammerComputeFov(net.neoforged.neoforge.client.event.ComputeFovModifierEvent event) {
        float fov = com.bannerbound.antiquity.client.HammerScreen.fovEffect();
        if (fov != 1.0F) {
            event.setNewFovModifier(event.getNewFovModifier() * fov);
        }
    }

    /** Ease the third-person hammer-arm raise for every tracked player each client tick, feeding the
     *  local player live from the open minigame. The {@code PlayerArmRaiseMixin} reads these values. */
    @SubscribeEvent
    static void onClientTickArmRaise(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        java.util.UUID local = mc.player != null ? mc.player.getUUID() : null;
        com.bannerbound.antiquity.client.HammerArmState.tick(local,
            com.bannerbound.antiquity.client.HammerScreen.MINIGAME_ACTIVE);
    }

    /** Raise the first-person hammer arm overhead during the cold-hammer swing — cocked back at the top
     *  of the swing, brought down on impact (driven by {@link com.bannerbound.antiquity.client.HammerScreen#handRaise()}). */
    @SubscribeEvent
    static void onHammerRenderHand(net.neoforged.neoforge.client.event.RenderHandEvent event) {
        if (!com.bannerbound.antiquity.client.HammerScreen.MINIGAME_ACTIVE) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        float raise = com.bannerbound.antiquity.client.HammerScreen.handRaise();
        if (raise <= 0.001F) return;
        com.mojang.blaze3d.vertex.PoseStack ps = event.getPoseStack();
        ps.translate(0.0F, raise * 0.45F, raise * 0.12F);             // lift it up (and slightly toward the camera)
        ps.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-raise * 55F)); // cock the head back overhead
    }


    /**
     * Tint the crucible's molten layer (tint index 1 — shared by the 3D model's molten faces and the
     * GUI sprite's liquid layer) with the metal's display colour from {@link CrucibleContents}. Index 0
     * (the ceramic body / hot sprite) and an empty crucible are left untinted (-1 = white).
     */
    @SubscribeEvent
    static void onRegisterItemColors(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> {
            if (tintIndex != 1) return -1;
            com.bannerbound.antiquity.item.CrucibleContents contents =
                stack.get(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
            return contents == null ? -1 : (0xFF000000 | contents.tintColor());
        }, BannerboundAntiquity.CRUCIBLE.get());

        // Grog vessels: tint the alcohol layer (index 1) with the held grog's colour; index 0 (the
        // empty mug/horn) and an empty vessel stay untinted.
        event.register((stack, tintIndex) -> {
            if (tintIndex != 1) return -1;
            com.bannerbound.antiquity.item.GrogContents grog =
                stack.get(BannerboundAntiquity.GROG_CONTENTS.get());
            return grog == null ? -1 : (0xFF000000 | (grog.tint() & 0xFFFFFF));
        }, BannerboundAntiquity.MUG.get(), BannerboundAntiquity.GOAT_HORN.get());
    }

}
