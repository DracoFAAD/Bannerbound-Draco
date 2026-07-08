package com.bannerbound.antiquity;

import com.bannerbound.antiquity.block.*;
import com.bannerbound.antiquity.block.entity.*;
import com.bannerbound.antiquity.entity.*;
import net.minecraft.world.food.FoodProperties;
import org.slf4j.Logger;

import com.bannerbound.antiquity.item.ClayBucketItem;
import com.bannerbound.antiquity.item.ClayFilledBucketItem;
import com.bannerbound.antiquity.item.FireSticksItem;
import com.bannerbound.antiquity.item.PrimitiveBowItem;
import com.bannerbound.antiquity.item.FirewoodItem;
import com.bannerbound.antiquity.item.FlintKnifeItem;
import com.bannerbound.antiquity.item.KnifeItem;
import com.bannerbound.antiquity.item.PaintBrushItem;
import com.bannerbound.antiquity.item.FiberRopeItem;
import com.bannerbound.antiquity.item.ModTiers;
import com.bannerbound.antiquity.item.SpearItem;
import com.bannerbound.antiquity.world.inventory.BasketMenu;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import com.bannerbound.antiquity.workshop.MetalworkingItems;

/**
 * The @Mod main class for Bannerbound: Antiquity, the Antiquity-age expansion to Bannerbound: Core.
 * This is the static registration hub: DeferredRegisters and every content holder for blocks, items,
 * block entities, menus, the creative tab, sounds, entities, attachments, particles, data components
 * and worldgen features. The constructor binds those registers to the mod event bus and registers the
 * COMMON config; commonSetup (run via enqueueWork -> main thread, post-config-load, after Core loads)
 * plugs Antiquity into Core's public API surface (jobs, hunter/herder/forager/fisher hooks, larder +
 * food hooks, citizen goals + thoughts, barbarian projectiles, and the whole workshop framework).
 *
 * Division of labour: workstations and worker units stay in Core because they only navigate vanilla
 * mechanics; content lands here only when it needs mechanics vanilla lacks.
 *
 * Design notes worth keeping:
 * - Each AttachmentType encodes its own persistence policy. Transient immersive-hunting state
 *   (fear/stamina/bleed/footprint) is deliberately NOT serialized or synced, so it resets on chunk
 *   reload; poison/curare/intoxication clocks ARE serialized (and mostly synced) so they survive a
 *   relog; STUCK_SPEARS is natively synced and dropped on death but NOT copyOnDeath (we drop the
 *   items, never clone them onto a respawn).
 * - Bloomery and Kiln have no block item: they exist only as formed multiblock structures built
 *   in-world. Any multiblock work block MUST supply an anchorTest in its WorkBlockDef or one station
 *   over-counts worker slots (counted once per cell instead of once per station).
 * - The creative tab's displayItems list is the source of truth for WHICH items appear;
 *   registerCreativeSections only re-orders them into labelled bands (an unassigned item just appears
 *   first, ungrouped). ARMORERS_WORKBENCH is registered but hidden from the tab (parked, ARMOR_PLAN.md).
 * - commonSetup forces VanillaContentState override false: Antiquity is a from-scratch conversion, so
 *   vanilla external content (hostile spawns, portals, chest/barrel access) is stripped.
 */
