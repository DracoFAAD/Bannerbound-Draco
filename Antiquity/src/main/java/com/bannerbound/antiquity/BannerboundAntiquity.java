package com.bannerbound.antiquity;

import org.slf4j.Logger;

import com.bannerbound.antiquity.block.BasketBlock;
import com.bannerbound.antiquity.block.BloomeryBlock;
import com.bannerbound.antiquity.block.ChoppingStumpBlock;
import com.bannerbound.antiquity.block.WoodworkingTableBlock;
import com.bannerbound.antiquity.block.ClayedCobblestoneBlock;
import com.bannerbound.antiquity.block.CraftingStoneBlock;
import com.bannerbound.antiquity.block.KilnBlock;
import com.bannerbound.antiquity.block.FletchingStationBlock;
import com.bannerbound.antiquity.block.FirewoodPileBlock;
import com.bannerbound.antiquity.block.MasonsBenchBlock;
import com.bannerbound.antiquity.block.MortarAndPestleBlock;
import com.bannerbound.antiquity.block.PotterySlabBlock;
import com.bannerbound.antiquity.block.RockBlock;
import com.bannerbound.antiquity.block.RopeCollisionBlock;
import com.bannerbound.antiquity.block.RopeFenceGateBlock;
import com.bannerbound.antiquity.block.RopeFencePostBlock;
import com.bannerbound.antiquity.block.ThatchBedBlock;
import com.bannerbound.antiquity.block.ThatchDoorBlock;
import com.bannerbound.antiquity.block.entity.BasketBlockEntity;
import com.bannerbound.antiquity.block.entity.BloomeryBlockEntity;
import com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity;
import com.bannerbound.antiquity.block.entity.ChoppingStumpBlockEntity;
import com.bannerbound.antiquity.block.entity.CraftingStoneBlockEntity;
import com.bannerbound.antiquity.block.entity.FletchingStationBlockEntity;
import com.bannerbound.antiquity.block.entity.KilnBlockEntity;
import com.bannerbound.antiquity.block.entity.MasonsBenchBlockEntity;
import com.bannerbound.antiquity.block.entity.MortarAndPestleBlockEntity;
import com.bannerbound.antiquity.block.entity.PotterySlabBlockEntity;
import com.bannerbound.antiquity.block.entity.RopeFenceGateBlockEntity;
import com.bannerbound.antiquity.block.entity.RopeFencePostBlockEntity;
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
import com.bannerbound.antiquity.entity.GroundDecalEntity;
import com.bannerbound.antiquity.entity.RaftEntity;
import com.bannerbound.antiquity.entity.SpearProjectile;
import com.bannerbound.antiquity.entity.SpearedFishEntity;
import com.bannerbound.antiquity.entity.StuckSpear;
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

