package com.bannerbound.core;

import org.jetbrains.annotations.ApiStatus;

import org.slf4j.Logger;

import com.bannerbound.core.block.StockpileBlock;
import com.bannerbound.core.block.entity.StockpileBlockEntity;
import com.bannerbound.core.menu.StockpileMenu;
import com.bannerbound.core.entity.BarbarianEntity;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.FisherBobber;
import com.bannerbound.core.item.ForemansRodItem;
import com.bannerbound.core.item.HousingOrdersItem;
import com.bannerbound.core.item.RegistrationTabletItem;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.minecraft.network.codec.ByteBufCodecs;

/**
 * Mod entry. Owns all DeferredRegisters (blocks, items, components, sound events, creative tab)
 * and the {@link #MODID} constant used to namespace everything in the mod.
 * <p>
 * Architecture overview lives in {@code ARCHITECTURE.md} at the project root.
 * Anything that needs to be visible from both sides goes here; client-only registration lives in
 * {@link BannerboundCoreClient}.
 */
@Mod(BannerboundCore.MODID)
@ApiStatus.Internal
public class BannerboundCore {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "bannerbound";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "bannerbound" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "bannerbound" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "bannerbound" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Workstation blocks (Forester's Log, Digger's Slab, Farmer's Granary, Fisher's Creel,
    // Stockpile Rack, Forager's Basket) were removed — jobs are now assigned via the citizen Job
    // tab instead of placeable workstations. See the Forester rebuild on CitizenEntity.

    // Homes have no anchor block — they are defined purely by the Housing Orders rod's HOME
    // selections and validated by the Homes service + HomeRevalidator sweep (see HOUSING_ORDERS).

    // Stockpile — community-storage anchor (all logic Core; the antiquity-styled model/texture are
    // the only Antiquity-side pieces). BE auto-scans the fence/roof enclosure and aggregates the
    // containers inside; can be assigned directly as a worker drop-off (no Stocker — that's the
    // later Warehouse tier).
    public static final DeferredBlock<StockpileBlock> STOCKPILE = BLOCKS.register("stockpile",
        () -> new StockpileBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0f)
            .sound(SoundType.STONE)
            .noOcclusion()));
    public static final DeferredItem<BlockItem> STOCKPILE_ITEM = ITEMS.registerSimpleBlockItem("stockpile", STOCKPILE);

    // Outposts no longer have a dedicated block. A WORKING claim is established by planting a plain
    // FACTION BANNER near (outside) the border and confirming it via the banner's right-click
    // screen — see com.bannerbound.core.api.settlement.Outpost + FactionBannerEvents. The old
    // OutpostBannerBlock/item were removed; MINER_PLAN.md phase 4 carries the rest of the design.

    // Block entity types. The six workstation BEs and the House BE were removed with their blocks;
    // only the Stockpile BE remains.
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StockpileBlockEntity>> STOCKPILE_BE =
        BLOCK_ENTITY_TYPES.register("stockpile",
            () -> BlockEntityType.Builder.of(StockpileBlockEntity::new, STOCKPILE.get())
                .build(null));

    // Menu types. The Stockpile terminal is the lone container menu (workstation GUIs were removed).
    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredHolder<MenuType<?>, MenuType<StockpileMenu>> STOCKPILE_MENU =
        MENUS.register("stockpile", () -> IMenuTypeExtension.create(StockpileMenu::new));

    // Data component carried by Registration Paper items, holding the settlement name to join.
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(MODID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> SETTLEMENT_REF =
        COMPONENTS.registerComponentType("settlement_ref",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> TABLET_CHARGES =
        COMPONENTS.registerComponentType("tablet_charges",
            builder -> builder
                .persistent(Codec.INT)
                .networkSynchronized(ByteBufCodecs.VAR_INT));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> TABLET_MAX_CHARGES =
        COMPONENTS.registerComponentType("tablet_max_charges",
            builder -> builder
                .persistent(Codec.INT)
                .networkSynchronized(ByteBufCodecs.VAR_INT));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> STOLEN_STANDARD_SETTLEMENT =
        COMPONENTS.registerComponentType("stolen_standard_settlement",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> STOLEN_STANDARD_NAME =
        COMPONENTS.registerComponentType("stolen_standard_name",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    // Foreman's Rod components — workstation type + in-progress A/B between right-clicks.
    // Committed selections live in the server-side BlockSelectionRegistry, not on the rod (one
    // rod can author many independent selections; the rod itself only tracks the next-click state).
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> FOREMAN_WORKSTATION_TYPE =
        COMPONENTS.registerComponentType("foreman_workstation_type",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> FOREMAN_POINT_A =
        COMPONENTS.registerComponentType("foreman_point_a",
            builder -> builder
                .persistent(BlockPos.CODEC)
                .networkSynchronized(BlockPos.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> FOREMAN_POINT_B =
        COMPONENTS.registerComponentType("foreman_point_b",
            builder -> builder
                .persistent(BlockPos.CODEC)
                .networkSynchronized(BlockPos.STREAM_CODEC));
    /** Target citizen for an Ordered (digger) rod, as a UUID string. Empty / absent = "all workers
     *  of the type" — every digger works selections committed in this mode. Set/cleared via the
     *  Job tab's "Select work area" and shift-right-click on a digger / in the air. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> FOREMAN_TARGET_CITIZEN =
        COMPONENTS.registerComponentType("foreman_target_citizen",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));
    /** Display name of the bound digger (plain text), shown on the rod's name + tooltip. Absent for
     *  the "all diggers" mode. Paired with {@link #FOREMAN_TARGET_CITIZEN}. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> FOREMAN_TARGET_NAME =
        COMPONENTS.registerComponentType("foreman_target_name",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    // In-progress point A between right-clicks — shared by the Workshop Orders and Housing Orders
    // rods (both bind by id; their committed boxes live in BlockSelectionRegistry).
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> MARKER_POINT_A =
        COMPONENTS.registerComponentType("marker_point_a",
            builder -> builder
                .persistent(BlockPos.CODEC)
                .networkSynchronized(BlockPos.STREAM_CODEC));

    // Housing Orders rod binding — the bound home's id as a UUID string ("" = unbound). Homes have
    // no anchor block, so this binds by id, not pos (the Workshop Orders twin of BOUND_WORKSHOP_ID).
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> BOUND_HOME_ID =
        COMPONENTS.registerComponentType("bound_home_id",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    // Workshop Orders rod binding — the bound workshop's id as a UUID string ("" = unbound).
    // Workshops have no anchor block, so this binds by id, not pos.
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> BOUND_WORKSHOP_ID =
        COMPONENTS.registerComponentType("bound_workshop_id",
            builder -> builder
                .persistent(Codec.STRING)
                .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    // Craftsmanship quality (Crude…Masterwork; guild-only Perfect/Legendary). Attached to any
    // crafted tool/weapon by minigames or Crafter NPCs; read by stat hooks and tooltips. Canonical
    // cross-suite primitive (see com.bannerbound.core.api.quality + FLETCHING_PLAN.md Part 4).
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<com.bannerbound.core.api.quality.QualityTier>> TOOL_QUALITY =
        COMPONENTS.registerComponentType("tool_quality",
            builder -> builder
                .persistent(com.bannerbound.core.api.quality.QualityTier.CODEC)
                .networkSynchronized(com.bannerbound.core.api.quality.QualityTier.STREAM_CODEC));

    // Registration Tablet: right-click to join the encoded settlement (Antiquity-age document).
    public static final DeferredItem<RegistrationTabletItem> REGISTRATION_TABLET = ITEMS.registerItem("registration_tablet",
        props -> new RegistrationTabletItem(props.stacksTo(16)));

    // Registration Paper: the Medieval-and-later replacement for the tablet. Identical join
    // behaviour (same RegistrationTabletItem class + shared data components); only the era it's
    // issued in and the texture differ. See ServerPayloadHandler.handleGetRegistrationTablet.
    public static final DeferredItem<RegistrationTabletItem> REGISTRATION_PAPER = ITEMS.registerItem("registration_paper",
        props -> new RegistrationTabletItem(props.stacksTo(16)));

    // Foreman's Rod: placeholder stick texture; behavior in ForemansRodItem.
    public static final DeferredItem<ForemansRodItem> FOREMANS_ROD = ITEMS.registerItem("foremans_rod",
        props -> new ForemansRodItem(props.stacksTo(1)));

    // Housing Orders rod: draw A→B boxes that union into a home (first box with a bed creates it);
    // shift-right-click inside an existing home opens its status panel. No anchor block — the home
    // twin of the Workshop Orders rod. Same-home overlap allowed; tinted in settlement colour.
    public static final DeferredItem<HousingOrdersItem> HOUSING_ORDERS = ITEMS.registerItem("housing_orders",
        props -> new HousingOrdersItem(props.stacksTo(1)));

    // Workshop Orders rod: draw A→B boxes that union into a crafter Workshop (first box creates
    // it); shift-right-click inside an existing workshop opens its menu. See CRAFTER_PLAN.md.
    public static final DeferredItem<com.bannerbound.core.item.WorkshopRodItem> WORKSHOP_ROD =
        ITEMS.registerItem("workshop_rod",
            props -> new com.bannerbound.core.item.WorkshopRodItem(props.stacksTo(1)));

    // Entity types
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<CitizenEntity>> CITIZEN =
        ENTITY_TYPES.register("citizen",
            () -> EntityType.Builder.<CitizenEntity>of(CitizenEntity::new, MobCategory.CREATURE)
                .sized(0.6f, 1.95f)
                .clientTrackingRange(10)
                .build("citizen"));

    // Barbarian camp member — a distinct type subclassing CitizenEntity (reuses the citizen model/skin),
    // so same-class confusion is gone: alert-others rallies only barbarians, and citizen targeting is a
    // clean instanceof. Same body size as a citizen.
    public static final DeferredHolder<EntityType<?>, EntityType<BarbarianEntity>> BARBARIAN =
        ENTITY_TYPES.register("barbarian",
            () -> EntityType.Builder.<BarbarianEntity>of(BarbarianEntity::new, MobCategory.CREATURE)
                .sized(0.6f, 1.95f)
                .clientTrackingRange(10)
                .build("barbarian"));

    // City-state mercenary — hired defenders a city-state fields only while at war. Distinct type
    // (like the barbarian) so alert-others rallies only mercenaries; same body as a citizen.
    public static final DeferredHolder<EntityType<?>, EntityType<com.bannerbound.core.entity.MercenaryEntity>> MERCENARY =
        ENTITY_TYPES.register("mercenary",
            () -> EntityType.Builder.<com.bannerbound.core.entity.MercenaryEntity>of(
                    com.bannerbound.core.entity.MercenaryEntity::new, MobCategory.CREATURE)
                .sized(0.6f, 1.95f)
                .clientTrackingRange(10)
                .build("mercenary"));

    public static final DeferredHolder<EntityType<?>, EntityType<FisherBobber>> FISHER_BOBBER =
        ENTITY_TYPES.register("fisher_bobber",
            () -> EntityType.Builder.<FisherBobber>of(FisherBobber::new, MobCategory.MISC)
                .sized(0.25f, 0.25f)
                .clientTrackingRange(8)
                .updateInterval(2)
                .build("fisher_bobber"));

    // Custom sound events (loaded from assets/bannerbound/sounds.json + sounds/*.ogg)
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);
    public static final DeferredHolder<SoundEvent, SoundEvent> FOUND_SETTLEMENT_SOUND = SOUNDS.register(
        "found_settlement",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "found_settlement")));
    public static final DeferredHolder<SoundEvent, SoundEvent> MEDIEVAL_SETTLEMENT_SOUND = SOUNDS.register(
        "medieval_settlement",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "medieval_settlement")));
    /** Plays once per bubble in a citizen conversation — pop on bubble appearance. */
    public static final DeferredHolder<SoundEvent, SoundEvent> BUBBLE_POP_SOUND = SOUNDS.register(
        "bubble_pop",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "bubble_pop")));
    /** Fanfare when a faith is founded or adopted (FAITH_PLAN.md). */
    public static final DeferredHolder<SoundEvent, SoundEvent> FOUND_RELIGION_SOUND = SOUNDS.register(
        "found_religion",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "found_religion")));
    public static final DeferredHolder<SoundEvent, SoundEvent> INSIGHT_SOUND = SOUNDS.register(
        "insight",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "insight")));
    public static final DeferredHolder<SoundEvent, SoundEvent> CRISIS_MENU_OPEN_SOUND = SOUNDS.register(
        "crisis_menu_open",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "crisis_menu_open")));
    public static final DeferredHolder<SoundEvent, SoundEvent> CRISIS_MENU_CLOSE_SOUND = SOUNDS.register(
        "crisis_menu_close",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "crisis_menu_close")));

    /** Picks the celebratory jingle for advancing INTO the given era. */
    public static SoundEvent getAgeAdvanceSound(com.bannerbound.core.api.settlement.Era era) {
        return switch (era) {
            case MEDIEVAL -> MEDIEVAL_SETTLEMENT_SOUND.get();
            // Future ages: register their own sounds and add cases here.
            default -> net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
        };
    }

    // Bannerbound: Core creative tab. Town Hall/Chronicle are legacy-hidden; campfires and the J
    // key own those flows now.
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BANNERBOUND_TAB = CREATIVE_MODE_TABS.register("bannerbound_core", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.bannerbound"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> REGISTRATION_TABLET.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(REGISTRATION_TABLET.get());
                output.accept(REGISTRATION_PAPER.get());
                output.accept(FOREMANS_ROD.get());
                output.accept(STOCKPILE_ITEM.get());
                output.accept(HOUSING_ORDERS.get());
                output.accept(WORKSHOP_ROD.get());
            }).build());

    // ── Command argument types ───────────────────────────────────────────────────────────────────
    // Custom brigadier argument types must be registered so the command tree (and its tab-complete
    // suggestions) sync to clients. EraGameRuleArgument backs `/gamerule forceMaxAge <era>`.
    public static final DeferredRegister<net.minecraft.commands.synchronization.ArgumentTypeInfo<?, ?>> COMMAND_ARGUMENT_TYPES =
        DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, MODID);
    public static final DeferredHolder<net.minecraft.commands.synchronization.ArgumentTypeInfo<?, ?>,
            net.minecraft.commands.synchronization.SingletonArgumentInfo<com.bannerbound.core.command.EraGameRuleArgument>> ERA_GAMERULE_ARG =
        COMMAND_ARGUMENT_TYPES.register("era_gamerule",
            () -> net.minecraft.commands.synchronization.ArgumentTypeInfos.registerByClass(
                com.bannerbound.core.command.EraGameRuleArgument.class,
                net.minecraft.commands.synchronization.SingletonArgumentInfo.contextFree(
                    com.bannerbound.core.command.EraGameRuleArgument::era)));

    // ── Entity attachments ──────────────────────────────────────────────────────────────────────
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
        DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MODID);
    /** Entity id of the herder currently herding this animal (0 = none). SYNCED so the client can draw the
     *  cosmetic rope from the animal to its herder; transient (not serialized — re-claimed after reload). */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> HERDED_BY =
        ATTACHMENT_TYPES.register("herded_by",
            () -> AttachmentType.<Integer>builder(() -> 0).sync(ByteBufCodecs.VAR_INT).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public BannerboundCore(IEventBus modEventBus, ModContainer modContainer) {
        // Register custom game rules (e.g. globalChat) before any level loads.
        com.bannerbound.core.chat.BannerboundGameRules.register();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so data components get registered
        COMPONENTS.register(modEventBus);
        // Register sound events
        SOUNDS.register(modEventBus);
        // Register entity types
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(this::registerEntityAttributes);
        // Block entity + menu registries
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENUS.register(modEventBus);
        // Entity attachments (e.g. the herder's "herded by" claim, synced for the cosmetic rope)
        ATTACHMENT_TYPES.register(modEventBus);
        // Custom command argument types (e.g. the forceMaxAge era preset argument)
        COMMAND_ARGUMENT_TYPES.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (BannerboundCore) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void registerEntityAttributes(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
        event.put(CITIZEN.get(), CitizenEntity.createAttributes().build());
        event.put(BARBARIAN.get(), BarbarianEntity.createAttributes().build());
        event.put(MERCENARY.get(), com.bannerbound.core.entity.MercenaryEntity.createAttributes().build());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        // The Hunter job registers through Core's own public Job API (same path an expansion uses)
        // rather than the legacy hardcoded job sites. Research-gated via "bannerbound.unlock.hunter";
        // weapon resolves through the "hunt" tool-age role (bow once Archery is researched — see
        // JobTools.allowedToolsFor). enqueueWork is main-thread, before any citizen spawns.
        event.enqueueWork(() -> com.bannerbound.core.api.job.CitizenJobRegistry.register(
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                .builder(com.bannerbound.core.entity.HunterWorkGoal.JOB_TYPE_ID)
                .gatherer(true)
                .anarchyOrder(4)   // after forester / fishers / forager
                .unit("hunter")
                .icon(com.bannerbound.core.social.JobIcons.ROLE_HUNT,
                    net.minecraft.world.item.Items.WOODEN_SWORD)
                // Tool-OPTIONAL: an anarchy hunter self-organizes bare-handed (slow punches); under a
                // government the "hunt" weapon auto-installs from preferred storage like any tool.
                .toolRequired(false)
                .goal((c, s) -> new com.bannerbound.core.entity.HunterWorkGoal(c, s))
                .build()));

        // The Guard job (GUARD_PLAN.md): a settlement's standing watch — government-assigned (never an
        // anarchy auto-role), research-gated by "bannerbound.unlock.guard". Patrols the claim perimeter
        // (GuardWorkGoal) and fights approaching hostiles with leash-to-home combat (GuardCombatGoal, a
        // priority-0 goal wired into CitizenEntity.registerGoals). Tool-OPTIONAL: the "guard" tool-age
        // weapon auto-installs from storage when stocked, else combat conjures one (never defenceless).
        event.enqueueWork(() -> com.bannerbound.core.api.job.CitizenJobRegistry.register(
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                .builder(com.bannerbound.core.entity.GuardWorkGoal.JOB_TYPE_ID)
                .gatherer(false)   // government-assigned institution, never anarchy self-organizes
                .unit("guard")
                .icon("guard", net.minecraft.world.item.Items.WOODEN_SWORD)
                .toolRequired(false)
                .goal((c, s) -> new com.bannerbound.core.entity.GuardWorkGoal(c, s))
                .build()));

        // The Miner job (MINER_PLAN.md): ORDERED worker on ore resource chunks — chips the surface
        // boulder (ore→body state swap, never destroys blocks; MinerVeinRegen swaps them back) and
        // carries the yield to its drop-off. Marked via the Foreman's Rod "miner" type (single
        // click, herder-style point marker). Pickaxe is the primary tool via the "pickaxe"
        // tool-age role; research-gated by "bannerbound.unlock.miner" (Antiquity's Ore Mining).
        event.enqueueWork(() -> com.bannerbound.core.api.job.CitizenJobRegistry.register(
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                .builder(com.bannerbound.core.entity.MinerWorkGoal.JOB_TYPE_ID)
                .gatherer(false)   // ordered: works player-marked deposits, never self-organizes
                .unit("miner")
                .icon("pickaxe", net.minecraft.world.item.Items.WOODEN_PICKAXE)
                .toolRequired(true)
                .goal((c, s) -> new com.bannerbound.core.entity.MinerWorkGoal(c, s))
                .build()));

        // The Builder job (WALLS_PLAN.md Phase 4): ORDERED worker on the settlement's wall plan —
        // no marker, the workplace IS the plan (WallTasks derived board). Places blueprint blocks
        // (materials from depot/stockpiles, remote), clears footprint vegetation (logs banked),
        // demolishes obsolete wall (refunded). Tool-free v1 (bare-hand swings; a hammer tool can
        // gate speed later). Research-gated by "bannerbound.unlock.builder" (Antiquity's Wall
        // Building node).
        event.enqueueWork(() -> com.bannerbound.core.api.job.CitizenJobRegistry.register(
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                .builder(com.bannerbound.core.entity.BuilderWorkGoal.JOB_TYPE_ID)
                .gatherer(false)   // ordered: works the committed plan, never self-organizes
                .unit("builder")
                .icon("builder", net.minecraft.world.item.Items.BRICK)
                .toolRequired(false)
                .goal((c, s) -> new com.bannerbound.core.entity.BuilderWorkGoal(c, s))
                .build()));

        // The Crafter job (CRAFTER_PLAN.md): workshop-bound, tool-free, never a gatherer (crafting
        // needs a workshop assignment, not self-organization). Gated for now by the FIRST crafter
        // profession's research flag (bannerbound.unlock.fletcher); when more crafter professions
        // exist this generalizes to "any bannerbound.unlock.<crafter> flag". The subtype is DERIVED
        // from the assigned workshop's contents — there is exactly one job id.
        event.enqueueWork(() -> com.bannerbound.core.api.job.CitizenJobRegistry.register(
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                .builder(com.bannerbound.core.entity.CrafterWorkGoal.JOB_TYPE_ID)
                .gatherer(false)
                .unit("fletcher")
                .toolRequired(false)
                .workshopBound(null)
                .goal((c, s) -> new com.bannerbound.core.entity.CrafterWorkGoal(c, s))
                .build()));

        // The Stocker — the first pure-logistics worker: no tool, no marked work area; its haul
        // targets are auto-assigned from the settlement-wide StockerTasks board (workshop supply
        // in, workshop surplus out to the stockpile). Research-gated by the existing
        // "bannerbound.unlock.stocker" flag (Antiquity's Storage Logistics). Never a gatherer —
        // logistics needs a government, it doesn't self-organize in anarchy.
        event.enqueueWork(() -> com.bannerbound.core.api.job.CitizenJobRegistry.register(
            com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                .builder(com.bannerbound.core.entity.StockerWorkGoal.JOB_TYPE_ID)
                .gatherer(false)
                .unit("stocker")
                .icon("stocker", net.minecraft.world.item.Items.BUNDLE)
                .toolRequired(false)
                .goal((c, s) -> new com.bannerbound.core.entity.StockerWorkGoal(c, s))
                .build()));

        // Core fallback for any pottery-type workshop: vanilla infrastructure only. Expansions
        // with era-specific stations can replace this rule during their common setup.
        event.enqueueWork(() -> com.bannerbound.core.api.workshop.WorkBlockRegistry
            .registerRequirementIfAbsent("pottery", BannerboundCore::validateDefaultPotteryWorkshop));

        // A generic Crafter that can't resolve a station family shows a vanilla crafting table as
        // its icon (never iconless). Era expansions override this with their own general-crafts
        // station via WorkBlockRegistry.setDefaultCrafterType (Antiquity → the crafting stone).
        event.enqueueWork(() -> com.bannerbound.core.api.workshop.WorkBlockRegistry
            .setDefaultCrafterIconBaseline(net.minecraft.world.item.Items.CRAFTING_TABLE));

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    private static com.bannerbound.core.api.settlement.Workshop.Status validateDefaultPotteryWorkshop(
            net.minecraft.server.level.ServerLevel sl,
            com.bannerbound.core.api.settlement.Workshop workshop,
            java.util.Set<BlockPos> marked,
            java.util.List<BlockPos> reachableWork,
            java.util.List<BlockPos> reachableStorage) {
        if (!containsMarkedBlock(sl, marked, Blocks.FURNACE)) {
            return com.bannerbound.core.api.settlement.Workshop.Status.MISSING_HEAT_SOURCE;
        }
        if (!containsMarkedBlock(sl, marked, Blocks.CRAFTING_TABLE)) {
            return com.bannerbound.core.api.settlement.Workshop.Status.MISSING_CRAFTING_SURFACE;
        }
        return null;
    }

    private static boolean containsMarkedBlock(net.minecraft.server.level.ServerLevel sl,
                                               java.util.Set<BlockPos> marked,
                                               net.minecraft.world.level.block.Block block) {
        for (BlockPos pos : marked) {
            if (sl.getBlockState(pos).is(block)) {
                return true;
            }
        }
        return false;
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }

    @SubscribeEvent
    public void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        // Re-apply force-load on every settlement-claimed chunk. setChunkForced persists across
        // restarts, but a save edited externally (or a settlement loaded from an older save) won't
        // have the flag set yet — this ensures bannerbound chunks always come up loaded.
        com.bannerbound.core.faction.ChunkForceLoader.reapplyAll(event.getServer());
    }
}