@Mod(BannerboundAntiquity.MODID)
public class BannerboundAntiquity {
    public static final String MODID = "bannerboundantiquity";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(Registries.SOUND_EVENT, MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
        DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MODID);
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
        DeferredRegister.create(Registries.PARTICLE_TYPE, MODID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    public static final DeferredRegister<net.minecraft.world.level.levelgen.feature.Feature<?>> FEATURES =
        DeferredRegister.create(Registries.FEATURE, MODID);

    public static final DeferredHolder<net.minecraft.world.level.levelgen.feature.Feature<?>,
            com.bannerbound.antiquity.worldgen.DoublePlantFeature> DOUBLE_PLANT_FEATURE =
        FEATURES.register("double_plant", () -> new com.bannerbound.antiquity.worldgen.DoublePlantFeature(
            net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration.CODEC));

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> BLOOD_DROP =
        PARTICLE_TYPES.register("blood_drop", () -> new SimpleParticleType(false));

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<List<StuckSpear>>> STUCK_SPEARS =
        ATTACHMENT_TYPES.register("stuck_spears", () ->
            AttachmentType.<List<StuckSpear>>builder(() -> List.<StuckSpear>of())
                .serialize(StuckSpear.LIST_CODEC)
                .sync(StuckSpear.LIST_STREAM_CODEC)
                .build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> SCARED_UNTIL =
        ATTACHMENT_TYPES.register("scared_until", () -> AttachmentType.<Long>builder(() -> 0L).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> BOAR_CHARGE_CLAIM =
        ATTACHMENT_TYPES.register("boar_charge_claim", () -> AttachmentType.<Long>builder(() -> 0L).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Float>> HUNT_STAMINA =
        ATTACHMENT_TYPES.register("hunt_stamina", () -> AttachmentType.<Float>builder(() -> -1.0F).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> BLEED_TICKS =
        ATTACHMENT_TYPES.register("bleed_ticks", () -> AttachmentType.<Integer>builder(() -> 0).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> BLEED_BY =
            ATTACHMENT_TYPES.register("bleed_by", () -> AttachmentType.<String>builder(() -> "").build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> POISON_NEXT_VOMIT =
        ATTACHMENT_TYPES.register("poison_next_vomit", () -> AttachmentType.<Long>builder(() -> 0L).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> POISON_BY =
        ATTACHMENT_TYPES.register("poison_by", () -> AttachmentType.<String>builder(() -> "")
            .serialize(Codec.STRING)
            .build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> POISON_CARDIAC_AT =
        ATTACHMENT_TYPES.register("poison_cardiac_at", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG)
            .build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> POISON_CURARE_FAINT_AT =
        ATTACHMENT_TYPES.register("poison_curare_faint_at", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG)
            .build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> POISON_CURARE_WAKE_AT =
        ATTACHMENT_TYPES.register("poison_curare_wake_at", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG)
            .build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> POISON_CURARE_IMMUNE_UNTIL =
        ATTACHMENT_TYPES.register("poison_curare_immune_until", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG)
            .build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> POISON_FOOD_APPLY_AT =
        ATTACHMENT_TYPES.register("poison_food_apply_at", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG).build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> POISON_FOOD_TYPE =
        ATTACHMENT_TYPES.register("poison_food_type", () -> AttachmentType.<String>builder(() -> "")
            .serialize(Codec.STRING).build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> POISON_FOOD_STAGE =
        ATTACHMENT_TYPES.register("poison_food_stage", () -> AttachmentType.<Integer>builder(() -> 0)
            .serialize(Codec.INT).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> DRAGGED_BY =
        ATTACHMENT_TYPES.register("dragged_by", () -> AttachmentType.<Integer>builder(() -> 0)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_INT)
            .build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> DRAGGING =
        ATTACHMENT_TYPES.register("dragging", () -> AttachmentType.<Integer>builder(() -> 0).build());

    public static final DeferredHolder<AttachmentType<?>,
            AttachmentType<com.bannerbound.antiquity.poison.PoisonState>> POISON_STATE =
        ATTACHMENT_TYPES.register("poison_state", () ->
            AttachmentType.<com.bannerbound.antiquity.poison.PoisonState>builder(
                    () -> com.bannerbound.antiquity.poison.PoisonState.NONE)
                .serialize(com.bannerbound.antiquity.poison.PoisonState.CODEC)
                .sync(com.bannerbound.antiquity.poison.PoisonState.STREAM_CODEC)
                .build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> STUN_UNTIL =
        ATTACHMENT_TYPES.register("stun_until", () -> AttachmentType.<Long>builder(() -> 0L)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG)
            .build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Float>> FOOTPRINT_DIST =
        ATTACHMENT_TYPES.register("footprint_dist", () -> AttachmentType.<Float>builder(() -> 0.0F).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> INTOXICATION_LEVEL =
        ATTACHMENT_TYPES.register("intoxication_level", () -> AttachmentType.<Integer>builder(() -> 0)
            .serialize(Codec.INT)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_INT).build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> INTOXICATION_LAST_SIP =
        ATTACHMENT_TYPES.register("intoxication_last_sip", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> PASS_OUT_UNTIL =
        ATTACHMENT_TYPES.register("pass_out_until", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> HANGOVER_UNTIL =
        ATTACHMENT_TYPES.register("hangover_until", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> VOMIT_OVERLAY_UNTIL =
        ATTACHMENT_TYPES.register("vomit_overlay_until", () -> AttachmentType.<Long>builder(() -> 0L)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Boolean>> TAMED_LIVESTOCK =
        ATTACHMENT_TYPES.register("tamed_livestock",
            () -> AttachmentType.<Boolean>builder(() -> false).serialize(Codec.BOOL).build());

    public static final DeferredHolder<AttachmentType<?>,
            AttachmentType<com.bannerbound.antiquity.deco.ChunkDecorations>> CHUNK_DECORATIONS =
        ATTACHMENT_TYPES.register("face_decorations", () ->
            AttachmentType.builder(() -> new com.bannerbound.antiquity.deco.ChunkDecorations())
                .serialize(com.bannerbound.antiquity.deco.ChunkDecorations.CODEC)
                .build());

    public static final ResourceKey<DamageType> BLEEDING_DAMAGE = ResourceKey.create(
        Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(MODID, "bleeding"));

    public static final ResourceKey<DamageType> POISON_DAMAGE = ResourceKey.create(
        Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(MODID, "poison"));

    public static final DeferredHolder<SoundEvent, SoundEvent> BLOOMERY_OPEN_SOUND = SOUNDS.register(
        "bloomery_open",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "bloomery_open")));
    public static final DeferredHolder<SoundEvent, SoundEvent> BLOOMERY_CLOSE_SOUND = SOUNDS.register(
        "bloomery_close",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "bloomery_close")));
    public static final DeferredHolder<SoundEvent, SoundEvent> BELLOWS_USE_SOUND = SOUNDS.register(
        "bellows_use",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "bellows_use")));
    public static final DeferredHolder<SoundEvent, SoundEvent> SMELTING_DONE_SOUND = SOUNDS.register(
        "smelting_done",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "smelting_done")));

    public static final DeferredHolder<SoundEvent, SoundEvent> KNAPPING_SOUND = SOUNDS.register(
        "knapping",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "knapping")));

    public static final DeferredHolder<SoundEvent, SoundEvent> HAMMER_POOR_SOUND = SOUNDS.register(
        "hammer_poor",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "hammer_poor")));
    public static final DeferredHolder<SoundEvent, SoundEvent> HAMMER_GOOD_SOUND = SOUNDS.register(
        "hammer_good",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "hammer_good")));
    public static final DeferredHolder<SoundEvent, SoundEvent> HAMMER_GREAT_SOUND = SOUNDS.register(
        "hammer_great",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "hammer_great")));
    public static final DeferredHolder<SoundEvent, SoundEvent> HAMMER_PERFECT_SOUND = SOUNDS.register(
        "hammer_perfect",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "hammer_perfect")));

    public static final DeferredHolder<SoundEvent, SoundEvent> FLETCHING_STRETCH_SOUND = SOUNDS.register(
        "fletching_stretch",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "fletching_stretch")));

    public static final DeferredHolder<SoundEvent, SoundEvent> SAW_SOUND = SOUNDS.register(
        "saw",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "saw")));

    public static final DeferredHolder<SoundEvent, SoundEvent> SAW_DONE_SOUND = SOUNDS.register(
        "saw_done",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "saw_done")));
    public static final DeferredHolder<SoundEvent, SoundEvent> CRISIS_ANTIQUITY_SOUND = SOUNDS.register(
        "crisis_antiquity",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "crisis_antiquity")));

    public static final DeferredHolder<SoundEvent, SoundEvent> SPEAR_HOLD_SOUND = SOUNDS.register(
        "spear_hold",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "spear_hold")));
    public static final DeferredHolder<SoundEvent, SoundEvent> SPEAR_THROW_SOUND = SOUNDS.register(
        "spear_throw",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "spear_throw")));
    public static final DeferredHolder<SoundEvent, SoundEvent> SPEAR_THROW_ROPE_SOUND = SOUNDS.register(
        "spear_throw_rope",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "spear_throw_rope")));
    public static final DeferredHolder<SoundEvent, SoundEvent> SPEAR_HIT_BLOCK_SOUND = SOUNDS.register(
        "spear_hit_block",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "spear_hit_block")));
    public static final DeferredHolder<SoundEvent, SoundEvent> SPEAR_HIT_FLESH_SOUND = SOUNDS.register(
        "spear_hit_flesh",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "spear_hit_flesh")));
    public static final DeferredHolder<SoundEvent, SoundEvent> SPEAR_REEL_SOUND = SOUNDS.register(
        "spear_reel",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "spear_reel")));

    public static final DeferredHolder<SoundEvent, SoundEvent> WOLFSBANE_AMBIENCE_1 = SOUNDS.register(
        "wolfsbane_ambience_1",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "wolfsbane_ambience_1")));
    public static final DeferredHolder<SoundEvent, SoundEvent> WOLFSBANE_AMBIENCE_2 = SOUNDS.register(
        "wolfsbane_ambience_2",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "wolfsbane_ambience_2")));
    public static final DeferredHolder<SoundEvent, SoundEvent> WOLFSBANE_AMBIENCE_3 = SOUNDS.register(
        "wolfsbane_ambience_3",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "wolfsbane_ambience_3")));
    public static final DeferredHolder<SoundEvent, SoundEvent> WOLFSBANE_AMBIENCE_4 = SOUNDS.register(
        "wolfsbane_ambience_4",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "wolfsbane_ambience_4")));
    public static final DeferredHolder<SoundEvent, SoundEvent> WOLFSBANE_HIT_CLIENT = SOUNDS.register(
        "wolfsbane_hit_client",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "wolfsbane_hit_client")));
    public static final DeferredHolder<SoundEvent, SoundEvent> WOLFSBANE_HEAL_CLIENT = SOUNDS.register(
        "wolfsbane_heal_client",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "wolfsbane_heal_client")));
    public static final DeferredHolder<SoundEvent, SoundEvent> WOLFSBANE_BELCH = SOUNDS.register(
        "wolfsbane_belch",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "wolfsbane_belch")));

    public static final DeferredHolder<SoundEvent, SoundEvent> BLOWGUN_SHOOT = SOUNDS.register(
        "blowgun_shoot",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "blowgun_shoot")));

    public static final DeferredHolder<SoundEvent, SoundEvent> SLING_PULL = SOUNDS.register(
        "sling_pull",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "sling_pull")));
    public static final DeferredHolder<SoundEvent, SoundEvent> SLING_SHOT = SOUNDS.register(
        "sling_shot",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "sling_shot")));
    public static final DeferredHolder<SoundEvent, SoundEvent> ROCK_IMPACT = SOUNDS.register(
        "rock_impact",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "rock_impact")));

    public static final DeferredHolder<SoundEvent, SoundEvent> BELLADONNA_AMBIENCE_1 = SOUNDS.register(
        "belladonna_ambience_1",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "belladonna_ambience_1")));
    public static final DeferredHolder<SoundEvent, SoundEvent> BELLADONNA_AMBIENCE_2 = SOUNDS.register(
        "belladonna_ambience_2",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "belladonna_ambience_2")));
    public static final DeferredHolder<SoundEvent, SoundEvent> BELLADONNA_AMBIENCE_3 = SOUNDS.register(
        "belladonna_ambience_3",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "belladonna_ambience_3")));
    public static final DeferredHolder<SoundEvent, SoundEvent> BELLADONNA_AMBIENCE_4 = SOUNDS.register(
        "belladonna_ambience_4",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "belladonna_ambience_4")));
    public static final DeferredHolder<SoundEvent, SoundEvent> BELLADONNA_HIT_CLIENT = SOUNDS.register(
        "belladonna_hit_client",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "belladonna_hit_client")));
    public static final DeferredHolder<SoundEvent, SoundEvent> BELLADONNA_HEAL_CLIENT = SOUNDS.register(
        "belladonna_heal_client",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "belladonna_heal_client")));

    public static final DeferredHolder<SoundEvent, SoundEvent> OLEANDER_HEARTBEAT = SOUNDS.register(
        "oleander_heartbeat",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "oleander_heartbeat")));

    // Plain SoundEvent constants (not just DeferredHolders): THATCH_SOUND/THATCH_SET_TYPE below need the instances at class-load; the holders register these SAME instances.
    public static final SoundEvent THATCH_BREAK_SOUND =
        SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "thatch_break"));
    public static final SoundEvent THATCH_PLACE_SOUND =
        SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "thatch_place"));
    public static final SoundEvent THATCH_DOOR_OPEN_SOUND =
        SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "thatch_door_open"));
    public static final SoundEvent THATCH_DOOR_CLOSE_SOUND =
        SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "thatch_door_close"));
    public static final DeferredHolder<SoundEvent, SoundEvent> THATCH_BREAK_REG =
        SOUNDS.register("thatch_break", () -> THATCH_BREAK_SOUND);
    public static final DeferredHolder<SoundEvent, SoundEvent> THATCH_PLACE_REG =
        SOUNDS.register("thatch_place", () -> THATCH_PLACE_SOUND);
    public static final DeferredHolder<SoundEvent, SoundEvent> THATCH_DOOR_OPEN_REG =
        SOUNDS.register("thatch_door_open", () -> THATCH_DOOR_OPEN_SOUND);
    public static final DeferredHolder<SoundEvent, SoundEvent> THATCH_DOOR_CLOSE_REG =
        SOUNDS.register("thatch_door_close", () -> THATCH_DOOR_CLOSE_SOUND);

    public static final SoundType THATCH_SOUND = new SoundType(1.0f, 1.0f,
        THATCH_BREAK_SOUND, SoundEvents.GRASS_STEP, THATCH_PLACE_SOUND,
        SoundEvents.GRASS_HIT, SoundEvents.GRASS_FALL);

    public static final BlockSetType THATCH_SET_TYPE = BlockSetType.register(new BlockSetType(
        MODID + ":thatch", true, true, true,
        BlockSetType.PressurePlateSensitivity.EVERYTHING, THATCH_SOUND,
        THATCH_DOOR_CLOSE_SOUND, THATCH_DOOR_OPEN_SOUND,
        SoundEvents.WOODEN_TRAPDOOR_CLOSE, SoundEvents.WOODEN_TRAPDOOR_OPEN,
        SoundEvents.WOODEN_PRESSURE_PLATE_CLICK_OFF, SoundEvents.WOODEN_PRESSURE_PLATE_CLICK_ON,
        SoundEvents.WOODEN_BUTTON_CLICK_OFF, SoundEvents.WOODEN_BUTTON_CLICK_ON));

    public static final DeferredBlock<MortarAndPestleBlock> MORTAR_AND_PESTLE = BLOCKS.register("mortar_and_pestle",
        () -> new MortarAndPestleBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(1.5f)
            .sound(SoundType.STONE)
            .noOcclusion()));
    public static final DeferredItem<BlockItem> MORTAR_AND_PESTLE_ITEM =
        ITEMS.registerSimpleBlockItem("mortar_and_pestle", MORTAR_AND_PESTLE);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MortarAndPestleBlockEntity>> MORTAR_AND_PESTLE_BE =
        BLOCK_ENTITY_TYPES.register("mortar_and_pestle",
            () -> BlockEntityType.Builder.of(MortarAndPestleBlockEntity::new, MORTAR_AND_PESTLE.get())
                .build(null));

    public static final DeferredBlock<BasketBlock> BASKET = BLOCKS.register("basket",
        () -> new BasketBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(1.5f)
            .sound(SoundType.BAMBOO_WOOD)
            .noOcclusion()));
    public static final DeferredItem<com.bannerbound.antiquity.item.BasketBlockItem> BASKET_ITEM =
        ITEMS.registerItem("basket",
            props -> new com.bannerbound.antiquity.item.BasketBlockItem(BASKET.get(), props),
            new Item.Properties());
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BasketBlockEntity>> BASKET_BE =
        BLOCK_ENTITY_TYPES.register("basket",
            () -> BlockEntityType.Builder.of(BasketBlockEntity::new, BASKET.get())
                .build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<BasketMenu>> BASKET_MENU =
        MENUS.register("basket", () -> IMenuTypeExtension.create(BasketMenu::new));

    public static final DeferredBlock<BloomeryBlock> BLOOMERY = BLOCKS.register("bloomery",
        () -> new BloomeryBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.TERRACOTTA_BROWN)
            .strength(3.0f, 6.0f)
            .sound(SoundType.TUFF_BRICKS)
            .requiresCorrectToolForDrops()
            .noOcclusion()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BloomeryBlockEntity>> BLOOMERY_BE =
        BLOCK_ENTITY_TYPES.register("bloomery",
            () -> BlockEntityType.Builder.of(BloomeryBlockEntity::new, BLOOMERY.get())
                .build(null));

    public static final DeferredBlock<ClayedCobblestoneBlock> CLAYED_COBBLESTONE = BLOCKS.register("clayed_cobblestone",
        () -> new ClayedCobblestoneBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0f, 6.0f)
            .requiresCorrectToolForDrops()
            .sound(SoundType.STONE)));
    public static final DeferredItem<BlockItem> CLAYED_COBBLESTONE_ITEM =
        ITEMS.registerSimpleBlockItem("clayed_cobblestone", CLAYED_COBBLESTONE);

    public static final DeferredBlock<KilnBlock> KILN = BLOCKS.register("kiln",
        () -> new KilnBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.TERRACOTTA_ORANGE)
            .strength(KilnBlock.DESTROY_TIME, 8.0f)
            .sound(SoundType.STONE)
            .requiresCorrectToolForDrops()
            .lightLevel(KilnBlock::lightEmission)
            .noOcclusion()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<KilnBlockEntity>> KILN_BE =
        BLOCK_ENTITY_TYPES.register("kiln",
            () -> BlockEntityType.Builder.of(KilnBlockEntity::new, KILN.get())
                .build(null));

    public static final DeferredBlock<com.bannerbound.antiquity.block.StoneCookingPotBlock> STONE_COOKING_POT =
        BLOCKS.register("stone_cooking_pot",
            () -> new com.bannerbound.antiquity.block.StoneCookingPotBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(2.0f, 6.0f)
                .sound(SoundType.STONE)
                .noOcclusion()));

    public static final DeferredBlock<com.bannerbound.antiquity.block.CookingFireBlock> COOKING_FIRE =
        BLOCKS.register("cooking_fire",
            () -> new com.bannerbound.antiquity.block.CookingFireBlock(
                BlockBehaviour.Properties.ofFullCopy(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                    .lightLevel(s -> s.getValue(net.minecraft.world.level.block.CampfireBlock.LIT) ? 10 : 0)));
    public static final DeferredItem<com.bannerbound.antiquity.item.StoneCookingPotItem> STONE_COOKING_POT_ITEM =
        ITEMS.register("stone_cooking_pot",
            () -> new com.bannerbound.antiquity.item.StoneCookingPotItem(STONE_COOKING_POT.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.bannerbound.antiquity.block.entity.StoneCookingPotBlockEntity>> STONE_COOKING_POT_BE =
        BLOCK_ENTITY_TYPES.register("stone_cooking_pot",
            () -> BlockEntityType.Builder.of(
                com.bannerbound.antiquity.block.entity.StoneCookingPotBlockEntity::new, STONE_COOKING_POT.get())
                .build(null));

    public static final DeferredBlock<com.bannerbound.antiquity.block.ClayTankBlock> CLAY_TANK =
        BLOCKS.register("clay_tank",
            () -> new com.bannerbound.antiquity.block.ClayTankBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.TERRACOTTA_ORANGE)
                .strength(1.5f, 6.0f)
                .sound(SoundType.STONE)
                .requiresCorrectToolForDrops()
                .noOcclusion()));
    public static final DeferredItem<BlockItem> CLAY_TANK_ITEM =
        ITEMS.registerSimpleBlockItem("clay_tank", CLAY_TANK);

    public static final DeferredItem<Item> UNFIRED_CLAY_TANK =
        ITEMS.registerSimpleItem("unfired_clay_tank", new Item.Properties());
    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.bannerbound.antiquity.block.entity.ClayTankBlockEntity>> CLAY_TANK_BE =
        BLOCK_ENTITY_TYPES.register("clay_tank",
            () -> BlockEntityType.Builder.of(
                com.bannerbound.antiquity.block.entity.ClayTankBlockEntity::new, CLAY_TANK.get())
                .build(null));

    public static final DeferredBlock<com.bannerbound.antiquity.block.TanningRackBlock> TANNING_RACK =
        BLOCKS.register("tanning_rack",
            () -> new com.bannerbound.antiquity.block.TanningRackBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(1.5f)
                .sound(SoundType.WOOD)
                .noOcclusion()));
    public static final DeferredItem<BlockItem> TANNING_RACK_ITEM =
        ITEMS.registerSimpleBlockItem("tanning_rack", TANNING_RACK);
    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.bannerbound.antiquity.block.entity.TanningRackBlockEntity>> TANNING_RACK_BE =
        BLOCK_ENTITY_TYPES.register("tanning_rack",
            () -> BlockEntityType.Builder.of(
                com.bannerbound.antiquity.block.entity.TanningRackBlockEntity::new, TANNING_RACK.get())
                .build(null));

    public static final DeferredBlock<CraftingStoneBlock> CRAFTING_STONE = BLOCKS.register("crafting_stone",
        () -> new CraftingStoneBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0f, 6.0f)
            .requiresCorrectToolForDrops()
            .sound(SoundType.STONE)
            .noOcclusion()));

    public static final DeferredItem<BlockItem> CRAFTING_STONE_ITEM =
        ITEMS.registerSimpleBlockItem("crafting_stone", CRAFTING_STONE);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CraftingStoneBlockEntity>> CRAFTING_STONE_BE =
        BLOCK_ENTITY_TYPES.register("crafting_stone",
            () -> BlockEntityType.Builder.of(CraftingStoneBlockEntity::new, CRAFTING_STONE.get())
                .build(null));

    // Worm Crate
    public static final DeferredBlock<WormCrateBlock> WORM_CRATE = BLOCKS.register("worm_crate",
        () -> new WormCrateBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0f, 6.0f)
                .requiresCorrectToolForDrops()
                .sound(SoundType.WOOD)
                .noOcclusion()
        ));

    public static final DeferredItem<BlockItem> WORM_CRATE_ITEM =
            ITEMS.registerSimpleBlockItem("worm_crate", WORM_CRATE);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WormCrateBlockEntity>> WORM_CRATE_BE =
            BLOCK_ENTITY_TYPES.register("worm_crate", () -> BlockEntityType.Builder.of(WormCrateBlockEntity::new, WORM_CRATE.get()).build(null));
    public static final DeferredBlock<FletchingStationBlock> FLETCHING_STATION = BLOCKS.register("fletching_station",
        () -> new FletchingStationBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0f, 6.0f)
            .requiresCorrectToolForDrops()
            .sound(SoundType.STONE)
            .noOcclusion()));
    public static final DeferredItem<BlockItem> FLETCHING_STATION_ITEM =
        ITEMS.registerSimpleBlockItem("fletching_station", FLETCHING_STATION);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FletchingStationBlockEntity>> FLETCHING_STATION_BE =
        BLOCK_ENTITY_TYPES.register("fletching_station",
            () -> BlockEntityType.Builder.of(FletchingStationBlockEntity::new, FLETCHING_STATION.get())
                .build(null));

    public static final DeferredBlock<PotterySlabBlock> POTTERY_SLAB = BLOCKS.register("pottery_slab",
        () -> new PotterySlabBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.TERRACOTTA_ORANGE)
            .strength(1.5f, 4.0f)
            .requiresCorrectToolForDrops()
            .sound(SoundType.STONE)
            .noOcclusion()));
    public static final DeferredItem<BlockItem> POTTERY_SLAB_ITEM =
        ITEMS.registerSimpleBlockItem("pottery_slab", POTTERY_SLAB);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PotterySlabBlockEntity>> POTTERY_SLAB_BE =
        BLOCK_ENTITY_TYPES.register("pottery_slab",
            () -> BlockEntityType.Builder.of(PotterySlabBlockEntity::new, POTTERY_SLAB.get())
                .build(null));

    public static final DeferredBlock<WoodworkingTableBlock> WOODWORKING_TABLE = BLOCKS.register("woodworking_table",
        () -> new WoodworkingTableBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.0f, 3.0f)
            .sound(SoundType.WOOD)
            .noOcclusion()));
    public static final DeferredItem<BlockItem> WOODWORKING_TABLE_ITEM =
        ITEMS.registerSimpleBlockItem("woodworking_table", WOODWORKING_TABLE);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WoodworkingTableBlockEntity>> WOODWORKING_TABLE_BE =
        BLOCK_ENTITY_TYPES.register("woodworking_table",
            () -> BlockEntityType.Builder.of(WoodworkingTableBlockEntity::new, WOODWORKING_TABLE.get())
                .build(null));

    public static final DeferredBlock<MasonsBenchBlock> MASONS_BENCH = BLOCKS.register("masons_bench",
        () -> new MasonsBenchBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0f, 6.0f)
            .sound(SoundType.STONE)
            .noOcclusion()));
    public static final DeferredItem<BlockItem> MASONS_BENCH_ITEM =
        ITEMS.registerSimpleBlockItem("masons_bench", MASONS_BENCH);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MasonsBenchBlockEntity>> MASONS_BENCH_BE =
        BLOCK_ENTITY_TYPES.register("masons_bench",
            () -> BlockEntityType.Builder.of(MasonsBenchBlockEntity::new, MASONS_BENCH.get())
                .build(null));

    public static final DeferredBlock<com.bannerbound.antiquity.block.ArmorersWorkbenchBlock> ARMORERS_WORKBENCH =
        BLOCKS.register("armorers_workbench",
            () -> new com.bannerbound.antiquity.block.ArmorersWorkbenchBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0f, 3.0f)
                .sound(SoundType.WOOD)
                .noOcclusion()));
    public static final DeferredItem<BlockItem> ARMORERS_WORKBENCH_ITEM =
        ITEMS.registerSimpleBlockItem("armorers_workbench", ARMORERS_WORKBENCH);

    private static BlockBehaviour.Properties rockProps(MapColor color) {
        return BlockBehaviour.Properties.of()
            .mapColor(color)
            .instabreak()
            .sound(SoundType.STONE)
            .pushReaction(PushReaction.DESTROY)
            .randomTicks()
            .noOcclusion();
    }
    public static final DeferredBlock<RockBlock> STONE_ROCK =
        BLOCKS.register("stone_rock", () -> new RockBlock(rockProps(MapColor.STONE)));
    public static final DeferredItem<com.bannerbound.antiquity.item.ThrownRockBlockItem> STONE_ROCK_ITEM =
        ITEMS.registerItem("stone_rock",
            props -> new com.bannerbound.antiquity.item.ThrownRockBlockItem(STONE_ROCK.get(), props),
            new Item.Properties());
    public static final DeferredBlock<RockBlock> SANDSTONE_ROCK =
        BLOCKS.register("sandstone_rock", () -> new RockBlock(rockProps(MapColor.SAND)));
    public static final DeferredItem<com.bannerbound.antiquity.item.ThrownRockBlockItem> SANDSTONE_ROCK_ITEM =
        ITEMS.registerItem("sandstone_rock",
            props -> new com.bannerbound.antiquity.item.ThrownRockBlockItem(SANDSTONE_ROCK.get(), props),
            new Item.Properties());
    public static final DeferredBlock<RockBlock> RED_SANDSTONE_ROCK =
        BLOCKS.register("red_sandstone_rock", () -> new RockBlock(rockProps(MapColor.COLOR_ORANGE)));
    public static final DeferredItem<com.bannerbound.antiquity.item.ThrownRockBlockItem> RED_SANDSTONE_ROCK_ITEM =
        ITEMS.registerItem("red_sandstone_rock",
            props -> new com.bannerbound.antiquity.item.ThrownRockBlockItem(RED_SANDSTONE_ROCK.get(), props),
            new Item.Properties());

    public static final DeferredBlock<com.bannerbound.antiquity.block.ManureBlock> MANURE =
        BLOCKS.register("manure", () -> new com.bannerbound.antiquity.block.ManureBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BROWN)
                .strength(0.4f)
                .sound(SoundType.MUD)
                .noCollission()
                .noOcclusion()
                .pushReaction(PushReaction.DESTROY)));
    public static final DeferredItem<BlockItem> MANURE_ITEM =
        ITEMS.registerSimpleBlockItem("manure", MANURE);

    public static final DeferredItem<net.minecraft.world.item.BoneMealItem> DUNG =
        ITEMS.registerItem("dung", net.minecraft.world.item.BoneMealItem::new, new Item.Properties());

    public static final DeferredBlock<ChoppingStumpBlock> CHOPPING_STUMP = BLOCKS.register("chopping_stump",
        () -> new ChoppingStumpBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.0f)
            .sound(SoundType.WOOD)
            .noOcclusion()));
    public static final DeferredItem<BlockItem> CHOPPING_STUMP_ITEM =
        ITEMS.registerSimpleBlockItem("chopping_stump", CHOPPING_STUMP);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChoppingStumpBlockEntity>> CHOPPING_STUMP_BE =
        BLOCK_ENTITY_TYPES.register("chopping_stump",
            () -> BlockEntityType.Builder.of(ChoppingStumpBlockEntity::new, CHOPPING_STUMP.get())
                .build(null));

    public static final String[] ROPE_FENCE_WOODS =
        { "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry" };
    public static final List<DeferredBlock<RopeFencePostBlock>> ROPE_FENCE_POSTS = new ArrayList<>();
    public static final List<DeferredBlock<RopeFenceGateBlock>> ROPE_FENCE_GATES = new ArrayList<>();

    public static final List<DeferredItem<BlockItem>> ROPE_FENCE_ITEMS = new ArrayList<>();
    static {
        for (String wood : ROPE_FENCE_WOODS) {
            DeferredBlock<RopeFencePostBlock> post = BLOCKS.register(wood + "_rope_fence",
                () -> new RopeFencePostBlock(ropeFenceWoodProps()));
            ROPE_FENCE_POSTS.add(post);
            ROPE_FENCE_ITEMS.add(ITEMS.registerSimpleBlockItem(wood + "_rope_fence", post));
            DeferredBlock<RopeFenceGateBlock> gate = BLOCKS.register(wood + "_rope_fence_gate",
                () -> new RopeFenceGateBlock(ropeFenceWoodProps()));
            ROPE_FENCE_GATES.add(gate);
            ROPE_FENCE_ITEMS.add(ITEMS.registerSimpleBlockItem(wood + "_rope_fence_gate", gate));
        }
    }

    private static BlockBehaviour.Properties ropeFenceWoodProps() {
        return BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.0f)
            .sound(SoundType.WOOD)
            .noOcclusion();
    }

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RopeFencePostBlockEntity>> ROPE_FENCE_POST_BE =
        BLOCK_ENTITY_TYPES.register("rope_fence_post",
            () -> BlockEntityType.Builder.of(RopeFencePostBlockEntity::new,
                ROPE_FENCE_POSTS.stream().map(DeferredBlock::get).toArray(Block[]::new)).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RopeFenceGateBlockEntity>> ROPE_FENCE_GATE_BE =
        BLOCK_ENTITY_TYPES.register("rope_fence_gate",
            () -> BlockEntityType.Builder.of(RopeFenceGateBlockEntity::new,
                ROPE_FENCE_GATES.stream().map(DeferredBlock::get).toArray(Block[]::new)).build(null));

    public static final DeferredBlock<RopeCollisionBlock> ROPE_COLLISION = BLOCKS.register("rope_collision",
        () -> new RopeCollisionBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.NONE)
            .noLootTable()
            .noOcclusion()
            .instabreak()
            .pushReaction(PushReaction.DESTROY)));

    public static final DeferredBlock<FirewoodPileBlock> FIREWOOD_PILE = BLOCKS.register("firewood_pile",
        () -> new FirewoodPileBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.0f)
            .sound(SoundType.WOOD)
            .noOcclusion()));

    public static final String[] STICK_FENCE_WOODS =
        { "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry" };

    public static final List<DeferredItem<BlockItem>> STICK_FENCE_ITEMS = new ArrayList<>();
    static {
        for (String wood : STICK_FENCE_WOODS) {
            DeferredBlock<FenceBlock> fence = BLOCKS.register(wood + "_stick_fence",
                () -> new FenceBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(1.0f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));
            STICK_FENCE_ITEMS.add(ITEMS.registerSimpleBlockItem(wood + "_stick_fence", fence));
        }
    }

    public static final String[] DRYING_RACK_WOODS =
        { "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry" };

    public static final java.util.Map<String, DeferredBlock<com.bannerbound.antiquity.block.DryingRackBlock>>
        DRYING_RACK_BY_WOOD = new java.util.LinkedHashMap<>();

    public static final List<DeferredItem<BlockItem>> DRYING_RACK_ITEMS = new ArrayList<>();
    static {
        for (String wood : DRYING_RACK_WOODS) {
            DeferredBlock<com.bannerbound.antiquity.block.DryingRackBlock> rack =
                BLOCKS.register(wood + "_drying_rack",
                    () -> new com.bannerbound.antiquity.block.DryingRackBlock(BlockBehaviour.Properties.of()
                        .mapColor(MapColor.WOOD)
                        .strength(1.5f)
                        .sound(SoundType.WOOD)
                        .noOcclusion()));
            DRYING_RACK_BY_WOOD.put(wood, rack);
            DRYING_RACK_ITEMS.add(ITEMS.registerSimpleBlockItem(wood + "_drying_rack", rack));
        }
    }

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.bannerbound.antiquity.block.entity.DryingRackBlockEntity>> DRYING_RACK_BE =
        BLOCK_ENTITY_TYPES.register("drying_rack",
            () -> BlockEntityType.Builder.of(
                com.bannerbound.antiquity.block.entity.DryingRackBlockEntity::new,
                DRYING_RACK_BY_WOOD.values().stream().map(DeferredBlock::get).toArray(Block[]::new))
                .build(null));

    public static final String[] FERMENTATION_TROUGH_WOODS = DRYING_RACK_WOODS;

    public static final java.util.Map<String, DeferredBlock<com.bannerbound.antiquity.block.FermentationTroughBlock>>
        FERMENTATION_TROUGH_BY_WOOD = new java.util.LinkedHashMap<>();

    public static final List<DeferredItem<BlockItem>> FERMENTATION_TROUGH_ITEMS = new ArrayList<>();
    static {
        for (String wood : FERMENTATION_TROUGH_WOODS) {
            DeferredBlock<com.bannerbound.antiquity.block.FermentationTroughBlock> trough =
                BLOCKS.register(wood + "_fermentation_trough",
                    () -> new com.bannerbound.antiquity.block.FermentationTroughBlock(BlockBehaviour.Properties.of()
                        .mapColor(MapColor.WOOD)
                        .strength(1.5f)
                        .sound(SoundType.WOOD)
                        .noOcclusion()));
            FERMENTATION_TROUGH_BY_WOOD.put(wood, trough);
            FERMENTATION_TROUGH_ITEMS.add(ITEMS.registerSimpleBlockItem(wood + "_fermentation_trough", trough));
        }
    }
    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.bannerbound.antiquity.block.entity.FermentationTroughBlockEntity>> FERMENTATION_TROUGH_BE =
        BLOCK_ENTITY_TYPES.register("fermentation_trough",
            () -> BlockEntityType.Builder.of(
                com.bannerbound.antiquity.block.entity.FermentationTroughBlockEntity::new,
                FERMENTATION_TROUGH_BY_WOOD.values().stream().map(DeferredBlock::get).toArray(Block[]::new))
                .build(null));

    private static BlockBehaviour.Properties thatchProps() {
        return BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_YELLOW)
            .strength(0.5f)
            .sound(THATCH_SOUND);
    }
    public static final DeferredBlock<Block> THATCH =
        BLOCKS.register("thatch", () -> new Block(thatchProps()));
    public static final DeferredItem<BlockItem> THATCH_ITEM =
        ITEMS.registerSimpleBlockItem("thatch", THATCH);
    public static final DeferredBlock<SlabBlock> THATCH_SLAB =
        BLOCKS.register("thatch_slab", () -> new SlabBlock(thatchProps()));
    public static final DeferredItem<BlockItem> THATCH_SLAB_ITEM =
        ITEMS.registerSimpleBlockItem("thatch_slab", THATCH_SLAB);
    public static final DeferredBlock<StairBlock> THATCH_STAIRS =
        BLOCKS.register("thatch_stairs",
            () -> new StairBlock(THATCH.get().defaultBlockState(), thatchProps()));
    public static final DeferredItem<BlockItem> THATCH_STAIRS_ITEM =
        ITEMS.registerSimpleBlockItem("thatch_stairs", THATCH_STAIRS);

    public static final DeferredBlock<ThatchDoorBlock> THATCH_DOOR =
        BLOCKS.register("thatch_door", () -> new ThatchDoorBlock(THATCH_SET_TYPE,
            thatchProps().noOcclusion()));
    public static final DeferredItem<BlockItem> THATCH_DOOR_ITEM =
        ITEMS.registerSimpleBlockItem("thatch_door", THATCH_DOOR);

    public static final DeferredBlock<ThatchBedBlock> THATCH_BED =
        BLOCKS.register("thatch_bed", () -> new ThatchBedBlock(DyeColor.YELLOW,
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_YELLOW)
                .sound(THATCH_SOUND)
                .strength(0.2f)
                .noOcclusion()
                .pushReaction(PushReaction.DESTROY)));
    public static final DeferredItem<BlockItem> THATCH_BED_ITEM =
        ITEMS.registerItem("thatch_bed", p -> new BlockItem(THATCH_BED.get(), p),
            new Item.Properties().stacksTo(1));

    public static final DeferredItem<Item> THATCH_BUNDLE =
        ITEMS.registerSimpleItem("thatch_bundle", new Item.Properties());

    public static final DeferredItem<FireSticksItem> FIRE_STICKS =
        ITEMS.registerItem("fire_sticks", FireSticksItem::new, new Item.Properties().durability(16));

    public static final DeferredItem<Item> FLINT_BLADE =
        ITEMS.registerSimpleItem("flint_blade", new Item.Properties());
    public static final DeferredItem<Item> BONE_BLADE =
        ITEMS.registerSimpleItem("bone_blade", new Item.Properties());
    public static final DeferredItem<Item> PLANT_FIBER =
        ITEMS.registerSimpleItem("plant_fiber", new Item.Properties());
    public static final DeferredItem<Item> DRY_FIBER =
        ITEMS.registerSimpleItem("dry_fiber", new Item.Properties());

    public static final DeferredItem<Item> COW_HIDE =
        ITEMS.registerSimpleItem("cow_hide", new Item.Properties());
    public static final DeferredItem<Item> SHEEP_HIDE =
        ITEMS.registerSimpleItem("sheep_hide", new Item.Properties());
    public static final DeferredItem<Item> PIG_HIDE =
        ITEMS.registerSimpleItem("pig_hide", new Item.Properties());
    public static final DeferredItem<Item> GOAT_HIDE =
        ITEMS.registerSimpleItem("goat_hide", new Item.Properties());
    public static final DeferredItem<Item> HORSE_HIDE =
        ITEMS.registerSimpleItem("horse_hide", new Item.Properties());

    public static final DeferredItem<Item> SCRAPED_HIDE =
        ITEMS.registerSimpleItem("scraped_hide", new Item.Properties());
    public static final DeferredItem<Item> CURED_HIDE =
        ITEMS.registerSimpleItem("cured_hide", new Item.Properties());

    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<com.bannerbound.antiquity.item.HideQuality>> HIDE_QUALITY =
        DATA_COMPONENTS.registerComponentType("hide_quality", b -> b
            .persistent(com.bannerbound.antiquity.item.HideQuality.CODEC)
            .networkSynchronized(com.bannerbound.antiquity.item.HideQuality.STREAM_CODEC));

    public static final DeferredBlock<net.minecraft.world.level.block.Block> LIMESTONE =
        BLOCKS.registerSimpleBlock("limestone", net.minecraft.world.level.block.state.BlockBehaviour.Properties
            .ofFullCopy(net.minecraft.world.level.block.Blocks.STONE));
    public static final DeferredItem<BlockItem> LIMESTONE_ITEM =
        ITEMS.registerSimpleBlockItem("limestone", LIMESTONE);
    public static final DeferredItem<Item> QUICKLIME =
        ITEMS.registerSimpleItem("quicklime", new Item.Properties());

    public static final DeferredItem<Item> RAW_TIN =
        ITEMS.registerSimpleItem("raw_tin", new Item.Properties());
    public static final DeferredBlock<net.minecraft.world.level.block.Block> TIN_ORE =
        BLOCKS.registerSimpleBlock("tin_ore", net.minecraft.world.level.block.state.BlockBehaviour.Properties
            .ofFullCopy(net.minecraft.world.level.block.Blocks.COPPER_ORE));
    public static final DeferredItem<BlockItem> TIN_ORE_ITEM =
        ITEMS.registerSimpleBlockItem("tin_ore", TIN_ORE);

    private static BlockBehaviour.Properties earthenBrickProps() {
        return BlockBehaviour.Properties.of()
            .mapColor(MapColor.DIRT)
            .strength(1.5f, 3.0f)
            .sound(SoundType.PACKED_MUD);
    }
    public static final DeferredBlock<Block> UNFIRED_MUD_BRICKS =
        BLOCKS.register("unfired_mud_bricks", () -> new Block(earthenBrickProps()));
    public static final DeferredItem<BlockItem> UNFIRED_MUD_BRICKS_ITEM =
        ITEMS.registerSimpleBlockItem("unfired_mud_bricks", UNFIRED_MUD_BRICKS);
    public static final DeferredBlock<StairBlock> UNFIRED_MUD_BRICK_STAIRS =
        BLOCKS.register("unfired_mud_brick_stairs",
            () -> new StairBlock(UNFIRED_MUD_BRICKS.get().defaultBlockState(), earthenBrickProps()));
    public static final DeferredItem<BlockItem> UNFIRED_MUD_BRICK_STAIRS_ITEM =
        ITEMS.registerSimpleBlockItem("unfired_mud_brick_stairs", UNFIRED_MUD_BRICK_STAIRS);
    public static final DeferredBlock<SlabBlock> UNFIRED_MUD_BRICK_SLAB =
        BLOCKS.register("unfired_mud_brick_slab", () -> new SlabBlock(earthenBrickProps()));
    public static final DeferredItem<BlockItem> UNFIRED_MUD_BRICK_SLAB_ITEM =
        ITEMS.registerSimpleBlockItem("unfired_mud_brick_slab", UNFIRED_MUD_BRICK_SLAB);
    public static final DeferredBlock<net.minecraft.world.level.block.WallBlock> UNFIRED_MUD_BRICK_WALL =
        BLOCKS.register("unfired_mud_brick_wall",
            () -> new net.minecraft.world.level.block.WallBlock(earthenBrickProps().forceSolidOn()));
    public static final DeferredItem<BlockItem> UNFIRED_MUD_BRICK_WALL_ITEM =
        ITEMS.registerSimpleBlockItem("unfired_mud_brick_wall", UNFIRED_MUD_BRICK_WALL);

    private static BlockBehaviour.Properties limestoneBrickProps() {
        return BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0f, 6.0f)
            .requiresCorrectToolForDrops()
            .sound(SoundType.STONE);
    }
    public static final DeferredBlock<Block> LIMESTONE_BRICKS =
        BLOCKS.register("limestone_bricks", () -> new Block(limestoneBrickProps()));
    public static final DeferredItem<BlockItem> LIMESTONE_BRICKS_ITEM =
        ITEMS.registerSimpleBlockItem("limestone_bricks", LIMESTONE_BRICKS);
    public static final DeferredBlock<StairBlock> LIMESTONE_BRICK_STAIRS =
        BLOCKS.register("limestone_brick_stairs",
            () -> new StairBlock(LIMESTONE_BRICKS.get().defaultBlockState(), limestoneBrickProps()));
    public static final DeferredItem<BlockItem> LIMESTONE_BRICK_STAIRS_ITEM =
        ITEMS.registerSimpleBlockItem("limestone_brick_stairs", LIMESTONE_BRICK_STAIRS);
    public static final DeferredBlock<SlabBlock> LIMESTONE_BRICK_SLAB =
        BLOCKS.register("limestone_brick_slab", () -> new SlabBlock(limestoneBrickProps()));
    public static final DeferredItem<BlockItem> LIMESTONE_BRICK_SLAB_ITEM =
        ITEMS.registerSimpleBlockItem("limestone_brick_slab", LIMESTONE_BRICK_SLAB);
    public static final DeferredBlock<net.minecraft.world.level.block.WallBlock> LIMESTONE_BRICK_WALL =
        BLOCKS.register("limestone_brick_wall",
            () -> new net.minecraft.world.level.block.WallBlock(limestoneBrickProps().forceSolidOn()));
    public static final DeferredItem<BlockItem> LIMESTONE_BRICK_WALL_ITEM =
        ITEMS.registerSimpleBlockItem("limestone_brick_wall", LIMESTONE_BRICK_WALL);

    public static final DeferredItem<com.bannerbound.antiquity.item.PlasterItem> PLASTER =
        ITEMS.registerItem("plaster", com.bannerbound.antiquity.item.PlasterItem::new, new Item.Properties());

    public static final DeferredItem<PaintBrushItem> PAINT_BRUSH =
        ITEMS.registerItem("paint_brush", PaintBrushItem::new, new Item.Properties());

    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<Integer>> TRIM_BRUSH_SHAPE =
        DATA_COMPONENTS.registerComponentType("trim_brush_shape", b -> b
            .persistent(Codec.INT)
            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT));

    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<com.bannerbound.antiquity.item.CrucibleContents>> CRUCIBLE_CONTENTS =
        DATA_COMPONENTS.registerComponentType("crucible_contents", b -> b
            .persistent(com.bannerbound.antiquity.item.CrucibleContents.CODEC)
            .networkSynchronized(com.bannerbound.antiquity.item.CrucibleContents.STREAM_CODEC));

    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<com.bannerbound.antiquity.item.PoisonedFoodData>> POISONED_FOOD =
        DATA_COMPONENTS.registerComponentType("poisoned_food", b -> b
            .persistent(com.bannerbound.antiquity.item.PoisonedFoodData.CODEC)
            .networkSynchronized(com.bannerbound.antiquity.item.PoisonedFoodData.STREAM_CODEC));

    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<String>> ARROW_POISON =
        DATA_COMPONENTS.registerComponentType("arrow_poison", b -> b
            .persistent(Codec.STRING)
            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8));

    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<String>> ARROW_TIP =
        DATA_COMPONENTS.registerComponentType("arrow_tip", b -> b
            .persistent(Codec.STRING)
            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8));
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<String>> ARROW_SHAFT =
        DATA_COMPONENTS.registerComponentType("arrow_shaft", b -> b
            .persistent(Codec.STRING)
            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8));
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<String>> ARROW_BACK =
        DATA_COMPONENTS.registerComponentType("arrow_back", b -> b
            .persistent(Codec.STRING)
            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8));

    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<net.minecraft.world.item.component.ItemContainerContents>> BASKET_CONTENTS =
        DATA_COMPONENTS.registerComponentType("basket_contents", b -> b
            .persistent(net.minecraft.world.item.component.ItemContainerContents.CODEC)
            .networkSynchronized(net.minecraft.world.item.component.ItemContainerContents.STREAM_CODEC));

    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<com.bannerbound.antiquity.item.FoodSpoilage>> FOOD_SPOILAGE =
        DATA_COMPONENTS.registerComponentType("food_spoilage", b -> b
            .persistent(com.bannerbound.antiquity.item.FoodSpoilage.CODEC)
            .networkSynchronized(com.bannerbound.antiquity.item.FoodSpoilage.STREAM_CODEC));

    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<com.bannerbound.antiquity.item.GrogContents>> GROG_CONTENTS =
        DATA_COMPONENTS.registerComponentType("grog_contents", b -> b
            .persistent(com.bannerbound.antiquity.item.GrogContents.CODEC)
            .networkSynchronized(com.bannerbound.antiquity.item.GrogContents.STREAM_CODEC));

    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<com.bannerbound.antiquity.item.StewContents>> STEW_CONTENTS =
        DATA_COMPONENTS.registerComponentType("stew_contents", b -> b
            .persistent(com.bannerbound.antiquity.item.StewContents.CODEC)
            .networkSynchronized(com.bannerbound.antiquity.item.StewContents.STREAM_CODEC));
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<Boolean>> STONE_POT_FILLED =
        DATA_COMPONENTS.registerComponentType("stone_pot_filled", b -> b
            .persistent(Codec.BOOL)
            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.BOOL));

    public static final DeferredItem<com.bannerbound.antiquity.item.GrogVesselItem> MUG =
        ITEMS.registerItem("mug", com.bannerbound.antiquity.item.GrogVesselItem::new,
            new Item.Properties().stacksTo(16));
    public static final DeferredItem<com.bannerbound.antiquity.item.GrogVesselItem> GOAT_HORN =
        ITEMS.registerItem("goat_horn", com.bannerbound.antiquity.item.GrogVesselItem::new,
            new Item.Properties().stacksTo(16));

    public static final DeferredBlock<com.bannerbound.antiquity.block.CrucibleBlock> CRUCIBLE_BLOCK =
        BLOCKS.register("crucible", () -> new com.bannerbound.antiquity.block.CrucibleBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_ORANGE).strength(0.8F)
                .sound(SoundType.STONE).noOcclusion()));
    public static final DeferredItem<com.bannerbound.antiquity.item.CrucibleItem> CRUCIBLE =
        ITEMS.registerItem("crucible",
            p -> new com.bannerbound.antiquity.item.CrucibleItem(CRUCIBLE_BLOCK.get(), p),
            new Item.Properties().stacksTo(1));
    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.bannerbound.antiquity.block.entity.CrucibleBlockEntity>> CRUCIBLE_BLOCK_BE =
        BLOCK_ENTITY_TYPES.register("crucible",
            () -> BlockEntityType.Builder.of(
                com.bannerbound.antiquity.block.entity.CrucibleBlockEntity::new, CRUCIBLE_BLOCK.get())
                .build(null));
    public static final DeferredItem<Item> UNFIRED_CRUCIBLE =
        ITEMS.registerSimpleItem("unfired_crucible", new Item.Properties());

    public static final DeferredItem<Item> SPOILED_FOOD =
        ITEMS.registerSimpleItem("spoiled_food", new Item.Properties());

    public static final java.util.Map<String, DeferredItem<Item>> DRIED_FOODS =
        new java.util.LinkedHashMap<>();
    static {
        for (String meat : new String[] { "beef", "porkchop", "mutton", "chicken", "rabbit" }) {
            DRIED_FOODS.put("dried_" + meat, ITEMS.registerItem("dried_" + meat, p -> new Item(p),
                new Item.Properties().food(new net.minecraft.world.food.FoodProperties.Builder()
                    .nutrition(4).saturationModifier(0.6F).build())));
        }
        for (String fish : new String[] { "cod", "salmon" }) {
            DRIED_FOODS.put("dried_" + fish, ITEMS.registerItem("dried_" + fish, p -> new Item(p),
                new Item.Properties().food(new net.minecraft.world.food.FoodProperties.Builder()
                    .nutrition(3).saturationModifier(0.5F).build())));
        }

        DRIED_FOODS.put("dried_worm", ITEMS.registerItem("dried_worm", p -> new Item(p), new Item.Properties().food(new FoodProperties.Builder().nutrition(1).saturationModifier(0.2F).build())));
    }

    public static final DeferredBlock<com.bannerbound.antiquity.block.BlueberryBushBlock> BLUEBERRY_BUSH =
        BLOCKS.register("blueberry_bush", () -> new com.bannerbound.antiquity.block.BlueberryBushBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.PLANT)
                .randomTicks()
                .noCollission()
                .instabreak()
                .sound(SoundType.SWEET_BERRY_BUSH)
                .pushReaction(PushReaction.DESTROY)));

    public static final DeferredItem<Item> BLUEBERRIES =
        ITEMS.registerItem("blueberries",
            p -> new net.minecraft.world.item.ItemNameBlockItem(BLUEBERRY_BUSH.get(), p),
            new Item.Properties().food(
                new net.minecraft.world.food.FoodProperties.Builder()
                    .nutrition(2).saturationModifier(0.1F).build()));

    public static final DeferredItem<Item> BLUEBERRIES_PESTLED =
        ITEMS.registerSimpleItem("blueberries_pestled", new Item.Properties());
    public static final DeferredItem<Item> SWEET_BERRIES_PESTLED =
        ITEMS.registerSimpleItem("sweet_berries_pestled", new Item.Properties());

    public static final DeferredItem<com.bannerbound.antiquity.item.SaltItem> SALT =
        ITEMS.registerItem("salt", com.bannerbound.antiquity.item.SaltItem::new, new Item.Properties());

    public static final DeferredItem<com.bannerbound.antiquity.item.WormBaitItem> WORM =
            ITEMS.registerItem("worm", com.bannerbound.antiquity.item.WormBaitItem::new, new Item.Properties());

    public static final DeferredItem<Item> COOKED_WORM =
            ITEMS.registerItem("cooked_worm", Item::new, new Item.Properties().food(new FoodProperties.Builder().nutrition(2).saturationModifier(0.3F).build()));
    public static final DeferredBlock<Block> SALT_BLOCK = BLOCKS.register("salt_block",
        () -> new Block(BlockBehaviour.Properties.of()
            .mapColor(MapColor.SNOW).strength(0.6F).sound(SoundType.SAND)));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> SALT_BLOCK_ITEM =
        ITEMS.registerSimpleBlockItem("salt_block", SALT_BLOCK);

    public static final DeferredBlock<com.bannerbound.antiquity.block.StoneAnvilBlock> STONE_ANVIL =
        BLOCKS.register("stone_anvil", () -> new com.bannerbound.antiquity.block.StoneAnvilBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(1.5F)
                .sound(SoundType.STONE).noOcclusion()));
    public static final DeferredItem<BlockItem> STONE_ANVIL_ITEM =
        ITEMS.registerSimpleBlockItem("stone_anvil", STONE_ANVIL);

    public static final DeferredBlock<com.bannerbound.antiquity.block.BellowsBlock> BELLOWS_BLOCK =
        BLOCKS.register("bellows_block", () -> new com.bannerbound.antiquity.block.BellowsBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.0F)
                .sound(SoundType.WOOD).noOcclusion()));
    public static final DeferredItem<BlockItem> BELLOWS_BLOCK_ITEM =
        ITEMS.registerSimpleBlockItem("bellows_block", BELLOWS_BLOCK);
    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.bannerbound.antiquity.block.entity.BellowsBlockEntity>> BELLOWS_BLOCK_BE =
        BLOCK_ENTITY_TYPES.register("bellows_block",
            () -> BlockEntityType.Builder.of(
                com.bannerbound.antiquity.block.entity.BellowsBlockEntity::new, BELLOWS_BLOCK.get())
                .build(null));
    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.bannerbound.antiquity.block.entity.StoneAnvilBlockEntity>> STONE_ANVIL_BE =
        BLOCK_ENTITY_TYPES.register("stone_anvil",
            () -> BlockEntityType.Builder.of(
                com.bannerbound.antiquity.block.entity.StoneAnvilBlockEntity::new, STONE_ANVIL.get())
                .build(null));

    public static final DeferredItem<Item> CLAY_POT_UNFIRED =
        ITEMS.registerSimpleItem("clay_pot_unfired", new Item.Properties());
    public static final DeferredItem<Item> CLAY_POT_FIRED =
        ITEMS.registerSimpleItem("clay_pot_fired", new Item.Properties());
    public static final DeferredItem<Item> CLAY_BUCKET =
        ITEMS.registerSimpleItem("clay_bucket", new Item.Properties());
    public static final DeferredItem<ClayBucketItem> CLAY_FIRED_BUCKET =
        ITEMS.registerItem("clay_fired_bucket", ClayBucketItem::new, new Item.Properties().stacksTo(16));
    public static final DeferredItem<BucketItem> CLAY_FIRED_WATER_BUCKET =
        ITEMS.registerItem("clay_fired_bucket_water",
            p -> new ClayFilledBucketItem(Fluids.WATER, false,
                p.craftRemainder(CLAY_FIRED_BUCKET.get()).stacksTo(1)),
            new Item.Properties());
    public static final DeferredItem<BucketItem> CLAY_FIRED_LAVA_BUCKET =
        ITEMS.registerItem("clay_fired_bucket_lava",
            p -> new ClayFilledBucketItem(Fluids.LAVA, true,
                p.craftRemainder(CLAY_FIRED_BUCKET.get()).stacksTo(1)),
            new Item.Properties());
    public static final DeferredItem<Item> RAW_MUD_BRICK =
        ITEMS.registerSimpleItem("raw_mud_brick", new Item.Properties());

    public static final int COLOR_COPPER = 0xED8E56;
    public static final int COLOR_TIN    = 0xD9DEE3;
    public static final int COLOR_BRONZE = 0xE29622;

    private static net.minecraft.world.item.ItemStack filledCrucible(String metalId, int color) {
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(CRUCIBLE.get());
        stack.set(CRUCIBLE_CONTENTS.get(),
            com.bannerbound.antiquity.item.CrucibleContents.molten(metalId, 200, color));
        return stack;
    }

    public static final DeferredItem<Item> PLANT_STRING =
        ITEMS.registerSimpleItem("plant_string", new Item.Properties());
    public static final DeferredItem<FiberRopeItem> FIBER_ROPE =
        ITEMS.registerItem("fiber_rope", FiberRopeItem::new, new Item.Properties());

    public static final DeferredItem<FirewoodItem> FIREWOOD =
        ITEMS.registerItem("firewood", FirewoodItem::new, new Item.Properties());

    public static final DeferredItem<FlintKnifeItem> FLINT_KNIFE =
        ITEMS.registerItem("flint_knife", FlintKnifeItem::new, new Item.Properties());

    public static final DeferredItem<KnifeItem> WOODEN_KNIFE = ITEMS.registerItem("wooden_knife",
        p -> new KnifeItem(p, 70, 3.0, 2.0), new Item.Properties());

    public static final DeferredItem<PickaxeItem> BONE_PICKAXE = ITEMS.registerItem("bone_pickaxe",
        p -> new PickaxeItem(ModTiers.BONE, p.attributes(PickaxeItem.createAttributes(ModTiers.BONE, 1.0F, -2.8F))),
        new Item.Properties());
    public static final DeferredItem<AxeItem> BONE_AXE = ITEMS.registerItem("bone_axe",
        p -> new AxeItem(ModTiers.BONE, p.attributes(AxeItem.createAttributes(ModTiers.BONE, 5.0F, -3.1F))),
        new Item.Properties());
    public static final DeferredItem<ShovelItem> BONE_SHOVEL = ITEMS.registerItem("bone_shovel",
        p -> new ShovelItem(ModTiers.BONE, p.attributes(ShovelItem.createAttributes(ModTiers.BONE, 1.5F, -3.0F))),
        new Item.Properties());
    public static final DeferredItem<HoeItem> BONE_HOE = ITEMS.registerItem("bone_hoe",
        p -> new HoeItem(ModTiers.BONE, p.attributes(HoeItem.createAttributes(ModTiers.BONE, 0.0F, -2.0F))),
        new Item.Properties());
    public static final DeferredItem<SwordItem> BONE_SWORD = ITEMS.registerItem("bone_sword",
        p -> new SwordItem(ModTiers.BONE, p.attributes(SwordItem.createAttributes(ModTiers.BONE, 3, -2.4F))),
        new Item.Properties());

    public static final DeferredItem<KnifeItem> BONE_KNIFE = ITEMS.registerItem("bone_knife",
        p -> new KnifeItem(p, 48, 3.5, 2.0), new Item.Properties());

    public static final DeferredItem<KnifeItem> STONE_KNIFE = ITEMS.registerItem("stone_knife",
        p -> new KnifeItem(p, 131, 4.0, 2.0), new Item.Properties());

    public static final DeferredItem<com.bannerbound.antiquity.item.ClubItem> BONE_CLUB =
        ITEMS.registerItem("bone_club",
            p -> new com.bannerbound.antiquity.item.ClubItem(p, 48, 4.0, 1.0), new Item.Properties());

    public static final DeferredItem<net.minecraft.world.item.ShearsItem> BONE_SHEARS =
        ITEMS.registerItem("bone_shears",
            p -> new net.minecraft.world.item.ShearsItem(p), new Item.Properties().durability(64)
                .component(net.minecraft.core.component.DataComponents.TOOL,
                    net.minecraft.world.item.ShearsItem.createToolProperties()));

    public static final DeferredItem<Item> BONE_SAW = ITEMS.registerSimpleItem("bone_saw",
        new Item.Properties().durability(250));

    public static final DeferredItem<Item> STONE_CHISEL = ITEMS.registerSimpleItem("stone_chisel",
        new Item.Properties().durability(192));

    public static final DeferredItem<SpearItem> WOODEN_SPEAR = ITEMS.registerItem("wooden_spear",
        p -> new SpearItem(p, 59, 4.0, 1.2), new Item.Properties());
    public static final DeferredItem<SpearItem> BONE_SPEAR = ITEMS.registerItem("bone_spear",
        p -> new SpearItem(p, 48, 4.0, 1.2), new Item.Properties());
    public static final DeferredItem<SpearItem> STONE_SPEAR = ITEMS.registerItem("stone_spear",
        p -> new SpearItem(p, 131, 5.5, 1.2), new Item.Properties());

    public static final DeferredItem<SpearItem> TIN_SPEAR = ITEMS.registerItem("tin_spear",
        p -> new SpearItem(p, 120, 5.0, 1.2), new Item.Properties());
    public static final DeferredItem<SpearItem> COPPER_SPEAR = ITEMS.registerItem("copper_spear",
        p -> new SpearItem(p, 180, 6.0, 1.2), new Item.Properties());
    public static final DeferredItem<SpearItem> BRONZE_SPEAR = ITEMS.registerItem("bronze_spear",
        p -> new SpearItem(p, 375, 7.0, 1.2), new Item.Properties());

    public static final DeferredItem<Item> STONE_PICK_HEAD =
        ITEMS.registerSimpleItem("stone_pick_head", new Item.Properties());
    public static final DeferredItem<Item> STONE_AXE_HEAD =
        ITEMS.registerSimpleItem("stone_axe_head", new Item.Properties());
    public static final DeferredItem<Item> STONE_SHOVEL_HEAD =
        ITEMS.registerSimpleItem("stone_shovel_head", new Item.Properties());
    public static final DeferredItem<Item> STONE_HOE_HEAD =
        ITEMS.registerSimpleItem("stone_hoe_head", new Item.Properties());
    public static final DeferredItem<Item> STONE_SWORD_BLADE =
        ITEMS.registerSimpleItem("stone_sword_blade", new Item.Properties());
    public static final DeferredItem<Item> STONE_SPEAR_POINT =
        ITEMS.registerSimpleItem("stone_spear_point", new Item.Properties());
    public static final DeferredItem<Item> STONE_KNIFE_HEAD =
        ITEMS.registerSimpleItem("stone_knife_head", new Item.Properties());

    public static final DeferredItem<PrimitiveBowItem> PRIMITIVE_BOW = ITEMS.registerItem("primitive_bow",
        PrimitiveBowItem::new, new Item.Properties().durability(240));

    public static final DeferredItem<com.bannerbound.antiquity.item.CompositeArrowItem> ARROW =
        ITEMS.registerItem("arrow",
            com.bannerbound.antiquity.item.CompositeArrowItem::new, new Item.Properties());

    public static final DeferredItem<Item> IN_PROGRESS_PRIMITIVE_BOW =
        ITEMS.registerSimpleItem("in_progress_primitive_bow", new Item.Properties());

    public static final DeferredHolder<EntityType<?>, EntityType<SpearProjectile>> SPEAR_PROJECTILE =
        ENTITY_TYPES.register("spear",
            () -> EntityType.Builder.<SpearProjectile>of(SpearProjectile::new, MobCategory.MISC)
                .sized(0.5f, 0.5f)
                .clientTrackingRange(4)
                .updateInterval(20)
                .build("spear"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.bannerbound.antiquity.entity.CompositeArrowEntity>> ARROW_ENTITY =
        ENTITY_TYPES.register("arrow",
            () -> EntityType.Builder.<com.bannerbound.antiquity.entity.CompositeArrowEntity>of(
                    com.bannerbound.antiquity.entity.CompositeArrowEntity::new, MobCategory.MISC)
                .sized(0.5f, 0.5f)
                .clientTrackingRange(4)
                .updateInterval(20)
                .build("arrow"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.bannerbound.antiquity.entity.BlowdartProjectile>> BLOWDART_PROJECTILE =
        ENTITY_TYPES.register("blowdart",
            () -> EntityType.Builder.<com.bannerbound.antiquity.entity.BlowdartProjectile>of(
                    com.bannerbound.antiquity.entity.BlowdartProjectile::new, MobCategory.MISC)
                .sized(0.4f, 0.4f)
                .clientTrackingRange(4)
                .updateInterval(20)
                .build("blowdart"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.bannerbound.antiquity.entity.ThrownRock>> THROWN_ROCK =
        ENTITY_TYPES.register("thrown_rock",
            () -> EntityType.Builder.<com.bannerbound.antiquity.entity.ThrownRock>of(
                    com.bannerbound.antiquity.entity.ThrownRock::new, MobCategory.MISC)
                .sized(0.25f, 0.25f)
                .clientTrackingRange(4)
                .updateInterval(10)
                .build("thrown_rock"));

    public static final DeferredHolder<EntityType<?>, EntityType<GroundDecalEntity>> GROUND_DECAL =
        ENTITY_TYPES.register("ground_decal",
            () -> EntityType.Builder.<GroundDecalEntity>of(GroundDecalEntity::new, MobCategory.MISC)
                .sized(1.0f, 0.25f)
                .clientTrackingRange(6)
                .updateInterval(40)
                .build("ground_decal"));

    public static final DeferredHolder<EntityType<?>, EntityType<SpearedFishEntity>> SPEARED_FISH =
        ENTITY_TYPES.register("speared_fish",
            () -> EntityType.Builder.<SpearedFishEntity>of(SpearedFishEntity::new, MobCategory.MISC)
                .sized(0.6f, 0.6f)
                .clientTrackingRange(6)
                .updateInterval(10)
                .build("speared_fish"));

    public static final DeferredHolder<EntityType<?>, EntityType<RaftEntity>> RAFT =
        ENTITY_TYPES.register("raft",
            () -> EntityType.Builder.<RaftEntity>of(RaftEntity::new, MobCategory.MISC)
                .sized(1.4f, 0.6f)
                .clientTrackingRange(10)
                .build("raft"));

    public static final DeferredHolder<EntityType<?>, EntityType<WormBaitEntity>> WORM_BAIT =
        ENTITY_TYPES.register("worm_bait",
                () -> EntityType.Builder.<WormBaitEntity>of(WormBaitEntity::new, MobCategory.MISC).build("worm_bait"));
    public static final DeferredItem<Item> OAR =
        ITEMS.registerSimpleItem("oar", new Item.Properties());

    private static BlockBehaviour.Properties herbProps() {
        return BlockBehaviour.Properties.of()
            .mapColor(MapColor.PLANT)
            .noCollission()
            .instabreak()
            .sound(SoundType.GRASS)
            .pushReaction(PushReaction.DESTROY);
    }

    public static final DeferredBlock<com.bannerbound.antiquity.block.ForageFlowerBlock> WOLFSBANE =
        BLOCKS.register("wolfsbane", () -> new com.bannerbound.antiquity.block.ForageFlowerBlock(herbProps()));
    public static final DeferredItem<BlockItem> WOLFSBANE_ITEM =
        ITEMS.registerSimpleBlockItem("wolfsbane", WOLFSBANE);

    public static final DeferredBlock<com.bannerbound.antiquity.block.ForageFlowerBlock> YARROW =
        BLOCKS.register("yarrow", () -> new com.bannerbound.antiquity.block.ForageFlowerBlock(herbProps()));
    public static final DeferredItem<BlockItem> YARROW_ITEM =
        ITEMS.registerSimpleBlockItem("yarrow", YARROW);

    public static final DeferredItem<com.bannerbound.antiquity.item.PoisonPasteItem> WOLFSBANE_POISON =
        ITEMS.registerItem("wolfsbane_poison",
            p -> new com.bannerbound.antiquity.item.PoisonPasteItem(p, com.bannerbound.antiquity.poison.PoisonType.WOLFSBANE),
            new Item.Properties());

    public static final DeferredItem<com.bannerbound.antiquity.item.AntidoteItem> YARROW_ANTIDOTE =
        ITEMS.registerItem("yarrow_antidote",
            p -> new com.bannerbound.antiquity.item.AntidoteItem(p, com.bannerbound.antiquity.poison.PoisonType.WOLFSBANE),
            new Item.Properties().stacksTo(16));

    public static final DeferredItem<com.bannerbound.antiquity.item.PoisonDartItem> WOLFSBANE_DART =
        ITEMS.registerItem("wolfsbane_dart",
            p -> new com.bannerbound.antiquity.item.PoisonDartItem(p, com.bannerbound.antiquity.poison.PoisonType.WOLFSBANE),
            new Item.Properties().stacksTo(16));

    public static final DeferredBlock<com.bannerbound.antiquity.block.TallForagePlantBlock> CURARE_VINE =
        BLOCKS.register("curare_vine", () -> new com.bannerbound.antiquity.block.TallForagePlantBlock(herbProps()));
    public static final DeferredItem<BlockItem> CURARE_VINE_ITEM =
        ITEMS.registerSimpleBlockItem("curare_vine", CURARE_VINE);
    public static final DeferredItem<com.bannerbound.antiquity.item.PoisonPasteItem> CURARE_POISON =
        ITEMS.registerItem("curare_poison",
            p -> new com.bannerbound.antiquity.item.PoisonPasteItem(p, com.bannerbound.antiquity.poison.PoisonType.CURARE),
            new Item.Properties());
    public static final DeferredItem<com.bannerbound.antiquity.item.PoisonDartItem> CURARE_DART =
        ITEMS.registerItem("curare_dart",
            p -> new com.bannerbound.antiquity.item.PoisonDartItem(p, com.bannerbound.antiquity.poison.PoisonType.CURARE),
            new Item.Properties().stacksTo(16));

    public static final DeferredBlock<com.bannerbound.antiquity.block.ForageFlowerBlock> ARNICA =
        BLOCKS.register("arnica", () -> new com.bannerbound.antiquity.block.ForageFlowerBlock(herbProps()));
    public static final DeferredItem<BlockItem> ARNICA_ITEM =
        ITEMS.registerSimpleBlockItem("arnica", ARNICA);
    public static final DeferredItem<com.bannerbound.antiquity.item.AntidoteItem> ARNICA_ANTIDOTE =
        ITEMS.registerItem("arnica_antidote",
            p -> new com.bannerbound.antiquity.item.AntidoteItem(p, com.bannerbound.antiquity.poison.PoisonType.CURARE),
            new Item.Properties().stacksTo(16));

    public static final DeferredBlock<com.bannerbound.antiquity.block.SandForagePlantBlock> OLEANDER =
        BLOCKS.register("oleander", () -> new com.bannerbound.antiquity.block.SandForagePlantBlock(herbProps()));
    public static final DeferredItem<BlockItem> OLEANDER_ITEM =
        ITEMS.registerSimpleBlockItem("oleander", OLEANDER);
    public static final DeferredItem<com.bannerbound.antiquity.item.PoisonPasteItem> OLEANDER_POISON =
        ITEMS.registerItem("oleander_poison",
            p -> new com.bannerbound.antiquity.item.PoisonPasteItem(p, com.bannerbound.antiquity.poison.PoisonType.OLEANDER),
            new Item.Properties());
    public static final DeferredItem<com.bannerbound.antiquity.item.PoisonDartItem> OLEANDER_DART =
        ITEMS.registerItem("oleander_dart",
            p -> new com.bannerbound.antiquity.item.PoisonDartItem(p, com.bannerbound.antiquity.poison.PoisonType.OLEANDER),
            new Item.Properties().stacksTo(16));

    public static final DeferredBlock<com.bannerbound.antiquity.block.ForageFlowerBlock> DEADLY_NIGHTSHADE =
        BLOCKS.register("deadly_nightshade", () -> new com.bannerbound.antiquity.block.ForageFlowerBlock(herbProps()));
    public static final DeferredItem<BlockItem> DEADLY_NIGHTSHADE_ITEM =
        ITEMS.registerSimpleBlockItem("deadly_nightshade", DEADLY_NIGHTSHADE);

    public static final DeferredBlock<com.bannerbound.antiquity.block.ForageFlowerBlock> MARSHMALLOW_ROOT =
        BLOCKS.register("marshmallow_root", () -> new com.bannerbound.antiquity.block.ForageFlowerBlock(herbProps()));
    public static final DeferredItem<BlockItem> MARSHMALLOW_ROOT_ITEM =
        ITEMS.registerSimpleBlockItem("marshmallow_root", MARSHMALLOW_ROOT);
    public static final DeferredItem<com.bannerbound.antiquity.item.PoisonPasteItem> DEADLY_NIGHTSHADE_POISON =
        ITEMS.registerItem("deadly_nightshade_poison",
            p -> new com.bannerbound.antiquity.item.PoisonPasteItem(p, com.bannerbound.antiquity.poison.PoisonType.BELLADONNA),
            new Item.Properties());
    public static final DeferredItem<com.bannerbound.antiquity.item.AntidoteItem> MARSHMALLOW_ROOT_ANTIDOTE =
        ITEMS.registerItem("marshmallow_root_antidote",
            p -> new com.bannerbound.antiquity.item.AntidoteItem(p, com.bannerbound.antiquity.poison.PoisonType.BELLADONNA),
            new Item.Properties().stacksTo(16));

    public static final DeferredBlock<com.bannerbound.antiquity.block.ForageFlowerBlock> CINCHONA =
        BLOCKS.register("cinchona", () -> new com.bannerbound.antiquity.block.ForageFlowerBlock(herbProps()));
    public static final DeferredItem<BlockItem> CINCHONA_ITEM =
        ITEMS.registerSimpleBlockItem("cinchona", CINCHONA);
    public static final DeferredItem<com.bannerbound.antiquity.item.AntidoteItem> CINCHONA_ANTIDOTE =
        ITEMS.registerItem("cinchona_antidote",
            p -> new com.bannerbound.antiquity.item.AntidoteItem(p, com.bannerbound.antiquity.poison.PoisonType.OLEANDER),
            new Item.Properties().stacksTo(16));
    public static final DeferredItem<com.bannerbound.antiquity.item.PoisonDartItem> NIGHTSHADE_DART =
        ITEMS.registerItem("nightshade_dart",
            p -> new com.bannerbound.antiquity.item.PoisonDartItem(p, com.bannerbound.antiquity.poison.PoisonType.BELLADONNA),
            new Item.Properties().stacksTo(16));

    public static final DeferredItem<com.bannerbound.antiquity.item.BlowgunItem> BLOWGUN =
        ITEMS.registerItem("blowgun",
            com.bannerbound.antiquity.item.BlowgunItem::new, new Item.Properties().stacksTo(1));

    public static final DeferredItem<com.bannerbound.antiquity.item.SlingshotItem> SLINGSHOT =
        ITEMS.registerItem("slingshot",
            com.bannerbound.antiquity.item.SlingshotItem::new, new Item.Properties().durability(150));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ANTIQUITY_TAB =
        CREATIVE_MODE_TABS.register("antiquity", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.bannerboundantiquity"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> MORTAR_AND_PESTLE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(MORTAR_AND_PESTLE_ITEM.get());
                output.accept(BASKET_ITEM.get());
                output.accept(FIRE_STICKS.get());
                output.accept(FLINT_BLADE.get());
                output.accept(BONE_BLADE.get());
                output.accept(PLANT_FIBER.get());
                output.accept(DRY_FIBER.get());
                output.accept(PLANT_STRING.get());
                output.accept(FIBER_ROPE.get());
                output.accept(FIREWOOD.get());
                output.accept(FLINT_KNIFE.get());
                output.accept(WOODEN_KNIFE.get());
                output.accept(CRAFTING_STONE_ITEM.get());
                output.accept(STONE_COOKING_POT_ITEM.get());
                output.accept(FLETCHING_STATION_ITEM.get());
                output.accept(POTTERY_SLAB_ITEM.get());
                output.accept(WOODWORKING_TABLE_ITEM.get());
                output.accept(BONE_SAW.get());
                output.accept(MASONS_BENCH_ITEM.get());
                output.accept(STONE_CHISEL.get());

                output.accept(SALT.get());
                output.accept(WORM.get());
                output.accept(COOKED_WORM.get());
                output.accept(SALT_BLOCK_ITEM.get());
                output.accept(SPOILED_FOOD.get());
                for (DeferredItem<Item> dried : DRIED_FOODS.values()) {
                    output.accept(dried.get());
                }
                output.accept(BLUEBERRIES.get());
                output.accept(BLUEBERRIES_PESTLED.get());
                output.accept(SWEET_BERRIES_PESTLED.get());
                output.accept(BONE_PICKAXE.get());
                output.accept(BONE_AXE.get());
                output.accept(BONE_SHOVEL.get());
                output.accept(BONE_HOE.get());
                output.accept(BONE_SWORD.get());
                output.accept(BONE_KNIFE.get());
                output.accept(STONE_KNIFE.get());
                output.accept(BONE_CLUB.get());
                output.accept(BONE_SHEARS.get());
                output.accept(WOODEN_SPEAR.get());
                output.accept(BONE_SPEAR.get());
                output.accept(STONE_SPEAR.get());
                output.accept(STONE_PICK_HEAD.get());
                output.accept(STONE_AXE_HEAD.get());
                output.accept(STONE_SHOVEL_HEAD.get());
                output.accept(STONE_HOE_HEAD.get());
                output.accept(STONE_SWORD_BLADE.get());
                output.accept(STONE_SPEAR_POINT.get());
                output.accept(STONE_KNIFE_HEAD.get());
                output.accept(SLINGSHOT.get());
                output.accept(PRIMITIVE_BOW.get());
                output.accept(ARROW.get());
                output.accept(com.bannerbound.antiquity.item.ArrowParts.makeArrow("flint", "wood", "fiber", 1));
                output.accept(OAR.get());

                output.accept(WOLFSBANE_ITEM.get());
                output.accept(YARROW_ITEM.get());
                output.accept(WOLFSBANE_POISON.get());
                output.accept(WOLFSBANE_DART.get());
                output.accept(YARROW_ANTIDOTE.get());
                output.accept(DEADLY_NIGHTSHADE_ITEM.get());
                output.accept(MARSHMALLOW_ROOT_ITEM.get());
                output.accept(DEADLY_NIGHTSHADE_POISON.get());
                output.accept(NIGHTSHADE_DART.get());
                output.accept(MARSHMALLOW_ROOT_ANTIDOTE.get());
                output.accept(CINCHONA_ITEM.get());
                output.accept(CINCHONA_ANTIDOTE.get());
                output.accept(CURARE_VINE_ITEM.get());
                output.accept(CURARE_POISON.get());
                output.accept(CURARE_DART.get());
                output.accept(ARNICA_ITEM.get());
                output.accept(ARNICA_ANTIDOTE.get());
                output.accept(OLEANDER_ITEM.get());
                output.accept(OLEANDER_POISON.get());
                output.accept(OLEANDER_DART.get());
                output.accept(BLOWGUN.get());

                output.accept(TIN_ORE_ITEM.get());
                output.accept(RAW_TIN.get());

                output.accept(UNFIRED_CRUCIBLE.get());
                output.accept(CRUCIBLE.get());
                output.accept(filledCrucible("copper", COLOR_COPPER));
                output.accept(filledCrucible("tin", COLOR_TIN));
                output.accept(filledCrucible("bronze", COLOR_BRONZE));

                output.accept(STONE_ANVIL_ITEM.get());
                output.accept(BELLOWS_BLOCK_ITEM.get());
                //output.accept(WORM_CRATE_ITEM.get());
                com.bannerbound.antiquity.workshop.MetalworkingItems.MOLDS.values()
                    .forEach(i -> output.accept(i.get()));
                com.bannerbound.antiquity.workshop.MetalworkingItems.HAMMERS.values()
                    .forEach(i -> output.accept(i.get()));
                com.bannerbound.antiquity.workshop.MetalworkingItems.CASTINGS.values()
                    .forEach(i -> output.accept(i.get()));
                com.bannerbound.antiquity.workshop.MetalworkingItems.TOOLS.values()
                    .forEach(i -> output.accept(i.get()));
                com.bannerbound.antiquity.workshop.MetalworkingItems.TONGS.values()
                    .forEach(i -> output.accept(i.get()));

                output.accept(TIN_SPEAR.get());
                output.accept(COPPER_SPEAR.get());
                output.accept(BRONZE_SPEAR.get());

                output.accept(com.bannerbound.antiquity.item.ArrowParts.makeArrow("copper", "copper", "feather", 1));
                output.accept(com.bannerbound.antiquity.item.ArrowParts.makeArrow("tin", "tin", "feather", 1));
                output.accept(com.bannerbound.antiquity.item.ArrowParts.makeArrow("bronze", "bronze", "feather", 1));
                output.accept(CLAY_POT_UNFIRED.get());
                output.accept(CLAY_POT_FIRED.get());
                output.accept(CLAY_BUCKET.get());
                output.accept(CLAY_FIRED_BUCKET.get());
                output.accept(CLAY_FIRED_WATER_BUCKET.get());
                output.accept(CLAY_FIRED_LAVA_BUCKET.get());
                output.accept(RAW_MUD_BRICK.get());

                output.accept(UNFIRED_MUD_BRICKS_ITEM.get());
                output.accept(UNFIRED_MUD_BRICK_STAIRS_ITEM.get());
                output.accept(UNFIRED_MUD_BRICK_SLAB_ITEM.get());
                output.accept(UNFIRED_MUD_BRICK_WALL_ITEM.get());
                output.accept(LIMESTONE_BRICKS_ITEM.get());
                output.accept(LIMESTONE_BRICK_STAIRS_ITEM.get());
                output.accept(LIMESTONE_BRICK_SLAB_ITEM.get());
                output.accept(LIMESTONE_BRICK_WALL_ITEM.get());
                output.accept(PLASTER.get());
                output.accept(PAINT_BRUSH.get());

                output.accept(COW_HIDE.get());
                output.accept(SHEEP_HIDE.get());
                output.accept(PIG_HIDE.get());
                output.accept(GOAT_HIDE.get());
                output.accept(HORSE_HIDE.get());
                output.accept(SCRAPED_HIDE.get());
                output.accept(CURED_HIDE.get());
                output.accept(LIMESTONE_ITEM.get());
                output.accept(QUICKLIME.get());
                output.accept(UNFIRED_CLAY_TANK.get());
                output.accept(CLAY_TANK_ITEM.get());
                output.accept(TANNING_RACK_ITEM.get());
                output.accept(CLAYED_COBBLESTONE_ITEM.get());
                output.accept(STONE_ROCK_ITEM.get());
                output.accept(SANDSTONE_ROCK_ITEM.get());
                output.accept(RED_SANDSTONE_ROCK_ITEM.get());
                output.accept(CHOPPING_STUMP_ITEM.get());
                for (DeferredItem<BlockItem> stickFenceItem : STICK_FENCE_ITEMS) {
                    output.accept(stickFenceItem.get());
                }
                for (DeferredItem<BlockItem> ropeFenceItem : ROPE_FENCE_ITEMS) {
                    output.accept(ropeFenceItem.get());
                }
                output.accept(MANURE_ITEM.get());
                output.accept(DUNG.get());
                output.accept(THATCH_BUNDLE.get());
                output.accept(THATCH_ITEM.get());
                output.accept(THATCH_SLAB_ITEM.get());
                output.accept(THATCH_STAIRS_ITEM.get());
                output.accept(THATCH_DOOR_ITEM.get());
                output.accept(THATCH_BED_ITEM.get());
                output.accept(WORM.get());
                output.accept(COOKED_WORM);
            }).build());

    public BannerboundAntiquity(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::addCookingFireToCampfireBE);

        com.bannerbound.antiquity.workshop.MetalworkingItems.register();

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENUS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        SOUNDS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        ATTACHMENT_TYPES.register(modEventBus);
        PARTICLE_TYPES.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        FEATURES.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void addCookingFireToCampfireBE(
            net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent event) {
        event.modify(net.minecraft.world.level.block.entity.BlockEntityType.CAMPFIRE, COOKING_FIRE.get());
    }

    private void registerCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
            BASKET_BE.get(),
            (be, side) -> new net.neoforged.neoforge.items.wrapper.InvWrapper(be));
    }

    private static com.bannerbound.core.creative.CreativeSection band(String id, int tint) {
        return new com.bannerbound.core.creative.CreativeSection(
            id,
            Component.translatable("itemGroup." + MODID + ".section." + id),
            ResourceLocation.fromNamespaceAndPath(MODID, "sections/banner"),
            tint, 0xCC140D06, 0xFFF5E6C8, false);
    }

    private static void registerCreativeSections() {
        com.bannerbound.core.creative.CreativeSections.forTab(ANTIQUITY_TAB)
            .section(band("tools", 0xFFC9A86B))
                .add(MORTAR_AND_PESTLE_ITEM, BASKET_ITEM, FIRE_STICKS, FLINT_BLADE, BONE_BLADE,
                     PLANT_FIBER, DRY_FIBER, PLANT_STRING, FIBER_ROPE, FIREWOOD, FLINT_KNIFE, WOODEN_KNIFE,
                     CRAFTING_STONE_ITEM, FLETCHING_STATION_ITEM, POTTERY_SLAB_ITEM, WOODWORKING_TABLE_ITEM,
                     BONE_SAW, MASONS_BENCH_ITEM, STONE_CHISEL, WORM)
            .section(band("primitive_tools", 0xFFB8B0A0))
                .add(BONE_PICKAXE, BONE_AXE, BONE_SHOVEL, BONE_HOE, BONE_SWORD, BONE_KNIFE, STONE_KNIFE,
                     BONE_CLUB, BONE_SHEARS,
                     WOODEN_SPEAR, BONE_SPEAR, STONE_SPEAR, STONE_PICK_HEAD, STONE_AXE_HEAD,
                     STONE_SHOVEL_HEAD, STONE_HOE_HEAD, STONE_SWORD_BLADE, STONE_SPEAR_POINT,
                     STONE_KNIFE_HEAD, SLINGSHOT, PRIMITIVE_BOW, ARROW, OAR)
            .section(band("poisons", 0xFF7FA86B))
                .add(WOLFSBANE_ITEM, YARROW_ITEM, WOLFSBANE_POISON, WOLFSBANE_DART, YARROW_ANTIDOTE,
                     DEADLY_NIGHTSHADE_ITEM, MARSHMALLOW_ROOT_ITEM, DEADLY_NIGHTSHADE_POISON, NIGHTSHADE_DART,
                     MARSHMALLOW_ROOT_ANTIDOTE, CINCHONA_ITEM, CINCHONA_ANTIDOTE, CURARE_VINE_ITEM,
                     CURARE_POISON, CURARE_DART, ARNICA_ITEM, ARNICA_ANTIDOTE, OLEANDER_ITEM, OLEANDER_POISON,
                     OLEANDER_DART, BLOWGUN)
                .addItems(net.minecraft.world.item.Items.ARROW)
            .section(band("metalworking", 0xFFCF9152))
                .add(TIN_ORE_ITEM, RAW_TIN, UNFIRED_CRUCIBLE, CRUCIBLE, STONE_ANVIL_ITEM,
                     BELLOWS_BLOCK_ITEM)
                .add(com.bannerbound.antiquity.workshop.MetalworkingItems.MOLDS.values())
                .add(com.bannerbound.antiquity.workshop.MetalworkingItems.HAMMERS.values())
                .add(com.bannerbound.antiquity.workshop.MetalworkingItems.CASTINGS.values())
                .add(com.bannerbound.antiquity.workshop.MetalworkingItems.TOOLS.values())
                .add(com.bannerbound.antiquity.workshop.MetalworkingItems.TONGS.values())
                .add(TIN_SPEAR, COPPER_SPEAR, BRONZE_SPEAR)
            .section(band("pottery", 0xFFC8785A))
                .add(CLAY_POT_UNFIRED, CLAY_POT_FIRED, CLAY_BUCKET, CLAY_FIRED_BUCKET,
                     CLAY_FIRED_WATER_BUCKET, CLAY_FIRED_LAVA_BUCKET, RAW_MUD_BRICK)
            .section(band("building", 0xFFB0805C))
                .add(UNFIRED_MUD_BRICKS_ITEM, UNFIRED_MUD_BRICK_STAIRS_ITEM, UNFIRED_MUD_BRICK_SLAB_ITEM,
                     UNFIRED_MUD_BRICK_WALL_ITEM, LIMESTONE_BRICKS_ITEM, LIMESTONE_BRICK_STAIRS_ITEM,
                     LIMESTONE_BRICK_SLAB_ITEM, LIMESTONE_BRICK_WALL_ITEM, PLASTER, PAINT_BRUSH,
                     CLAYED_COBBLESTONE_ITEM, STONE_ROCK_ITEM, SANDSTONE_ROCK_ITEM, RED_SANDSTONE_ROCK_ITEM,
                     CHOPPING_STUMP_ITEM, THATCH_BUNDLE, THATCH_ITEM, THATCH_SLAB_ITEM, THATCH_STAIRS_ITEM,
                     THATCH_DOOR_ITEM, THATCH_BED_ITEM)
                .add(STICK_FENCE_ITEMS)
                .add(ROPE_FENCE_ITEMS)
                .add(DRYING_RACK_ITEMS)
                .add(FERMENTATION_TROUGH_ITEMS)
                .add(MUG, GOAT_HORN)
            .section(band("tannery", 0xFFA9764B))
                .add(COW_HIDE, SHEEP_HIDE, PIG_HIDE, GOAT_HIDE, HORSE_HIDE, SCRAPED_HIDE, CURED_HIDE,
                     LIMESTONE_ITEM, QUICKLIME, UNFIRED_CLAY_TANK, CLAY_TANK_ITEM, TANNING_RACK_ITEM)
            .section(band("husbandry", 0xFF93864F))
                .add(MANURE_ITEM, DUNG);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Bannerbound: Antiquity loaded.");

        registerCreativeSections();

        com.bannerbound.core.api.settlement.AppealContributors.register(
            com.bannerbound.antiquity.deco.DecoAppeal::contribute);

        com.bannerbound.core.api.research.ItemKnowledge.registerStackGate(
            (settlement, stack) -> com.bannerbound.antiquity.item.ArrowParts.partsKnown(
                stack, item -> com.bannerbound.core.api.research.ItemKnowledge.isKnown(settlement, item)));

        event.enqueueWork(() -> {
            com.bannerbound.core.api.vanilla.VanillaContentState.setOverride(false);

            com.bannerbound.core.barbarian.BarbarianProjectiles.register(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "spear"),
                (lvl, shooter, dmg) -> {
                    net.minecraft.world.item.ItemStack spear = shooter.getMainHandItem();
                    if (spear.isEmpty()) {
                        spear = new net.minecraft.world.item.ItemStack(STONE_SPEAR.get());
                    }
                    com.bannerbound.antiquity.entity.SpearProjectile s =
                        new com.bannerbound.antiquity.entity.SpearProjectile(lvl, shooter, spear, dmg);
                    s.markNoRecovery();
                    return s;
                });
            com.bannerbound.core.barbarian.BarbarianProjectiles.register(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "arrow"),
                (lvl, shooter, dmg) -> {
                    com.bannerbound.antiquity.entity.CompositeArrowEntity a =
                        new com.bannerbound.antiquity.entity.CompositeArrowEntity(lvl, shooter,
                            new net.minecraft.world.item.ItemStack(ARROW.get()), null);
                    a.setBaseDamage(dmg);
                    return a;
                });

            // FireBlock.setFlammable mutates a shared, non-thread-safe map -> only touch it here on the main thread (enqueueWork).
            FireBlock fire = (FireBlock) Blocks.FIRE;
            fire.setFlammable(THATCH.get(), 60, 20);
            fire.setFlammable(THATCH_SLAB.get(), 60, 20);
            fire.setFlammable(THATCH_STAIRS.get(), 60, 20);
            fire.setFlammable(THATCH_DOOR.get(), 60, 20);
            fire.setFlammable(THATCH_BED.get(), 60, 20);

            com.bannerbound.core.api.job.CitizenJobRegistry.register(
                com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                    .builder(com.bannerbound.antiquity.entity.SpearFisherWorkGoal.JOB_TYPE_ID)
                    .gatherer(true)
                    .anarchyOrder(3)
                    .unit("spear_fisher")
                    .icon("spearfish", net.minecraft.world.item.Items.COD)
                    .toolRequired(false)
                    .obsoletedBy("fisher")
                    .goal((c, s) -> new com.bannerbound.antiquity.entity.SpearFisherWorkGoal(c, s))
                    .build());

            com.bannerbound.core.api.fisher.FishingVessels.setProvider(
                (lvl, x, y, z, yaw) -> com.bannerbound.antiquity.entity.RaftEntity.spawnGhost(lvl, x, y, z, yaw));

            com.bannerbound.core.api.hunter.HunterHooks.setExtension(
                new com.bannerbound.antiquity.entity.AntiquityHunterHooks());

            com.bannerbound.core.api.herder.HerderHooks.setExtension(
                new com.bannerbound.antiquity.entity.AntiquityHerderHooks());

            com.bannerbound.core.api.settlement.food.LarderHooks.processWith(
                com.bannerbound.antiquity.food.Spoilage::tick);

            com.bannerbound.core.api.settlement.food.LarderHooks.normalizeWith((handler, level) -> {
                if (handler instanceof net.neoforged.neoforge.items.IItemHandlerModifiable mod) {
                    com.bannerbound.antiquity.food.Spoilage.compactStorage(mod);
                }
            });
            com.bannerbound.core.api.settlement.food.LarderHooks.excludeWhen(
                (stack, level) -> stack.has(POISONED_FOOD.get()));

            com.bannerbound.core.api.settlement.food.LarderHooks.multiplyValueWith((stack, level) -> {
                com.bannerbound.antiquity.item.FoodSpoilage fs = stack.get(FOOD_SPOILAGE.get());
                return fs == null ? 1.0 : fs.foodMultiplier();
            });

            com.bannerbound.core.api.settlement.food.LarderHooks.contributeValueWith((stack, level) -> {
                com.bannerbound.antiquity.item.GrogContents grog = stack.get(GROG_CONTENTS.get());
                return grog == null ? 0.0 : grog.foodValue();
            });

            com.bannerbound.core.api.settlement.food.LarderHooks.provideStoresWith((level, settlement) -> {
                java.util.List<com.bannerbound.core.api.settlement.food.LarderHooks.FoodStore> stores =
                    new java.util.ArrayList<>();
                for (long packed : settlement.claimedChunks()) {
                    net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(packed);
                    net.minecraft.world.level.chunk.LevelChunk chunk =
                        level.getChunkSource().getChunkNow(cp.x, cp.z);
                    if (chunk == null) continue;
                    for (net.minecraft.core.BlockPos pos : chunk.getBlockEntities().keySet()) {
                        if (level.getBlockEntity(pos) instanceof
                                com.bannerbound.antiquity.block.entity.StoneCookingPotBlockEntity pot
                                && pot.hasStew() && pot.stew() != null && !pot.stew().poisoned()) {
                            stores.add(new com.bannerbound.core.api.settlement.food.LarderHooks.FoodStore() {
                                @Override public double availableFoodValue() { return pot.remainingFoodValue(); }
                                @Override public double drainFoodValue(double max) { return pot.drainValue(max); }
                            });
                        }
                    }
                }
                return stores;
            });

            // Priority 3, NOT 4: vanilla goal selection is strict-less-than, so a priority-4 goal can never preempt the running priority-4 patrol -> at 4 an idle citizen patrols forever and never drinks.
            com.bannerbound.core.api.entity.CitizenGoalRegistry.register(
                3, com.bannerbound.antiquity.entity.GrogDrinkGoal::new);

            com.bannerbound.core.api.entity.CitizenGoalRegistry.register(
                3, com.bannerbound.antiquity.entity.StewEatGoal::new);

            com.bannerbound.antiquity.social.AntiquityThoughts.bootstrap();

            com.bannerbound.core.api.forager.ForagerHooks.setScavengeYield((lvl, state, rng) -> {
                java.util.List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>(2);
                boolean grassy = state.is(net.minecraft.world.level.block.Blocks.SHORT_GRASS)
                    || state.is(net.minecraft.world.level.block.Blocks.TALL_GRASS)
                    || state.is(net.minecraft.world.level.block.Blocks.FERN)
                    || state.is(net.minecraft.world.level.block.Blocks.LARGE_FERN);
                if (grassy) {
                    if (rng.nextFloat() < 0.50f) {
                        out.add(new net.minecraft.world.item.ItemStack(PLANT_FIBER.get(), 1 + rng.nextInt(2)));
                    }
                    if (rng.nextFloat() < 0.25f) {
                        out.add(new net.minecraft.world.item.ItemStack(
                            net.minecraft.world.item.Items.WHEAT_SEEDS, 1));
                    }
                } else if (state.is(net.minecraft.tags.BlockTags.LEAVES) && rng.nextFloat() < 0.40f) {
                    out.add(new net.minecraft.world.item.ItemStack(
                        net.minecraft.world.item.Items.STICK, 1 + rng.nextInt(2)));
                } else if (state.is(net.minecraft.world.level.block.Blocks.DEAD_BUSH)
                        && rng.nextFloat() < 0.25f) {
                    out.add(new net.minecraft.world.item.ItemStack(DRY_FIBER.get(), 1));
                }
                return out;
            });

            com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                    FLETCHING_STATION.get(), "fletchery",
                    new com.bannerbound.antiquity.workshop.FletcherExecutor(),
                    net.minecraft.world.item.Items.STRING));
            com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                    CRAFTING_STONE.get(), "general_crafts",
                    new com.bannerbound.antiquity.workshop.GeneralCraftsExecutor(),
                    CRAFTING_STONE_ITEM.get()));
            com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                    POTTERY_SLAB.get(), "pottery",
                    new com.bannerbound.antiquity.workshop.PotterExecutor(),
                    net.minecraft.world.item.Items.CLAY_BALL));

            com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                    WOODWORKING_TABLE.get(), "carpentry",
                    new com.bannerbound.antiquity.workshop.CarpenterExecutor(),
                    net.minecraft.world.item.Items.OAK_PLANKS,
                    state -> state.getValue(com.bannerbound.antiquity.block.WoodworkingTableBlock.MAIN)));

            com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                    MASONS_BENCH.get(), "masonry",
                    new com.bannerbound.antiquity.workshop.MasonExecutor(),
                    net.minecraft.world.item.Items.STONE_BRICKS,
                    state -> state.getValue(com.bannerbound.antiquity.block.MasonsBenchBlock.MAIN)));

            com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                    TANNING_RACK.get(), "tannery",
                    new com.bannerbound.antiquity.workshop.TanneryExecutor(),
                    net.minecraft.world.item.Items.LEATHER,
                    state -> state.getValue(com.bannerbound.antiquity.block.TanningRackBlock.PART) == 0));

            {
                com.bannerbound.antiquity.workshop.BrewerExecutor brewer =
                    new com.bannerbound.antiquity.workshop.BrewerExecutor();
                for (DeferredBlock<com.bannerbound.antiquity.block.FermentationTroughBlock> trough
                        : FERMENTATION_TROUGH_BY_WOOD.values()) {
                    com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                        new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                            trough.get(), "brewery", brewer, MUG.get(),
                            state -> !state.getValue(
                                com.bannerbound.antiquity.block.FermentationTroughBlock.RIGHT)));
                }
            }

            com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                    STONE_COOKING_POT.get(), "cooking",
                    new com.bannerbound.antiquity.workshop.CookExecutor(),
                    net.minecraft.world.item.Items.COOKED_BEEF));

            com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                    STONE_ANVIL.get(), "smithy",
                    new com.bannerbound.antiquity.workshop.SmithExecutor(),
                    net.minecraft.world.item.Items.COPPER_INGOT));

            com.bannerbound.core.api.workshop.WorkBlockRegistry.setDefaultCrafterType("general_crafts");

            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("fletchery", "fletcher");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("general_crafts", "fletcher");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("pottery", "potter");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("carpentry", "carpenter");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("masonry", "mason");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("tannery", "tanner");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("brewery", "brewer");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("cooking", "cook");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("smithy", "smith");

            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerRequirement(
                "pottery", com.bannerbound.antiquity.workshop.PotteryWorkshopRules::validatePottery);
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerRequirement(
                "carpentry", com.bannerbound.antiquity.workshop.CarpentryWorkshopRules::validateCarpentry);
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerRequirement(
                "masonry", com.bannerbound.antiquity.workshop.MasonryWorkshopRules::validateMasonry);
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerRequirement(
                "tannery", com.bannerbound.antiquity.workshop.TanneryWorkshopRules::validateTannery);
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerRequirement(
                "brewery", com.bannerbound.antiquity.workshop.BreweryWorkshopRules::validateBrewery);
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerRequirement(
                "cooking", com.bannerbound.antiquity.workshop.CookingWorkshopRules::validateKitchen);
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerRequirement(
                "smithy", com.bannerbound.antiquity.workshop.SmithyWorkshopRules::validateSmithy);
        });
    }
}