/**
 * Main mod class for Bannerbound: Antiquity — the Antiquity-age expansion to Bannerbound: Core.
 * This expansion holds Antiquity content that changes Minecraft through new items and blocks.
 * Workstations and worker units stay in Core (they navigate vanilla mechanics); only when an
 * Antiquity worker needs mechanics that don't exist in vanilla would it move here.
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

    /** Places a 2-block {@code DoublePlantBlock} (both halves) in worldgen — vanilla {@code simple_block}
     *  only sets the lower half, leaving the upper missing. Used by the curare vine. */
    public static final DeferredHolder<net.minecraft.world.level.levelgen.feature.Feature<?>,
            com.bannerbound.antiquity.worldgen.DoublePlantFeature> DOUBLE_PLANT_FEATURE =
        FEATURES.register("double_plant", () -> new com.bannerbound.antiquity.worldgen.DoublePlantFeature(
            net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration.CODEC));

    /** Red blood droplet — falls with gravity and vanishes on hitting the ground (see client). */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> BLOOD_DROP =
        PARTICLE_TYPES.register("blood_drop", () -> new SimpleParticleType(false));

    // Per-mob list of spears embedded in it (the arrow-style "stuck in the mob" state). Serialized
    // to the mob's NBT (server) and natively synced to clients on tracking + on every setData, so a
    // render layer can draw them with no follow-entity and no relog. Drops on death (see
    // StuckSpearDropEvents); NOT copyOnDeath (we drop the items, we don't copy them to a respawn).
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<List<StuckSpear>>> STUCK_SPEARS =
        ATTACHMENT_TYPES.register("stuck_spears", () ->
            AttachmentType.<List<StuckSpear>>builder(() -> List.<StuckSpear>of())
                .serialize(StuckSpear.LIST_CODEC)
                .sync(StuckSpear.LIST_STREAM_CODEC)
                .build());

    // ── Immersive-hunting transient state on vanilla animals (server-only AI; NOT serialized or
    // synced — fear/stamina/bleed are momentary and intentionally reset on chunk reload). ──
    /** Gametick until which the animal stays spooked (drives flee/herd/charge). */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> SCARED_UNTIL =
        ATTACHMENT_TYPES.register("scared_until", () -> AttachmentType.<Long>builder(() -> 0L).build());
    /** Gametick a pig's single-charger claim is valid until (one boar charges per herd). */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> BOAR_CHARGE_CLAIM =
        ATTACHMENT_TYPES.register("boar_charge_claim", () -> AttachmentType.<Long>builder(() -> 0L).build());
    /** Flee stamina (persistence hunting). -1 = uninitialized → seeded to the configured max. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Float>> HUNT_STAMINA =
        ATTACHMENT_TYPES.register("hunt_stamina", () -> AttachmentType.<Float>builder(() -> -1.0F).build());
    /** Remaining bleed ticks (0 = not bleeding). */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> BLEED_TICKS =
        ATTACHMENT_TYPES.register("bleed_ticks", () -> AttachmentType.<Integer>builder(() -> 0).build());

    /** Bleed inflicted by which entity */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> BLEED_BY =
            ATTACHMENT_TYPES.register("bleed_by", () -> AttachmentType.<String>builder(() -> "").build());

    /** Game-time of the next blood-vomit at the lethal poison stage (transient; 0 = none scheduled). */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> POISON_NEXT_VOMIT =
        ATTACHMENT_TYPES.register("poison_next_vomit", () -> AttachmentType.<Long>builder(() -> 0L).build());

    /** Oleander's absolute cardiac deadline (game-time the heart gives out). SERIALIZED (survives
     *  reload mid-clock) and SYNCED so the client can drive the continuous blood-vignette + accelerating
     *  heartbeat from how close it is. 0 = no clock running. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> POISON_CARDIAC_AT =
        ATTACHMENT_TYPES.register("poison_cardiac_at", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG)
            .build());

    /** Curare phase deadlines. {@code FAINT_AT} = game-time the victim passes out (stun ends),
     *  {@code WAKE_AT} = game-time they fully recover (unconscious ends). SERIALIZED + SYNCED so the
     *  client drives the eyelid HUD and the prone render off the phase. 0 = no curare clock. */
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

    /** Game-time until which an entity resists NEW curare doses (granted by the arnica antidote, so a
     *  freed victim can't be instantly re-kidnapped). Server-only; serialized so it survives reload. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> POISON_CURARE_IMMUNE_UNTIL =
        ATTACHMENT_TYPES.register("poison_curare_immune_until", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG)
            .build());

    /** A pending food-poison dose: it lands a short while AFTER the victim eats the laced food (so the
     *  link to the meal isn't obvious). {@code _AT} = game-time to apply (0 = none); {@code _TYPE} = the
     *  poison id; {@code _STAGE} = the starting stage from the food's dose. Server-only; serialized. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> POISON_FOOD_APPLY_AT =
        ATTACHMENT_TYPES.register("poison_food_apply_at", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG).build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> POISON_FOOD_TYPE =
        ATTACHMENT_TYPES.register("poison_food_type", () -> AttachmentType.<String>builder(() -> "")
            .serialize(Codec.STRING).build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> POISON_FOOD_STAGE =
        ATTACHMENT_TYPES.register("poison_food_stage", () -> AttachmentType.<Integer>builder(() -> 0)
            .serialize(Codec.INT).build());

    /** Kidnap drag links. {@code DRAGGED_BY} (on the victim, SYNCED) = the dragger's entity id, drives
     *  the rope render + "being kidnapped" check; {@code DRAGGING} (on the dragger, server-only) = the
     *  victim's entity id, drives the per-tick tow. Both transient (0 = none); mirror Core's HERDED_BY. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> DRAGGED_BY =
        ATTACHMENT_TYPES.register("dragged_by", () -> AttachmentType.<Integer>builder(() -> 0)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_INT)
            .build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> DRAGGING =
        ATTACHMENT_TYPES.register("dragging", () -> AttachmentType.<Integer>builder(() -> 0).build());

    /** The poison afflicting an entity (blowdart/herb poisons). SERIALIZED (poison survives reload)
     *  and SYNCED to clients (player HUD vignette + citizen poison glyph read it). Default NONE so
     *  getData never returns null. NOT copyOnDeath — a respawn is clean. See the {@code poison} package. */
    public static final DeferredHolder<AttachmentType<?>,
            AttachmentType<com.bannerbound.antiquity.poison.PoisonState>> POISON_STATE =
        ATTACHMENT_TYPES.register("poison_state", () ->
            AttachmentType.<com.bannerbound.antiquity.poison.PoisonState>builder(
                    () -> com.bannerbound.antiquity.poison.PoisonState.NONE)
                .serialize(com.bannerbound.antiquity.poison.PoisonState.CODEC)
                .sync(com.bannerbound.antiquity.poison.PoisonState.STREAM_CODEC)
                .build());

    // Distance walked since the last footprint track was dropped (for even track spacing).
    /** Blunt-weapon crit STUN deadline (game-time the stagger ends). SYNCED so the dazed victim's
     *  client can blur their vision; NOT serialized — a 1s stagger never needs to survive a reload
     *  (and a stale value would re-pin the speed modifier on load). 0 = not stunned. See the
     *  {@code combat} package ({@link com.bannerbound.antiquity.combat.BluntStun}). */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> STUN_UNTIL =
        ATTACHMENT_TYPES.register("stun_until", () -> AttachmentType.<Long>builder(() -> 0L)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG)
            .build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Float>> FOOTPRINT_DIST =
        ATTACHMENT_TYPES.register("footprint_dist", () -> AttachmentType.<Float>builder(() -> 0.0F).build());

    /** Player drunkenness from grog (GROG_PLAN.md Phase 3.5). LEVEL is synced (drives drunk visuals /
     *  inverted controls + HUD); LAST_SIP backs the 30s stacking window + decay. SERIALIZED so you can't
     *  relog to instantly sober up. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> INTOXICATION_LEVEL =
        ATTACHMENT_TYPES.register("intoxication_level", () -> AttachmentType.<Integer>builder(() -> 0)
            .serialize(Codec.INT)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_INT).build());
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> INTOXICATION_LAST_SIP =
        ATTACHMENT_TYPES.register("intoxication_last_sip", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG).build());

    /** Game-time a black-out (drinking past the limit) ends — synced so the client draws the fade-to-
     *  black + locks input while you're out cold. SERIALIZED so relogging mid-blackout doesn't escape it.
     *  0 = conscious. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> PASS_OUT_UNTIL =
        ATTACHMENT_TYPES.register("pass_out_until", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG).build());

    /** Game-time a hangover ends — set on waking up still hammered (clears the drink instantly but you
     *  pay for it). Synced: drives the pounding vignette + muffled sound + crude-craft penalty. SERIALIZED
     *  so sleeping it off survives a relog. 0 = no hangover. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> HANGOVER_UNTIL =
        ATTACHMENT_TYPES.register("hangover_until", () -> AttachmentType.<Long>builder(() -> 0L)
            .serialize(Codec.LONG)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG).build());

    /** Game-time the green vomit goo on your screen clears — set when someone retches in your face
     *  (GROG_PLAN.md Phase 3.5). Synced so the client draws + fades the goo overlay. Transient. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> VOMIT_OVERLAY_UNTIL =
        ATTACHMENT_TYPES.register("vomit_overlay_until", () -> AttachmentType.<Long>builder(() -> 0L)
            .sync(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG).build());

    /** Set once an untameable livestock animal (cow, sheep, pig, …) has been fed its favourite food:
     *  it reverts to vanilla behaviour (no fleeing, no footprints, no hunting fear). SERIALIZED so it
     *  persists across save/reload (unlike the transient fear/stamina state above). */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Boolean>> TAMED_LIVESTOCK =
        ATTACHMENT_TYPES.register("tamed_livestock",
            () -> AttachmentType.<Boolean>builder(() -> false).serialize(Codec.BOOL).build());

    /** Per-chunk plaster/trim face decorations (see the {@code deco} package). Persisted with the
     *  chunk; edits sync to tracking clients via DecoUpdatePayload, full chunk on ChunkWatch.Sent. */
    public static final DeferredHolder<AttachmentType<?>,
            AttachmentType<com.bannerbound.antiquity.deco.ChunkDecorations>> CHUNK_DECORATIONS =
        ATTACHMENT_TYPES.register("face_decorations", () ->
            AttachmentType.builder(() -> new com.bannerbound.antiquity.deco.ChunkDecorations())
                .serialize(com.bannerbound.antiquity.deco.ChunkDecorations.CODEC)
                .build());

    /** Damage type for bleed-over-time (defined in data/.../damage_type/bleeding.json). */
    public static final ResourceKey<DamageType> BLEEDING_DAMAGE = ResourceKey.create(
        Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(MODID, "bleeding"));

    /** Damage type for poison-over-time (defined in data/.../damage_type/poison.json). */
    public static final ResourceKey<DamageType> POISON_DAMAGE = ResourceKey.create(
        Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(MODID, "poison"));

    // Bloomery door sounds — backed by sounds/bloomery_open.ogg / bloomery_close.ogg.
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
    // Knapping — the chink of stone-on-stone when shaping flint/bone or carving a crafting stone.
    public static final DeferredHolder<SoundEvent, SoundEvent> KNAPPING_SOUND = SOUNDS.register(
        "knapping",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "knapping")));
    // Cold-hammer minigame — one strike sound per grade (poor/good/great/perfect).
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
    // Fletching — the creak/pull as the stretch bar is drawn (plays each time SPACE is held).
    public static final DeferredHolder<SoundEvent, SoundEvent> FLETCHING_STRETCH_SOUND = SOUNDS.register(
        "fletching_stretch",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "fletching_stretch")));
    // Carpentry — the rasp of the saw (played while sawing, throttled so it never overlaps itself).
    public static final DeferredHolder<SoundEvent, SoundEvent> SAW_SOUND = SOUNDS.register(
        "saw",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "saw")));
    // Carpentry — plays once when a batch finishes sawing (the carpenter's-table completion chime).
    public static final DeferredHolder<SoundEvent, SoundEvent> SAW_DONE_SOUND = SOUNDS.register(
        "saw_done",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "saw_done")));
    public static final DeferredHolder<SoundEvent, SoundEvent> CRISIS_ANTIQUITY_SOUND = SOUNDS.register(
        "crisis_antiquity",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "crisis_antiquity")));

    // ── Spear sounds ──────────────────────────────────────────────────────────────────────────
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

    // ── Wolfsbane poison sounds ─────────────────────────────────────────────────────────────────
    // Four loopable ambience drones, one per stage, crossfaded by PoisonAmbienceManager; the client
    // hit/heal cues played only to the afflicted player; the stage-4 retch.
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
    // The puff of a blowgun firing a dart.
    public static final DeferredHolder<SoundEvent, SoundEvent> BLOWGUN_SHOOT = SOUNDS.register(
        "blowgun_shoot",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "blowgun_shoot")));

    // Slingshot: the elastic stretching back as the first pull begins, the snap of the release, and
    // the crack of a rock shattering on impact (a thrown OR slung rock — see ThrownRock).
    public static final DeferredHolder<SoundEvent, SoundEvent> SLING_PULL = SOUNDS.register(
        "sling_pull",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "sling_pull")));
    public static final DeferredHolder<SoundEvent, SoundEvent> SLING_SHOT = SOUNDS.register(
        "sling_shot",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "sling_shot")));
    public static final DeferredHolder<SoundEvent, SoundEvent> ROCK_IMPACT = SOUNDS.register(
        "rock_impact",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "rock_impact")));

    // ── Belladonna poison sounds (deliriant) ────────────────────────────────────────────────────
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
    /** A single heart-thud, replayed by the client at an accelerating cadence as oleander's cardiac
     *  clock runs down (asset added later — silent until then, no crash). */
    public static final DeferredHolder<SoundEvent, SoundEvent> OLEANDER_HEARTBEAT = SOUNDS.register(
        "oleander_heartbeat",
        () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "oleander_heartbeat")));

    // ── Thatch sounds ───────────────────────────────────────────────────────────────────────
    // Created as plain SoundEvents (not just DeferredHolders) so THATCH_SOUND / THATCH_SET_TYPE
    // below can reference them at class-load; the holders register the SAME instances into the
    // SOUND_EVENT registry. Files: sounds/thatch_{break,place,door_open,door_close}.ogg.
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

    /** SoundType for every thatch building block: custom break/place OGGs, grass-like step/hit/fall. */
    public static final SoundType THATCH_SOUND = new SoundType(1.0f, 1.0f,
        THATCH_BREAK_SOUND, SoundEvents.GRASS_STEP, THATCH_PLACE_SOUND,
        SoundEvents.GRASS_HIT, SoundEvents.GRASS_FALL);

    /** Block-set type for the thatch door — supplies the custom door open/close sounds (and the
     *  thatch SoundType). Trapdoor/pressure-plate/button sounds are unused by thatch but required by
     *  the record, so they fall back to wood. */
    public static final BlockSetType THATCH_SET_TYPE = BlockSetType.register(new BlockSetType(
        MODID + ":thatch", true, true, true,
        BlockSetType.PressurePlateSensitivity.EVERYTHING, THATCH_SOUND,
        THATCH_DOOR_CLOSE_SOUND, THATCH_DOOR_OPEN_SOUND,
        SoundEvents.WOODEN_TRAPDOOR_CLOSE, SoundEvents.WOODEN_TRAPDOOR_OPEN,
        SoundEvents.WOODEN_PRESSURE_PLATE_CLICK_OFF, SoundEvents.WOODEN_PRESSURE_PLATE_CLICK_ON,
        SoundEvents.WOODEN_BUTTON_CLICK_OFF, SoundEvents.WOODEN_BUTTON_CLICK_ON));

    // Mortar and Pestle — a stone bowl whose contents (water for now) render as an animated,
    // tinted liquid surface via its block entity renderer.
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

    // Basket — a 9-slot storage block. Its first slot's contents are shown on top of the basket
    // by a block entity renderer.
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

    // Bloomery — a 1×1×2 multiblock. It has no item: it only exists as a formed structure,
    // built by right-clicking a block of coal on two stacked mud bricks (see AntiquityEvents).
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

    // Clayed Cobblestone — cobblestone right-clicked with a clay ball. Eight in a 2×2×2 cube form a Kiln.
    public static final DeferredBlock<ClayedCobblestoneBlock> CLAYED_COBBLESTONE = BLOCKS.register("clayed_cobblestone",
        () -> new ClayedCobblestoneBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0f, 6.0f)
            .requiresCorrectToolForDrops()
            .sound(SoundType.STONE)));
    public static final DeferredItem<BlockItem> CLAYED_COBBLESTONE_ITEM =
        ITEMS.registerSimpleBlockItem("clayed_cobblestone", CLAYED_COBBLESTONE);

    // Kiln — a 2×2×2 multiblock. Like the bloomery it has no item: it only exists as a formed
    // structure, built by claying eight cobblestone into a cube (see KilnFormation). Used earlier
    // than the bloomery for ceramics/lime; fired with charcoal instead of stoked with a bellows.
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

    // Stone cooking pot — a single placed block (5 cobblestone + 1 stick at the crafting stone). Fill
    // with water, set on a lit campfire that ISN'T the town hall, add food → cooks into a stew that
    // feeds the settlement larder over time. See StoneCookingPotBlockEntity / the food-economy overhaul.
    public static final DeferredBlock<com.bannerbound.antiquity.block.StoneCookingPotBlock> STONE_COOKING_POT =
        BLOCKS.register("stone_cooking_pot",
            () -> new com.bannerbound.antiquity.block.StoneCookingPotBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(2.0f, 6.0f)
                .sound(SoundType.STONE)
                .noOcclusion()));
    // A flame-less campfire variant the cooking pot swaps in while it sits on top (so no flame pokes
    // through the pot). Not obtainable as an item — purely a render swap; see StoneCookingPotBlock.
    public static final DeferredBlock<com.bannerbound.antiquity.block.CookingFireBlock> COOKING_FIRE =
        BLOCKS.register("cooking_fire",
            () -> new com.bannerbound.antiquity.block.CookingFireBlock(
                BlockBehaviour.Properties.ofFullCopy(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                    // A vanilla campfire emits 15, which renders its own logs near full-bright — fine
                    // there because the tall flame hides the logs, but our flame-less variant exposes
                    // them, so at 15 they wash out with no visible ambient occlusion. A pot also largely
                    // covers the fire, so a lower emission is both better-looking (AO/depth returns) and
                    // more sensible. The low ember still carries the glow.
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

    // Clay Tank — a stackable pillar (up to 4) holding curing liquid for the tannery (TANNERY plan).
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
    // Unfired clay tank — the craftable, NON-placeable form (clay texture). Fired in the kiln into the
    // placeable CLAY_TANK, mirroring the unfired crucible → crucible chain.
    public static final DeferredItem<Item> UNFIRED_CLAY_TANK =
        ITEMS.registerSimpleItem("unfired_clay_tank", new Item.Properties());
    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<com.bannerbound.antiquity.block.entity.ClayTankBlockEntity>> CLAY_TANK_BE =
        BLOCK_ENTITY_TYPES.register("clay_tank",
            () -> BlockEntityType.Builder.of(
                com.bannerbound.antiquity.block.entity.ClayTankBlockEntity::new, CLAY_TANK.get())
                .build(null));

    // Tanning Rack — the tier-2 tannery work block (scrape → cure → dry → leather). TANNERY plan.
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

    // Crafting Stone — a knapping workbench carved from cobblestone/sandstone/red_sandstone by a
    // flint knife. Items are placed on it one at a time; a matched recipe shows a spinning result.
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

    // Fletching Station — the Tier-2 refinement bench. Place sticks + plant string on it, shift-
    // right-click to play the stretch minigame; performance rolls the output's craftsmanship quality.
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

    // Pottery Slab — the clay-shaping Tier-2 refinement bench. Place a clay block, choose the
    // floating recipe, then hold left-click and spin the mouse to shape the output.
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

    // Carpenter's Table — the Tier-2 batch-woodworking bench. Place logs to build a theoretical wood
    // budget, pick + queue outputs from the in-world ghost picker, then saw the batch (non-skill
    // minigame) to output the whole list at once. No quality.
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

    // Mason's Bench — the Tier-2 batch-stoneworking bench (stone analogue of the Carpenter's Table).
    // Place base stone (cobblestone, stone, sandstone…) to build a budget, pick + queue dressed
    // variants from the in-world ghost picker, then run the chisel-strike minigame to output the
    // whole list at once. No quality.
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

    // Armorer's Workbench — the player-designed-armor station (ARMOR_PLAN.md). A 2-cell bench (32px,
    // laid out like the Carpenter's Table); shift-right-click opens the design screen with a live 3D
    // helmet preview. No block entity — the bench is a static model; the design lives in the screen.
    public static final DeferredBlock<com.bannerbound.antiquity.block.ArmorersWorkbenchBlock> ARMORERS_WORKBENCH =
        BLOCKS.register("armorers_workbench",
            () -> new com.bannerbound.antiquity.block.ArmorersWorkbenchBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0f, 3.0f)
                .sound(SoundType.WOOD)
                .noOcclusion()));
    public static final DeferredItem<BlockItem> ARMORERS_WORKBENCH_ITEM =
        ITEMS.registerSimpleBlockItem("armorers_workbench", ARMORERS_WORKBENCH);

    // ── Rocks (Phase 4) ─────────────────────────────────────────────────────────────────────
    // Loose ground rocks that spawn in worldgen; four of a kind craft into the matching stone
    // block (the no-tools way to bootstrap cobblestone before you can mine). Instant break, sit on
    // any solid surface. Not noCollission/replaceable: the rock manages its own collision (none,
    // until snow-logged) and must NOT be replaced by snow/water — it absorbs them instead. randomTicks
    // drives the snow-logging sync (see RockBlock).
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

    // ── Manure + Dung (Animal Husbandry) ──────────────────────────────────────────────────────
    // Penned livestock leave flat manure pats on the floor (ManureEvents). Manure fouls the pen's
    // fertility (Core BreedingEvents, via #bannerbound:manure) until it's cleared — faster with a
    // shovel — which yields Dung, a bone-meal-style fertilizer. Herders muck it out as upkeep.
    // No collision (animals walk over it); not requiresCorrectToolForDrops, so a bare-hand clear still
    // gives dung (the shovel just speeds it up via minecraft:mineable/shovel).
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
    // Dung — the fertilizer. Registered as a vanilla BoneMealItem so it works exactly like bone meal
    // (right-click crops/saplings to grow them); it's the era-appropriate alternative.
    public static final DeferredItem<net.minecraft.world.item.BoneMealItem> DUNG =
        ITEMS.registerItem("dung", net.minecraft.world.item.BoneMealItem::new, new Item.Properties());

    // ── Chopping Stump (Phase 5) ────────────────────────────────────────────────────────────
    // Carved from a lone log with an axe; holds logs that an axe splits into firewood.
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

    // ── Rope Fence + Gate (one per wood type) ─────────────────────────────────────────────────
    // A bare post and a lift-bar gate per wood ("Oak Rope Fence" / "Oak Rope Fence Gate", …). The
    // wood is only the model (logs differ, the rope is the same); one block class + one BE type per
    // kind serves every wood, so all woods share the rope renderer and can rope to each other. To add
    // a wood: append it here and add its texture-only child models/blockstates (see the generator).
    public static final String[] ROPE_FENCE_WOODS =
        { "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry" };
    public static final List<DeferredBlock<RopeFencePostBlock>> ROPE_FENCE_POSTS = new ArrayList<>();
    public static final List<DeferredBlock<RopeFenceGateBlock>> ROPE_FENCE_GATES = new ArrayList<>();
    /** Every rope-fence + gate item, in registration order (for the creative tab). */
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

    // Invisible, un-targetable collision filler placed in the cells along a rope span so the rope
    // behaves like a fence (no model, no item — managed entirely by RopeFencePostBlock). Air-like
    // and destroyed by pistons rather than pushed.
    public static final DeferredBlock<RopeCollisionBlock> ROPE_COLLISION = BLOCKS.register("rope_collision",
        () -> new RopeCollisionBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.NONE)
            .noLootTable()
            .noOcclusion()
            .instabreak()
            .pushReaction(PushReaction.DESTROY)));

    // Firewood Pile — placed/grown by the firewood item; the 4th firewood turns it into a campfire.
    // No block item of its own (you build it by hand from firewood).
    public static final DeferredBlock<FirewoodPileBlock> FIREWOOD_PILE = BLOCKS.register("firewood_pile",
        () -> new FirewoodPileBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.0f)
            .sound(SoundType.WOOD)
            .noOcclusion()));

    // Stick Fence — a primitive lashed-stick fence, one per wood type (vanilla log textures, like the
    // rope fences). Plain vanilla FenceBlock: auto-connects to its own kind (+ solid faces) and gets
    // standard fence collision + FENCE pathfinding (via the minecraft:fences tag). Multipart blockstate
    // composes a post + a side arm per connected direction; each wood is a texture-only child model.
    public static final String[] STICK_FENCE_WOODS =
        { "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry" };
    /** Every per-wood stick-fence item, in registration order (for the creative tab). */
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

    // ── Drying Rack (one per wood type) ───────────────────────────────────────────────────────
    // A primitive line carved from a log with a bone blade. Holds four hanging spots; data-driven
    // drying recipes (data/.../drying_recipes/) cross-fade each spot's input into its result. Two
    // adjacent same-wood racks render as one "double" (chest-style LEFT/RIGHT), purely cosmetic —
    // each block keeps its own four spots + block entity. One block class + one BE type per wood,
    // each a texture-only child model (like the stick/rope fences).
    public static final String[] DRYING_RACK_WOODS =
        { "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry" };
    /** Per-wood drying-rack block, keyed by wood (for the carve handler's source-log lookup). */
    public static final java.util.Map<String, DeferredBlock<com.bannerbound.antiquity.block.DryingRackBlock>>
        DRYING_RACK_BY_WOOD = new java.util.LinkedHashMap<>();
    /** Every per-wood drying-rack item, in registration order (for the creative tab). */
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

    // ── Fermentation Trough (one per wood type) ────────────────────────────────────────────────
    // A hollowed-log vessel for grog (GROG_PLAN.md), carved from a log with a bone axe once the civ
    // knows the Fermentation research. Fills with water now; Phase 2 ferments loaded mash into a
    // tinted grog. Adjacent same-wood troughs connect into a run (cosmetic, like the drying rack) —
    // one block class + one BE type per wood, each a texture-only child model.
    public static final String[] FERMENTATION_TROUGH_WOODS = DRYING_RACK_WOODS;
    /** Per-wood trough block, keyed by wood (for the carve handler's source-log lookup). */
    public static final java.util.Map<String, DeferredBlock<com.bannerbound.antiquity.block.FermentationTroughBlock>>
        FERMENTATION_TROUGH_BY_WOOD = new java.util.LinkedHashMap<>();
    /** Every per-wood trough item, in registration order (for the creative tab). */
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

    // ── Thatch building set ─────────────────────────────────────────────────────────────────
    // Straw roofing/walling bundled from plant matter: a full block plus a slab, stairs (vanilla
    // shape connectivity via the stair blockstate) and a low straw-mat bed. Hay-soft, grass sound.
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
    // Thatch door — a non-swinging straw curtain: right-click toggles closed (solid panel) ↔ open
    // (passable, no collision). Two-tall like a vanilla door via ThatchDoorBlock extends DoorBlock.
    public static final DeferredBlock<ThatchDoorBlock> THATCH_DOOR =
        BLOCKS.register("thatch_door", () -> new ThatchDoorBlock(THATCH_SET_TYPE,
            thatchProps().noOcclusion()));
    public static final DeferredItem<BlockItem> THATCH_DOOR_ITEM =
        ITEMS.registerSimpleBlockItem("thatch_door", THATCH_DOOR);
    // Thatch bed — extends BedBlock but renders as a static model (see ThatchBedBlock). Stacks to 1
    // like vanilla beds.
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
    // Thatch bundle — the harvested plant-matter crafting material the thatch set is built from.
    public static final DeferredItem<Item> THATCH_BUNDLE =
        ITEMS.registerSimpleItem("thatch_bundle", new Item.Properties());

    // Antiquity fire tools.
    public static final DeferredItem<FireSticksItem> FIRE_STICKS =
        ITEMS.registerItem("fire_sticks", FireSticksItem::new, new Item.Properties().durability(16));
    // (The old handheld Bellows item was retired — the Bellows is now a jump-on block, BELLOWS_BLOCK.)

    // ── Primitive-tech materials (Phase 0) ──────────────────────────────────────────────────
    // Crude knapped edges (reusable, no durability) — drive grass/leaves harvesting via the
    // #cutting_tools item tag. Knife upgrades (durable, combat) are registered in later phases.
    public static final DeferredItem<Item> FLINT_BLADE =
        ITEMS.registerSimpleItem("flint_blade", new Item.Properties());
    public static final DeferredItem<Item> BONE_BLADE =
        ITEMS.registerSimpleItem("bone_blade", new Item.Properties());
    public static final DeferredItem<Item> PLANT_FIBER =
        ITEMS.registerSimpleItem("plant_fiber", new Item.Properties());

    // ── Tannery: raw hides (TANNERY plan) ───────────────────────────────────────────────────────
    // Per-species raw hides dropped by hunting/herding, tagged with HIDE_QUALITY (POOR/STANDARD/
    // GREAT). Quality is realized as the QUANTITY of scraped_hide at the tanning rack. Species map
    // lives in com.bannerbound.antiquity.tannery.Hides (used by both the hunt and herd paths).
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
    // Generic intermediates (no quality — quality was consumed at the scrape step).
    public static final DeferredItem<Item> SCRAPED_HIDE =
        ITEMS.registerSimpleItem("scraped_hide", new Item.Properties());
    public static final DeferredItem<Item> CURED_HIDE =
        ITEMS.registerSimpleItem("cured_hide", new Item.Properties());
    // HideQuality data component — Antiquity-local (Core never reads it; the herder cull receives an
    // already-tagged stack via HerderHooks). Mirrors CRUCIBLE_CONTENTS below.
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<com.bannerbound.antiquity.item.HideQuality>> HIDE_QUALITY =
        DATA_COMPONENTS.registerComponentType("hide_quality", b -> b
            .persistent(com.bannerbound.antiquity.item.HideQuality.CODEC)
            .networkSynchronized(com.bannerbound.antiquity.item.HideQuality.STREAM_CODEC));

    // ── Tannery: limestone → quicklime (TANNERY plan) ───────────────────────────────────────────
    // Limestone: a stone variant that generates in underground blobs (worldgen JSON); fired in the
    // kiln into quicklime, which converts a clay tank's water into the hide-curing liquid.
    public static final DeferredBlock<net.minecraft.world.level.block.Block> LIMESTONE =
        BLOCKS.registerSimpleBlock("limestone", net.minecraft.world.level.block.state.BlockBehaviour.Properties
            .ofFullCopy(net.minecraft.world.level.block.Blocks.STONE));
    public static final DeferredItem<BlockItem> LIMESTONE_ITEM =
        ITEMS.registerSimpleBlockItem("limestone", LIMESTONE);
    public static final DeferredItem<Item> QUICKLIME =
        ITEMS.registerSimpleItem("quicklime", new Item.Properties());
    // Tin — the Miner's chain (MINER_PLAN.md): tin chunks' boulders carry TIN_ORE, the miner chips
    // it into RAW_TIN. Core's BoulderLayout resolves both by string id, so Core stays standalone.
    // Raw tin feeds the crucible bronze chain (METALWORKING_PLAN.md).
    public static final DeferredItem<Item> RAW_TIN =
        ITEMS.registerSimpleItem("raw_tin", new Item.Properties());
    public static final DeferredBlock<net.minecraft.world.level.block.Block> TIN_ORE =
        BLOCKS.registerSimpleBlock("tin_ore", net.minecraft.world.level.block.state.BlockBehaviour.Properties
            .ofFullCopy(net.minecraft.world.level.block.Blocks.COPPER_ORE));
    public static final DeferredItem<BlockItem> TIN_ORE_ITEM =
        ITEMS.registerSimpleBlockItem("tin_ore", TIN_ORE);

    // ── Building materials: brick families ──────────────────────────────────────────────────────
    // Two new building-block families, each a full block + stairs + slab + wall: earthen UNFIRED
    // MUD BRICKS (raw clay bricks pressed into a block — soft, no firing) and dressed LIMESTONE
    // BRICKS (stone-tier). Plaster + trim are NOT blocks — they are per-face decoration layers; see
    // the plaster item + paint brush below and the {@code deco} package.
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

    // Plaster + trim are NOT blocks — they are per-face decoration layers (a plaster coat under an
    // optional dyed trim) stored in the CHUNK_DECORATIONS attachment and drawn flush onto the face via
    // AddSectionGeometryEvent, so the adjacent cell stays free for torches/storage. Plaster is applied
    // with the plaster ITEM (right-click a face to coat, consumes 1; sneak-right-click to strip); trim
    // is applied/recoloured/removed by the paint brush. See the deco package + PlasterItem/PaintBrushItem.
    public static final DeferredItem<com.bannerbound.antiquity.item.PlasterItem> PLASTER =
        ITEMS.registerItem("plaster", com.bannerbound.antiquity.item.PlasterItem::new, new Item.Properties());

    // Paint brush — the trim tool: sneak-cycles the selected shape (incl. "None" = remove), right-
    // click stamps it on a face coloured by the off-hand dye (placeholder stick art). See PaintBrushItem.
    public static final DeferredItem<PaintBrushItem> PAINT_BRUSH =
        ITEMS.registerItem("paint_brush", PaintBrushItem::new, new Item.Properties());
    // The brush's currently-selected trim shape: 0 = None (remove), 1..9 = TrimShape.ALL[n-1].
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<Integer>> TRIM_BRUSH_SHAPE =
        DATA_COMPONENTS.registerComponentType("trim_brush_shape", b -> b
            .persistent(Codec.INT)
            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT));

    // ── Crucible (METALWORKING_PLAN.md Part 2) ──────────────────────────────────────────────────
    // A clay crucible item that carries molten metal. Its molten layer is a grayscale animated
    // texture hue-shifted per metal by CrucibleColors (tint index 1). An empty crucible renders the
    // dry clay_crucible model; once it has CRUCIBLE_CONTENTS it swaps to clay_crucible_with_molten_metal
    // (driven by the "filled" item property → the model's override). 2D sprite in the GUI, 3D in-hand
    // (neoforge:separate_transforms). The mB composition / casting flow is still to come — this first
    // slice exists so copper / tin / bronze tints are testable in-game.
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<com.bannerbound.antiquity.item.CrucibleContents>> CRUCIBLE_CONTENTS =
        DATA_COMPONENTS.registerComponentType("crucible_contents", b -> b
            .persistent(com.bannerbound.antiquity.item.CrucibleContents.CODEC)
            .networkSynchronized(com.bannerbound.antiquity.item.CrucibleContents.STREAM_CODEC));
    /** Hidden poison on a coated food stack — see {@link com.bannerbound.antiquity.item.PoisonedFoodData}. */
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<com.bannerbound.antiquity.item.PoisonedFoodData>> POISONED_FOOD =
        DATA_COMPONENTS.registerComponentType("poisoned_food", b -> b
            .persistent(com.bannerbound.antiquity.item.PoisonedFoodData.CODEC)
            .networkSynchronized(com.bannerbound.antiquity.item.PoisonedFoodData.STREAM_CODEC));
    /** The {@link com.bannerbound.antiquity.poison.PoisonType} id coating a poison-arrow stack — set by
     *  the crafting recipe, read when the bow fires it, shown openly on the tooltip. */
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<String>> ARROW_POISON =
        DATA_COMPONENTS.registerComponentType("arrow_poison", b -> b
            .persistent(Codec.STRING)
            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8));
    /** The three modular parts of a composite {@link com.bannerbound.antiquity.item.CompositeArrowItem
     *  arrow} — the material id of its tip (flint/copper/tin/bronze), shaft (wood/copper/tin/bronze),
     *  and back/fletching (feather/fiber). Stamped by the fletching station's modular match (see
     *  {@link com.bannerbound.antiquity.recipe.ModularArrow}); read for the item icon (combo overrides),
     *  in-flight 3-pass render, and derived damage/accuracy (see
     *  {@link com.bannerbound.antiquity.item.ArrowParts}). Absent → the part's default (flint/wood/feather). */
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
    /** The 9 slots a Basket was holding when a player sneak-broke it, so the dropped basket item
     *  carries its contents and re-places them — like a bundle. Reuses vanilla's container payload.
     *  See {@link com.bannerbound.antiquity.block.BasketBlock} and {@code BasketContentsTooltip}. */
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<net.minecraft.world.item.component.ItemContainerContents>> BASKET_CONTENTS =
        DATA_COMPONENTS.registerComponentType("basket_contents", b -> b
            .persistent(net.minecraft.world.item.component.ItemContainerContents.CODEC)
            .networkSynchronized(net.minecraft.world.item.component.ItemContainerContents.STREAM_CODEC));
    /** Perishability on a food stack — an absolute spoil deadline + salt bonus. See
     *  {@link com.bannerbound.antiquity.item.FoodSpoilage} and {@code food_spoilage/*.json}
     *  (COOKING_PLAN.md). Antiquity-only; Core never sees it. */
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<com.bannerbound.antiquity.item.FoodSpoilage>> FOOD_SPOILAGE =
        DATA_COMPONENTS.registerComponentType("food_spoilage", b -> b
            .persistent(com.bannerbound.antiquity.item.FoodSpoilage.CODEC)
            .networkSynchronized(com.bannerbound.antiquity.item.FoodSpoilage.STREAM_CODEC));

    // ── Grog vessels (GROG_PLAN.md Phase 3) ─────────────────────────────────────────────────────
    // The grog a filled mug/horn holds (a snapshot of its GrogRecipe). Its presence = "full" (drives
    // the `filled` model override + the tinted alcohol layer); drinking applies it and clears it.
    public static final DeferredHolder<net.minecraft.core.component.DataComponentType<?>,
            net.minecraft.core.component.DataComponentType<com.bannerbound.antiquity.item.GrogContents>> GROG_CONTENTS =
        DATA_COMPONENTS.registerComponentType("grog_contents", b -> b
            .persistent(com.bannerbound.antiquity.item.GrogContents.CODEC)
            .networkSynchronized(com.bannerbound.antiquity.item.GrogContents.STREAM_CODEC));
    // Stew cooked in a stone cooking pot (food-economy overhaul) — identity + per-serving value, also
    // forward-compatible onto the future bowl item. STONE_POT_FILLED marks a held pot that holds water.
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
    // Drinking vessels — empty by default, filled (GROG_CONTENTS) at a ready Fermentation Trough. Both
    // share GrogVesselItem; the mug uses our empty_mug art, the goat horn the vanilla goat-horn texture.
    public static final DeferredItem<com.bannerbound.antiquity.item.GrogVesselItem> MUG =
        ITEMS.registerItem("mug", com.bannerbound.antiquity.item.GrogVesselItem::new,
            new Item.Properties().stacksTo(16));
    public static final DeferredItem<com.bannerbound.antiquity.item.GrogVesselItem> GOAT_HORN =
        ITEMS.registerItem("goat_horn", com.bannerbound.antiquity.item.GrogVesselItem::new,
            new Item.Properties().stacksTo(16));

    // The crucible is now a PLACEABLE block holding a charge of smeltable items (overhaul): place it,
    // right-click in raw ore / metal, break to pocket the charge, melt it in a bloomery, pour at the anvil.
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

    // ── Food preservation (COOKING_PLAN.md) ─────────────────────────────────────────────────────
    /** What perishable food turns into once its spoil deadline passes. Carries no food value, so the
     *  larder ignores it; it never spoils further. */
    public static final DeferredItem<Item> SPOILED_FOOD =
        ITEMS.registerSimpleItem("spoiled_food", new Item.Properties());
    /** Per-species dried foods — raw meat/fish air-dried on a drying rack (COOKING preservation
     *  line, tended by the Cook NPC). Worth LESS than the cooked equivalent but on the
     *  non-perishable list: preservation trades peak value for immortality, so roasting AND drying
     *  both stay worthwhile. Keyed by id suffix (dried_beef, dried_cod, …). */
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
    }
    /** Blueberry bush — a sweet-berry-style 4-stage bush that yields {@link #BLUEBERRIES}. Generates
     *  rarely in plains, a bit more in (non-taiga) forests. */
    public static final DeferredBlock<com.bannerbound.antiquity.block.BlueberryBushBlock> BLUEBERRY_BUSH =
        BLOCKS.register("blueberry_bush", () -> new com.bannerbound.antiquity.block.BlueberryBushBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.PLANT)
                .randomTicks()
                .noCollission()
                .instabreak()
                .sound(SoundType.SWEET_BERRY_BUSH)
                .pushReaction(PushReaction.DESTROY)));
    /** Blueberries — a foraged fruit (mirrors vanilla sweet berries: snackable, a grog input, AND the
     *  bush's planting item via {@link net.minecraft.world.item.ItemNameBlockItem}).
     *  See {@code data/.../grog_recipes/blueberry_grog.json} (ferments to a Haste grog). */
    public static final DeferredItem<Item> BLUEBERRIES =
        ITEMS.registerItem("blueberries",
            p -> new net.minecraft.world.item.ItemNameBlockItem(BLUEBERRY_BUSH.get(), p),
            new Item.Properties().food(
                new net.minecraft.world.food.FoodProperties.Builder()
                    .nutrition(2).saturationModifier(0.1F).build()));
    /** Crushed berries — raw berries pulped at the Mortar and Pestle into the fermentable mash that
     *  feeds the trough (GROG_PLAN.md mortar-crush step). Intermediates, not food on their own. */
    public static final DeferredItem<Item> BLUEBERRIES_PESTLED =
        ITEMS.registerSimpleItem("blueberries_pestled", new Item.Properties());
    public static final DeferredItem<Item> SWEET_BERRIES_PESTLED =
        ITEMS.registerSimpleItem("sweet_berries_pestled", new Item.Properties());
    /** Salt — rub it on a food (food in the other hand, hold right-click) for +25% shelf life. Also a
     *  pot preservative later. */
    public static final DeferredItem<com.bannerbound.antiquity.item.SaltItem> SALT =
        ITEMS.registerItem("salt", com.bannerbound.antiquity.item.SaltItem::new, new Item.Properties());
    /** A block of stored salt (decorative / bulk storage for now). */
    public static final DeferredBlock<Block> SALT_BLOCK = BLOCKS.register("salt_block",
        () -> new Block(BlockBehaviour.Properties.of()
            .mapColor(MapColor.SNOW).strength(0.6F).sound(SoundType.SAND)));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> SALT_BLOCK_ITEM =
        ITEMS.registerSimpleBlockItem("salt_block", SALT_BLOCK);

    // Stone Anvil — early smithing station (METALWORKING_PLAN.md Part 2). Holds a fired mold + the
    // molten pour; created by right-clicking a stone block with a hammer.
    public static final DeferredBlock<com.bannerbound.antiquity.block.StoneAnvilBlock> STONE_ANVIL =
        BLOCKS.register("stone_anvil", () -> new com.bannerbound.antiquity.block.StoneAnvilBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(1.5F)
                .sound(SoundType.STONE).noOcclusion()));
    public static final DeferredItem<BlockItem> STONE_ANVIL_ITEM =
        ITEMS.registerSimpleBlockItem("stone_anvil", STONE_ANVIL);

    // Bellows Block — jump on it to pump heat into an adjacent bloomery (METALWORKING_PLAN.md Part 1).
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

    // Pottery outputs shaped on the Pottery Slab. Unfired ceramics can later be kiln-fired; raw mud
    // brick bridges clay shaping into vanilla mud-brick construction.
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

    /** Display colours (0xRRGGBB) for the testable molten metals — the hue the molten layer tints to. */
    public static final int COLOR_COPPER = 0xED8E56;
    public static final int COLOR_TIN    = 0xD9DEE3;
    public static final int COLOR_BRONZE = 0xE29622;

    /** A crucible stack pre-filled with the given molten metal (for the creative tab / testing). */
    private static net.minecraft.world.item.ItemStack filledCrucible(String metalId, int color) {
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(CRUCIBLE.get());
        stack.set(CRUCIBLE_CONTENTS.get(),
            com.bannerbound.antiquity.item.CrucibleContents.molten(metalId, 200, color));
        return stack;
    }

    // Cordage: plant fiber → string → rope (recipes added later). Fiber Rope, held in the off hand
    // while throwing a spear, tethers the spear with a rope (gated by the spear-fishing research).
    public static final DeferredItem<Item> PLANT_STRING =
        ITEMS.registerSimpleItem("plant_string", new Item.Properties());
    public static final DeferredItem<FiberRopeItem> FIBER_ROPE =
        ITEMS.registerItem("fiber_rope", FiberRopeItem::new, new Item.Properties());
    // Firewood — split logs that stack by hand into a campfire (see FirewoodItem).
    public static final DeferredItem<FirewoodItem> FIREWOOD =
        ITEMS.registerItem("firewood", FirewoodItem::new, new Item.Properties());
    // Flint Knife — first durable tool: light weapon + cutting tool (+ carves a crafting stone).
    // Durability/attributes are applied inside FlintKnifeItem's constructor.
    public static final DeferredItem<FlintKnifeItem> FLINT_KNIFE =
        ITEMS.registerItem("flint_knife", FlintKnifeItem::new, new Item.Properties());
    // Wooden Knife — carpenter-made cutting tool: better durability than bone, slightly less damage.
    // 3.0 dmg, 2.0 speed; same cutting functionality as the other knives (made at the carpenter's table).
    public static final DeferredItem<KnifeItem> WOODEN_KNIFE = ITEMS.registerItem("wooden_knife",
        p -> new KnifeItem(p, 70, 3.0, 2.0), new Item.Properties());

    // ── Bone tool set (crafted at the crafting stone) ───────────────────────────────────────
    // Bone tier sits before wood: lower durability, slightly faster, mines stone but no ores.
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
    // Bone Knife — 3.5 dmg, 2.0 speed, bone-tier durability; cutting tool (no mining).
    public static final DeferredItem<KnifeItem> BONE_KNIFE = ITEMS.registerItem("bone_knife",
        p -> new KnifeItem(p, 48, 3.5, 2.0), new Item.Properties());
    // Bone Club — heavy, slow BLUNT weapon (TANNERY plan): the hide-preference Blunt category. Not a
    // cutting tool. 4.0 dmg, 1.0 speed, bone-tier durability.
    public static final DeferredItem<com.bannerbound.antiquity.item.ClubItem> BONE_CLUB =
        ITEMS.registerItem("bone_club",
            p -> new com.bannerbound.antiquity.item.ClubItem(p, 48, 4.0, 1.0), new Item.Properties());

    // Bone Shears — primitive shears (two bone blades lashed with plant fiber). Functions as vanilla
    // shears (shear sheep, harvest leaves/vines/wool, etc.); bone-tier durability.
    public static final DeferredItem<net.minecraft.world.item.ShearsItem> BONE_SHEARS =
        ITEMS.registerItem("bone_shears",
            p -> new net.minecraft.world.item.ShearsItem(p), new Item.Properties().durability(64)
                .component(net.minecraft.core.component.DataComponents.TOOL,
                    net.minecraft.world.item.ShearsItem.createToolProperties()));

    // Bone Saw — the carpenter's tool (bone-age tier; the "saw" tool role resolves it per age, so a
    // future flint/metal saw can slot in). Triggers the carpenter's-table saw minigame (right-click
    // the table with it) and is the Carpenter NPC's job tool. Plain durable item.
    public static final DeferredItem<Item> BONE_SAW = ITEMS.registerSimpleItem("bone_saw",
        new Item.Properties().durability(250));

    // Stone Chisel — the mason's tool (the "chisel" tool role resolves it per age). Knapped by hand
    // (see knapping_shapes/stone_chisel.json), kept in a masonry workshop's storage to staff it, and
    // triggers the mason's-bench chisel-strike minigame (right-click the bench with it). Plain durable.
    public static final DeferredItem<Item> STONE_CHISEL = ITEMS.registerSimpleItem("stone_chisel",
        new Item.Properties().durability(192));

    // ── Spears (3 tiers, throwable) ─────────────────────────────────────────────────────────
    // Melee weapons with +2 entity-reach over a sword and a 1.2 attack speed; charge like a bow
    // and release to throw a SpearProjectile that sticks in mobs and is recovered as the same
    // spear (see SpearItem / SpearProjectile). The thrown projectile's base damage = the spear's
    // melee damage. Durabilities: wood = wooden sword (59), bone = bone tier (48, < wood),
    // stone = stone sword (131).
    public static final DeferredItem<SpearItem> WOODEN_SPEAR = ITEMS.registerItem("wooden_spear",
        p -> new SpearItem(p, 59, 4.0, 1.2), new Item.Properties());
    public static final DeferredItem<SpearItem> BONE_SPEAR = ITEMS.registerItem("bone_spear",
        p -> new SpearItem(p, 48, 4.0, 1.2), new Item.Properties());
    public static final DeferredItem<SpearItem> STONE_SPEAR = ITEMS.registerItem("stone_spear",
        p -> new SpearItem(p, 131, 5.5, 1.2), new Item.Properties());
    // Metal spears (cast spear-point → hafted at the Crafting Stone). Durability = the metal tier's
    // uses (tin 120, copper 180, bronze 375); damage scales with the tier. Tin sits just under stone,
    // copper above it, bronze the strongest melee spear. All keep the 1.2 spear attack speed.
    public static final DeferredItem<SpearItem> TIN_SPEAR = ITEMS.registerItem("tin_spear",
        p -> new SpearItem(p, 120, 5.0, 1.2), new Item.Properties());
    public static final DeferredItem<SpearItem> COPPER_SPEAR = ITEMS.registerItem("copper_spear",
        p -> new SpearItem(p, 180, 6.0, 1.2), new Item.Properties());
    public static final DeferredItem<SpearItem> BRONZE_SPEAR = ITEMS.registerItem("bronze_spear",
        p -> new SpearItem(p, 375, 7.0, 1.2), new Item.Properties());

    // ── Stone tool heads (knapped by hand; see KNAPPING_PLAN.md) ─────────────────────────────
    // Made from two rocks via the two-rocks gesture → KnappingScreen (shape grid picks the head,
    // the timing minigame rolls its TOOL_QUALITY). Each head is then hafted at the Crafting Stone
    // (head + stick + plant fiber) onto the matching VANILLA stone tool, transferring its quality.
    // Plain crafting components — flat sprites under textures/item/stone_heads/, never tools.
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

    // ── Fletching (Tier-2) outputs ──────────────────────────────────────────────────────────
    // Primitive Bow — a hand-fletched bow, a touch less durable than the vanilla bow (the Antiquity
    // ranged weapon; vanilla bow is research-gated out). Ships its own pulling sprites (the pull/
    // pulling item properties are registered in BannerboundAntiquityClient). Quality scales arrow
    // velocity (in PrimitiveBowItem) and durability (MAX_DAMAGE component set at craft time).
    public static final DeferredItem<PrimitiveBowItem> PRIMITIVE_BOW = ITEMS.registerItem("primitive_bow",
        PrimitiveBowItem::new, new Item.Properties().durability(240));
    // Modular Arrow — ONE composite bow-ammo item assembled from three parts (tip / shaft / back),
    // each stamped as a data component by the fletching station's free-mix match (ModularArrow). The
    // tip + shaft (metal weight) scale damage, the back scales accuracy, and quality scales on top;
    // the icon (layered combo model) and in-flight render (3-pass) both read the parts. Replaces the
    // old per-material flint/copper/tin/bronze arrow items.
    public static final DeferredItem<com.bannerbound.antiquity.item.CompositeArrowItem> ARROW =
        ITEMS.registerItem("arrow",
            com.bannerbound.antiquity.item.CompositeArrowItem::new, new Item.Properties());
    // Poison arrows are NOT a separate item — ANY arrow (the #minecraft:arrows tag) can be COATED via
    // the poison paste (like food), stamping the ARROW_POISON component; PoisonArrowEvents delivers it
    // (impact = apply the poison, tick = colour trail). See ARROW_POISON above.
    // Display-only "work in progress" bow shown lying on the fletching station while its minigame
    // runs (declared per-recipe via the optional "in_progress" field). Never obtainable: no recipe,
    // no creative-tab entry — it exists so the station's renderer has a sprite to draw.
    public static final DeferredItem<Item> IN_PROGRESS_PRIMITIVE_BOW =
        ITEMS.registerSimpleItem("in_progress_primitive_bow", new Item.Properties());

    // The thrown-spear projectile. MISC category (it's not a mob); arrow-like tracking. The model
    // is drawn by SpearProjectileRenderer from the carried spear item.
    public static final DeferredHolder<EntityType<?>, EntityType<SpearProjectile>> SPEAR_PROJECTILE =
        ENTITY_TYPES.register("spear",
            () -> EntityType.Builder.<SpearProjectile>of(SpearProjectile::new, MobCategory.MISC)
                .sized(0.5f, 0.5f)
                .clientTrackingRange(4)
                .updateInterval(20)
                .build("spear"));

    // The modular arrow in flight — ONE entity type for every part combination. Its renderer draws
    // the projectile in three layers (back / shaft / tip) from the parts on the pickup stack, so a
    // single entity covers all materials (vanilla Arrow's renderer is hardwired to arrow.png).
    public static final DeferredHolder<EntityType<?>, EntityType<com.bannerbound.antiquity.entity.CompositeArrowEntity>> ARROW_ENTITY =
        ENTITY_TYPES.register("arrow",
            () -> EntityType.Builder.<com.bannerbound.antiquity.entity.CompositeArrowEntity>of(
                    com.bannerbound.antiquity.entity.CompositeArrowEntity::new, MobCategory.MISC)
                .sized(0.5f, 0.5f)
                .clientTrackingRange(4)
                .updateInterval(20)
                .build("arrow"));

    // The blowdart projectile — a poison delivery dart (the carried PoisonType decides the coating).
    // Almost no impact damage; arrow-like tracking, same as the spear/flint arrow.
    public static final DeferredHolder<EntityType<?>, EntityType<com.bannerbound.antiquity.entity.BlowdartProjectile>> BLOWDART_PROJECTILE =
        ENTITY_TYPES.register("blowdart",
            () -> EntityType.Builder.<com.bannerbound.antiquity.entity.BlowdartProjectile>of(
                    com.bannerbound.antiquity.entity.BlowdartProjectile::new, MobCategory.MISC)
                .sized(0.4f, 0.4f)
                .clientTrackingRange(4)
                .updateInterval(20)
                .build("blowdart"));

    // Thrown rock — snowball-style throwable (stone/sandstone/red sandstone). Minimal damage + stun.
    public static final DeferredHolder<EntityType<?>, EntityType<com.bannerbound.antiquity.entity.ThrownRock>> THROWN_ROCK =
        ENTITY_TYPES.register("thrown_rock",
            () -> EntityType.Builder.<com.bannerbound.antiquity.entity.ThrownRock>of(
                    com.bannerbound.antiquity.entity.ThrownRock::new, MobCategory.MISC)
                .sized(0.25f, 0.25f)
                .clientTrackingRange(4)
                .updateInterval(10)
                .build("thrown_rock"));

    // Cosmetic ground decal — blood splats + footprint tracks (the hunting tracker). MISC category.
    public static final DeferredHolder<EntityType<?>, EntityType<GroundDecalEntity>> GROUND_DECAL =
        ENTITY_TYPES.register("ground_decal",
            () -> EntityType.Builder.<GroundDecalEntity>of(GroundDecalEntity::new, MobCategory.MISC)
                .sized(1.0f, 0.25f) // a little height so the cursor can right-click it to examine
                .clientTrackingRange(6)
                .updateInterval(40)
                .build("ground_decal"));

    // Spear-fishing catch — a thrown spear that kills a fish leaves this floating object (the spear
    // with the fish impaled on its tip) instead of loose drops; walk over it to collect the spear +
    // the fish + its drops (see SpearedFishEntity / SpearFishingEvents). MISC category.
    public static final DeferredHolder<EntityType<?>, EntityType<SpearedFishEntity>> SPEARED_FISH =
        ENTITY_TYPES.register("speared_fish",
            () -> EntityType.Builder.<SpearedFishEntity>of(SpearedFishEntity::new, MobCategory.MISC)
                .sized(0.6f, 0.6f)
                .clientTrackingRange(6)
                .updateInterval(10)
                .build("speared_fish"));

    // ── Raft ────────────────────────────────────────────────────────────────────────────────
    // A primitive boat: RaftEntity extends vanilla Boat, so it inherits water physics, WASD
    // steering, paddle sounds and the mount/dismount + paddle-input wiring. It is NOT a placed
    // item — you form it from a line of 3 thatch blocks right-clicked with an oar (see
    // AntiquityEvents#onFormRaft), it carries thatch "integrity" health, and it breaks back into
    // thatch. The hitbox is a touch larger than a vanilla boat (1.375); the model is a long raft, so
    // tweak .sized() if the footprint feels off. MISC category like the vanilla boat.
    public static final DeferredHolder<EntityType<?>, EntityType<RaftEntity>> RAFT =
        ENTITY_TYPES.register("raft",
            () -> EntityType.Builder.<RaftEntity>of(RaftEntity::new, MobCategory.MISC)
                // Physical/float box ≈ the raft's real width. The raft's LENGTH is covered for
                // clicking/attacking by rotating hit-parts (see RaftEntity / RaftPart), so this
                // square no longer has to be oversized to reach the ends.
                .sized(1.4f, 0.6f)
                .clientTrackingRange(10)
                .build("raft"));
    // Oar — the tool that forms a raft from a line of thatch (placeholder stick texture). Reusable;
    // forming consumes the thatch, not the oar.
    public static final DeferredItem<Item> OAR =
        ITEMS.registerSimpleItem("oar", new Item.Properties());

    // ── Biome poison + remedy herbs (POISON_PLAN) ───────────────────────────────────────────────
    // Each biome grows a signature poison plant + (a different biome's) remedy herb — the cross-biome
    // cure cycle that feeds trade. Wolfsbane (mountain) is the first poison end-to-end; yarrow (forest)
    // is its antidote. Cross-model ground plants ground at the Mortar and Pestle into paste / antidote.
    private static BlockBehaviour.Properties herbProps() {
        return BlockBehaviour.Properties.of()
            .mapColor(MapColor.PLANT)
            .noCollission()
            .instabreak()
            .sound(SoundType.GRASS)
            .pushReaction(PushReaction.DESTROY);
    }
    // Wolfsbane — mountain/meadow herb; ground into wolfsbane poison paste.
    public static final DeferredBlock<com.bannerbound.antiquity.block.ForageFlowerBlock> WOLFSBANE =
        BLOCKS.register("wolfsbane", () -> new com.bannerbound.antiquity.block.ForageFlowerBlock(herbProps()));
    public static final DeferredItem<BlockItem> WOLFSBANE_ITEM =
        ITEMS.registerSimpleBlockItem("wolfsbane", WOLFSBANE);
    // Yarrow — forest remedy herb; ground into the antidote that cures wolfsbane.
    public static final DeferredBlock<com.bannerbound.antiquity.block.ForageFlowerBlock> YARROW =
        BLOCKS.register("yarrow", () -> new com.bannerbound.antiquity.block.ForageFlowerBlock(herbProps()));
    public static final DeferredItem<BlockItem> YARROW_ITEM =
        ITEMS.registerSimpleBlockItem("yarrow", YARROW);
    // Wolfsbane poison paste — the ground coating; crafted onto a dart shaft, or rubbed into food.
    public static final DeferredItem<com.bannerbound.antiquity.item.PoisonPasteItem> WOLFSBANE_POISON =
        ITEMS.registerItem("wolfsbane_poison",
            p -> new com.bannerbound.antiquity.item.PoisonPasteItem(p, com.bannerbound.antiquity.poison.PoisonType.WOLFSBANE),
            new Item.Properties());
    // Yarrow antidote — drink to cure WOLFSBANE only (the cross-biome cure cycle).
    public static final DeferredItem<com.bannerbound.antiquity.item.AntidoteItem> YARROW_ANTIDOTE =
        ITEMS.registerItem("yarrow_antidote",
            p -> new com.bannerbound.antiquity.item.AntidoteItem(p, com.bannerbound.antiquity.poison.PoisonType.WOLFSBANE),
            new Item.Properties().stacksTo(16));
    // Wolfsbane blowdart — bamboo dart coated in wolfsbane; thrown by hand it's weak/short-range.
    public static final DeferredItem<com.bannerbound.antiquity.item.PoisonDartItem> WOLFSBANE_DART =
        ITEMS.registerItem("wolfsbane_dart",
            p -> new com.bannerbound.antiquity.item.PoisonDartItem(p, com.bannerbound.antiquity.poison.PoisonType.WOLFSBANE),
            new Item.Properties().stacksTo(16));

    // ── Curare (jungle) — the kidnap poison ──────────────────────────────────────────────────────
    // Curare vine: a TALL two-block jungle plant (rose-bush/peony shape, TallForagePlantBlock). Ground
    // into curare poison; arnica (mountain) is its antidote (the cross-biome cure cycle).
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
    // Arnica (mountain) cures CURARE — its remedy herb (ground at the mortar) + the antidote.
    public static final DeferredBlock<com.bannerbound.antiquity.block.ForageFlowerBlock> ARNICA =
        BLOCKS.register("arnica", () -> new com.bannerbound.antiquity.block.ForageFlowerBlock(herbProps()));
    public static final DeferredItem<BlockItem> ARNICA_ITEM =
        ITEMS.registerSimpleBlockItem("arnica", ARNICA);
    public static final DeferredItem<com.bannerbound.antiquity.item.AntidoteItem> ARNICA_ANTIDOTE =
        ITEMS.registerItem("arnica_antidote",
            p -> new com.bannerbound.antiquity.item.AntidoteItem(p, com.bannerbound.antiquity.poison.PoisonType.CURARE),
            new Item.Properties().stacksTo(16));

    // ── Oleander (desert) — the cardiac poison, cured by jungle cinchona ───────────────────────────
    // SandForagePlantBlock so it can root in desert sand (the base ForageFlowerBlock is dirt/grass only).
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

    // ── Belladonna (deadly nightshade) — forest deliriant, cured by swamp marshmallow root ──────
    // Deadly nightshade — forest poison plant; ground into nightshade poison paste.
    public static final DeferredBlock<com.bannerbound.antiquity.block.ForageFlowerBlock> DEADLY_NIGHTSHADE =
        BLOCKS.register("deadly_nightshade", () -> new com.bannerbound.antiquity.block.ForageFlowerBlock(herbProps()));
    public static final DeferredItem<BlockItem> DEADLY_NIGHTSHADE_ITEM =
        ITEMS.registerSimpleBlockItem("deadly_nightshade", DEADLY_NIGHTSHADE);
    // Marshmallow root — swamp remedy herb; ground into the antidote that cures belladonna.
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
    // Cinchona (jungle) cures OLEANDER (desert) — its remedy herb (ground at the mortar) + the antidote.
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

    // Blowgun — a bamboo tube that fires any poison dart from the inventory far faster + straighter
    // than a hand throw. Reusable; consumes one dart per shot.
    public static final DeferredItem<com.bannerbound.antiquity.item.BlowgunItem> BLOWGUN =
        ITEMS.registerItem("blowgun",
            com.bannerbound.antiquity.item.BlowgunItem::new, new Item.Properties().stacksTo(1));

    // Slingshot — the crude pre-Archery ranged weapon, fletched before the bow. Draws like a bow and
    // flings a rock (stone/sandstone/red sandstone) from the inventory: harder-hitting than a hand
    // throw but plainly worse than a bow (slow, arcing, inaccurate). The pull/pulling draw sprites are
    // registered in BannerboundAntiquityClient; quality scales durability (MAX_DAMAGE set at craft).
    public static final DeferredItem<com.bannerbound.antiquity.item.SlingshotItem> SLINGSHOT =
        ITEMS.registerItem("slingshot",
            com.bannerbound.antiquity.item.SlingshotItem::new, new Item.Properties().durability(150));

    // Creative tab for this expansion's content.
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
                // ARMORERS_WORKBENCH_ITEM — PARKED (ARMOR_PLAN.md pivot 2026-06-23): registered but
                // hidden from the creative tab until the bone-era armor kit lands. /give to test.
                output.accept(SALT.get());
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
                output.accept(SLINGSHOT.get());
                output.accept(PRIMITIVE_BOW.get());
                output.accept(ARROW.get()); // base = flint tip / wood shaft / feather back
                output.accept(com.bannerbound.antiquity.item.ArrowParts.makeArrow("flint", "wood", "fiber", 1));
                output.accept(OAR.get());
                // Biome poison + remedy herbs (POISON_PLAN).
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
                // Tin ore chain (Miner → crucible bronze): the ore boulder block + the chipped raw tin.
                output.accept(TIN_ORE_ITEM.get());
                output.accept(RAW_TIN.get());
                // Crucible: the empty clay crucible + a pre-filled one per testable metal (so the
                // per-metal molten tint is visible without the casting flow built yet).
                output.accept(UNFIRED_CRUCIBLE.get());
                output.accept(CRUCIBLE.get());
                output.accept(filledCrucible("copper", COLOR_COPPER));
                output.accept(filledCrucible("tin", COLOR_TIN));
                output.accept(filledCrucible("bronze", COLOR_BRONZE));
                // Metalworking: the smithing station, molds, cast heads/blades, finished tools + hammers.
                output.accept(STONE_ANVIL_ITEM.get());
                output.accept(BELLOWS_BLOCK_ITEM.get());
                com.bannerbound.antiquity.metalworking.MetalworkingItems.MOLDS.values()
                    .forEach(i -> output.accept(i.get()));
                com.bannerbound.antiquity.metalworking.MetalworkingItems.HAMMERS.values()
                    .forEach(i -> output.accept(i.get()));
                com.bannerbound.antiquity.metalworking.MetalworkingItems.CASTINGS.values()
                    .forEach(i -> output.accept(i.get()));
                com.bannerbound.antiquity.metalworking.MetalworkingItems.TOOLS.values()
                    .forEach(i -> output.accept(i.get()));
                com.bannerbound.antiquity.metalworking.MetalworkingItems.TONGS.values()
                    .forEach(i -> output.accept(i.get()));
                // Metal weapons (cast head → hafted spear / fletched arrow). Heads + molds are in the
                // CASTINGS/MOLDS maps above; these are the finished items.
                output.accept(TIN_SPEAR.get());
                output.accept(COPPER_SPEAR.get());
                output.accept(BRONZE_SPEAR.get());
                // Composite arrows: preset metal-tip combos so each is discoverable (ANY tip/shaft/
                // back mix is craftable at the fletching station — see ModularArrow).
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
                // Building materials: brick families + face coatings + paint brush.
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
                // Tannery: raw hides + processing intermediates (TANNERY plan).
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
            }).build());

    public BannerboundAntiquity(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::addCookingFireToCampfireBE);

        // Populate the metalworking item maps into ITEMS before it is bound to the bus.
        com.bannerbound.antiquity.metalworking.MetalworkingItems.register();

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

    /** Let the flame-less {@link com.bannerbound.antiquity.block.CookingFireBlock} carry a vanilla
     *  {@code CampfireBlockEntity} (it isn't in that type's block set by default) so it keeps the
     *  campfire's roasting + light behaviour — it's a real campfire, just without the rendered flame. */
    private void addCookingFireToCampfireBE(
            net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent event) {
        event.modify(net.minecraft.world.level.block.entity.BlockEntityType.CAMPFIRE, COOKING_FIRE.get());
    }

    /** Block-entity capabilities. The basket implements vanilla {@code Container} but custom BEs
     *  get NO automatic item-handler capability (unlike chests/barrels, which NeoForge wraps for
     *  free) — without this, workshop crafters and hoppers see a basket as empty. */
    private void registerCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
            BASKET_BE.get(),
            (be, side) -> new net.neoforged.neoforge.items.wrapper.InvWrapper(be));
    }

    /** One labelled band on the Antiquity creative tab. Shares the greyscale {@code sections/banner}
     *  sprite, recoloured per section by {@code tint}; label text reads from
     *  {@code itemGroup.bannerboundantiquity.section.<id>}. */
    private static com.bannerbound.core.creative.CreativeSection band(String id, int tint) {
        return new com.bannerbound.core.creative.CreativeSection(
            id,
            Component.translatable("itemGroup." + MODID + ".section." + id),
            ResourceLocation.fromNamespaceAndPath(MODID, "sections/banner"),
            tint, 0xCC140D06, 0xFFF5E6C8, false);
    }

    /**
     * Groups the Antiquity creative tab into Create-Aeronautics-style labelled sections. This does NOT
     * change which items appear (the tab's {@code displayItems} list above is still the source of
     * truth) — it only assigns each item to a band, which {@link com.bannerbound.core.creative.CreativeSections}
     * uses to re-order the grid with banner dividers. Any item left unassigned simply appears first,
     * ungrouped, so nothing can vanish. Runs post-registration (from {@code commonSetup}) since it
     * resolves item suppliers eagerly to build the membership map.
     */
    private static void registerCreativeSections() {
        com.bannerbound.core.creative.CreativeSections.forTab(ANTIQUITY_TAB)
            .section(band("tools", 0xFFC9A86B))
                .add(MORTAR_AND_PESTLE_ITEM, BASKET_ITEM, FIRE_STICKS, FLINT_BLADE, BONE_BLADE,
                     PLANT_FIBER, PLANT_STRING, FIBER_ROPE, FIREWOOD, FLINT_KNIFE, WOODEN_KNIFE,
                     CRAFTING_STONE_ITEM, FLETCHING_STATION_ITEM, POTTERY_SLAB_ITEM, WOODWORKING_TABLE_ITEM,
                     BONE_SAW, MASONS_BENCH_ITEM, STONE_CHISEL)
            .section(band("primitive_tools", 0xFFB8B0A0))
                .add(BONE_PICKAXE, BONE_AXE, BONE_SHOVEL, BONE_HOE, BONE_SWORD, BONE_KNIFE, BONE_CLUB,
                     BONE_SHEARS,
                     WOODEN_SPEAR, BONE_SPEAR, STONE_SPEAR, STONE_PICK_HEAD, STONE_AXE_HEAD,
                     STONE_SHOVEL_HEAD, STONE_HOE_HEAD, STONE_SWORD_BLADE, STONE_SPEAR_POINT,
                     SLINGSHOT, PRIMITIVE_BOW, ARROW, OAR)
            .section(band("poisons", 0xFF7FA86B))
                .add(WOLFSBANE_ITEM, YARROW_ITEM, WOLFSBANE_POISON, WOLFSBANE_DART, YARROW_ANTIDOTE,
                     DEADLY_NIGHTSHADE_ITEM, MARSHMALLOW_ROOT_ITEM, DEADLY_NIGHTSHADE_POISON, NIGHTSHADE_DART,
                     MARSHMALLOW_ROOT_ANTIDOTE, CINCHONA_ITEM, CINCHONA_ANTIDOTE, CURARE_VINE_ITEM,
                     CURARE_POISON, CURARE_DART, ARNICA_ITEM, ARNICA_ANTIDOTE, OLEANDER_ITEM, OLEANDER_POISON,
                     OLEANDER_DART, BLOWGUN)
                .addItems(net.minecraft.world.item.Items.ARROW) // poison-coated arrow display stacks
            .section(band("metalworking", 0xFFCF9152))
                .add(TIN_ORE_ITEM, RAW_TIN, UNFIRED_CRUCIBLE, CRUCIBLE, STONE_ANVIL_ITEM,
                     BELLOWS_BLOCK_ITEM)
                .add(com.bannerbound.antiquity.metalworking.MetalworkingItems.MOLDS.values())
                .add(com.bannerbound.antiquity.metalworking.MetalworkingItems.HAMMERS.values())
                .add(com.bannerbound.antiquity.metalworking.MetalworkingItems.CASTINGS.values())
                .add(com.bannerbound.antiquity.metalworking.MetalworkingItems.TOOLS.values())
                .add(com.bannerbound.antiquity.metalworking.MetalworkingItems.TONGS.values())
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
        // Group the creative tab into labelled sections (CreativeSections in Core). Post-registration,
        // so item suppliers resolve. Pure metadata over the existing displayItems list.
        registerCreativeSections();
        // Plaster/trim face coatings add appeal even though they aren't blocks (read from the chunk
        // decoration store per scanned position in homes/workshops). CopyOnWrite list — thread-safe.
        com.bannerbound.core.api.settlement.AppealContributors.register(
            com.bannerbound.antiquity.deco.DecoAppeal::contribute);
        // A modular arrow is "known" only if the civ knows ALL its part ingredients — so a stray arrow
        // made from a metal another settlement researched (steel, bronze before you reach it…) reads as
        // an unknown item: it can't be fired and shows as ??? until you know that material.
        com.bannerbound.core.api.research.ItemKnowledge.registerStackGate(
            (settlement, stack) -> com.bannerbound.antiquity.item.ArrowParts.partsKnown(
                stack, item -> com.bannerbound.core.api.research.ItemKnowledge.isKnown(settlement, item)));
        // FireBlock.setFlammable mutates a shared map and isn't thread-safe, so defer to the
        // main thread. Dry straw catches and burns readily — hay-bale-like values (encouragement
        // 60, flammability 20) for the whole thatch set: block, slab, stairs, door and bed.
        event.enqueueWork(() -> {
            // Antiquity is a from-scratch conversion: force vanilla external content off. Core's
            // runtime gates (hostile spawning, vanilla portals, chest/barrel access) read this;
            // the matching worldgen/loot strips ship as datapacks in this mod. Main-thread,
            // post-config-load, after Core loads — so the override always wins.
            com.bannerbound.core.api.vanilla.VanillaContentState.setOverride(false);

            // Barbarian ranged weapons: Core fires projectiles by id; supply the Antiquity entities
            // (thrown spear uses the fighter's held spear; flint arrow). See BarbarianProjectiles.
            com.bannerbound.core.barbarian.BarbarianProjectiles.register(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "spear"),
                (lvl, shooter, dmg) -> {
                    net.minecraft.world.item.ItemStack spear = shooter.getMainHandItem();
                    if (spear.isEmpty()) {
                        spear = new net.minecraft.world.item.ItemStack(STONE_SPEAR.get());
                    }
                    com.bannerbound.antiquity.entity.SpearProjectile s =
                        new com.bannerbound.antiquity.entity.SpearProjectile(lvl, shooter, spear, dmg);
                    s.markNoRecovery(); // throwaway copy — never drops (no dup)
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

            FireBlock fire = (FireBlock) Blocks.FIRE;
            fire.setFlammable(THATCH.get(), 60, 20);
            fire.setFlammable(THATCH_SLAB.get(), 60, 20);
            fire.setFlammable(THATCH_STAIRS.get(), 60, 20);
            fire.setFlammable(THATCH_DOOR.get(), 60, 20);
            fire.setFlammable(THATCH_BED.get(), 60, 20);

            // Register the spear-fisher citizen job into Core via the public Job API. The goal lives
            // here (it throws SpearProjectiles); the lambda keeps Core ignorant of the goal class. It's
            // an early gatherer (tool = bone spear, role "spear") that is RETIRED once the rod fisher
            // unlocks (obsoletedBy "fisher" → spear fishers migrate to the rod fisher). enqueueWork is
            // main-thread, before world load, so the registry is populated before any citizen spawns.
            com.bannerbound.core.api.job.CitizenJobRegistry.register(
                com.bannerbound.core.api.job.CitizenJobRegistry.JobDef
                    .builder(com.bannerbound.antiquity.entity.SpearFisherWorkGoal.JOB_TYPE_ID)
                    .gatherer(true)
                    .anarchyOrder(3)
                    .unit("spear_fisher")
                    // Icon role "spearfish" = a COD (per-age via tool_ages; bone.json maps it) so the
                    // job reads as "fisher", distinct from the spear-wielding hunter. The tool slot
                    // still takes the tiered "spear" items via JobTools' spearfish→spear alias.
                    .icon("spearfish", net.minecraft.world.item.Items.COD)
                    // Tool-OPTIONAL: a primitive spear fisher works with a bare bone spear (the AI
                    // falls back to a default one), so an auto-assigned citizen fishes without the
                    // player hand-installing a spear. The tool slot still shows — install a better
                    // spear and the AI throws that instead.
                    .toolRequired(false)
                    .obsoletedBy("fisher")
                    .goal((c, s) -> new com.bannerbound.antiquity.entity.SpearFisherWorkGoal(c, s))
                    .build());

            // Sailing fishers ride a ghost RAFT (instead of Core's default vanilla boat) wherever
            // Antiquity is installed. Gated behind the Antiquity "sailing" research (FLAG_SAILING).
            com.bannerbound.core.api.fisher.FishingVessels.setProvider(
                (lvl, x, y, z, yaw) -> com.bannerbound.antiquity.entity.RaftEntity.spawnGhost(lvl, x, y, z, yaw));

            // Plug the immersive-hunting layer into Core's Hunter job: fed-livestock counts as
            // domesticated, calm prey → crouch-stalk, spooked prey → chase, and a spear job tool
            // opens each engagement with a throw. See AntiquityHunterHooks / FleeFromHunterGoal.
            com.bannerbound.core.api.hunter.HunterHooks.setExtension(
                new com.bannerbound.antiquity.entity.AntiquityHunterHooks());
            // Herder harvest: a culled domesticated animal yields its per-species raw hide
            // (TANNERY plan), quality graded by living conditions + herder skill. See AntiquityHerderHooks.
            com.bannerbound.core.api.herder.HerderHooks.setExtension(
                new com.bannerbound.antiquity.entity.AntiquityHerderHooks());

            // Stored-food scan integration (COOKING_PLAN.md): stamp perishable food in claimed storage
            // as fresh and roll its once-a-second chance to degrade (finally to spoiled_food), then
            // reject poisoned food. Spoiled food is its own zero-value item, so it drops out on its own.
            com.bannerbound.core.api.settlement.food.LarderHooks.processWith(
                com.bannerbound.antiquity.food.Spoilage::tick);
            // After the per-slot pass degrades/converts food slot-by-slot, re-merge the fragments so a
            // stockpile holds one stack per freshness level, not a slot full of singles.
            com.bannerbound.core.api.settlement.food.LarderHooks.normalizeWith((handler, level) -> {
                if (handler instanceof net.neoforged.neoforge.items.IItemHandlerModifiable mod) {
                    com.bannerbound.antiquity.food.Spoilage.compactStorage(mod);
                }
            });
            com.bannerbound.core.api.settlement.food.LarderHooks.excludeWhen(
                (stack, level) -> stack.has(POISONED_FOOD.get()));
            // Bland food is worth half its food value to the settlement larder (the larder side of
            // FoodSpoilage.BLAND_FOOD_MULTIPLIER; the eater side lives in FoodSpoilageEvents).
            com.bannerbound.core.api.settlement.food.LarderHooks.multiplyValueWith((stack, level) -> {
                com.bannerbound.antiquity.item.FoodSpoilage fs = stack.get(FOOD_SPOILAGE.get());
                return fs == null ? 1.0 : fs.foodMultiplier();
            });

            // Grog in the larder (GROG_PLAN.md Phase 4): a filled mug/horn feeds the settlement food
            // pool by its per-serving food value (component-carried, so empty vessels count for nothing).
            com.bannerbound.core.api.settlement.food.LarderHooks.contributeValueWith((stack, level) -> {
                com.bannerbound.antiquity.item.GrogContents grog = stack.get(GROG_CONTENTS.get());
                return grog == null ? 0.0 : grog.foodValue();
            });

            // Stone cooking pots are block-based food stores (food-economy overhaul): a finished,
            // non-poisoned stew is part of the settlement reserve and the larder drains its servings
            // like stored food (drained AFTER perishables, since stew is rot-proof). Poisoned stews
            // are simply not offered, so they never feed the settlement.
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

            // Citizens drink at a grog trough during downtime (GROG_PLAN.md Phase 4). The goal lives
            // entirely here (grog is an Antiquity system); it attaches to Core's citizen via the
            // generic, grog-agnostic CitizenGoalRegistry. Priority 3 — same tier as ConversationGoal
            // (registered after it, so socializing still wins the tick), NOT 4: a priority-4 goal can
            // never preempt the running priority-4 SettlementPatrolGoal (vanilla uses STRICT less-than),
            // so at 4 an idle citizen patrols forever and rarely drinks. At 3 a work goal still holds
            // MOVE while working (drinks happen off-shift), but an idle/unemployed citizen reliably
            // breaks for a drink and patrol can't cut it short.
            com.bannerbound.core.api.entity.CitizenGoalRegistry.register(
                3, com.bannerbound.antiquity.entity.GrogDrinkGoal::new);
            // Citizens also break to eat a warm stew from a cooking pot — same leisure tier, its own
            // cooldown (the two are mutually exclusive on MOVE, so a citizen does one or the other).
            com.bannerbound.core.api.entity.CitizenGoalRegistry.register(
                3, com.bannerbound.antiquity.entity.StewEatGoal::new);
            // Register Antiquity's citizen thoughts (e.g. "enjoyed a drink", "ate a warm stew") through
            // Core's extensible ThoughtType API — these moods are NOT baked into Core's ThoughtKind enum.
            com.bannerbound.antiquity.social.AntiquityThoughts.bootstrap();

            // Forager scavenging yields — mirror the player's cutting-tool harvest
            // (AntiquityEvents.onCuttingHarvest) so a scavenging forager is exactly as productive
            // as a player with a knife: grass → plant fibers, leaves → sticks. This is what makes
            // the fletching/crafting chain self-sustaining without manual grass-punching.
            //
            // PLUS a forager-only wheat-seed yield from grass (the knife deliberately gets NONE — see
            // onCuttingHarvest): seeds are gated behind the same item-knowledge as the Farmer, so they
            // start surfacing exactly when Animal Husbandry is researched (the prereq to the Farmer's
            // Agricultural Revolution), making the forager → seed → farmer chain self-sustaining too.
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
                    if (rng.nextFloat() < 0.25f) {   // independent roll — a steady trickle for the farmer
                        out.add(new net.minecraft.world.item.ItemStack(
                            net.minecraft.world.item.Items.WHEAT_SEEDS, 1));
                    }
                } else if (state.is(net.minecraft.tags.BlockTags.LEAVES) && rng.nextFloat() < 0.40f) {
                    out.add(new net.minecraft.world.item.ItemStack(
                        net.minecraft.world.item.Items.STICK, 1 + rng.nextInt(2)));
                }
                return out;
            });

            // Register Antiquity's work blocks into Core's Workshop framework (CRAFTER_PLAN.md).
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
            // Woodworking Table → the "woodworking" workshop (display), driven by the carpenter
            // executor. Internal type id stays "carpentry" (keyed to the unlock flag + XP bucket).
            // The table is a 2-block multiblock — anchorTest restricts the work-slot to the MAIN
            // cell so one table counts as ONE station, not two.
            com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                    WOODWORKING_TABLE.get(), "carpentry",
                    new com.bannerbound.antiquity.workshop.CarpenterExecutor(),
                    net.minecraft.world.item.Items.OAK_PLANKS,
                    state -> state.getValue(com.bannerbound.antiquity.block.WoodworkingTableBlock.MAIN)));
            // Mason's Bench → a "Masonry" workshop, driven by the Mason NPC executor. The bench is a
            // 2-block multiblock — anchorTest restricts the work-slot to the MAIN cell so one bench
            // counts as ONE station, not two (see the multiblock-work-slot-counting rule).
            com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                    MASONS_BENCH.get(), "masonry",
                    new com.bannerbound.antiquity.workshop.MasonExecutor(),
                    net.minecraft.world.item.Items.STONE_BRICKS,
                    state -> state.getValue(com.bannerbound.antiquity.block.MasonsBenchBlock.MAIN)));
            // Tanning Rack → a "Tannery" workshop, driven by the Tanner NPC executor (TANNERY plan).
            // The rack is a 2×2 multiblock — anchorTest restricts the work-slot to the PART 0 master
            // so a rack counts as ONE station, not four (the shell cells carry no BE / capacity).
            com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                    TANNING_RACK.get(), "tannery",
                    new com.bannerbound.antiquity.workshop.TanneryExecutor(),
                    net.minecraft.world.item.Items.LEATHER,
                    state -> state.getValue(com.bannerbound.antiquity.block.TanningRackBlock.PART) == 0));

            // Fermentation troughs → the "brewery" workshop, driven by the Brewer NPC executor. All
            // eight wood variants share one type id + one stateless executor. A connected run (≤3
            // cells, one shared liquid pool) counts as ONE station: the anchorTest admits only the
            // pool-start cell (uniquely RIGHT=false — the same test the BE's serverTick uses), so
            // worker capacity scales with pools, not trough blocks.
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

            // Stone cooking pot → the "cooking" workshop (display "Kitchen"), driven by the Cook NPC
            // executor. Campfires under the pots are auxiliaries (validated, never work blocks), so a
            // kitchen with pots + fires + storage stays a pure "cooking" workshop, not TYPE_MIXED.
            com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                    STONE_COOKING_POT.get(), "cooking",
                    new com.bannerbound.antiquity.workshop.CookExecutor(),
                    net.minecraft.world.item.Items.COOKED_BEEF));

            // Stone anvil → the "smithy" workshop, driven by the Smith NPC executor. The bloomery
            // and bellows are validated auxiliaries (never work blocks), so a smithy with anvil +
            // bloomery + bellows + storage stays a pure "smithy" workshop, not TYPE_MIXED.
            com.bannerbound.core.api.workshop.WorkBlockRegistry.register(
                new com.bannerbound.core.api.workshop.WorkBlockRegistry.WorkBlockDef(
                    STONE_ANVIL.get(), "smithy",
                    new com.bannerbound.antiquity.workshop.SmithExecutor(),
                    net.minecraft.world.item.Items.COPPER_INGOT));

            // A generic Crafter with no resolved station (unpositioned, or a mixed workshop) shows
            // the crafting stone as its icon — never iconless.
            com.bannerbound.core.api.workshop.WorkBlockRegistry.setDefaultCrafterType("general_crafts");

            // One generic Crafter staffs every workshop; its specialty — executor, icon, research gate
            // and tool requirement — derives from the workshop's type rather than from a per-station
            // job id. Declare each crafter PROFESSION's research-unlock unit on its workshop type so
            // Core can gate "can a Crafter be assigned here" and "is the Crafter job available at all".
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("fletchery", "fletcher");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("general_crafts", "fletcher");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("pottery", "potter");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("carpentry", "carpenter");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("masonry", "mason");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("tannery", "tanner");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("brewery", "brewer");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("cooking", "cook");
            com.bannerbound.core.api.workshop.WorkBlockRegistry.registerTypeUnit("smithy", "smith");

            // Per-workshop-type structure rules: pottery needs a kiln; carpentry needs a saw stored
            // inside (the saw moved off the old tool-bound carpenter job onto the workshop itself).
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
