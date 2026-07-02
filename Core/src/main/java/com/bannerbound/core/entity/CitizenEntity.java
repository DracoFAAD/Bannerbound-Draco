package com.bannerbound.core.entity;


import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.CitizenTrait;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.social.Relationships;
import com.bannerbound.core.social.ThoughtKind;
import com.bannerbound.core.social.Thoughts;
import com.bannerbound.core.api.settlement.ChunkBeauty;
import com.bannerbound.core.api.settlement.ChunkBeautyManager;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * A settlement-bound NPC. Owns a soft binding to its settlement (saved by UUID) and is built to
 * idle / wander inside the settlement's claimed chunks via {@link SettlementPatrolGoal}. The
 * entity persists its own settlement and citizen identifiers in NBT so it can be reattached to
 * the settlement roster after a chunk reload.
 * <p>
 * Workers (foresters etc.) don't carry an inventory â€” chopped drops teleport straight to the
 * assigned workstation's block entity. Instead, a per-citizen stamina pool throttles how often
 * they can produce work: 1 tree = -1 stamina, full pool of {@link #MAX_STAMINA}, recharges to
 * full in ~2.5 min. When stamina hits 0 the work goal yields and the citizen falls through to
 * {@link SettlementPatrolGoal} until stamina returns.
 */
public class CitizenEntity extends PathfinderMob {
    private static final String TAG_SETTLEMENT_ID = "SettlementId";
    private static final String TAG_STAMINA = "Stamina";
    private static final String TAG_STAMINA_TIMER = "StaminaTimer";
    private static final String TAG_RESTING = "Resting";
    /** Stable id for the per-citizen speed modifier â€” replaced whenever conditions change. */
    public static final net.minecraft.resources.ResourceLocation SPEED_MODIFIER_ID =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bannerbound", "civic_speed");

    /** Maximum stamina a citizen can hold. Spent at 1 point per log felled, so a small oak (~5
     *  logs) is cheap and a fancy oak (~15-20 logs) is a serious dent. */
    public static final int MAX_STAMINA = 100;
    /** Ticks per stamina point regenerated. 100 Ã— 12 = 1200 ticks â‰ˆ 60 seconds to fully recharge
     *  from zero, but ONLY when not actively working â€” see {@link #working}. */
    private static final int RECHARGE_TICKS_PER_POINT = 12;
    /** Default base movement speed. {@link #recomputeSpeedModifier} normalizes existing citizens
     *  to this value so old saves don't drift from new spawns. */
    private static final double BASE_MOVEMENT_SPEED = 0.4;
    /** Tick-rate LOD for the Village "ambient brain": run the full AI step only every Nth tick.
     *  DISABLED (= 1): tested at 2 and it halved cost but skipping the whole step also skips
     *  movement, so motion stutters ("looks like TPS errors"). Smooth tick-LOD would require a
     *  mixin to throttle only decisions/repaths while keeping movement every tick; the cleaner
     *  scaling lever is proximity virtualization (fewer loaded citizens, each ticking fully). */
    private static final int AI_LOD_INTERVAL = 1;

    // â”€â”€ AI activation tier (performance) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // A citizen with no player nearby (the whole-settlement case on a server where nobody's home
    // right now) stops scanning/patrolling/working and just idles â€” it stays a full real entity
    // (still loaded, punchable, falls, takes damage) but skips the recurring AI scans + pathfinding
    // that dominate the per-citizen cost. It reactivates within ~half a second of a player coming
    // into range. Range is generous (64) so a citizen the player can actually see is never frozen.
    /** A player must be within this distance for the citizen to run full AI. 64 â‰ˆ the distance a
     *  player would notice a citizen standing still, so we never idle a visibly-watched one. */
    private static final double AI_ACTIVE_RANGE = 64.0;
    /** How often (ticks) the cheap nearest-player activation check runs. 10 = â‰¤0.5 s wake latency. */
    private static final int AI_ACTIVATION_RECHECK_TICKS = 10;
    /** Cached activation state + the tick the next recheck is due. Server-side only. */
    private boolean aiActive = true;
    private int aiActivationRecheckAt = 0;
    /** Heavy decisions (pathfinding kick-offs) start only 1-in-this-many ticks, offset by entity id,
     *  so the fleet's A* calls spread across ticks instead of spiking on one. 8 â‡’ â‰¤0.4s latency
     *  before a citizen picks its next destination â€” invisible, since movement isn't gated. */
    private static final int THINK_PERIOD = 8;

    /** Synced int slot so the client always knows every nearby citizen's current stamina without
     *  per-screen polling. Server is the sole writer (consume / regen). */
    private static final EntityDataAccessor<Integer> DATA_STAMINA =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    /** True while the fisher work goal has a bobber out â€” read client-side by the fishing-rod
     *  cast property override so the citizen's held rod renders the bent (cast) variant. */
    private static final EntityDataAccessor<Boolean> DATA_CASTING =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);
    /** Citizen gender ({@link CitizenGender#ordinal()}). Drives name pool, name icon, and the
     *  body model (male = wide, female = slim). Synced so the renderer can pick the model. */
    private static final EntityDataAccessor<Integer> DATA_GENDER =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    /** Packed bitmask of {@link CitizenTrait} bits. Synced so {@link #getMainArm} resolves the
     *  same on client and server (handedness must agree for held-item rendering). */
    private static final EntityDataAccessor<Integer> DATA_TRAITS =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    /** Stable per-citizen cosmetic seed. The renderer takes {@code variant % variantCount} to
     *  pick one of the era/gender texture variants â€” stays put as the citizen ages. */
    private static final EntityDataAccessor<Integer> DATA_TEXTURE_VARIANT =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    /** {@link Era#ordinal()} of the citizen's settlement's CURRENT era. Re-synced from the
     *  settlement every few ticks (see {@link #aiStep}), so the renderer's texture set tracks the
     *  settlement advancing â€” a citizen restyles when their settlement reaches a new era. The
     *  name pool is still fixed at the era of immigration; only the look follows the times. */
    private static final EntityDataAccessor<Integer> DATA_ERA =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    /** Active speech-bubble topic for the social/conversation system. {@code 0} = no bubble;
     *  non-zero values map onto {@link com.bannerbound.core.social.ConversationTopic#bubbleId()}
     *  (1 = CULTURE, 2 = FOOD, 3 = SCIENCE). Server writes from {@code ConversationGoal};
     *  client {@code SpeechBubbleLayer} renders the bubble + topic icon above the head when
     *  non-zero. Vanilla syncs entity data each tick â€” no custom packet. */
    private static final EntityDataAccessor<Integer> DATA_BUBBLE =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    /** Current happiness score [0, 100]. Synced so the per-citizen detail screen reads it live
     *  without an extra round-trip. Server-only writer; recomputed from {@link #thoughts}
     *  whenever the thoughts list changes (added, removed, or expired in {@link #aiStep}). */
    private static final EntityDataAccessor<Integer> DATA_HAPPINESS =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    /** True while pregnant. Synced so the client renderer can prefix the pregnancy glyph on the
     *  display name and so any future client-side cues (idle animation, etc.) have a hook. */
    private static final EntityDataAccessor<Boolean> DATA_PREGNANT =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);
    /** True for children (born from the procreation loop, not from immigration). Synced so the
     *  client renderer scales the model down and any user-facing screens can label the citizen
     *  as a child. Server-side guards (work assignment, UNEMPLOYED thought) read this same flag. */
    private static final EntityDataAccessor<Boolean> DATA_IS_CHILD =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);
    /** Game-tick at which the pregnancy started. {@code -1L} when not pregnant. Synced so the
     *  citizen-screen Info tab can render a live progress bar without an extra round-trip â€” the
     *  bar reads {@code now - pregnantSinceTick} against {@link #PREGNANCY_DURATION_TICKS}. */
    private static final EntityDataAccessor<Long> DATA_PREGNANT_SINCE_TICK =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.LONG);
    /** Registry ids of the settlement's current tool-age shovel + hoe, synced so the JOB speech
     *  bubble can show the right Digger / Farmer tool texture client-side (where the settlement's
     *  tool age isn't otherwise available). 0 = none. Refreshed on the 10-tick settlement read. */
    private static final EntityDataAccessor<Integer> DATA_TOOL_SHOVEL =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_TOOL_HOE =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    /** 1-based {@link com.bannerbound.core.social.WorkstationIcons} ordinal of the citizen's job
     *  ({@code 0} = unemployed). Synced so the JOB speech bubble + any client-side job cue resolve
     *  without a settlement lookup. Server writes from {@link #setJobType}. */
    private static final EntityDataAccessor<Integer> DATA_JOB =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    /** Current poison stage (0 = not poisoned, 1..N = escalation stage). Mirrored from the shared
     *  persistent-data bridge an expansion stamps (Antiquity's poison attachment is server-only and
     *  can't be imported here) and synced so the renderer can draw the poison name-tag glyph. */
    private static final EntityDataAccessor<Integer> DATA_POISON_STAGE =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    /** True when the citizen's current work status is in the {@link CitizenWorkStatus.Category#BLOCKED}
     *  bucket (can't work due to a problem â€” no tool, banner down, storage full, â€¦). Recomputed
     *  server-side on the 20-tick poll in {@link #aiStep}; the client renderer draws a red "!" glyph
     *  above the head while set (see {@code SpeechBubbleLayer}). Synced via vanilla entity-data â€” no
     *  custom packet. */
    private static final EntityDataAccessor<Boolean> DATA_WORK_BLOCKED =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);

    /** Shared persistent-data key an expansion stamps with the citizen's current poison stage
     *  (mirrors the {@code BannerboundDomesticated} bridge). {@code 0} = not poisoned. */
    public static final String POISON_STAGE_TAG = "BannerboundPoisonStage";

    private static final String TAG_GENDER = "Gender";
    private static final String TAG_TRAITS = "Traits";
    private static final String TAG_TEXTURE_VARIANT = "TextureVariant";
    private static final String TAG_ERA = "Era";
    private static final String TAG_RELATIONS = "Relations";
    private static final String TAG_THOUGHTS = "Thoughts";
    /** Per-citizen tier-sample counters for the daily chunk-quality thought. Indexed by
     *  {@link ChunkBeauty#ordinal()}; persisted so a save/load mid-day doesn't lose progress. */
    private static final String TAG_CHUNK_SAMPLES = "ChunkSamples";
    /** Day-of-game that we last evaluated {@code chunkSamples} on. Used to detect dawn so the
     *  evaluation runs exactly once per day boundary, not on every tick of dawn. */
    private static final String TAG_LAST_CHUNK_EVAL_DAY = "ChunkEvalDay";
    /** Font + glyph chars for the gender icon shown before the citizen's name. The glyphs live
     *  in the {@code bannerbound:icons} bitmap font (see {@code assets/.../font/icons.json}). */
    private static final ResourceLocation ICONS_FONT =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "icons");
    /** Private-use codepoints; must match the male/female bitmap providers in icons.json. */
    private static final String MALE_GLYPH = Character.toString(0xE100);
    private static final String FEMALE_GLYPH = Character.toString(0xE101);
    /** PUA codepoint for the pregnancy glyph (U+E102) â€” prepended before the female glyph on a
     *  pregnant woman's display name. Provider in {@code assets/bannerbound/font/icons.json}. */
    private static final String PREGNANT_GLYPH = Character.toString(0xE102);

    private static final String TAG_PREGNANT = "Pregnant";
    private static final String TAG_PREGNANT_SINCE_TICK = "PregnantSinceTick";
    private static final String TAG_PREGNANCY_FATHER = "PregnancyFather";
    private static final String TAG_IS_CHILD = "IsChild";
    private static final String TAG_BORN_AT_TICK = "BornAtTick";
    private static final String TAG_MOTHER = "Mother";
    /** Raw name (no styling) â€” persisted so {@link #refreshDisplayName} can rebuild the full
     *  styled component (with pregnancy glyph prefix when pregnant) without re-deriving it from
     *  the displayed {@code customName} component. */
    private static final String TAG_CITIZEN_NAME = "CitizenName";
    /** True once {@link #citizenName} holds the in-language (baked) given name rather than a raw
     *  name-pool draw. Absent on pre-feature saves â†’ those migrate on load. */
    private static final String TAG_NAME_BAKED = "NameBaked";
    private static final String TAG_EARNED_SURNAME_CONCEPT = "EarnedSurnameConcept";
    private static final String TAG_EARNED_SURNAME_JOB = "EarnedSurnameJob";
    private static final String TAG_EARNED_SURNAME_TICK = "EarnedSurnameTick";
    private static final int SURNAME_XP_THRESHOLD = 80;
    /** Step 9: compliance (0..100) â€” defaults to 100 (fresh immigrants are eager). Drops
     *  as a citizen accumulates unhappiness / leader-resentment. The compliance/resentment
     *  tick + refusal-effect behaviours are Steps 10â€“12; Step 9 ships the data layer only. */
    private static final String TAG_COMPLIANCE = "Compliance";
    /** Step 9: per-player resentment map. NBT shape is a list of {@code {Uuid, Value}}
     *  compounds â€” keeps the on-disk form stable even when the player set changes between
     *  saves. Empty by default â€” no entry for a player means zero resentment toward them. */
    private static final String TAG_RESENTMENT_MAP = "ResentmentByPlayer";
    private static final String TAG_RESENTMENT_UUID = "Uuid";
    private static final String TAG_RESENTMENT_VALUE = "Value";
    // Per-citizen job state (replaces workstation assignment). All omitted at defaults so an
    // unemployed citizen stays compact on disk.
    private static final String TAG_JOB_TYPE = "JobType";
    private static final String TAG_JOB_PINNED = "JobPinned";
    private static final String TAG_WORKSHOP_ID = "WorkshopId";
    private static final String TAG_JOB_TOOL = "JobTool";
    private static final String TAG_JOB_PICKAXE = "JobPickaxe";
    private static final String TAG_PREFERRED_LOG = "PreferredLog";
    private static final String TAG_DROP_OFF = "DropOff";
    private static final String TAG_SEED_SOURCE = "SeedSource";
    private static final String TAG_FORAGE_TARGETS = "ForageTargets";
    private static final String TAG_HUNTER_PREY_OFF = "HunterPreyOff";
    private static final String TAG_FORESTER_KEEP_EXTRAS = "ForesterKeepExtras";
    private static final String TAG_SEED_CACHE = "SeedCache";
    private static final String TAG_ANARCHY_HAUL = "AnarchyHaul";

    private static final int COMPLIANCE_MIN = 0;
    private static final int COMPLIANCE_MAX = 100;
    private static final int DEFAULT_COMPLIANCE = COMPLIANCE_MAX;
    /** At or below this compliance a citizen is rebellious by nature rather than an eager would-be
     *  worker, so being jobless doesn't make them feel "unhelpful" â€” the {@code UNEMPLOYED} thought is
     *  suppressed. This stops it from contradicting compliance: a low-compliance citizen who <i>refuses</i>
     *  work shouldn't also be docked happiness <i>for</i> not working. Matches the dawn full-day-strike
     *  breakpoint (see {@link com.bannerbound.core.api.settlement.ComplianceTables}). */
    private static final int UNEMPLOYED_COMPLIANCE_FLOOR = 30;

    private UUID settlementId;
    private int staminaRechargeTimer = 0;
    /** Step 9: compliance 0..100. Default 100 (eager); decays via Step 10's hourly tick when
     *  the citizen is unhappy under high-resentment leaders. */
    private int compliance = DEFAULT_COMPLIANCE;
    /** Step 9: resentment per player UUID (citizens â†’ players). No entry = 0. Driven by Steps
     *  10 (passive hourly leader-resentment) + 11 (per-hit on attacks). */
    private final java.util.Map<UUID, Integer> resentmentByPlayer = new java.util.HashMap<>();
    /** Step 10: fractional compliance accumulator. Compliance itself is an int; the hourly
     *  delta is fractional (Â±0.5 per 5 happiness/resentment step), so we accumulate here and
     *  apply integer steps when |accum| â‰¥ 1. Transient â€” losing the fractional remainder on
     *  reload is fine, it just shifts the next-integer-step boundary by less than an hour. */
    private transient double complianceFractional = 0.0;
    /** Per-citizen relationships â€” saved/loaded with the entity NBT as the {@code Relations} list. */
    private final Relationships relationships = new Relationships();
    /** Per-citizen active thoughts â€” saved as the {@code Thoughts} list, aggregated into the
     *  citizen's happiness score. See {@link Thoughts} and {@link ThoughtKind}. */
    private final Thoughts thoughts = new Thoughts();
    /** Running per-tier sample counts for the daily chunk-quality thought. Indexed by
     *  {@link ChunkBeauty#ordinal()} so {@code chunkSamples[ChunkBeauty.BREATHTAKING.ordinal()]}
     *  is "samples-spent-in-a-breathtaking-chunk since dawn." Reset at the daily evaluation. */
    private final int[] chunkSamples = new int[ChunkBeauty.values().length];
    /** {@code level.getDayTime() / 24000} at the time of the last chunk-quality evaluation.
     *  Used to detect the dawn boundary exactly once per day. {@code -1} = never evaluated. */
    private long lastChunkEvalDay = -1L;
    /** Transient cooldown (ticks) before this citizen may enter another conversation. Decremented
     *  in {@link #aiStep}; reset to {@code ConversationGoal.CONV_COOLDOWN_TICKS} when a chat ends. */
    private int conversationCooldown = 0;
    /** Sticky "resting" flag: latches true when stamina hits 0, clears only when stamina returns to
     *  {@link #MAX_STAMINA}. While set, work goals must yield. Prevents the yo-yo where a citizen
     *  chops a tree, regens 1 stamina, chops another, and never visibly stops. */
    private boolean resting = false;
    /** True while an active work goal is running (set by {@code ForesterWorkGoal.start}/{@code stop}).
     *  Used to pause stamina regen during work â€” every other state (patrol, idle, exhausted,
     *  BE-full, no-trees, unassigned) lets stamina tick back up. */
    private boolean working = false;

    /** Raw citizen name (no styling). Stored so {@link #refreshDisplayName} can rebuild the full
     *  styled component (with optional pregnancy glyph prefix) without re-parsing the existing
     *  display component. Set in {@link #initializeCitizen}; persisted as {@link #TAG_CITIZEN_NAME}. */
    private String citizenName;
    /** True once {@link #citizenName} is the in-language given name (baked at creation). False on
     *  pre-feature saves until the load-path migration styles them. */
    private boolean nameBaked;
    /** Profession-root surname earned on the first Veteran-tier job promotion. Null = no surname. */
    private String earnedSurnameConcept;
    private String earnedSurnameJob;
    private long earnedSurnameTick = -1L;
    /** Cached settlement-color formatting for the name text â€” captured at immigration time and
     *  re-applied on every {@link #refreshDisplayName}. Persisted as part of the existing
     *  customName component (the styled text contains the color), so we re-derive it on load by
     *  resolving the settlement's current color. */
    private ChatFormatting nameColor = ChatFormatting.WHITE;
    // pregnantSinceTick now lives on DATA_PREGNANT_SINCE_TICK (synced long). Reads + writes go
    // through {@link #getPregnantSinceTick} / {@link #setPregnant} so the client's citizen-screen
    // bar updates live as the pregnancy advances.
    /** UUID of the father of the current pregnancy, or {@code null} if not pregnant. Used at
     *  birth to install the FAMILY bond on both parents + add {@code MY_CHILD_BORN} to the
     *  father if he's still alive. Server-only. */
    private UUID pregnancyFatherId;
    /** Game-tick at which this citizen was born from the procreation loop. {@code -1} for
     *  citizens that immigrated (i.e. never had a "birth" event). Drives the child-grows-up
     *  transition at {@code now - bornAtTick >= ADULTHOOD_TICKS}. */
    private long bornAtTick = -1L;
    /** UUID of the mother this citizen was born to via the procreation loop, or {@code null} for
     *  immigrants and pre-existing citizens with no recorded birth. Persisted so maternal siblings
     *  (children sharing a mother) can be linked with the FAMILY bond at each new birth. */
    private UUID motherId;

    // â”€â”€â”€ Brawl state (transient â€” server-only, not persisted) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** UUID of the other citizen this citizen is currently brawling with, or {@code null}.
     *  Set the moment a swing is exchanged (initial punch by AnarchyWorkGoal, or any
     *  successful retaliation). When this citizen is hit by the same opponent within
     *  {@link #BRAWL_ONGOING_WINDOW_TICKS}, the hurt-handler treats it as a continuing
     *  brawl (50% retaliate roll) rather than a fresh first hit (75% retaliate roll). */
    @org.jetbrains.annotations.Nullable
    private UUID lastBrawlOpponentId = null;
    /** Game-tick of the most recent brawl-related exchange with {@link #lastBrawlOpponentId}.
     *  Used to age out the ongoing-brawl state â€” after the window expires, a re-hit by the
     *  same citizen starts a fresh brawl with the 75% first-hit roll. */
    private long lastBrawlTick = 0L;
    /** UUID of the citizen this entity is scheduled to swing at, or {@code null}. Set by
     *  {@code CitizenBrawlEvents} when a retaliation roll passes; consumed in
     *  {@link #aiStep} when the scheduled tick arrives. */
    @org.jetbrains.annotations.Nullable
    private UUID pendingRetaliationTargetId = null;
    /** Game-tick on which the pending retaliation swing should fire. */
    private long pendingRetaliationTick = 0L;

    /** Stuck-watchdog: last position sample + the tick we last sampled at. If the citizen sits
     *  effectively still for {@link #STUCK_TICK_THRESHOLD} ticks while their pathfinder thinks
     *  it's trying to go somewhere, teleport them back to the town hall. Catches "fell in a hole"
     *  and "blocked by a built wall" scenarios. */
    private double stuckLastX, stuckLastY, stuckLastZ;
    private int stuckTicks = 0;
    /** Squared distance threshold: under this, the citizen is "not moving". 0.5 block radius. */
    private static final double STUCK_RADIUS_SQ = 0.25;
    /** Sample once every {@link #STUCK_SAMPLE_INTERVAL} ticks. */
    private static final int STUCK_SAMPLE_INTERVAL = 40; // 2s
    /** Teleport after this many CONSECUTIVE samples with no movement while pathfinding. */
    private static final int STUCK_TICK_THRESHOLD = 8 * STUCK_SAMPLE_INTERVAL; // ~16s

    /** Absolute tick at which the current item-capture window expires. -1 = no active window. */
    private long captureWindowEndTick = -1;
    /** Center of the capture window â€” the BlockPos of the log a forester just felled. */
    private BlockPos captureCenter;

    // â”€â”€â”€ Employment (per-citizen job state â€” replaces workstation assignment) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** Workstation-type id of the citizen's job (e.g. {@code "foresters_log"}), or {@code null}
     *  when unemployed. Stored as the same string id used by {@link com.bannerbound.core.social.WorkstationIcons}
     *  / {@link com.bannerbound.core.api.settlement.WorkstationUnlocks} so the work icon + research
     *  flag resolve off it directly. */
    private String jobTypeId = null;
    /** True when the player explicitly assigned this citizen's job (so the settlement labor
     *  distributor leaves them alone â€” a manual override). Set by the Job-tab assign/switch handler;
     *  cleared by the "Auto" button and on unassign. NBT-persisted ({@code "JobPinned"}). */
    private boolean jobPinned = false;
    /** The crafter's bound workshop (see CRAFTER_PLAN.md), null when not a crafter / unbound.
     *  NBT-persisted ({@code "WorkshopId"}); cleared on any job change away from crafter. */
    private java.util.UUID assignedWorkshopId = null;
    /** Per-profession craft experience (key = workshop type id, e.g. "fletchery"), the growth stat
     *  behind crafter quality: mean rises / variance shrinks with XP, and only veterans roll
     *  MASTERWORK. Kept across job changes â€” a retrained fletcher remembers fletching. NBT
     *  {@code "JobXpMap"}; omitted when empty. */
    private final java.util.Map<String, Float> jobXp = new java.util.HashMap<>();
    /** Registry id of the job-icon item last baked into the name-tag suffix glyph. Transient
     *  (recomputed on load, starts unset): the 10-tick loop compares the citizen's currently
     *  resolved {@link com.bannerbound.core.social.JobIcons#iconItemId job icon} to this and
     *  rebuilds the display name only when the tool age advanced (e.g. stone â†’ iron axe). */
    private int lastJobIconItemId = Integer.MIN_VALUE;
    /** The tool the player physically handed this citizen (any axe for a forester). Never consumed
     *  and takes no durability damage â€” it's a gate + a held-item render source, not a resource.
     *  Returned to the drop-off (or dropped at feet) on unassign / exile / death. */
    private net.minecraft.world.item.ItemStack jobTool = net.minecraft.world.item.ItemStack.EMPTY;
    /** Secondary tool slot â€” the quarryworker's pickaxe, used (with the Quarry research) to mine
     *  stone / ores. Empty for jobs that don't have a second tool. Returned with the primary tool. */
    private net.minecraft.world.item.ItemStack jobPickaxe = net.minecraft.world.item.ItemStack.EMPTY;
    /** Forester's preferred log species â€” relocated from the old Forester's Log block entity. A
     *  soft bias; the forester still falls back to the nearest tree of any species. */
    private net.minecraft.world.level.block.Block preferredLog = net.minecraft.world.level.block.Blocks.OAK_LOG;
    /** Forager only: 7-bit mask of which {@link com.bannerbound.core.api.forager.ForageCategory}
     *  categories this citizen gathers. Defaults to all-on; a category only actually gathers when it's
     *  also research-unlocked. */
    private int forageTargetBits = com.bannerbound.core.api.forager.ForageCategory.ALL_BITS;
    /** Hunter only: entity-type ids of {@code #bannerbound:huntable} species this citizen must NOT
     *  hunt (a "leave the cows for the herder" toggle in the Job tab). Stored as the DISABLED set so
     *  every species â€” including ones a datapack adds later â€” defaults to huntable. */
    private final java.util.Set<String> hunterPreyOff = new java.util.HashSet<>();
    /** Forester only: when true (default) the forester also stores the canopy's saplings / apples /
     *  sticks in the drop-off; when false it keeps only logs (still clears the leaves, but discards
     *  their drops). A per-citizen production-chain choice set from the Job tab. */
    private boolean foresterKeepExtras = true;
    /** Transient (server-only, not synced/saved) live work status, published to the Job tab via the
     *  1 Hz job-state poll. Only goals with meaningful live sub-states set it (currently
     *  {@link ForesterPlantationGoal}); everything else leaves it {@link CitizenWorkStatus#IDLE} and
     *  the payload handler derives a status from observable facts instead. A goal that sets it must
     *  clear it back to IDLE in its {@code stop()} so a finished task can't leave a stale verdict. */
    private CitizenWorkStatus currentWorkStatus = CitizenWorkStatus.IDLE;
    /** Farmer only: a small overflow buffer for harvested SEEDS that wouldn't fit the drop-off. The
     *  farmer replants from it (and its marked seed source) so seeds are never lost or spilled â€” see
     *  {@link FarmerWorkGoal}. Empty for every other job. */
    private final net.minecraft.world.SimpleContainer seedCache = new net.minecraft.world.SimpleContainer(6);
    /** Anarchy carry pack â€” the deposit sink for a self-organizing gatherer with no real storage. A
     *  normal stacking container: the gatherer fills it with WHOLE harvest drops (never split) and
     *  delivers once it's carrying {@link #ANARCHY_HAUL_CAPACITY}+ items (a soft threshold â€” the drop
     *  that crosses it is absorbed in full, so no berry / log / sapling is ever spilled or deleted).
     *  Because it stacks and is sized far larger than the count threshold, it never runs out of slots
     *  mid-haul, so every gatherer's per-action room check (hasFreeSlot / roomFor) passes against it
     *  and the only stop signal is the count threshold (see {@link #isHaulFull}). The worker then
     *  hauls it to the town hall and dumps it (see {@code DeliverHaulGoal}). Empty unless gathering in
     *  anarchy with no chest. */
    private final net.minecraft.world.SimpleContainer anarchyHaul = new net.minecraft.world.SimpleContainer(64);
    /** Player-marked drop-off container (a chest or an Antiquity basket) the forester routes its
     *  yield into. {@code null} until the player marks one via the Job tab. */
    private BlockPos dropOffPos = null;
    /** Player-marked seed-source container the FARMER pulls seeds from (may be the same block as the
     *  drop-off, or a stockpile block). {@code null} until marked via the Job tab. Farmer-only. */
    private BlockPos seedSourcePos = null;

    /** Throwaway flag for the {@code /bannerbound simulate} crowd-LOD stress test. A simulated
     *  citizen renders and ticks like any other (so the near band looks real), but is NEVER written
     *  to disk â€” see {@link #shouldBeSaved()} â€” and is excluded from the settlement roster, so the
     *  sandbox can spawn hundreds and discard them all without touching the real save. */
    private boolean simulated = false;

    // ─── Trade courier (the walking trader — see TradeCourierManager) ───────────────────────────
    /** Player opt-in on the stocker Job tab: this stocker may be adopted as a trade courier. */
    private boolean tradingCourier = false;
    /** Non-null while adopted by a {@code TraderSimManager} journey — suspends ALL other AI (the
     *  {@link TradeCourierGoal} holds MOVE+LOOK and every WorkGoal bails). Persisted so a courier
     *  reloaded mid-route is resumed (or cleaned up) by {@code TradeCourierManager}. */
    @Nullable
    private UUID tradeJourneyId = null;

    public boolean isTradingCourier() {
        return tradingCourier;
    }

    public void setTradingCourier(boolean value) {
        this.tradingCourier = value;
    }

    @Nullable
    public UUID getTradeJourneyId() {
        return tradeJourneyId;
    }

    public void setTradeJourneyId(@Nullable UUID id) {
        this.tradeJourneyId = id;
    }

    /** True while this citizen is walking a trade route — all normal AI yields. */
    public boolean isOnTradeJourney() {
        return tradeJourneyId != null;
    }

    public CitizenEntity(EntityType<? extends CitizenEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCustomNameVisible(true);
        // Let the pathfinder route through buildings: closed doors become valid path nodes and the
        // navigation itself opens them on traversal. Without this the citizen would refuse to enter
        // the building they're supposed to work in.
        if (this.getNavigation() instanceof GroundPathNavigation gpn) {
            gpn.setCanOpenDoors(true);
            gpn.setCanPassDoors(true);
            gpn.setCanFloat(true); // float + keep pathing in water (e.g. a pen's drinking pool) instead of stalling
        }
    }

    /** Herders are unpushable so their own herd can't bodycheck them into a corner while they corral/lead
     *  (they still push animals aside as they walk). Other citizens push normally. */
    @Override
    public boolean isPushable() {
        return !HerderWorkGoal.JOB_TYPE_ID.equals(jobTypeId) && super.isPushable();
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        // CitizenGroundNavigation routes through closed fence gates (vanilla treats them as
        // impassable FENCE nodes); OpenFenceGateGoal opens them on arrival.
        return new CitizenGroundNavigation(this, level);
    }

    @Override
    public HumanoidArm getMainArm() {
        // Handedness is the LEFT_HANDED trait â€” rolled at ~10% on immigration. Reads synced data
        // so the renderer's held-item / hand-position math agrees with the server.
        return hasTrait(CitizenTrait.LEFT_HANDED) ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.4)
            // FOLLOW_RANGE doubles as the pathfinder's search radius (PathNavigation
            // maxDistanceToWaypoint derives from it). 32 was tight for cross-settlement work:
            // a citizen patrolling near the town hall couldn't pathfind to a workstation 30+
            // blocks away, so the work goal never started. 64 gives headroom for medium
            // settlements without making patrol/sight-range feel weird.
            .add(Attributes.FOLLOW_RANGE, 64.0);
    }

    /**
     * Make fire/lava/campfire blocks unwalkable for pathfinding so citizens route around them
     * instead of through. Vanilla {@link PathfinderMob} returns 0 for DAMAGE_FIRE/LAVA (mob walks
     * straight in); we return -1 so the path planner refuses those nodes entirely. DANGER_FIRE
     * (adjacent-to-fire) gets a high malus so the path strongly prefers detours but isn't blocked
     * outright â€” needed so citizens can still reach a workstation that sits next to a campfire.
     */
    @Override
    public float getPathfindingMalus(PathType type) {
        return switch (type) {
            case DAMAGE_FIRE, LAVA -> -1.0f;
            case DANGER_FIRE -> 24.0f;
            case DAMAGE_OTHER, DANGER_OTHER -> 16.0f;
            default -> super.getPathfindingMalus(type);
        };
    }

    /** Set by {@link DiggerWorkGoal} while a quarryworker is actively excavating: lets the pathfinder
     *  route DOWN into its own pit (vanilla refuses drops &gt; 3, so a 4+-deep excavation reads as an
     *  impassable wall and the worker stalls at the rim) and waives the fall damage of riding in. */
    private boolean deepDigDescent = false;

    public void setDeepDigDescent(boolean on) {
        this.deepDigDescent = on;
    }

    /** Set by {@link FisherWorkGoal} while it walks to / holds a fishing spot: makes the pathfinder
     *  treat water as expensive so the fisher takes the land/pier route to the water's edge instead of
     *  swimming a shortcut across the lake (which left it bobbing in the water to fish). It can still
     *  cross water if there's genuinely no land path â€” the malus is a strong preference, not a wall. */
    private boolean avoidWaterPathing = false;

    public void setAvoidWaterPathing(boolean on) {
        this.avoidWaterPathing = on;
    }

    public boolean isAvoidWaterPathing() {
        return avoidWaterPathing;
    }

    /** Set by {@link MinerWorkGoal} when this citizen's current work marker sits in an OUTPOST
     *  working claim (null = working home territory). Transient by design: drives outpost-managed
     *  storage (the Job tab greys the drop-off button), and {@link SleepGoal} beds the worker down
     *  at the outpost (roofed bed in that chunk) instead of trekking home every night. */
    private BlockPos outpostSite;

    public void setOutpostSite(BlockPos site) {
        this.outpostSite = site;
    }

    public BlockPos getOutpostSite() {
        return outpostSite;
    }

    /** Set by {@link StockerWorkGoal} during an outpost haul leg: the node evaluator turns on the
     *  dirt-path preference (independent of the Roads policy), so the stocker FOLLOWS the road it
     *  tramples into existence on the first trip. Transient. */
    private boolean roadBuilding;

    public void setRoadBuilding(boolean on) {
        this.roadBuilding = on;
    }

    public boolean isRoadBuilding() {
        return roadBuilding;
    }

    @Override
    public int getMaxFallDistance() {
        // Only while digging â€” ordinary citizens keep the vanilla 3 so they don't stroll off cliffs.
        return deepDigDescent ? 24 : super.getMaxFallDistance();
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        if (deepDigDescent) return false; // a quarryworker rides its own excavation down unharmed
        return super.causeFallDamage(fallDistance, multiplier, source);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STAMINA, MAX_STAMINA);
        builder.define(DATA_CASTING, false);
        builder.define(DATA_GENDER, CitizenGender.MALE.ordinal());
        builder.define(DATA_TRAITS, 0);
        builder.define(DATA_TEXTURE_VARIANT, 0);
        builder.define(DATA_ERA, Era.ANCIENT.ordinal());
        builder.define(DATA_BUBBLE, 0);
        builder.define(DATA_HAPPINESS, Thoughts.BASE_HAPPINESS);
        builder.define(DATA_PREGNANT, false);
        builder.define(DATA_IS_CHILD, false);
        builder.define(DATA_PREGNANT_SINCE_TICK, -1L);
        builder.define(DATA_TOOL_SHOVEL, 0);
        builder.define(DATA_TOOL_HOE, 0);
        builder.define(DATA_JOB, 0);
        builder.define(DATA_POISON_STAGE, 0);
        builder.define(DATA_WORK_BLOCKED, false);
    }

    /** True when the citizen can't work due to a problem (a {@link CitizenWorkStatus.Category#BLOCKED}
     *  status). Synced â€” the renderer draws a red "!" above the head while set. */
    public boolean isWorkBlocked() { return this.entityData.get(DATA_WORK_BLOCKED); }
    /** Server-only writer â€” set from the 20-tick work-status poll in {@link #aiStep}. */
    public void setWorkBlocked(boolean blocked) {
        if (this.entityData.get(DATA_WORK_BLOCKED) != blocked) {
            this.entityData.set(DATA_WORK_BLOCKED, blocked);
        }
    }

    /** The settlement's current tool-age shovel / hoe, resolved client-side from the synced
     *  registry id (0 â†’ AIR). Used by the JOB speech bubble for Digger / Farmer icons. */
    public net.minecraft.world.item.Item getToolShovelItem() {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(this.entityData.get(DATA_TOOL_SHOVEL));
    }
    public net.minecraft.world.item.Item getToolHoeItem() {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(this.entityData.get(DATA_TOOL_HOE));
    }

    // â”€â”€â”€ Gender / traits / cosmetics â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    public CitizenGender getGender() {
        return CitizenGender.fromOrdinalOrMale(this.entityData.get(DATA_GENDER));
    }

    /** True if the citizen rolled the given trait. Reads synced data, so it agrees on both sides. */
    public boolean hasTrait(CitizenTrait trait) {
        return (this.entityData.get(DATA_TRAITS) & trait.bit()) != 0;
    }

    /** Stable cosmetic seed â€” the renderer reduces it modulo the available texture-variant count. */
    public int getTextureVariant() {
        return this.entityData.get(DATA_TEXTURE_VARIANT);
    }

    /** The citizen's settlement's current era â€” kept fresh by {@link #aiStep}. The renderer
     *  reads it to pick the texture set, so citizens visually advance with their settlement. */
    public Era getEra() {
        return Era.fromOrdinalOrDefault(this.entityData.get(DATA_ERA));
    }

    /** True iff this citizen is currently in the fisher work goal's CAST/WAIT/RETRACT window.
     *  Read by the fishing-rod cast item-property override so the held rod renders bent. */
    public boolean isCasting() { return this.entityData.get(DATA_CASTING); }
    public void setCasting(boolean casting) { this.entityData.set(DATA_CASTING, casting); }

    // â”€â”€â”€ Compliance + resentment (Step 9 data layer) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    public int getCompliance() { return compliance; }
    /** Set compliance, clamped to [COMPLIANCE_MIN, COMPLIANCE_MAX]. */
    public void setCompliance(int value) {
        compliance = Math.max(COMPLIANCE_MIN, Math.min(COMPLIANCE_MAX, value));
    }

    /** Resentment toward {@code player} (defaults to 0 when no entry exists). */
    public int getResentment(UUID player) {
        if (player == null) return 0;
        Integer v = resentmentByPlayer.get(player);
        return v == null ? 0 : v;
    }
    /** Increment resentment toward {@code player} by {@code delta} (may be negative). The
     *  per-player value is clamped to a non-negative range â€” resentment doesn't bottom out
     *  into appreciation. */
    public void addResentment(UUID player, int delta) {
        if (player == null) return;
        int next = Math.max(0, getResentment(player) + delta);
        if (next == 0) resentmentByPlayer.remove(player);
        else resentmentByPlayer.put(player, next);
    }
    /** Read-only snapshot for serialisation / UI. */
    public java.util.Map<UUID, Integer> resentmentByPlayer() {
        return java.util.Collections.unmodifiableMap(resentmentByPlayer);
    }
    /** Max resentment across the given leader UUIDs â€” used by Step 10's per-tick compliance
     *  hit. Returns 0 if the set is empty or contains no entries this citizen resents. */
    public int getLeaderResentmentMax(java.util.Collection<UUID> leaders) {
        if (leaders == null || leaders.isEmpty()) return 0;
        int max = 0;
        for (UUID leader : leaders) {
            int v = getResentment(leader);
            if (v > max) max = v;
        }
        return max;
    }

    // â”€â”€â”€ Step 10 tunables â€” single block for the hourly compliance/resentment math â”€â”€â”€â”€â”€â”€â”€â”€
    /** 1000 ticks = 50 s real time = ~1 in-game hour. Compliance/resentment tick cadence. */
    private static final int HOURLY_TICK_PERIOD_TICKS = 1000;
    /** Below this happiness, the citizen starts blaming their leaders. */
    private static final int RESENTMENT_GAIN_HAPPINESS_THRESHOLD = 50;
    /** Resentment gain divisor: an unhappy citizen blames each leader by {@code (50 - happiness)
     *  / 15} per in-game hour. At rock-bottom happiness (0) that's ~3/hr, so the per-citizen
     *  coup threshold (80) is reached after roughly a day of sustained misery — fast enough to
     *  feel, slow enough to react to the dusk warning. */
    private static final int RESENTMENT_GAIN_DIVISOR = 15;
    /** Above this happiness, the citizen forgets grudges â€” resentment toward leaders decays
     *  by {@code (happiness - threshold)/5} steps per hour. */
    private static final int RESENTMENT_DECAY_HAPPINESS_THRESHOLD = 60;
    /** Above this happiness, compliance climbs back by {@link #COMPLIANCE_GAIN_PER_STEP} per 5
     *  happiness step â€” fixing the cause of unrest visibly restores willingness within a day. */
    private static final int COMPLIANCE_GAIN_HAPPINESS_THRESHOLD = 60;
    /** Recovery is deliberately faster than erosion: once you fix what made citizens unhappy,
     *  willingness should come back quickly so good management feels rewarding. At happiness 75
     *  that's ~4.5/hr (a striking citizen back to full in well under an in-game day); at 100,
     *  ~12/hr. */
    private static final double COMPLIANCE_GAIN_PER_STEP = 1.5;
    /** PRIMARY compliance erosion channel (the fix for "absurdly slow"): below neutral happiness
     *  (60) compliance erodes by {@code (60 - happiness)/5 * 0.4} per hour, reading misery
     *  DIRECTLY instead of waiting for resentment to build first. At happiness 0 that's ~4.8/hr
     *  â€” a thoroughly miserable citizen falls from 100 into refusal territory (~30) inside one
     *  in-game day. This is what makes compliance actually felt in a play session. */
    private static final int COMPLIANCE_LOSS_HAPPINESS_THRESHOLD = 60;
    private static final double COMPLIANCE_LOSS_PER_STEP = 0.4;
    /** SECONDARY compliance erosion: leader blame. Above this max-leader-resentment, compliance
     *  erodes an extra {@code (resentment - 20)/5 * 0.25} per hour â€” a hated chief loses
     *  cooperation faster than a merely failing one. */
    private static final int COMPLIANCE_LOSS_RESENTMENT_THRESHOLD = 20;
    private static final double COMPLIANCE_RESENTMENT_LOSS_PER_STEP = 0.25;
    /** When the AVERAGE leader-resentment crosses this line, compliance erosion doubles.
     *  Numerically matches {@code SettlementManager.COUP_CITIZEN_THRESHOLD} (also 80) â€” the
     *  same "80 is the danger line" used by the coup trigger â€” but the two are conceptually
     *  distinct (average here, per-citizen there). Tune them independently if needed. */
    private static final int BRINK_AVG_RESENTMENT = 80;

    /** Step 10: the once-per-in-game-hour resentment + compliance update. Called from
     *  {@link #aiStep} on a per-citizen offset so settlements don't synchronise their ticks
     *  onto the same world frame. Pure data â†’ data; refusal effects (Step 12) read the
     *  resulting compliance value through {@link #getCompliance()}.
     *
     *  <p>Three channels:
     *  <ul>
     *    <li><b>Resentment gain</b> â€” happiness below 50: +steps to each leader.</li>
     *    <li><b>Resentment decay</b> â€” happiness above 60: âˆ’steps from each leader (mirror
     *        of the gain path so satisfied citizens forgive past grievances over time).</li>
     *    <li><b>Compliance drift</b> â€” +0.5 per 5 happiness above 60, âˆ’0.5 per 5 max-
     *        leader-resentment above 20; doubled when the average leader resentment is
     *        critically high (>80) to model "the settlement is on the brink."</li>
     *  </ul> */
    private void tickComplianceResentmentHourly(Settlement settlement) {
        java.util.Set<UUID> leaders = settlement.leaderPlayerIds();
        int happiness = getHappiness();

        if (!leaders.isEmpty()) {
            // Resentment gain: an unhappy citizen blames whoever's in charge. Divided by 15
            // (was effectively /5) so resentment to the coup line takes ~a day of misery, not
            // a handful of hours.
            if (happiness < RESENTMENT_GAIN_HAPPINESS_THRESHOLD) {
                int steps = (RESENTMENT_GAIN_HAPPINESS_THRESHOLD - happiness) / RESENTMENT_GAIN_DIVISOR;
                if (steps > 0) for (UUID leader : leaders) addResentment(leader, steps);
            }
            // Resentment decay: a happy citizen forgets grudges FAST â€” forgiveness should feel as
            // snappy as the punishment. The 2Ã— multiplier means a citizen at happiness 75 sheds
            // ~6/hr toward each leader (a full 58 grudge gone in ~10 in-game hours), so fixing
            // the cause of unrest visibly clears resentment within a day, not several.
            if (happiness > RESENTMENT_DECAY_HAPPINESS_THRESHOLD) {
                int steps = 2 * (happiness - RESENTMENT_DECAY_HAPPINESS_THRESHOLD) / 5;
                if (steps > 0) for (UUID leader : leaders) addResentment(leader, -steps);
            }
        }

        // Compliance drift. Same per-tick formula for Council + Chiefdom; the asymmetry
        // (Chiefdom recovers post-coup, Council doesn't) emerges from Step 13's reset valve.
        // Gain XOR erode, split at neutral happiness (60). A HAPPY citizen recovers compliance
        // cleanly â€” we deliberately do NOT also apply resentment erosion here, so old grudges
        // (which decay on their own above) can't pin a now-content citizen at 0 compliance.
        // An UNHAPPY citizen erodes via the primary (unhappiness) + secondary (leader-blame)
        // channels. At exactly 60 (no thoughts) compliance holds steady.
        double delta = 0.0;
        if (happiness > COMPLIANCE_GAIN_HAPPINESS_THRESHOLD) {
            int steps = (happiness - COMPLIANCE_GAIN_HAPPINESS_THRESHOLD) / 5;
            delta += steps * COMPLIANCE_GAIN_PER_STEP;
        } else {
            // PRIMARY erosion: unhappiness erodes compliance directly, no resentment lag.
            if (happiness < COMPLIANCE_LOSS_HAPPINESS_THRESHOLD) {
                int steps = (COMPLIANCE_LOSS_HAPPINESS_THRESHOLD - happiness) / 5;
                delta -= steps * COMPLIANCE_LOSS_PER_STEP;
            }
            if (!leaders.isEmpty()) {
                // SECONDARY erosion: a specifically hated leader bleeds cooperation on top.
                int leaderResentment = getLeaderResentmentMax(leaders);
                if (leaderResentment > COMPLIANCE_LOSS_RESENTMENT_THRESHOLD) {
                    int steps = (leaderResentment - COMPLIANCE_LOSS_RESENTMENT_THRESHOLD) / 5;
                    delta -= steps * COMPLIANCE_RESENTMENT_LOSS_PER_STEP;
                }
                // "Brink" doubler â€” compliance erodes twice as fast when EITHER the average
                // leader-resentment crosses the critical line (general unrest) OR the settlement
                // is in singleplayer Chiefdom with the coup condition met (coupSuppressed): the
                // "no NPCs to depose you, but you'll feel it" path.
                double avgLeaderResentment = averageLeaderResentment(leaders);
                boolean brink = avgLeaderResentment > BRINK_AVG_RESENTMENT
                    || settlement.isCoupSuppressed();
                if (brink && delta < 0) {
                    delta *= 2.0;
                }
            }
        }
        if (delta != 0.0) {
            complianceFractional += delta;
            // Apply whole-integer steps when the accumulator crosses Â±1, leaving the
            // remainder for next hour. Keeps the compliance int monotonic-ish without
            // ever-changing rounding noise.
            int wholeStep = (int) complianceFractional;
            if (wholeStep != 0) {
                setCompliance(getCompliance() + wholeStep);
                complianceFractional -= wholeStep;
            }
        }
    }

    /** Average resentment across the given leader set, computed against THIS citizen's
     *  per-leader values. Used by the "brink" doubler in the hourly tick â€” when the average
     *  leader-resentment crosses the threshold the compliance loss doubles. */
    private double averageLeaderResentment(java.util.Set<UUID> leaders) {
        if (leaders.isEmpty()) return 0.0;
        int total = 0;
        for (UUID leader : leaders) total += getResentment(leader);
        return total / (double) leaders.size();
    }

    // â”€â”€â”€ Conversation bubble + relationships â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Current speech-bubble topic id â€” {@code 0} = none. Read by {@code SpeechBubbleLayer}. */
    public int getBubbleTopic() { return this.entityData.get(DATA_BUBBLE); }
    /** Server-only writer â€” {@code ConversationGoal} sets this on bubble flip + back to 0 on clear. */
    public void setBubbleTopic(int id) { this.entityData.set(DATA_BUBBLE, id); }

    public Relationships getRelationships() { return relationships; }
    public Thoughts getThoughts() { return thoughts; }

    /** True while any "won't work" refusal thought is active (assignment refusal, dawn full-day
     *  strike, or the per-minute refusal). Mirrors {@link WorkGoal}'s refusal gate â€” used to suppress
     *  the UNEMPLOYED "unhelpful" thought so a citizen who is actively refusing work isn't <i>also</i>
     *  docked happiness for not working. */
    private boolean hasWorkRefusalThought() {
        if (thoughts.has(ThoughtKind.NO_WORK_RIGHT_NOW, null)) return true;
        if (thoughts.has(ThoughtKind.NO_WORK_TODAY, null)) return true;
        for (com.bannerbound.core.social.Thought th : thoughts.entries()) {
            if (th.kind() == ThoughtKind.NO_WORK_AS_JOB) return true;
        }
        return false;
    }

    /** Server-only writer for the synched-data happiness slot. Clamped to {@code [0, 100]}. */
    private void setHappinessInternal(int value) {
        int clamped = Math.max(Thoughts.MIN_HAPPINESS, Math.min(Thoughts.MAX_HAPPINESS, value));
        if (this.entityData.get(DATA_HAPPINESS) != clamped) {
            this.entityData.set(DATA_HAPPINESS, clamped);
        }
    }
    /** Live happiness reading [0, 100]. Synced â€” both sides agree. */
    public int getHappiness() { return this.entityData.get(DATA_HAPPINESS); }
    /** Current poison stage (0 = not poisoned). Synced so the renderer can draw the poison glyph. */
    public int getPoisonStage() { return this.entityData.get(DATA_POISON_STAGE); }
    /** True if this citizen is currently poisoned (any stage). */
    public boolean isPoisoned() { return getPoisonStage() > 0; }
    /** Server-side: mirror the poison stage from the shared persistent-data bridge into synced data. */
    public void setPoisonStage(int stage) {
        int clamped = Math.max(0, stage);
        if (this.entityData.get(DATA_POISON_STAGE) != clamped) {
            this.entityData.set(DATA_POISON_STAGE, clamped);
        }
    }
    /** Constant â€” the screen renders out of {@link Thoughts#MAX_HAPPINESS}. */
    public int getHappinessMax() { return Thoughts.MAX_HAPPINESS; }

    // â”€â”€ Happiness bands & effects â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // The Info-tab bar is split green / yellow / red at these ratios (CitizenScreen mirrors them for
    // the colour segments). The band a citizen sits in drives a flat performance multiplier applied
    // to walk speed, work step speed, and XP gain â€” single source of truth so the tooltip's promised
    // numbers and the actual in-world effect can never drift apart.
    public static final float HAPPINESS_GREEN_RATIO = 0.70f; // >= â†’ green (bonus)
    public static final float HAPPINESS_RED_RATIO   = 0.40f; // <  â†’ red   (penalty)

    /** Mood band for {@code happiness/max}: {@code +1} green, {@code 0} yellow, {@code -1} red.
     *  Static + side-agnostic so the client screen and the server effects classify identically. */
    public static int happinessBand(int happiness, int max) {
        double ratio = max > 0 ? (double) happiness / (double) max : 0.5;
        if (ratio >= HAPPINESS_GREEN_RATIO) return 1;
        if (ratio <  HAPPINESS_RED_RATIO)   return -1;
        return 0;
    }

    /** Performance multiplier from current mood: green {@code +15%}, yellow neutral, red {@code -30%}.
     *  Multiplies walk speed and XP directly; work-step durations divide by it (faster = fewer ticks). */
    public float happinessPerformanceMultiplier() {
        return switch (happinessBand(getHappiness(), getHappinessMax())) {
            case 1  -> 1.15f;
            case -1 -> 0.70f;
            default -> 1.0f;
        };
    }

    /** Mood multiplier for WALK speed only. Decoupled from {@link #happinessPerformanceMultiplier}
     *  (work/XP) because a full -30% on movement makes miserable citizens crawl noticeably; the
     *  penalty is softened to -10% here while the work/XP penalty stays at -30%. Green keeps the
     *  same +15% spring-in-the-step. Tooltip line {@code red.speed} mirrors this number. */
    public float happinessSpeedMultiplier() {
        return switch (happinessBand(getHappiness(), getHappinessMax())) {
            case 1  -> 1.15f;
            case -1 -> 0.90f;
            default -> 1.0f;
        };
    }
    /** Recomputes the citizen's happiness from {@link #thoughts} and writes it to synched data.
     *  Called whenever a thought is added, removed, or expires (idempotent â€” synched data only
     *  emits a packet when the value actually changes). */
    public void recomputeHappiness() {
        // Game-time drives escalating grievances (no home / jobless / starving deepen as they
        // age). level() is non-null whenever a citizen is ticking; guard for the pre-spawn
        // window (NBT load before world-add) where it can briefly be null.
        long now = this.level() != null ? this.level().getGameTime() : 0L;
        setHappinessInternal(thoughts.aggregateHappiness(now));
    }

    public int getConversationCooldown() { return conversationCooldown; }
    public void setConversationCooldown(int ticks) { this.conversationCooldown = Math.max(0, ticks); }

    /** Predicate used by the citizen's target selector + the reciprocal-targeting event listener
     *  on hostile mobs. Whitelist of hostile types citizens fight (and that fight citizens back).
     *  Enderman is explicitly excluded â€” they're neutral until provoked and we don't want to
     *  turn villages into ender-aggro zones. Zombies, Skeletons, Spiders cover their respective
     *  family branches (Drowned, Husk; Stray, WitherSkeleton; CaveSpider) via inheritance. */
    public static boolean isHostileToCitizens(net.minecraft.world.entity.LivingEntity entity) {
        if (entity instanceof net.minecraft.world.entity.monster.EnderMan) return false;
        return entity instanceof net.minecraft.world.entity.monster.Zombie
            || entity instanceof net.minecraft.world.entity.monster.Skeleton
            || entity instanceof net.minecraft.world.entity.monster.AbstractSkeleton
            || entity instanceof net.minecraft.world.entity.monster.Spider
            || entity instanceof net.minecraft.world.entity.monster.Creeper
            || entity instanceof net.minecraft.world.entity.monster.Witch
            || entity instanceof net.minecraft.world.entity.monster.AbstractIllager
            || entity instanceof net.minecraft.world.entity.monster.Vex;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Trade-courier blocker — priority 0, registered before the creeper-flee/combat goals so
        // insertion order hands it MOVE+LOOK first whenever a journey is active. Its tick() is
        // empty: TraderSimManager drives the navigation externally; holding the flags simply
        // starves every other movement goal (work, patrol, sleep, combat, panic) for the trip.
        // FloatGoal keeps JUMP, so a swimming courier still floats.
        this.goalSelector.addGoal(0, new TradeCourierGoal(this));
        // Flee swelling creepers at the highest priority. AvoidEntityGoal uses MOVE+LOOK, same
        // flags as CitizenCombatGoal â€” registering it FIRST at priority 0 means it acquires the
        // flags whenever its canUse fires (creeper within 8 blocks AND fuse already lit, i.e.
        // getSwellDir > 0). CitizenCombatGoal's canContinueToUse also yields when its target is
        // a swelling creeper (see CitizenCombatGoal), so combat releases MOVE the same tick the
        // flee goal needs it. Net effect: the citizen chases the creeper while it walks, swings
        // at it once or twice, and the moment the fuse lights up they sprint away.
        this.goalSelector.addGoal(0, new net.minecraft.world.entity.ai.goal.AvoidEntityGoal<>(
            this, net.minecraft.world.entity.monster.Creeper.class,
            (java.util.function.Predicate<net.minecraft.world.entity.LivingEntity>)
                living -> living instanceof net.minecraft.world.entity.monster.Creeper c && c.getSwellDir() > 0,
            8.0f, 1.1, 1.25,
            (java.util.function.Predicate<net.minecraft.world.entity.LivingEntity>)
                living -> living instanceof net.minecraft.world.entity.monster.Creeper c && c.getSwellDir() > 0));
        // CitizenCombatGoal at priority 0 â€” overrides every work / sleep / patrol / conversation
        // goal AND PanicGoal (priority 1) when a hostile target is set. Flags MOVE+LOOK; no
        // conflict with FloatGoal's JUMP flag at the same priority. The hostile target itself is
        // chosen by NearestAttackableTargetGoal in the targetSelector below.
        this.goalSelector.addGoal(0, new CitizenCombatGoal(this, 1.1));
        // GuardCombatGoal â€” the guard job's smarter fight (leash-to-home so it won't suicide-chase into
        // the wild, quality+skill scaled damage, same creeper-swell yield). Also priority 0 / MOVE+LOOK,
        // but canUse-able ONLY for a guard, and CitizenCombatGoal yields for guards â€” so exactly one of
        // the two ever runs. A plain Goal (not a WorkGoal) so it fights through the ambient-brain
        // throttle that pauses patrol. See GUARD_PLAN.md.
        this.goalSelector.addGoal(0, new GuardCombatGoal(this, 1.1));
        // BrawlRetaliationGoal also sits at priority 0 â€” preempts PanicGoal (priority 1) so a
        // citizen who's been hit plants their feet and swings back instead of fleeing in fear.
        // Fires only when CitizenBrawlEvents has set a pendingRetaliationTargetId; otherwise
        // canUse returns false and the goal sits dormant.
        this.goalSelector.addGoal(0, new BrawlRetaliationGoal(this));
        // CitizenPanicGoal â€” a vanilla PanicGoal subclass that yields whenever a brawl
        // retaliation is pending on this citizen. Without that override the citizen would
        // flee from the punch they're about to swing back at; CitizenPanicGoal.canUse +
        // canContinueToUse explicitly check getPendingRetaliationTargetId() so panic never
        // starts (or stops mid-flight) while a counter-swing is queued. Otherwise behaves
        // identically to vanilla PanicGoal â€” fire, lava, hurt-without-a-pending-retaliation
        // all still flee at 1.5Ã— speed.
        this.goalSelector.addGoal(1, new CitizenPanicGoal(this, 1.5));
        // GuardMusterGoal — raid muster (guards only): converge on a besieged hub when nothing is
        // in detection range yet. Priority 1 AFTER CitizenPanicGoal (environmental panic wins the
        // same-priority tie) and ABOVE SleepGoal (2) — the war horn wakes a sleeping watch. A plain
        // Goal, so it musters through the ambient-brain throttle. See GUARD_PLAN.md.
        this.goalSelector.addGoal(1, new GuardMusterGoal(this, 1.1));
        // Target selector â€” picks the nearest hostile within FOLLOW_RANGE (64). Combat goal in
        // goalSelector reads citizen.getTarget() set by this. Excludes Enderman (per the user's
        // spec). Re-targets every 10 ticks.
        this.targetSelector.addGoal(1,
            new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<net.minecraft.world.entity.Mob>(
                this, net.minecraft.world.entity.Mob.class, 10, true, false,
                (java.util.function.Predicate<net.minecraft.world.entity.LivingEntity>)
                    m -> engagesHostiles() && !isGuard() && isHostileToCitizens(m)));
        this.targetSelector.addGoal(0,
            new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<Player>(
                this, Player.class, 5, true, false,
                (java.util.function.Predicate<net.minecraft.world.entity.LivingEntity>)
                    target -> com.bannerbound.core.api.settlement.DiplomacyManager.isRallyTarget(this, target)));
        // Fight back against hostile barbarian camp members (they're CitizenEntity, so not covered by
        // the isHostileToCitizens mob goal above). Guarded so barbarians themselves don't use this.
        this.targetSelector.addGoal(2,
            new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<CitizenEntity>(
                this, CitizenEntity.class, 10, true, false,
                (java.util.function.Predicate<net.minecraft.world.entity.LivingEntity>)
                    t -> engagesHostiles() && !isGuard() && isHostileBarbarianToMe(t)));
        // Fight back against city-state mercenaries actively at war with us — also CitizenEntity, also
        // gated on engagesHostiles (guards always, the rest when rallied). Closes the gap where only the
        // player, never the citizenry, answered a mercenary siege. See GUARD_PLAN.md.
        this.targetSelector.addGoal(2,
            new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<MercenaryEntity>(
                this, MercenaryEntity.class, 10, true, false,
                (java.util.function.Predicate<net.minecraft.world.entity.LivingEntity>)
                    e -> engagesHostiles() && !isGuard() && isHostileMercenaryToMe(e)));
        // Guards do NOT use the per-type "nearest" selectors above — GuardTargetingGoal gives them a
        // TACTICAL target instead: spread the squad across raiders, prioritise ranged threats / wounded /
        // the banner, rather than everyone piling onto whoever's closest. See GUARD_PLAN.md §10.
        this.targetSelector.addGoal(1, new GuardTargetingGoal(this));
        // OpenDoorGoal actually opens the door when the citizen reaches one along their path.
        // {@code true} closes it behind them so they don't leave the building wide open.
        this.goalSelector.addGoal(2, new OpenDoorGoal(this, true));
        // Fence-gate counterpart of OpenDoorGoal â€” flagless, opens/closes gates as a side effect.
        this.goalSelector.addGoal(2, new OpenFenceGateGoal(this));
        // OutpostCommuteGoal walks an outpost-assigned worker the long haul out to its remote site
        // in short, vanilla-pathable hops (one moveTo to a 128-block-away site truncates at the
        // FOLLOW_RANGE search radius and stalls). Priority 2, registered BEFORE SleepGoal: same
        // priority means a worker mid-commute at nightfall isn't preempted into a doomed single
        // 128-block walk to a far outpost bed â€” it finishes the hop-walk to the site first, then
        // SleepGoal beds it down on arrival. Inert (one null check) for any non-outpost citizen.
        this.goalSelector.addGoal(2, new OutpostCommuteGoal(this, 0.85));
        // SleepGoal preempts every work goal at night for homed citizens â€” strictly less than 3
        // so it wins the MOVE-flag race against work; greater than 1 so PanicGoal (fire, mob
        // attack) wakes them up the way it would wake a villager.
        this.goalSelector.addGoal(2, new SleepGoal(this, 0.9));
        // Gatherer work goals run whenever there's actual work to do (resources in range,
        // building valid, depot not full, stamina available). When they yield for any of those
        // reasons, the citizen falls straight through to SettlementPatrolGoal â€” no "idle at
        // station" middle state, so unemployable or exhausted gatherers wander like any
        // unassigned citizen until conditions change. ForesterWorkGoal is the first concrete
        // gatherer; future jobs (miner, farmer, hunter, etc.) plug in at the same priority.
        // Anarchy carry-home: when a gatherer with no real storage has filled its carry pack, walk it
        // to the town hall and dump it. Same priority as the work goals, registered before them so it
        // wins the MOVE-flag race the moment a delivery is due (the gatherer has yielded by then, so
        // they're never both runnable). Dormant unless the carry pack has something in it.
        this.goalSelector.addGoal(3, new DeliverHaulGoal(this, 0.85));
        // Plantation goal sits BEFORE the gatherer so a forester with a bound forester_farm order
        // tends its grove instead of clear-cutting wild trees (ForesterWorkGoal.canStartWork also
        // hard-yields when a plantation order exists â€” both are required).
        this.goalSelector.addGoal(3, new ForesterPlantationGoal(this, 0.8));
        this.goalSelector.addGoal(3, new ForesterWorkGoal(this, 0.8));
        this.goalSelector.addGoal(3, new DiggerWorkGoal(this, 0.8));
        this.goalSelector.addGoal(3, new FarmerWorkGoal(this, 0.8));
        this.goalSelector.addGoal(3, new FisherWorkGoal(this, 0.8));
        this.goalSelector.addGoal(3, new ForagerWorkGoal(this, 0.8));
        this.goalSelector.addGoal(3, new HerderWorkGoal(this, 0.8));
        // Registry-defined jobs from Core or an expansion (e.g. the Antiquity spear fisher) plug in
        // here via CitizenJobRegistry â€” no Core edit per job. The goal factory keeps Core ignorant of
        // the goal class, so an expansion's goal can live entirely in that mod. See
        // com.bannerbound.core.api.job.CitizenJobRegistry.
        for (com.bannerbound.core.api.job.CitizenJobRegistry.JobDef def
                : com.bannerbound.core.api.job.CitizenJobRegistry.all()) {
            if (def.goalFactory() == null) continue;
            net.minecraft.world.entity.ai.goal.Goal g = def.goalFactory().apply(this, 0.8);
            if (g != null) this.goalSelector.addGoal(3, g);
        }
        // The remaining worker jobs (stocker) were retired when the workstation blocks were removed;
        // they'll be re-added here as each is migrated to the citizen-menu job system.
        // ConversationGoal sits at priority 3 â€” same as work goals, registered AFTER them so
        // any active work goal wins the MOVE-flag race. Why 3 and not 4: vanilla's
        // WrappedGoal.canBeReplacedBy uses STRICT less-than priority, so a priority-4 goal can
        // never preempt the priority-4 SettlementPatrolGoal once it's running. Living
        // conversation at priority 3 means: work-running â†’ conversation can't claim MOVE, sits
        // idle; work yields â†’ conversation claims MOVE and runs; once running, patrol (4)
        // can't preempt it (good â€” chats run to completion).
        this.goalSelector.addGoal(3, new ConversationGoal(this, 0.8));
        // Domestication policy: occasionally bond a nearby untamed wolf. Sparse (low roll +
        // long cooldown) and gated on the policy, so it's dormant unless Domestication is active.
        this.goalSelector.addGoal(3, new CitizenAdoptPetGoal(this, 0.8));
        this.goalSelector.addGoal(4, new SettlementPatrolGoal(this, 0.8));
        // AnarchyWorkGoal sits at priority 4 alongside patrol. Same MOVE+LOOK flags, so the
        // two compete on equal terms â€” patrol holds MOVE while running, anarchy work claims it
        // during patrol's between-segment cooldown when its own 30% canUse roll succeeds.
        // Only fires when settlement.governmentType == NONE (anarchy phase).
        this.goalSelector.addGoal(4, new AnarchyWorkGoal(this, 0.8));
        // Auxiliary (non-job) goals registered by Core or an expansion — e.g. Antiquity's "drink at a
        // grog trough" leisure goal. Same goalFactory decoupling as the job registry, so the goal
        // class lives entirely in the registering mod and Core never references it.
        for (com.bannerbound.core.api.entity.CitizenGoalRegistry.Entry e
                : com.bannerbound.core.api.entity.CitizenGoalRegistry.all()) {
            net.minecraft.world.entity.ai.goal.Goal g = e.factory().apply(this, 0.8);
            if (g != null) this.goalSelector.addGoal(e.priority(), g);
        }
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 6.0f));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
    }

    /**
     * One-shot setup for a freshly-immigrated citizen: binds the settlement, fixes gender, seeds
     * the current era, rolls a cosmetic texture variant and any random traits, and builds the
     * styled name shown above the head â€” a gender icon, a space, then the name tinted to the
     * settlement's banner color. {@code era} seeds {@link #DATA_ERA}; {@link #aiStep} keeps it
     * current from there on.
     */
    public void initializeCitizen(UUID settlementId, String name, CitizenGender gender,
                                   Era era, ChatFormatting nameColor) {
        this.settlementId = settlementId;
        this.entityData.set(DATA_GENDER, gender.ordinal());
        this.entityData.set(DATA_ERA, era.ordinal());
        this.entityData.set(DATA_TEXTURE_VARIANT, this.random.nextInt(256));
        rollTraits();
        // Bake the raw name-pool draw into this settlement's language ONCE, here â€” the stored name
        // IS the in-language name, so every surface (chat, recall, workshop, roster, persisted
        // partner names) reads it styled. Detached citizens (null settlement) keep the base verbatim;
        // barbarian camps pre-style with their own tongue + a null settlementId, so this is a no-op
        // for them and never double-styles.
        bakeNameInLanguage(name);
        this.nameColor = nameColor;
        refreshDisplayName();
        this.setCustomNameVisible(true);
    }

    @Override
    public void tick() {
        // Profile the full server-side citizen tick (AI goals + navigation + aiStep) so the
        // CitizenAiProfiler HUD can show whether the Village cheap-brain actually lowers cost.
        if (level().isClientSide) {
            super.tick();
            return;
        }
        long start = System.nanoTime();
        super.tick();
        com.bannerbound.core.sim.CitizenAiProfiler.add(System.nanoTime() - start);
    }

    /** Marks this citizen as a throwaway crowd-simulation entity (see {@link #simulated}). */
    public void markSimulated() { this.simulated = true; }

    public boolean isSimulated() { return simulated; }

    /** True if {@code target} is a {@link BarbarianEntity} whose camp is HOSTILE toward THIS citizen's
     *  settlement â€” so a player citizen fights back against hostile camp members. (Barbarians are their
     *  own type now, so this is only ever added to real citizens' goals.) */
    private boolean isHostileBarbarianToMe(net.minecraft.world.entity.LivingEntity target) {
        // Envoys carry diplomatic immunity from our citizens â€” the PLAYER decides whether to parley or
        // strike them (striking one is the hard-refusal path), so citizens never pre-empt that choice.
        if (!(target instanceof BarbarianEntity b) || b.isMessenger() || b.campId() == null
                || !(level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return false;
        }
        com.bannerbound.core.barbarian.BarbarianCamp camp =
            com.bannerbound.core.barbarian.BarbarianData.get(sl).getById(b.campId());
        return camp != null && BarbarianEntity.campHostileTo(camp, this.getSettlement());
    }

    /** True if {@code target} is a city-state MERCENARY whose city-state is ACTIVELY at war with THIS
     *  citizen's settlement — the symmetric counterpart to {@link MercenaryEntity#isCityStateEnemy}.
     *  Lets a guard fight back against a besieging mercenary; before, only the PLAYER ever did. Gated by
     *  {@link #engagesHostiles()} in the selector like the barbarian one. */
    private boolean isHostileMercenaryToMe(net.minecraft.world.entity.LivingEntity target) {
        if (!(target instanceof MercenaryEntity m) || m.cityStateId() == null || getSettlementId() == null
                || !(level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return false;
        }
        com.bannerbound.core.citystate.CityState cs =
            com.bannerbound.core.citystate.CityStateData.get(sl).getById(m.cityStateId());
        return cs != null && cs.isActiveEnemy(getSettlementId());
    }

    /** True if {@code target} is hostile to THIS citizen's settlement by ANY route — a vanilla hostile
     *  mob, a hostile-camp barbarian, or an at-war city-state mercenary. The guard tactical targeting
     *  ({@link GuardTargetingGoal}) gathers candidates with this; the shared per-type selectors still
     *  test each route individually (and are gated to non-guard militia). */
    public boolean isHostileToMe(net.minecraft.world.entity.LivingEntity target) {
        return isHostileToCitizens(target) || isHostileBarbarianToMe(target) || isHostileMercenaryToMe(target);
    }

    /** True once this citizen's settlement reaches the Village stage: it then runs the cheap
     *  ambient steering brain (vanilla {@code MoveControl}, no A* work/patrol) instead of the full
     *  pathfinding work goals. Server-side only. See {@link AmbientWanderGoal} + the gates in
     *  {@link WorkGoal}/{@link SettlementPatrolGoal}. */
    public boolean usesAmbientBrain() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel)) return false;
        com.bannerbound.core.api.settlement.Settlement s = getSettlement();
        return s != null
            && s.stage().ordinal() >= com.bannerbound.core.api.settlement.SettlementStage.VILLAGE.ordinal();
    }

    /** True when this citizen holds the guard job — the settlement's standing watch. Guards fight
     *  hostiles by default; ordinary citizens only do while {@link #engagesHostiles() rallied}. */
    public boolean isGuard() {
        return GuardWorkGoal.JOB_TYPE_ID.equals(jobTypeId);
    }

    /** True while this citizen's settlement has called a RALLY — the wartime / under-raid mobilization
     *  that drops all work and turns every citizen into a fighter (see
     *  {@link com.bannerbound.core.api.settlement.DiplomacyManager#toggleRally}). */
    public boolean isSettlementRallying() {
        return level() instanceof net.minecraft.server.level.ServerLevel sl && settlementId != null
            && com.bannerbound.core.api.settlement.SettlementData.get(sl).isRallying(settlementId);
    }

    /** Whether this citizen actively acquires and fights hostiles. GUARDS always do — it is their job.
     *  Every other adult does so only while the settlement is RALLYING; otherwise ordinary folk leave
     *  the fighting to the watch (and flee if attacked). Children never fight. Gates the mob and
     *  barbarian target selectors so "only guards defend unless you rally" holds. See GUARD_PLAN.md. */
    public boolean engagesHostiles() {
        if (isChild()) return false;
        return isGuard() || isSettlementRallying();
    }

    @Override
    public boolean shouldBeSaved() {
        // Simulated citizens are never written to chunk NBT, so a crash or unload mid-sim can't
        // strand them in the save. The normal cleanup path discards them explicitly.
        return !simulated && super.shouldBeSaved();
    }

    /** Recomputes {@link #aiActive} on a cheap cadence: is any player within {@link #AI_ACTIVE_RANGE}?
     *  Called once per server aiStep; the nearest-player lookup itself runs only every
     *  {@link #AI_ACTIVATION_RECHECK_TICKS} ticks (the player list is tiny, so this is negligible). */
    private void refreshAiActivation() {
        if (this.tickCount < this.aiActivationRecheckAt) return;
        this.aiActivationRecheckAt = this.tickCount + AI_ACTIVATION_RECHECK_TICKS;
        // Outpost-appointed workers stay active regardless of player proximity: the player is
        // usually AT the outpost (>64 blocks from the worker mid-commute) or at home (>64 from
        // the outpost) â€” freezing would visibly strand the commute either way. Bounded cost:
        // at most MAX_OUTPOSTS workers per settlement hold an outpost site.
        this.aiActive = this.outpostSite != null
            || this.level().getNearestPlayer(this, AI_ACTIVE_RANGE) != null;
    }

    /** True when a player is near enough that this citizen should run full AI (scans, patrol, work,
     *  conversation). When false, the heavy goals yield and the citizen idles â€” still a real,
     *  loaded, punchable entity, just not burning CPU on behaviour nobody is around to see. This is
     *  the activation tier: the dominant saving is every citizen in a settlement that currently has
     *  no player present. Defaults to true so a citizen is never accidentally frozen before its
     *  first recheck. */
    public boolean isAiActive() { return this.aiActive; }

    /** Heavy-decision stagger gate. Goals that kick off pathfinding (patrol segment starts, work
     *  scans, conversation initiation) only START on this citizen's "think tick" â€” 1-in-
     *  {@link #THINK_PERIOD}, offset by entity id â€” so the fleet's A* calls spread evenly across
     *  ticks instead of clustering and producing per-tick cost spikes. Movement, navigation
     *  following, and animation are NEVER gated by this (they run every tick), so the staggering is
     *  invisible: a citizen just waits up to {@value #THINK_PERIOD} ticks before picking its next
     *  destination. The earlier whole-step tick-LOD stuttered precisely because it gated movement;
     *  this gates only the decision. */
    public boolean isThinkTick() {
        return (this.tickCount + Math.floorMod(this.getId(), THINK_PERIOD)) % THINK_PERIOD == 0;
    }

    /** Independently rolls each {@link CitizenTrait} by its {@code chance} and packs the result
     *  into {@link #DATA_TRAITS}. Called once on immigration. */
    private void rollTraits() {
        int mask = 0;
        for (CitizenTrait trait : CitizenTrait.values()) {
            if (this.random.nextFloat() < trait.chance()) {
                mask |= trait.bit();
            }
        }
        this.entityData.set(DATA_TRAITS, mask);
    }

    /** Builds + sets the custom name: {@code [<pregnant-icon>] <gender-icon> <name>}. The icon
     *  glyphs are pinned white so the settlement color tint applies only to the name text, not
     *  the icons. Call this any time pregnancy state, gender (immigration), or settlement color
     *  changes so the visible name catches up. Safe to call on the server only â€” vanilla synced
     *  customName broadcasts the change to nearby clients automatically. */
    /** Re-derives the name-tag color from the settlement's live identity and rebuilds the
     *  display name. Called when the faction banner design changes â€” citizens wear the new
     *  identity color immediately, not on next entity reload. */
    public void refreshNameColor() {
        Settlement s = getSettlement();
        this.nameColor = s != null ? s.identityFormatting() : ChatFormatting.WHITE;
        refreshDisplayName();
    }

    public void refreshDisplayName() {
        // Lazy migration: pre-feature citizens have no citizenName field in NBT. Recover it
        // from the live settlement roster the first time refreshDisplayName runs so they get
        // the pregnancy glyph without a world reload.
        if (citizenName == null) {
            Settlement s = getSettlement();
            if (s != null) {
                for (com.bannerbound.core.api.settlement.Citizen c : s.citizens()) {
                    if (c.entityId().equals(this.getUUID())) {
                        this.citizenName = c.name();
                        break;
                    }
                }
            }
        }
        if (citizenName == null) return;
        // Expose the plain name as a vanilla scoreboard tag, separate from the styled customName.
        // The display name carries the gender/pregnancy glyphs + settlement colour, so it can't be
        // matched by @e[name=...]; this bare-name tag lets commands target a citizen by the name
        // shown above their head, e.g.
        //   /bannerbound set_relationship @e[tag=Bjorn,limit=1] @e[tag=Astrid,limit=1] 50
        // addTag is idempotent (tags are a Set) and vanilla persists it in the entity's Tags NBT.
        this.addTag(citizenName);
        Style iconStyle = Style.EMPTY.withFont(ICONS_FONT).withColor(TextColor.fromRgb(0xFFFFFF));
        String genderGlyph = getGender() == CitizenGender.FEMALE ? FEMALE_GLYPH : MALE_GLYPH;
        net.minecraft.network.chat.MutableComponent full = Component.empty();
        if (isPregnant()) {
            full.append(Component.literal(PREGNANT_GLYPH).withStyle(iconStyle));
        }
        String visibleName = displayCitizenName();
        full.append(Component.literal(genderGlyph).withStyle(iconStyle))
            .append(Component.literal(" "))
            .append(Component.literal(visibleName).withStyle(nameColor));
        // Job-icon suffix: the citizen's job tool at the settlement's CURRENT tool age (bone â†’
        // wooden â†’ stone â†’ iron axe as the forester's settlement advances; fisher = rod, herder =
        // rope, forager = poppy). Empty for the unemployed â€” no glyph, no trailing space. Pinned to
        // the same white icon font as the gender prefix so the settlement colour tints only the name.
        String jobGlyph = com.bannerbound.core.social.JobIcons.jobGlyph(getSettlement(), jobTypeId);
        if (!jobGlyph.isEmpty()) {
            full.append(Component.literal(" "))
                .append(Component.literal(jobGlyph).withStyle(iconStyle));
        }
        this.setCustomName(full);
    }

    // â”€â”€â”€ Pregnancy + child state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    public boolean isPregnant() { return this.entityData.get(DATA_PREGNANT); }
    public boolean isChild()    { return this.entityData.get(DATA_IS_CHILD); }
    /** Raw citizen name, no gender/pregnancy glyphs or settlement-colour styling. Used by
     *  systems that need just the plain name (death-thought {@code savedPartnerName}, lookups).
     *  Returns {@code null} for citizens that haven't run {@link #initializeCitizen} yet â€”
     *  callers should fall back to a generic label in that case. */
    public String getCitizenName() { return citizenName; }

    public String displayCitizenName() {
        if (citizenName == null) return "";
        Settlement settlement = getSettlement();
        // citizenName is already the in-language given name (baked at creation / migrated on load),
        // so it is composed verbatim here â€” only the EARNED surname is styled per-call.
        return com.bannerbound.core.language.CustomLanguageLabel.compose(
            settlement, citizenName, earnedSurnameConcept, earnedSurnameJob, getUUID().toString());
    }

    /** Styles the raw given {@code base} through this citizen's settlement language and stores it as
     *  the verbatim display name, marking it baked so a later load never double-styles. No-op styling
     *  for detached citizens (null settlement) â€” they keep {@code base}. */
    private void bakeNameInLanguage(String base) {
        this.citizenName = com.bannerbound.core.language.CustomLanguageLabel.styleGiven(
            getSettlement(), base, getUUID().toString());
        this.nameBaked = true;
    }
    /** Live game-tick when the current pregnancy started, or {@code -1L} if not pregnant. Reads
     *  from synced data so the client renders the live pregnancy progress bar. */
    public long getPregnantSinceTick() { return this.entityData.get(DATA_PREGNANT_SINCE_TICK); }
    public UUID getPregnancyFatherId() { return pregnancyFatherId; }
    public long getBornAtTick() { return bornAtTick; }
    public void setBornAtTick(long t) { this.bornAtTick = t; }

    public UUID getMotherId() { return motherId; }
    public void setMotherId(UUID id) { this.motherId = id; }

    /** Flips the pregnancy state. Caller passes the start tick and father UUID on a true flip;
     *  pass {@code -1L / null} on a clear. Both writes also trigger {@link #refreshDisplayName}
     *  so the pregnancy glyph appears / disappears immediately on the next render frame. */
    public void setPregnant(boolean pregnant, long startTick, UUID fatherId) {
        this.entityData.set(DATA_PREGNANT, pregnant);
        this.entityData.set(DATA_PREGNANT_SINCE_TICK, pregnant ? startTick : -1L);
        this.pregnancyFatherId = pregnant ? fatherId : null;
        refreshDisplayName();
    }

    /** Flag the citizen as a child (born from the procreation loop) or as an adult (the
     *  growup transition). The renderer reads this synced flag every frame to pick the model
     *  scale; server-side guards (work assignment, UNEMPLOYED thought, child thought poll)
     *  consult the same flag. */
    public void setIsChild(boolean child) {
        this.entityData.set(DATA_IS_CHILD, child);
        refreshDisplayName();
    }

    public UUID getSettlementId() { return settlementId; }

    // â”€â”€â”€ Brawl state accessors â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** How many ticks the "we're already brawling with this opponent" memory persists. After
     *  this window, a fresh hit by the same citizen counts as the start of a new brawl (75%
     *  retaliation roll) rather than a continuation (50%). 200 ticks = 10 s â€” long enough to
     *  cover the swing delay + path-back time, short enough that the next argument feels new. */
    public static final int BRAWL_ONGOING_WINDOW_TICKS = 200;
    /** Ticks between a retaliation roll passing and the actual retaliation swing landing.
     *  Short on purpose â€” a longer delay lets the attacker back-pedal out of swing reach
     *  while the citizen stands still preempting PanicGoal. 5 ticks (0.25 s) is enough for
     *  the brawl to read as alternating swings without giving the attacker time to escape. */
    public static final int BRAWL_RETALIATION_DELAY_TICKS = 5;

    @org.jetbrains.annotations.Nullable
    public UUID getLastBrawlOpponentId() { return lastBrawlOpponentId; }
    public long getLastBrawlTick() { return lastBrawlTick; }
    /** Record an exchange with {@code opponent} at {@code tick}. Both the initial AnarchyWorkGoal
     *  punch and every retaliation swing call this so the ongoing-brawl window stays alive. */
    public void noteBrawlExchange(UUID opponent, long tick) {
        this.lastBrawlOpponentId = opponent;
        this.lastBrawlTick = tick;
    }
    /** Schedule a retaliation swing at {@code target} to land at {@code tick}. Consumed by
     *  {@link #aiStep}'s brawl-tick block. */
    public void schedulePendingRetaliation(UUID target, long tick) {
        this.pendingRetaliationTargetId = target;
        this.pendingRetaliationTick = tick;
    }
    @org.jetbrains.annotations.Nullable
    public UUID getPendingRetaliationTargetId() { return pendingRetaliationTargetId; }
    public long getPendingRetaliationTick() { return pendingRetaliationTick; }
    public void clearPendingRetaliation() {
        this.pendingRetaliationTargetId = null;
        this.pendingRetaliationTick = 0L;
    }

    // ─── Guard retaliation (GUARD_PLAN.md §11) ───────────────────────────────────────────────
    /** How long a guard keeps hunting whoever last damaged it. Refreshed on every hit taken, so a
     *  kiting attacker stays legal for as long as it keeps shooting. 400 ticks = 20 s. */
    public static final int GUARD_RETALIATION_WINDOW_TICKS = 400;

    @org.jetbrains.annotations.Nullable private UUID guardRetaliationId;
    private long guardRetaliationTick;

    /** Record {@code attackerId} as this guard's live retaliation target (set by
     *  {@code GuardCombatEvents} on incoming damage from any non-friendly attacker). Transient by
     *  design — a reload forgives, like the brawl window. */
    public void noteGuardRetaliation(UUID attackerId, long tick) {
        this.guardRetaliationId = attackerId;
        this.guardRetaliationTick = tick;
    }

    /** True while {@code e} is the attacker this guard is still licensed to counter-attack —
     *  inside the {@link #GUARD_RETALIATION_WINDOW_TICKS} window since the last hit taken. The
     *  guard combat goal accepts such a target even OUTSIDE the defense-band leash, so a guard
     *  can't be plinked from just past the border with impunity. */
    public boolean isGuardRetaliationTarget(net.minecraft.world.entity.LivingEntity e) {
        if (guardRetaliationId == null || e == null || !e.getUUID().equals(guardRetaliationId)) {
            return false;
        }
        long now = level().getGameTime();
        if (now - guardRetaliationTick > GUARD_RETALIATION_WINDOW_TICKS) {
            guardRetaliationId = null;
            return false;
        }
        return true;
    }

    /** The live retaliation attacker as an entity, or null when the window lapsed, it died, or it
     *  left this level. Used by {@code GuardTargetingGoal} to fold the attacker into scoring. */
    @org.jetbrains.annotations.Nullable
    public net.minecraft.world.entity.LivingEntity guardRetaliationTarget(
            net.minecraft.server.level.ServerLevel sl) {
        UUID id = guardRetaliationId;
        if (id == null) return null;
        if (sl.getGameTime() - guardRetaliationTick > GUARD_RETALIATION_WINDOW_TICKS) {
            guardRetaliationId = null;
            return null;
        }
        if (sl.getEntity(id) instanceof net.minecraft.world.entity.LivingEntity le
                && le.isAlive()) {
            return le;
        }
        return null;
    }

    /** Look at + swing at + bare-handed-hit + angry-villager burst on {@code target}. Used by the
     *  brawl-retaliation goal so every retaliation swing uses identical visuals + damage. Target can
     *  be a citizen or a player:
     *  for citizen targets we also apply the âˆ’5 mutual relationship swing; for players we
     *  skip the social side (the citizen's resentment toward the player rises only when the
     *  PLAYER attacks back â€” see Step 11). Returns true iff the hit actually landed (target
     *  alive + within reach). */
    public boolean performBrawlSwing(net.minecraft.world.entity.LivingEntity target) {
        if (target == null || !target.isAlive()) return false;
        // 7-block reach (49) â€” generous on purpose so attackers backing away during the
        // retaliation-delay window still get clipped. Tested empirically; tighter values
        // silently miss when the attacker drifts even slightly.
        if (this.distanceToSqr(target) > 49.0) return false;
        this.getLookControl().setLookAt(target, 30.0f, 30.0f);
        this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        target.hurt(this.damageSources().mobAttack(this), 1.0f);
        if (target instanceof CitizenEntity ct) {
            com.bannerbound.core.social.SocialEvents.applyMutual(this, ct,
                AnarchyWorkGoal.ANARCHY_RELATION_DELTA_PER_HIT);
        }
        if (this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER,
                target.getX(),
                target.getY() + target.getBbHeight() + 0.3,
                target.getZ(),
                3, 0.2, 0.1, 0.2, 0.0);
        }
        return true;
    }

    // â”€â”€â”€ Stamina â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Live stamina â€” backed by SynchedEntityData so every client that can see this entity reads
     *  the same value without any extra packet. Server writes; both sides read. */
    public int getStamina() { return this.entityData.get(DATA_STAMINA); }
    public int getStaminaMax() { return MAX_STAMINA; }
    private void setStaminaInternal(int value) {
        this.entityData.set(DATA_STAMINA, Math.max(0, Math.min(MAX_STAMINA, value)));
    }
    /** Called by work goals at start/stop. While true, {@link #aiStep} skips stamina regen. */
    public void setWorking(boolean working) { this.working = working; }
    public boolean isWorking() { return working; }
    /** True while the citizen is in their sticky rest period (entered at 0 stamina, exited only at
     *  full stamina). Work goals should treat this as "no work available" â€” not just {@code stamina == 0}. */
    public boolean isStaminaExhausted() { return resting; }

    /**
     * Decrement stamina by {@code amount} (clamped at 0) and reset the recharge timer so a tick
     * that was mid-recharge doesn't grant a free point immediately after consuming. If the
     * decrement drops stamina to 0, latch the resting flag so the citizen can't restart work
     * until stamina is fully regenerated.
     */
    public void consumeStamina(int amount) {
        int next = Math.max(0, getStamina() - amount);
        setStaminaInternal(next);
        staminaRechargeTimer = 0;
        if (next == 0) {
            resting = true;
        }
    }

    // â”€â”€â”€ Capture window (PFT drop hijack) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Squared radius around {@link #captureCenter} within which incoming ItemEntities are
     *  routed to the citizen's workstation BE. 16-block radius covers PFT's falling-tree spread. */
    public static final double CAPTURE_RADIUS_SQ = 16.0 * 16.0;

    /** Pregnancy duration â€” 1 in-game day. After this many ticks since {@link #pregnantSinceTick}
     *  the 20-tick poll hands control to {@code BabyMakingManager.deliver} which spawns the child
     *  and clears the pregnancy. */
    public static final long PREGNANCY_DURATION_TICKS = 24_000L;
    /** Childhood duration â€” 3 in-game days. After this many ticks since {@link #bornAtTick} the
     *  child flips back to adult: model scale resets, work goals re-enable, UNEMPLOYED can apply. */
    public static final long ADULTHOOD_TICKS = 72_000L;
    /** How often the random child-thought roll fires (1 in-game minute). */
    public static final int CHILD_THOUGHT_INTERVAL = 1_200;
    /** Probability that any single child-thought roll actually fires a thought. With 1-min
     *  intervals and 3-min thoughts, ~0.20 keeps at most one active most of the time. */
    public static final double CHILD_THOUGHT_CHANCE = 0.20;

    /**
     * Begin a capture window: any {@link net.minecraft.world.entity.item.ItemEntity} that joins
     * the level within {@link #CAPTURE_RADIUS_SQ} of {@code center} over the next {@code ticks}
     * ticks will be routed to the assigned workstation BE by
     * {@link com.bannerbound.core.event.ForesterDropCaptureEvents}.
     */
    public void beginCaptureWindow(BlockPos center, int ticks) {
        this.captureCenter = center;
        this.captureWindowEndTick = this.tickCount + ticks;
    }

    public boolean isCaptureWindowActive() {
        return captureCenter != null && this.tickCount < captureWindowEndTick;
    }

    public BlockPos getCaptureCenter() {
        return captureCenter;
    }

    // â”€â”€â”€ Employment (per-citizen job) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Workstation-type id of the current job, or {@code null} when unemployed. Server-side only â€”
     *  on the client use {@link #isClientJob(String)} (the type string isn't synced, the ordinal is). */
    public String getJobType() { return jobTypeId; }

    /** Client-safe job check: compares the synced {@link #DATA_JOB} ordinal to {@code typeId}'s
     *  ordinal. Works on both sides; use this in client render/glow code. */
    public boolean isClientJob(String typeId) {
        return this.entityData.get(DATA_JOB)
            == com.bannerbound.core.social.WorkstationIcons.ordinalOf(typeId);
    }

    /**
     * Client-only "fake glow": drives the GLOWING shared flag (bit 6) directly. On the client
     * {@code isCurrentlyGlowing()} reads this flag, so setting it produces the white entity outline
     * for the local player only â€” never synced to the server, so other players don't see it.
     * {@link #setGlowingTag} can't be used here: client-side it would read the (false) flag back and
     * clear itself. Re-applied every tick by the rod-glow handler since a data resync can clear it.
     */
    public void setClientGlow(boolean on) {
        if (level().isClientSide()) {
            setSharedFlag(6, on);
        }
    }

    /** True when this citizen has any job assigned (replaces the old "has a workstation" check). */
    public boolean isEmployed() { return jobTypeId != null; }

    /** True when the player has PINNED this citizen's job â€” a manual override the settlement labor
     *  distributor must leave alone. Set by an explicit Job-tab assign/switch; cleared by "Auto". */
    public boolean isJobPinned() { return jobPinned; }
    public void setJobPinned(boolean pinned) { this.jobPinned = pinned; }

    /** The crafter's bound workshop id, or null. Set by the workshop-menu assignment (and the
     *  future Job-tab workshop picker); cleared whenever the job changes away from crafter. The
     *  Workshop's own workers list is reconciled lazily against this field (it's the source of
     *  truth â€” the citizen owns its employment, per the jobs-on-citizen migration). */
    public java.util.UUID getAssignedWorkshopId() { return assignedWorkshopId; }
    public void setAssignedWorkshopId(java.util.UUID id) { this.assignedWorkshopId = id; }

    /** Craft experience for a profession key (workshop type id, e.g. "fletchery"); 0 if none. */
    public float getJobXp(String key) {
        Float v = jobXp.get(key);
        return v == null ? 0.0F : v;
    }

    /** Adds craft experience for a profession key (one completed craft â‰ˆ +1). Crossing a skill-
     *  tier boundary (Novice â†’ Apprentice â†’ â€¦) is a PROMOTION: a short pride/happiness boost,
     *  so leveling up is a felt moment and not just a quietly bigger number. */
    public void addJobXp(String key, float amount) {
        grantJobXp(key, amount, key);
    }

    /** Adds work experience and awards the first profession surname at Veteran tier. */
    public void grantJobXp(String key, float amount, String surnameConcept) {
        if (key == null || key.isBlank() || amount <= 0.0F) return;
        // Mood scales how much a citizen learns from a day's work: green +15%, red -30%, yellow flat.
        amount *= happinessPerformanceMultiplier();
        float before = getJobXp(key);
        jobXp.merge(key, amount, Float::sum);
        float after = getJobXp(key);
        String oldTier = com.bannerbound.core.api.quality.QualityMath.skillTierKey((int) before);
        String newTier = com.bannerbound.core.api.quality.QualityMath.skillTierKey((int) after);
        if (!oldTier.equals(newTier)
                && level() instanceof net.minecraft.server.level.ServerLevel sl
                && getThoughts() != null) {
            getThoughts().add(com.bannerbound.core.social.ThoughtKind.PROMOTED, null,
                sl.getGameTime(), sl.getRandom());
            recomputeHappiness();
        }
        if (earnedSurnameConcept == null
                && before < SURNAME_XP_THRESHOLD
                && after >= SURNAME_XP_THRESHOLD) {
            earnedSurnameConcept = (surnameConcept == null || surnameConcept.isBlank()) ? key : surnameConcept;
            earnedSurnameJob = key;
            earnedSurnameTick = level() == null ? -1L : level().getGameTime();
            refreshDisplayName();
        }
    }

    /** True while this citizen is still under a "won't work as that" refusal (an active
     *  {@link com.bannerbound.core.social.ThoughtKind#NO_WORK_AS_JOB} thought from a recently-refused
     *  job-switch request). The Town-Hall/Citizen "Request switch" button stays greyed until it lapses
     *  so the player can't spam re-requests that the refusal thought is meant to block. */
    public boolean hasActiveJobRefusal() {
        long now = level().getGameTime();
        for (com.bannerbound.core.social.Thought t : thoughts.entries()) {
            if (t.kind() == com.bannerbound.core.social.ThoughtKind.NO_WORK_AS_JOB && !t.isExpired(now)) {
                return true;
            }
        }
        return false;
    }

    /** Assigns (or clears, when {@code typeId == null}) the job. Re-syncs {@link #DATA_JOB} so the
     *  JOB speech bubble updates client-side. Clearing the job also drops the citizen's preferred
     *  log + drop-off back to defaults; the held tool is handled separately by the caller (it must
     *  be returned to storage/feet, never silently dropped here). */
    public void setJobType(String typeId) {
        String oldJob = this.jobTypeId;
        BlockPos shoreSnap = this.dropOffPos;   // captured before an unassign nulls it (boat bail-out below)
        this.jobTypeId = typeId;
        // Any job change away from crafter drops the workshop binding (the Workshop's own workers
        // list reconciles lazily against this field â€” the citizen is the source of truth).
        if (!CrafterWorkGoal.isWorkshopJob(typeId)) {
            this.assignedWorkshopId = null;
        }
        // The trade-courier opt-in is a stocker capability; leaving the job drops it.
        if (!StockerWorkGoal.JOB_TYPE_ID.equals(typeId)) {
            this.tradingCourier = false;
        }
        this.entityData.set(DATA_JOB,
            typeId == null ? 0 : com.bannerbound.core.social.WorkstationIcons.ordinalOf(typeId));
        if (typeId == null) {
            this.dropOffPos = null;
            this.seedSourcePos = null;
            this.jobPinned = false;   // unassign releases any manual pin
        } else if (oldJob != null && !oldJob.equals(typeId) && (hasJobTool() || hasJobPickaxe())) {
            // Job CHANGED (e.g. the labor distributor re-skilled a forester into a fisher): the held
            // tool is the wrong one for the new role. Return it to storage / the citizen's feet and
            // clear it so the new job starts tool-free and a fresh tool can be installed. The drop-off
            // and carry pack are kept (a re-skilled worker keeps its destination).
            returnJobToolsForReskill();
        }
        // Job changed while out on a fishing vessel (a sailing fisher re-skilled by the labor
        // distributor / Job tab / migration): the NEW job's goal can't run seated â€” navigation no-ops
        // for passengers â€” and it outcompetes the fisher goal's ride-home adoption (same priority,
        // earlier registration), so she'd sit in the boat forever. Bail out here at the single choke
        // point every job switch passes through: step off and snap to the kept drop-off (already on
        // settlement land); the abandoned ghost vessel despawns itself. Load is safe: readNBT calls
        // this before the vehicle mounts its passengers, so getVehicle() is still null then.
        if (!java.util.Objects.equals(oldJob, typeId)
                && this.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat) {
            this.stopRiding();
            if (shoreSnap != null) {
                this.moveTo(shoreSnap.getX() + 0.5, shoreSnap.getY() + 1.0, shoreSnap.getZ() + 0.5,
                    this.getYRot(), 0.0F);
                tagDeliberateTeleport(this);
                this.getNavigation().stop();
            }
        }
        // Rebuild the display name so the job-icon suffix appears (on hire) or disappears (on
        // clear) immediately â€” mirrors setPregnant / setIsChild. No-op pre-naming (the rebuild
        // guards on a null citizenName), so the load path (readNBT â†’ setJobType) is safe too.
        refreshDisplayName();
    }

    public net.minecraft.world.item.ItemStack getJobTool() { return jobTool; }
    public boolean hasJobTool() { return !jobTool.isEmpty(); }
    public void setJobTool(net.minecraft.world.item.ItemStack stack) {
        this.jobTool = stack == null ? net.minecraft.world.item.ItemStack.EMPTY : stack;
    }

    public net.minecraft.world.item.ItemStack getJobPickaxe() { return jobPickaxe; }
    public boolean hasJobPickaxe() { return !jobPickaxe.isEmpty(); }
    public void setJobPickaxe(net.minecraft.world.item.ItemStack stack) {
        this.jobPickaxe = stack == null ? net.minecraft.world.item.ItemStack.EMPTY : stack;
    }

    public net.minecraft.world.level.block.Block getPreferredLog() { return preferredLog; }
    public void setPreferredLog(net.minecraft.world.level.block.Block block) {
        if (block != null) this.preferredLog = block;
    }

    /** Forager's enabled-category bitmask (see {@link com.bannerbound.core.api.forager.ForageCategory}). */
    public int getForageTargetBits() { return forageTargetBits; }
    public void setForageTargetBits(int bits) {
        this.forageTargetBits = bits & com.bannerbound.core.api.forager.ForageCategory.ALL_BITS;
    }
    /** Forester only: whether the canopy's saplings / apples / sticks are stored (true) or kept out
     *  of the drop-off / carry pack and discarded (false, logs-only). Defaults true. */
    public boolean foresterKeepsExtras() { return foresterKeepExtras; }
    public void setForesterKeepExtras(boolean keep) { this.foresterKeepExtras = keep; }

    /** Live work status for the Job-tab headline. Server-only/transient â€” see the field doc. */
    public CitizenWorkStatus getCurrentWorkStatus() { return currentWorkStatus; }
    public void setCurrentWorkStatus(CitizenWorkStatus status) {
        this.currentWorkStatus = status == null ? CitizenWorkStatus.IDLE : status;
    }

    /** Toggle a single forage category on/off by its enum ordinal (bit index). */
    public void setForageTarget(int ordinal, boolean enabled) {
        int bit = 1 << ordinal;
        if ((bit & com.bannerbound.core.api.forager.ForageCategory.ALL_BITS) == 0) return;
        this.forageTargetBits = enabled ? (forageTargetBits | bit) : (forageTargetBits & ~bit);
    }

    /** Hunter only: whether this citizen hunts {@code type}. Everything in the huntable tag
     *  defaults ON; the Job-tab prey picker writes the disabled set. */
    public boolean isHunterPreyEnabled(net.minecraft.world.entity.EntityType<?> type) {
        return hunterPreyOff.isEmpty()
            || !hunterPreyOff.contains(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(type).toString());
    }

    /** Toggle one huntable species on/off by its entity-type id string (Job-tab prey picker). */
    public void setHunterPreyEnabled(String entityTypeId, boolean enabled) {
        if (entityTypeId == null || entityTypeId.isEmpty()) return;
        if (enabled) hunterPreyOff.remove(entityTypeId);
        else hunterPreyOff.add(entityTypeId);
    }

    /** Snapshot of the DISABLED prey ids, for the Job-tab sync payload. */
    public java.util.List<String> getHunterPreyOffIds() {
        return java.util.List.copyOf(hunterPreyOff);
    }

    public BlockPos getDropOff() { return dropOffPos; }
    public void setDropOff(BlockPos pos) {
        BlockPos old = this.dropOffPos;
        // Switching away from the anarchy town-hall carry pack to a real container (auto-discovered or
        // marked by the player): deliver whatever's in the pack to the town hall first so it isn't
        // stranded â€” the carry pack only feeds the sink drop-off. Covers both the auto path and a
        // manual Job-tab mark.
        if (old != null && (pos == null || !pos.equals(old)) && hasHaul()
                && isAnarchyTownHallSink(old) && level() instanceof ServerLevel sl) {
            dumpHaulAt(sl, old);
        }
        this.dropOffPos = pos == null ? null : pos.immutable();
    }

    public BlockPos getSeedSource() { return seedSourcePos; }
    public void setSeedSource(BlockPos pos) { this.seedSourcePos = pos == null ? null : pos.immutable(); }

    /** Farmer's seed overflow buffer (see {@link FarmerWorkGoal}). Always present; empty for non-farmers. */
    public net.minecraft.world.SimpleContainer getSeedCache() { return seedCache; }

    /** A worker only works once it has the given job, a tool, and a drop-off. The drop-off
     *  block still being a valid container is checked at work time (it may have been broken). */
    public boolean isJobReady(String typeId) {
        return typeId.equals(jobTypeId) && hasJobTool() && hasDropDepot();
    }

    /** True when this citizen has somewhere to deposit its yield: an explicitly marked drop-off, or â€”
     *  failing that â€” the settlement's government-set preferred storage, the settlement-wide fallback
     *  that lets any unmarked worker function (mirrors how {@link JobTools} provisions tools and how
     *  {@link DropOffContainers#resolveOrPreferred} routes deposits). The preferred fallback is
     *  government-only, so in anarchy this is just the marked drop-off (anarchy workers discover their
     *  own via {@link #autoFindDropOff}). */
    public boolean hasDropDepot() {
        return dropOffPos != null || (!isAnarchy() && settlementHasPooledStorage(true));
    }

    /** True when this citizen can obtain seeds: a marked seed source, or â€” failing that â€” the
     *  settlement's preferred storage (same government-only fallback as {@link #hasDropDepot}). */
    public boolean hasSeedDepot() {
        return seedSourcePos != null || (!isAnarchy() && settlementHasPooledStorage(false));
    }

    /** True if the settlement has a preferred-storage depot set (the position only; whether it still
     *  resolves to a live container is checked at work time by {@link DropOffContainers#resolveOrPreferred}). */
    private boolean settlementHasPooledStorage(boolean deposit) {
        Settlement s = getSettlement();
        if (s == null || !(level() instanceof ServerLevel sl)) return false;
        return deposit ? SettlementStorage.hasDeposit(sl, s) : SettlementStorage.hasTake(sl, s);
    }

    /** Convenience for the forester (job + axe + drop-off). In anarchy the axe is optional â€” a
     *  self-organizing citizen works bare-handed (slower, see {@link #anarchyWorkSpeedFactor()}). */
    public boolean isForesterReady() {
        if (isAnarchy()) return ForesterWorkGoal.JOB_TYPE_ID.equals(jobTypeId) && hasDropDepot();
        return isJobReady(ForesterWorkGoal.JOB_TYPE_ID);
    }

    /** Convenience for the fisher (job + fishing rod + drop-off). In anarchy the rod is optional â€”
     *  a self-organizing citizen fishes bare-handed (slower). */
    public boolean isFisherReady() {
        if (isAnarchy()) return FisherWorkGoal.JOB_TYPE_ID.equals(jobTypeId) && hasDropDepot();
        return isJobReady(FisherWorkGoal.JOB_TYPE_ID);
    }

    /** Forager needs only its job + a drop-off â€” it works bare-handed (no tool slot). */
    public boolean isForagerReady() {
        return ForagerWorkGoal.JOB_TYPE_ID.equals(jobTypeId) && hasDropDepot();
    }

    /** Generic readiness for a {@link com.bannerbound.core.api.job.CitizenJobRegistry registry-defined}
     *  gatherer job. Mirrors {@link #isFisherReady}/{@link #isForagerReady}: in anarchy a citizen
     *  self-organizes with just its job + drop-off (tool optional, slower bare-handed); otherwise it
     *  also needs a job tool when the {@link com.bannerbound.core.api.job.CitizenJobRegistry.JobDef}
     *  declares {@code toolRequired}. Lets an expansion goal gate readiness without a bespoke
     *  {@code isXxxReady} method on Core. */
    public boolean isGatherJobReady(String typeId) {
        if (typeId == null || !typeId.equals(jobTypeId) || !hasDropDepot()) return false;
        if (isAnarchy()) return true;
        com.bannerbound.core.api.job.CitizenJobRegistry.JobDef def =
            com.bannerbound.core.api.job.CitizenJobRegistry.byId(typeId);
        boolean toolRequired = def == null || def.toolRequired();
        return !toolRequired || hasJobTool();
    }

    /**
     * Clears the drop-off / seed-source reference when its block is LOADED but no longer a valid
     * storage block â€” i.e. the player broke or replaced the chest/basket. Without this the worker keeps
     * pointing at a dead container, never works, and the Job tab still shows it as marked. Deliberately
     * leaves the reference alone when the chunk is merely UNLOADED (the container may still be there).
     * Called from each work goal's readiness check. Returns true if anything was cleared.
     */
    public boolean validateJobStorage() {
        boolean cleared = false;
        if (dropOffPos != null && level().isLoaded(dropOffPos)
                && !DropOffContainers.isDropOffBlock(level(), dropOffPos)
                && !isAnarchyTownHallSink(dropOffPos)) {
            dropOffPos = null;
            cleared = true;
        }
        if (seedSourcePos != null && level().isLoaded(seedSourcePos)
                && !DropOffContainers.isDropOffBlock(level(), seedSourcePos)) {
            seedSourcePos = null;
            cleared = true;
        }
        return cleared;
    }

    /** Farmer needs its job + hoe + drop-off (harvest sink) AND a seed source to pull seeds from.
     *  Both the drop-off and the seed source fall back to the settlement's preferred storage when
     *  unmarked (see {@link #hasDropDepot}/{@link #hasSeedDepot}). */
    public boolean isFarmerReady() {
        return isJobReady(FarmerWorkGoal.JOB_TYPE_ID) && hasSeedDepot();
    }

    // â”€â”€â”€ Anarchy self-organization â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Tool-free anarchy work multiplier (>1 = slower): a citizen self-organizing without the
     *  proper tool produces at a reduced rate. */
    public static final double ANARCHY_TOOLFREE_WORK_FACTOR = 2.0;
    /** Soft delivery threshold: a gatherer with no real storage hauls its carry pack to the town hall
     *  once it's carrying at least this many items. SOFT â€” the single harvest that crosses it is
     *  absorbed in full first (whole drops are never split), so a variable loot-table roll (a 3-berry
     *  pick, a tree's random sticks/saplings) never spills or is lost. */
    public static final int ANARCHY_HAUL_CAPACITY = 64;

    /** True when this citizen belongs to a settlement that has not yet enacted a government â€” the
     *  anarchy / self-organizing phase. Gates the auto-employ loop, tool-free work, and the
     *  reduced-rate factor. */
    public boolean isAnarchy() {
        Settlement s = getSettlement();
        return s != null && s.governmentType() == Settlement.Government.NONE;
    }

    /** Wind-up multiplier applied to self-organized gatherer work. A citizen working without the
     *  proper tool (always, in anarchy) produces at {@link #ANARCHY_TOOLFREE_WORK_FACTOR}Ã— the
     *  ticks-per-action; handing them the tool restores full speed (1.0). */
    public double anarchyWorkSpeedFactor() {
        return (isAnarchy() && !hasJobTool()) ? ANARCHY_TOOLFREE_WORK_FACTOR : 1.0;
    }

    /**
     * Per-citizen drop-off discovery for an auto-assigned gatherer (the settlement-level
     * {@link AnarchyJobDistributor} assigns the job itself). If the citizen holds a gatherer job but
     * has no drop-off yet, it lazily adopts the nearest storage container near the town hall â€” so the
     * player only has to <i>place</i> a chest (no marking required) for work to start flowing; an
     * explicit Job-tab mark overrides this. In anarchy only, falls back to piling at the town hall
     * (the carry pack) when there's no container. Server-side; runs while the settlement
     * auto-distributes labor (anarchy, or a government with auto-assign on).
     */
    public void autoFindDropOff(Settlement s) {
        if (jobTypeId == null || !AnarchyJobs.isGathererJob(jobTypeId)) return;
        // The storage pool handles real storage now (deposit into the nearest open basket/stockpile)
        // in both anarchy and government. The only per-worker marker still needed is the anarchy
        // town-hall carry sink: resolveJobDepot falls back to it (piling the load at the town hall via
        // the carry pack) when the pool is empty. No-op under a government — the pool is the whole story.
        if (isAnarchy() && dropOffPos == null && s.townHallPos() != null) {
            setDropOff(s.townHallPos());
        }
    }

    /** True when {@code pos} is this citizen's anarchy communal drop-off â€” the town-hall ground pile
     *  (a virtual {@code GroundPileContainer} sink, not a real block). Kept out of
     *  {@link #validateJobStorage}'s clear sweep, and treated as "still auto" so a chest placed later
     *  can replace it. */
    private boolean isAnarchyTownHallSink(BlockPos pos) {
        if (pos == null || !isAnarchy()) return false;
        Settlement s = getSettlement();
        return s != null && pos.equals(s.townHallPos());
    }

    /** True when this citizen's job drop-off is the anarchy carry-home sentinel (the town hall, no
     *  real container) â€” so its gatherer deposits into the {@link #anarchyHaul carry pack} and hauls
     *  it home instead of stashing into a chest. */
    public boolean isAnarchyHaulDropOff() {
        return isAnarchyTownHallSink(getDropOff());
    }

    /** The carry pack the self-directed gatherers deposit into when there's no real storage. Returned
     *  as their depot by {@link DropOffContainers#resolveJobDepot}. */
    public net.minecraft.world.SimpleContainer getAnarchyHaul() { return anarchyHaul; }

    /** True if the carry pack holds anything waiting to be hauled to the town hall. */
    public boolean hasHaul() { return !anarchyHaul.isEmpty(); }

    /** Total item count currently in the carry pack (across all stacks). */
    public int haulItemCount() {
        int n = 0;
        for (int i = 0; i < anarchyHaul.getContainerSize(); i++) n += anarchyHaul.getItem(i).getCount();
        return n;
    }

    /** True once the carry pack is carrying at least {@link #ANARCHY_HAUL_CAPACITY} items â€” a SOFT
     *  threshold checked AFTER each whole drop is deposited, so the gatherer finishes (and fully keeps)
     *  the harvest that crosses it, then yields so {@link DeliverHaulGoal} walks the load home. */
    public boolean isHaulFull() { return haulItemCount() >= ANARCHY_HAUL_CAPACITY; }

    /** Drains the carry pack, merging its single-item bundle slots back into proper stacks. */
    private java.util.List<net.minecraft.world.item.ItemStack> drainHaulMerged() {
        java.util.List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>();
        for (int i = 0; i < anarchyHaul.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack s = anarchyHaul.getItem(i);
            if (s.isEmpty()) continue;
            for (net.minecraft.world.item.ItemStack o : out) {
                if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(o, s)
                        && o.getCount() < o.getMaxStackSize()) {
                    int move = Math.min(s.getCount(), o.getMaxStackSize() - o.getCount());
                    o.grow(move);
                    s.shrink(move);
                    if (s.isEmpty()) break;
                }
            }
            if (!s.isEmpty()) out.add(s.copy());
            anarchyHaul.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
        }
        return out;
    }

    /** Drops the whole carry pack as merged item stacks at {@code pos} (the town hall) â€” the worker
     *  physically dumping what it hauled home. Clears the pack. */
    public void dumpHaulAt(ServerLevel sl, BlockPos pos) {
        for (net.minecraft.world.item.ItemStack s : drainHaulMerged()) {
            net.minecraft.world.entity.item.ItemEntity e = new net.minecraft.world.entity.item.ItemEntity(
                sl, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, s);
            e.setDefaultPickUpDelay();
            sl.addFreshEntity(e);
        }
    }

    /** Spills the carry pack at the citizen's feet â€” used on death / exile so a half-gathered haul
     *  isn't silently lost. */
    private void dumpHaulAtFeet() {
        for (net.minecraft.world.item.ItemStack s : drainHaulMerged()) {
            this.spawnAtLocation(s);
        }
    }

    /**
     * Returns the held job tool to the world and clears it: into the marked drop-off container if
     * it has room, otherwise dropped at the citizen's feet. Called on unassign, exile, and death so
     * the player's axe is never lost. No-op when there's no tool. Also clears the MAINHAND render
     * copy the work goal may have equipped.
     */
    public void returnJobToolAndClear() {
        if (this.level() instanceof ServerLevel sl) {
            returnOneTool(sl, jobTool);
            returnOneTool(sl, jobPickaxe);
            dumpSeedCache(sl);
            dumpHaulAtFeet();   // don't lose a half-gathered anarchy carry pack
        }
        this.jobTool = net.minecraft.world.item.ItemStack.EMPTY;
        this.jobPickaxe = net.minecraft.world.item.ItemStack.EMPTY;
        this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            net.minecraft.world.item.ItemStack.EMPTY);
    }

    /** Returns just the held tool + pickaxe to storage (or the citizen's feet) and clears them, for a
     *  job re-skill. Unlike {@link #returnJobToolAndClear} it does NOT dump the seed cache or carry
     *  pack and does NOT clear the drop-off â€” a re-skilled worker keeps its destination and any haul. */
    private void returnJobToolsForReskill() {
        if (this.level() instanceof ServerLevel sl) {
            returnOneTool(sl, jobTool);
            returnOneTool(sl, jobPickaxe);
        }
        this.jobTool = net.minecraft.world.item.ItemStack.EMPTY;
        this.jobPickaxe = net.minecraft.world.item.ItemStack.EMPTY;
        this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            net.minecraft.world.item.ItemStack.EMPTY);
    }

    /** Empties the farmer's seed buffer on unassign / exile / death so cached seeds aren't lost:
     *  into the drop-off (marked, or the preferred-storage fallback) if it has room, else dropped at
     *  the citizen's feet. */
    private void dumpSeedCache(ServerLevel sl) {
        net.minecraft.world.Container depot = DropOffContainers.resolveOrPreferred(this, dropOffPos);
        for (int i = 0; i < seedCache.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack s = seedCache.getItem(i);
            if (s.isEmpty()) continue;
            net.minecraft.world.item.ItemStack leftover = depot != null ? DropOffContainers.insert(depot, s) : s;
            if (!leftover.isEmpty()) this.spawnAtLocation(leftover);
            seedCache.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
        }
    }

    /** Insert {@code tool} into the drop-off (marked, or the preferred-storage fallback) if it has
     *  room, else drop it at the citizen's feet. */
    private void returnOneTool(ServerLevel sl, net.minecraft.world.item.ItemStack tool) {
        if (tool.isEmpty()) return;
        net.minecraft.world.Container c = DropOffContainers.resolveOrPreferred(this, dropOffPos);
        boolean stored = c != null && DropOffContainers.insert(c, tool).isEmpty();
        if (!stored) this.spawnAtLocation(tool);
    }

    @Override
    public void die(net.minecraft.world.damagesource.DamageSource cause) {
        // Hand the player's axe back before the entity goes away (death drops nothing else of ours).
        returnJobToolAndClear();
        super.die(cause);
    }

    // â”€â”€â”€ Settlement lookup â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Resolves the settlement this citizen belongs to, server-side. Returns null when not on the
     * server or when the settlement has been removed (orphaned citizen â€” caller may choose to
     * despawn it).
     */
    public Settlement getSettlement() {
        if (settlementId == null) return null;
        if (!(this.level() instanceof ServerLevel serverLevel)) return null;
        SettlementData data = SettlementData.get(serverLevel);
        return data.getById(settlementId);
    }

    /** True if {@code pos}'s chunk is part of this citizen's settlement claims. */
    public boolean isInOwnedChunk(BlockPos pos) {
        Settlement s = getSettlement();
        if (s == null) return true;
        long packed = new ChunkPos(pos).toLong();
        return s.claimedChunks().contains(packed);
    }

    // â”€â”€â”€ Interaction â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Override
    public net.minecraft.world.InteractionResult mobInteract(net.minecraft.world.entity.player.Player player,
                                                              net.minecraft.world.InteractionHand hand) {
        if (player.level().isClientSide) {
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        // Shift-right-click with a Foreman's Rod on a DIGGER binds the rod's selections to only this
        // digger â€” handled here (not in the rod's interactLivingEntity) because mobInteract runs
        // first and would otherwise just open the screen. Opens nothing; consumes the click.
        net.minecraft.world.item.ItemStack rod =
            serverPlayer.getMainHandItem().is(com.bannerbound.core.BannerboundCore.FOREMANS_ROD.get())
                ? serverPlayer.getMainHandItem()
                : serverPlayer.getOffhandItem().is(com.bannerbound.core.BannerboundCore.FOREMANS_ROD.get())
                    ? serverPlayer.getOffhandItem()
                    : net.minecraft.world.item.ItemStack.EMPTY;
        // Shift-right-click an ordered worker (digger/farmer) with a rod â†’ bind the rod to THIS worker.
        String rodType = com.bannerbound.core.network.ServerPayloadHandler.rodTypeForJob(getJobType());
        if (serverPlayer.isShiftKeyDown() && !rod.isEmpty() && rodType != null) {
            rod.set(com.bannerbound.core.BannerboundCore.FOREMAN_WORKSTATION_TYPE.get(), rodType);
            rod.set(com.bannerbound.core.BannerboundCore.FOREMAN_TARGET_CITIZEN.get(),
                getUUID().toString());
            Component dname = getCustomName() != null ? getCustomName() : Component.literal("Worker");
            rod.set(com.bannerbound.core.BannerboundCore.FOREMAN_TARGET_NAME.get(), dname.getString());
            Component wname = com.bannerbound.core.social.WorkstationNames.dynamic(getSettlement(), rodType);
            serverPlayer.displayClientMessage(Component.translatable(
                "bannerbound.foremans_rod.one", wname, dname).withStyle(ChatFormatting.AQUA), true);
            return net.minecraft.world.InteractionResult.CONSUME;
        }
        Settlement settlement = getSettlement();
        boolean canModify = settlement != null && settlement.members().contains(serverPlayer.getUUID());

        // Pass the styled name Component as-is â€” flattening to a string would drop the gender
        // icon's custom font and the settlement-color tint.
        Component displayName = this.getCustomName() != null
            ? this.getCustomName()
            : Component.literal("Citizen");
        // Snapshot every known relationship into a list of (styled name, score) rows for the
        // Relationships tab. Partners that aren't currently loaded fall back to a neutral
        // "Unknown Citizen" component so the score still shows.
        java.util.List<com.bannerbound.core.network.RelationshipEntry> rels = new java.util.ArrayList<>();
        net.minecraft.server.level.ServerLevel sl = (net.minecraft.server.level.ServerLevel) serverPlayer.level();
        for (java.util.Map.Entry<UUID, com.bannerbound.core.social.Relationship> e : relationships.entries().entrySet()) {
            net.minecraft.world.entity.Entity ent = sl.getEntity(e.getKey());
            Component otherName;
            if (ent instanceof CitizenEntity oc && oc.getCustomName() != null) {
                otherName = oc.getCustomName();
            } else {
                otherName = Component.literal("Unknown Citizen");
            }
            rels.add(new com.bannerbound.core.network.RelationshipEntry(
                otherName, e.getValue().score(), e.getValue().isFamily()));
        }
        // Snapshot every active thought into transport rows. Social thoughts get the partner's
        // styled name baked into the label component server-side so the client doesn't have to
        // resolve UUIDs to citizen names. We pass the ABSOLUTE expire-game-tick (not a frozen
        // remaining count) so the client can subtract its live game time each render frame and
        // draw a time-bar that actually shrinks while the screen is open.
        java.util.List<com.bannerbound.core.network.ThoughtEntry> thoughtRows = new java.util.ArrayList<>();
        for (com.bannerbound.core.social.Thought t : thoughts.entries()) {
            Component partnerName = null;
            if (t.otherUuid() != null) {
                net.minecraft.world.entity.Entity ent = sl.getEntity(t.otherUuid());
                if (ent instanceof CitizenEntity oc && oc.getCustomName() != null) {
                    partnerName = oc.getCustomName();
                } else if (t.savedPartnerName() != null) {
                    // Partner entity is gone (death thoughts) â€” fall back to the snapshot
                    // string captured at thought-creation time.
                    partnerName = Component.literal(t.savedPartnerName());
                } else {
                    partnerName = Component.literal("Someone");
                }
            }
            Component label = partnerName != null
                ? Component.translatable(t.kind().labelKey(), partnerName)
                : Component.translatable(t.kind().labelKey());
            // Show the CURRENT (escalated) modifier so a festering grievance reads "-40" not
            // its day-one "-5" — otherwise the displayed thoughts don't sum to the happiness
            // number and it looks like an event tanked happiness out of nowhere.
            thoughtRows.add(new com.bannerbound.core.network.ThoughtEntry(
                label, t.effectiveModifier(sl.getGameTime()), t.expireGameTime(), t.totalDurationTicks(),
                t.kind().category().ordinal()));
        }
        // Step 9 polish: the screen shows the citizen's resentment toward the VIEWING
        // player only. Filter server-side so no player can read what others think of them.
        int viewerResentment = getResentment(serverPlayer.getUUID());
        com.bannerbound.core.network.OpenCitizenScreenPayload payload =
            new com.bannerbound.core.network.OpenCitizenScreenPayload(
                this.getId(),
                displayName,
                this.getHealth(),
                this.getMaxHealth(),
                getHappiness(),
                getHappinessMax(),
                canModify,
                getStamina(),
                getStaminaMax(),
                rels,
                thoughtRows,
                compliance,
                viewerResentment
            );
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer, payload);
        // Follow up with the Job-tab state so the new screen's Job tab has data on first open.
        com.bannerbound.core.network.ServerPayloadHandler.sendJobState(serverPlayer, this);
        return net.minecraft.world.InteractionResult.CONSUME;
    }

    // â”€â”€â”€ Persistence â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (settlementId != null) {
            tag.putUUID(TAG_SETTLEMENT_ID, settlementId);
        }
        tag.putBoolean("TradingCourier", tradingCourier);
        if (tradeJourneyId != null) tag.putUUID("TradeJourneyId", tradeJourneyId);
        tag.putInt(TAG_STAMINA, getStamina());
        tag.putInt(TAG_STAMINA_TIMER, staminaRechargeTimer);
        tag.putBoolean(TAG_RESTING, resting);
        tag.putInt(TAG_GENDER, this.entityData.get(DATA_GENDER));
        tag.putInt(TAG_TRAITS, this.entityData.get(DATA_TRAITS));
        tag.putInt(TAG_TEXTURE_VARIANT, this.entityData.get(DATA_TEXTURE_VARIANT));
        tag.putInt(TAG_ERA, this.entityData.get(DATA_ERA));
        // Relationships: omit the tag entirely when empty so fresh citizens stay compact.
        if (!relationships.isEmpty()) {
            tag.put(TAG_RELATIONS, relationships.save());
        }
        // Thoughts: same compact-when-empty discipline. Per-citizen daily chunk-sample counters
        // and the last-eval day are saved so a save/load mid-day doesn't lose the running tally.
        if (!thoughts.isEmpty()) {
            tag.put(TAG_THOUGHTS, thoughts.save());
        }
        tag.putIntArray(TAG_CHUNK_SAMPLES, chunkSamples.clone());
        tag.putLong(TAG_LAST_CHUNK_EVAL_DAY, lastChunkEvalDay);
        // Pregnancy + child + raw name. Optional tags â€” omit at defaults so older / non-pregnant
        // / adult / pre-procreation citizens stay compact.
        if (citizenName != null) tag.putString(TAG_CITIZEN_NAME, citizenName);
        tag.putBoolean(TAG_NAME_BAKED, nameBaked);
        if (isPregnant()) {
            tag.putBoolean(TAG_PREGNANT, true);
            tag.putLong(TAG_PREGNANT_SINCE_TICK, getPregnantSinceTick());
            if (pregnancyFatherId != null) tag.putUUID(TAG_PREGNANCY_FATHER, pregnancyFatherId);
        }
        if (isChild()) tag.putBoolean(TAG_IS_CHILD, true);
        if (bornAtTick > 0L) tag.putLong(TAG_BORN_AT_TICK, bornAtTick);
        if (motherId != null) tag.putUUID(TAG_MOTHER, motherId);
        if (earnedSurnameConcept != null && !earnedSurnameConcept.isBlank()) {
            tag.putString(TAG_EARNED_SURNAME_CONCEPT, earnedSurnameConcept);
            if (earnedSurnameJob != null && !earnedSurnameJob.isBlank()) {
                tag.putString(TAG_EARNED_SURNAME_JOB, earnedSurnameJob);
            }
            if (earnedSurnameTick >= 0L) {
                tag.putLong(TAG_EARNED_SURNAME_TICK, earnedSurnameTick);
            }
        }
        // Compliance â€” omit at default (100) to keep fresh-citizen NBT compact.
        if (compliance != DEFAULT_COMPLIANCE) {
            tag.putInt(TAG_COMPLIANCE, compliance);
        }
        // Resentment map â€” list of {Uuid, Value} compounds. Skip entirely when empty so
        // citizens who've never been wronged stay compact on disk.
        if (!resentmentByPlayer.isEmpty()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (java.util.Map.Entry<UUID, Integer> e : resentmentByPlayer.entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                CompoundTag entry = new CompoundTag();
                entry.putUUID(TAG_RESENTMENT_UUID, e.getKey());
                entry.putInt(TAG_RESENTMENT_VALUE, e.getValue());
                list.add(entry);
            }
            if (!list.isEmpty()) tag.put(TAG_RESENTMENT_MAP, list);
        }
        // Job state â€” all omitted at defaults so unemployed citizens stay compact.
        if (jobTypeId != null) tag.putString(TAG_JOB_TYPE, jobTypeId);
        if (jobPinned) tag.putBoolean(TAG_JOB_PINNED, true);
        if (assignedWorkshopId != null) tag.putUUID(TAG_WORKSHOP_ID, assignedWorkshopId);
        if (!jobXp.isEmpty()) {
            net.minecraft.nbt.CompoundTag xpTag = new net.minecraft.nbt.CompoundTag();
            for (java.util.Map.Entry<String, Float> e : jobXp.entrySet()) {
                xpTag.putFloat(e.getKey(), e.getValue());
            }
            tag.put("JobXpMap", xpTag);
        }
        if (!jobTool.isEmpty()) tag.put(TAG_JOB_TOOL, jobTool.save(this.registryAccess()));
        if (!jobPickaxe.isEmpty()) tag.put(TAG_JOB_PICKAXE, jobPickaxe.save(this.registryAccess()));
        if (preferredLog != net.minecraft.world.level.block.Blocks.OAK_LOG) {
            net.minecraft.resources.ResourceLocation logId =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(preferredLog);
            if (logId != null) tag.putString(TAG_PREFERRED_LOG, logId.toString());
        }
        if (dropOffPos != null) tag.putLong(TAG_DROP_OFF, dropOffPos.asLong());
        if (seedSourcePos != null) tag.putLong(TAG_SEED_SOURCE, seedSourcePos.asLong());
        if (forageTargetBits != com.bannerbound.core.api.forager.ForageCategory.ALL_BITS) {
            tag.putInt(TAG_FORAGE_TARGETS, forageTargetBits);
        }
        if (!hunterPreyOff.isEmpty()) {   // default = everything huntable â€” persist only exclusions
            net.minecraft.nbt.ListTag off = new net.minecraft.nbt.ListTag();
            for (String id : hunterPreyOff) off.add(net.minecraft.nbt.StringTag.valueOf(id));
            tag.put(TAG_HUNTER_PREY_OFF, off);
        }
        if (!foresterKeepExtras) {   // default true â€” only persist the non-default "logs only" choice
            tag.putBoolean(TAG_FORESTER_KEEP_EXTRAS, false);
        }
        if (!seedCache.isEmpty()) {
            tag.put(TAG_SEED_CACHE, seedCache.createTag(this.registryAccess()));
        }
        if (!anarchyHaul.isEmpty()) {
            tag.put(TAG_ANARCHY_HAUL, anarchyHaul.createTag(this.registryAccess()));
        }
        // Persist the outpost residence so an appointed worker stays anchored to its outpost across
        // reload / idle, independent of the work-goal scan that also maintains it at runtime.
        if (outpostSite != null) {
            tag.putLong("OutpostSite", outpostSite.asLong());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(TAG_SETTLEMENT_ID)) {
            this.settlementId = tag.getUUID(TAG_SETTLEMENT_ID);
        }
        this.tradingCourier = tag.getBoolean("TradingCourier");
        this.tradeJourneyId = tag.hasUUID("TradeJourneyId") ? tag.getUUID("TradeJourneyId") : null;
        this.outpostSite = tag.contains("OutpostSite") ? BlockPos.of(tag.getLong("OutpostSite")) : null;
        int loadedStamina = tag.contains(TAG_STAMINA)
            ? Math.max(0, Math.min(MAX_STAMINA, tag.getInt(TAG_STAMINA)))
            : MAX_STAMINA;
        setStaminaInternal(loadedStamina);
        this.staminaRechargeTimer = tag.contains(TAG_STAMINA_TIMER)
            ? Math.max(0, tag.getInt(TAG_STAMINA_TIMER))
            : 0;
        // Older saves without TAG_RESTING infer the flag from stamina == 0 so an exhausted
        // citizen loaded from disk doesn't instantly resume chopping.
        this.resting = tag.contains(TAG_RESTING)
            ? tag.getBoolean(TAG_RESTING)
            : loadedStamina == 0;
        // Old saves may still carry a "Carried" list from the pre-stamina design. We ignore it
        // intentionally â€” the new model has no per-citizen carry, so those items would have no
        // sensible home. They're dropped on load.

        // Gender / cosmetics. Citizens saved before this feature have none of these tags â€” rather
        // than make every old citizen an identical male, derive stable pseudo-random values from
        // the entity UUID so the existing population still looks varied.
        int uuidHash = this.getUUID().hashCode();
        this.entityData.set(DATA_GENDER, tag.contains(TAG_GENDER)
            ? tag.getInt(TAG_GENDER)
            : (uuidHash & 1));
        this.entityData.set(DATA_TRAITS, tag.contains(TAG_TRAITS) ? tag.getInt(TAG_TRAITS) : 0);
        this.entityData.set(DATA_TEXTURE_VARIANT, tag.contains(TAG_TEXTURE_VARIANT)
            ? tag.getInt(TAG_TEXTURE_VARIANT)
            : (uuidHash >>> 1) & 0xFF);
        this.entityData.set(DATA_ERA, tag.contains(TAG_ERA)
            ? tag.getInt(TAG_ERA)
            : Era.ANCIENT.ordinal());
        // Relationships: missing tag â†’ empty map (citizens saved before this feature).
        relationships.load(tag.contains(TAG_RELATIONS)
            ? tag.getList(TAG_RELATIONS, net.minecraft.nbt.Tag.TAG_COMPOUND)
            : null);
        // Thoughts: missing tag â†’ empty list. Recompute happiness from the loaded thoughts so
        // the synched-data slot is correct from frame zero (otherwise it'd sit at the default
        // BASE until the next mutation).
        thoughts.load(tag.contains(TAG_THOUGHTS)
            ? tag.getList(TAG_THOUGHTS, net.minecraft.nbt.Tag.TAG_COMPOUND)
            : null);
        if (tag.contains(TAG_CHUNK_SAMPLES)) {
            int[] saved = tag.getIntArray(TAG_CHUNK_SAMPLES);
            int n = Math.min(saved.length, chunkSamples.length);
            System.arraycopy(saved, 0, chunkSamples, 0, n);
        }
        this.lastChunkEvalDay = tag.contains(TAG_LAST_CHUNK_EVAL_DAY)
            ? tag.getLong(TAG_LAST_CHUNK_EVAL_DAY)
            : -1L;
        // Pregnancy + child + raw name. Optional tags, all default to "non-pregnant adult
        // immigrant" so saves from before this feature load cleanly.
        this.citizenName = tag.contains(TAG_CITIZEN_NAME) ? tag.getString(TAG_CITIZEN_NAME) : null;
        // NameBaked absent â†’ pre-feature save whose stored name is a raw name-pool draw; the
        // migration below styles it once. New saves carry the flag true.
        this.nameBaked = tag.contains(TAG_NAME_BAKED) && tag.getBoolean(TAG_NAME_BAKED);
        boolean loadedPregnant = tag.contains(TAG_PREGNANT) && tag.getBoolean(TAG_PREGNANT);
        this.entityData.set(DATA_PREGNANT, loadedPregnant);
        long loadedSinceTick = loadedPregnant && tag.contains(TAG_PREGNANT_SINCE_TICK)
            ? tag.getLong(TAG_PREGNANT_SINCE_TICK) : -1L;
        this.entityData.set(DATA_PREGNANT_SINCE_TICK, loadedSinceTick);
        this.pregnancyFatherId = loadedPregnant && tag.hasUUID(TAG_PREGNANCY_FATHER)
            ? tag.getUUID(TAG_PREGNANCY_FATHER) : null;
        boolean loadedChild = tag.contains(TAG_IS_CHILD) && tag.getBoolean(TAG_IS_CHILD);
        this.entityData.set(DATA_IS_CHILD, loadedChild);
        this.bornAtTick = tag.contains(TAG_BORN_AT_TICK) ? tag.getLong(TAG_BORN_AT_TICK) : -1L;
        this.motherId = tag.hasUUID(TAG_MOTHER) ? tag.getUUID(TAG_MOTHER) : null;
        this.earnedSurnameConcept = tag.contains(TAG_EARNED_SURNAME_CONCEPT)
            ? tag.getString(TAG_EARNED_SURNAME_CONCEPT) : null;
        this.earnedSurnameJob = tag.contains(TAG_EARNED_SURNAME_JOB)
            ? tag.getString(TAG_EARNED_SURNAME_JOB) : null;
        this.earnedSurnameTick = tag.contains(TAG_EARNED_SURNAME_TICK)
            ? tag.getLong(TAG_EARNED_SURNAME_TICK) : -1L;
        // Re-derive the settlement-color formatting from the live settlement so the rebuilt
        // name picks up any settlement color change since save time. Falls back to white if
        // the settlement is gone (orphan citizen) â€” they get a plain-white name and stay
        // visible until cleanup removes them.
        Settlement s = getSettlement();
        this.nameColor = s != null ? s.identityFormatting() : ChatFormatting.WHITE;
        // Migration: pre-feature citizens have no TAG_CITIZEN_NAME, so refreshDisplayName would
        // return early and the pregnancy glyph would never appear on them. Recover the raw name
        // from the settlement roster (Citizen record carries name string) so existing worlds
        // benefit from the feature without re-spawning the whole population.
        if (this.citizenName == null && s != null) {
            for (com.bannerbound.core.api.settlement.Citizen c : s.citizens()) {
                if (c.entityId().equals(this.getUUID())) {
                    this.citizenName = c.name();
                    break;
                }
            }
        }
        // Language migration + roster reconcile: pre-feature saves stored a RAW name-pool draw â€”
        // bake it into the settlement language once so chat / recall / workshop / roster all read
        // the in-language name. Then keep the roster entry in sync with the (baked) name every load
        // so unloaded-citizen surfaces match the loaded name tag (and the rename actually persists).
        // Detached citizens (no settlement) can't be styled â€” leave them for a later load.
        if (this.citizenName != null && s != null) {
            if (!this.nameBaked) {
                bakeNameInLanguage(this.citizenName);
            }
            if (s.renameCitizen(this.getUUID(), this.citizenName)
                    && this.level() instanceof ServerLevel sl) {
                SettlementData.get(sl).setDirty();
            }
        }
        // Rebuild the display name so the pregnancy glyph re-appears on a saved pregnant load.
        refreshDisplayName();
        recomputeHappiness();
        // Compliance defaults to 100 when the tag is missing (pre-Step-9 saves).
        this.compliance = tag.contains(TAG_COMPLIANCE)
            ? Math.max(COMPLIANCE_MIN, Math.min(COMPLIANCE_MAX, tag.getInt(TAG_COMPLIANCE)))
            : DEFAULT_COMPLIANCE;
        this.resentmentByPlayer.clear();
        if (tag.contains(TAG_RESENTMENT_MAP)) {
            net.minecraft.nbt.ListTag list = tag.getList(TAG_RESENTMENT_MAP,
                net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (!entry.hasUUID(TAG_RESENTMENT_UUID)) continue;
                int v = entry.getInt(TAG_RESENTMENT_VALUE);
                if (v <= 0) continue;
                this.resentmentByPlayer.put(entry.getUUID(TAG_RESENTMENT_UUID), v);
            }
        }
        // Job state. Defaults (unemployed, no tool, oak, no drop-off) for pre-feature saves.
        setJobType(tag.contains(TAG_JOB_TYPE) ? tag.getString(TAG_JOB_TYPE) : null);
        this.jobPinned = tag.getBoolean(TAG_JOB_PINNED);   // after setJobType (which clears it on null)
        // After setJobType â€” which nulls the binding for non-crafter jobs (incl. the load call).
        this.assignedWorkshopId = tag.hasUUID(TAG_WORKSHOP_ID) ? tag.getUUID(TAG_WORKSHOP_ID) : null;
        // Migration: the workshop crafter specialties (carpenter, potter, â€¦) were collapsed into the
        // single generic Crafter â€” their executor/icon/research gate now derive from the workshop, so
        // those job ids no longer register. A citizen saved under a removed specialty (still holding a
        // workshop binding) is remapped to the generic Crafter so its goal runs again; setJobType keeps
        // the binding (crafter is a workshop job) and returns any now-unneeded held tool (e.g. a saw).
        if (this.assignedWorkshopId != null && this.jobTypeId != null
                && !CrafterWorkGoal.JOB_TYPE_ID.equals(this.jobTypeId)
                && com.bannerbound.core.api.job.CitizenJobRegistry.byId(this.jobTypeId) == null) {
            java.util.UUID keep = this.assignedWorkshopId;
            setJobType(CrafterWorkGoal.JOB_TYPE_ID);
            this.assignedWorkshopId = keep;
        }
        this.jobXp.clear();
        if (tag.contains("JobXpMap")) {
            net.minecraft.nbt.CompoundTag xpTag = tag.getCompound("JobXpMap");
            for (String key : xpTag.getAllKeys()) {
                this.jobXp.put(key, xpTag.getFloat(key));
            }
        }
        this.jobTool = tag.contains(TAG_JOB_TOOL)
            ? net.minecraft.world.item.ItemStack.parse(this.registryAccess(), tag.getCompound(TAG_JOB_TOOL))
                .orElse(net.minecraft.world.item.ItemStack.EMPTY)
            : net.minecraft.world.item.ItemStack.EMPTY;
        this.jobPickaxe = tag.contains(TAG_JOB_PICKAXE)
            ? net.minecraft.world.item.ItemStack.parse(this.registryAccess(), tag.getCompound(TAG_JOB_PICKAXE))
                .orElse(net.minecraft.world.item.ItemStack.EMPTY)
            : net.minecraft.world.item.ItemStack.EMPTY;
        this.preferredLog = net.minecraft.world.level.block.Blocks.OAK_LOG;
        if (tag.contains(TAG_PREFERRED_LOG)) {
            net.minecraft.resources.ResourceLocation logId =
                net.minecraft.resources.ResourceLocation.tryParse(tag.getString(TAG_PREFERRED_LOG));
            if (logId != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(logId)) {
                this.preferredLog = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(logId);
            }
        }
        this.dropOffPos = tag.contains(TAG_DROP_OFF)
            ? BlockPos.of(tag.getLong(TAG_DROP_OFF)) : null;
        this.seedSourcePos = tag.contains(TAG_SEED_SOURCE)
            ? BlockPos.of(tag.getLong(TAG_SEED_SOURCE)) : null;
        this.forageTargetBits = tag.contains(TAG_FORAGE_TARGETS)
            ? (tag.getInt(TAG_FORAGE_TARGETS) & com.bannerbound.core.api.forager.ForageCategory.ALL_BITS)
            : com.bannerbound.core.api.forager.ForageCategory.ALL_BITS;
        this.hunterPreyOff.clear();
        if (tag.contains(TAG_HUNTER_PREY_OFF)) {
            net.minecraft.nbt.ListTag off = tag.getList(TAG_HUNTER_PREY_OFF,
                net.minecraft.nbt.Tag.TAG_STRING);
            for (int i = 0; i < off.size(); i++) hunterPreyOff.add(off.getString(i));
        }
        this.foresterKeepExtras = !tag.contains(TAG_FORESTER_KEEP_EXTRAS)
            || tag.getBoolean(TAG_FORESTER_KEEP_EXTRAS);
        seedCache.fromTag(tag.contains(TAG_SEED_CACHE)
            ? tag.getList(TAG_SEED_CACHE, net.minecraft.nbt.Tag.TAG_COMPOUND)
            : new net.minecraft.nbt.ListTag(), this.registryAccess());
        anarchyHaul.fromTag(tag.contains(TAG_ANARCHY_HAUL)
            ? tag.getList(TAG_ANARCHY_HAUL, net.minecraft.nbt.Tag.TAG_COMPOUND)
            : new net.minecraft.nbt.ListTag(), this.registryAccess());
    }

    // â”€â”€â”€ Tick â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Override
    public void aiStep() {
        // Drive the swing-time ramp ourselves: in vanilla 1.21.1, only Monster.aiStep / Player.aiStep
        // / RemotePlayer.tick call updateSwingTime â€” Mob.aiStep and LivingEntity.aiStep do NOT.
        // CitizenEntity extends PathfinderMob (not Monster), so without this call swingTime stays
        // at 0 on both sides and the chop arm-swing never visibly renders, no matter how many
        // AnimatePackets we broadcast. Drive it every tick on both sides.
        this.updateSwingTime();
        // Tick-rate LOD: a Village+ citizen runs its full brain only every AI_LOD_INTERVAL ticks,
        // staggered by entity id so the per-tick server load spreads out. Skipped ticks do no
        // goals/navigation/thoughts/movement; client interpolation smooths the reduced-rate motion.
        if (!this.level().isClientSide && AI_LOD_INTERVAL > 1 && usesAmbientBrain()
                && (this.tickCount + this.getId()) % AI_LOD_INTERVAL != 0) {
            return;
        }
        // Refresh the activation tier before the goal selectors run (inside super.aiStep), so the
        // heavy goals see an up-to-date isAiActive() this tick.
        if (!this.level().isClientSide) {
            refreshAiActivation();
        }
        super.aiStep();
        if (this.level().isClientSide) return;
        // Pending brawl retaliation is now handled by BrawlRetaliationGoal at priority 0 â€”
        // it preempts PanicGoal so the citizen plants feet, holds for the scheduled delay,
        // and swings. Keeping it as a Goal (not an aiStep poke) is what makes the brawl
        // override panic.
        // Conversation cooldown ticks down even while patrolling, working, or sleeping. Cheap.
        if (this.conversationCooldown > 0) {
            this.conversationCooldown--;
        }
        // Stamina recharge: tick-by-tick, grants +1 stamina every RECHARGE_TICKS_PER_POINT ticks.
        // Resting flag clears only when stamina hits MAX, so a citizen that just regenerated 1
        // point can't immediately leap back into work â€” they have to fully rest. Regen pauses
        // while a work goal is actively running; idle, patrolling, resting, BE-full, no-trees,
        // and unassigned all count as "not working" and let stamina tick up.
        int cur = getStamina();
        if (!working && cur < MAX_STAMINA) {
            // Poison saps recovery â€” a poisoned citizen regenerates stamina at half rate (POISON_PLAN).
            int rechargeTicks = isPoisoned() ? RECHARGE_TICKS_PER_POINT * 2 : RECHARGE_TICKS_PER_POINT;
            if (++staminaRechargeTimer >= rechargeTicks) {
                int next = cur + 1;
                setStaminaInternal(next);
                staminaRechargeTimer = 0;
                if (next >= MAX_STAMINA) {
                    resting = false;
                }
            }
        }
        // Falling-water burst while sticky-resting (stamina hit 0; won't work again until full).
        // Visible signal that this citizen is on a forced rest and isn't broken AI â€” without
        // this, an exhausted worker just stands at their workstation and the player wonders why
        // they stopped. 1 burst every 3 s reads as "still resting" without spamming particles.
        if (resting && this.tickCount % 60 == 0
            && this.level() instanceof ServerLevel restSl) {
            restSl.sendParticles(net.minecraft.core.particles.ParticleTypes.FALLING_WATER,
                this.getX(), this.getY() + this.getBbHeight() + 0.25, this.getZ(),
                6, 0.25, 0.1, 0.25, 0.0);
        }
        // Every half-second, recompute the dynamic speed modifier so research effects + dirt_path
        // tile bonus apply without us touching the citizen on every research-complete event.
        if (this.tickCount % 10 == 0) {
            recomputeSpeedModifier();
            // Keep DATA_ERA in step with the settlement's current era so the renderer's texture
            // set follows the settlement advancing. Cheap synced-data write only when it changed.
            Settlement eraSettlement = getSettlement();
            if (eraSettlement != null) {
                int ord = eraSettlement.age().ordinal();
                if (this.entityData.get(DATA_ERA) != ord) {
                    this.entityData.set(DATA_ERA, ord);
                    refreshDisplayName();
                }
                // Sync current tool-age shovel/hoe ids so the JOB bubble can show the right
                // Digger/Farmer tool client-side. Only writes when changed.
                int shovelId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(
                    eraSettlement.getToolForRole("shovel"));
                int hoeId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(
                    eraSettlement.getToolForRole("hoe"));
                if (this.entityData.get(DATA_TOOL_SHOVEL) != shovelId) {
                    this.entityData.set(DATA_TOOL_SHOVEL, shovelId);
                }
                if (this.entityData.get(DATA_TOOL_HOE) != hoeId) {
                    this.entityData.set(DATA_TOOL_HOE, hoeId);
                }
                // The name-tag job glyph tracks the tool age too (covers the axe role, which the
                // shovel/hoe sync above doesn't): rebuild the display name when this citizen's
                // resolved job-icon item changes. Guarded so we rebuild only on an actual change.
                int jobIconId = com.bannerbound.core.social.JobIcons.iconItemId(eraSettlement, jobTypeId);
                if (jobIconId != lastJobIconItemId) {
                    lastJobIconItemId = jobIconId;
                    refreshDisplayName();
                }
            }
        }
        // Step 10: hourly compliance + resentment tick. Per-citizen offset (entity id %
        // period) so a freshly-immigrated wave doesn't all tick on the same world frame.
        // Offset is stable per entity instance â€” re-evaluated only when the citizen is
        // reloaded into the world, which spreads ticks naturally over time.
        if ((this.tickCount + Math.abs(this.getId()) % HOURLY_TICK_PERIOD_TICKS)
                % HOURLY_TICK_PERIOD_TICKS == 0) {
            Settlement complianceS = getSettlement();
            if (complianceS != null) {
                // Escalating grievances (no home / jobless / starving) deepen silently between
                // add/remove events, so re-derive happiness hourly before the compliance tick
                // reads it. Idempotent: synced data only emits a packet if the value changed.
                recomputeHappiness();
                tickComplianceResentmentHourly(complianceS);
            }
        }
        // â”€â”€ Thoughts / happiness â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Tick thoughts every game tick (cheap â€” list is short, most ticks the iterator finds
        // nothing to drop). Recompute happiness only when something actually expired.
        if (this.level() instanceof ServerLevel thoughtSl) {
            long now = thoughtSl.getGameTime();
            if (thoughts.tick(now)) recomputeHappiness();
            // Unemployment / homelessness check every 20 ticks (1s). Same idempotent add/remove
            // pattern for both: the citizen's settlement either has a workstation/home assigned
            // to them or doesn't, and the corresponding infinite-duration thought reflects it.
            // Children are exempt from UNEMPLOYED â€” childhood isn't a job they're supposed to
            // have, so don't dock their happiness for it.
            if (this.tickCount % 20 == 0) {
                Settlement jobS = getSettlement();
                // Only a real tribe expects everyone to have a job â€” during the Hearth stage
                // (pre-government, small) citizens freelance and shouldn't feel "unhelpful". And a
                // rebellious low-compliance citizen (â‰¤ floor) doesn't feel unhelpful either â€” being
                // jobless is their choice, so don't dock them for it (would contradict their refusal).
                boolean unemployed = !isChild() && jobS != null && jobS.isTribe()
                    && !isEmployed()
                    && this.compliance > UNEMPLOYED_COMPLIANCE_FLOOR
                    && !hasWorkRefusalThought();   // actively refusing â†’ don't also feel "unhelpful"
                boolean hasUnemployed = thoughts.has(ThoughtKind.UNEMPLOYED, null);
                if (unemployed && !hasUnemployed) {
                    thoughts.add(ThoughtKind.UNEMPLOYED, null, now, thoughtSl.random);
                    recomputeHappiness();
                } else if (!unemployed && hasUnemployed) {
                    thoughts.remove(ThoughtKind.UNEMPLOYED, null);
                    recomputeHappiness();
                }
                // Self-organized labor: the settlement-level AnarchyJobDistributor assigns the job;
                // here each citizen just lazily finds a drop-off for it. Runs while the settlement
                // auto-distributes (anarchy, or a government with auto-assign left on).
                if (jobS != null
                        && (jobS.governmentType() == Settlement.Government.NONE || jobS.laborAutoAssign())) {
                    autoFindDropOff(jobS);
                }
                // Remote tool provisioning: under a government, a gatherer missing its job tool draws
                // one from the settlement's preferred storage (no walk â€” pulled straight from the
                // chest/stockpile, consuming one). Works in anarchy too now (still optional — a tribe
                // gatherer stays bare-handed, slower, if no tool is in the pool).
                if (jobS != null && !hasJobTool()) {
                    JobTools.tryEquipToolFromStorage(this, jobS);
                }
                // Pregnancy due check â€” runs in the same 20-tick block so a freshly-delivered
                // mother's flag clears the same tick the next UNEMPLOYED roll sees her. No-op
                // when not pregnant or the duration hasn't elapsed yet.
                long startTick = getPregnantSinceTick();
                if (isPregnant() && startTick > 0L
                    && now - startTick >= PREGNANCY_DURATION_TICKS) {
                    com.bannerbound.core.social.BabyMakingManager.deliver(this, thoughtSl, now);
                }
                // Aging: child â†’ adult after the configured childhood window. Flag flip auto-
                // resets the renderer scale on the next frame, re-enables work goals, lets
                // UNEMPLOYED start applying again (the very next tick of this poll).
                if (isChild() && bornAtTick > 0L && now - bornAtTick >= ADULTHOOD_TICKS) {
                    setIsChild(false);
                    com.bannerbound.core.social.BabyMakingManager.broadcastGrewUp(thoughtSl, this);
                }
                // Food state: STARVING when the settlement food bar is empty; eating-well thoughts read
                // the same net food/sec the Town Hall shows. All gated on consumption > 0 so
                // pre-government settlements get no food thoughts.
                Settlement foodS = getSettlement();
                if (foodS != null) {
                    double consumption = foodS.foodConsumptionPerSecond();
                    boolean hasStarving = thoughts.has(ThoughtKind.STARVING, null);
                    boolean hasEatingWell = thoughts.has(ThoughtKind.EATING_WELL, null);
                    boolean hasEatingVeryWell = thoughts.has(ThoughtKind.EATING_VERY_WELL, null);
                    if (consumption <= 0.0) {
                        // Pre-cap settlement: nothing to think about food-wise. Clear any stale entries.
                        if (hasStarving) { thoughts.remove(ThoughtKind.STARVING, null); recomputeHappiness(); }
                        if (hasEatingWell) { thoughts.remove(ThoughtKind.EATING_WELL, null); recomputeHappiness(); }
                        if (hasEatingVeryWell) { thoughts.remove(ThoughtKind.EATING_VERY_WELL, null); recomputeHappiness(); }
                    } else {
                        boolean starving = foodS.isStarving();
                        if (starving && !hasStarving) {
                            thoughts.add(ThoughtKind.STARVING, null, now, thoughtSl.random);
                            if (hasEatingWell) thoughts.remove(ThoughtKind.EATING_WELL, null);
                            if (hasEatingVeryWell) thoughts.remove(ThoughtKind.EATING_VERY_WELL, null);
                            recomputeHappiness();
                        } else if (!starving && hasStarving) {
                            thoughts.remove(ThoughtKind.STARVING, null);
                            thoughts.add(ThoughtKind.WAS_STARVING_RECENTLY, null, now, thoughtSl.random);
                            recomputeHappiness();
                        }
                        if (!starving) {
                            // Eating well is about ABUNDANCE: key it on how many days of food reserve the
                            // settlement holds, not the (often-flat) net rate.
                            double days = foodS.reserveDays();
                            boolean veryWell = days >= 3.0;
                            boolean well = days >= 1.0;
                            if (veryWell) {
                                if (!hasEatingVeryWell) { thoughts.add(ThoughtKind.EATING_VERY_WELL, null, now, thoughtSl.random); recomputeHappiness(); }
                                if (hasEatingWell) { thoughts.remove(ThoughtKind.EATING_WELL, null); recomputeHappiness(); }
                            } else if (well) {
                                if (!hasEatingWell) { thoughts.add(ThoughtKind.EATING_WELL, null, now, thoughtSl.random); recomputeHappiness(); }
                                if (hasEatingVeryWell) { thoughts.remove(ThoughtKind.EATING_VERY_WELL, null); recomputeHappiness(); }
                            } else {
                                if (hasEatingWell) { thoughts.remove(ThoughtKind.EATING_WELL, null); recomputeHappiness(); }
                                if (hasEatingVeryWell) { thoughts.remove(ThoughtKind.EATING_VERY_WELL, null); recomputeHappiness(); }
                            }
                        }
                    }
                }
                // Same Hearth-stage exemption â€” a campfire band doesn't fret about housing yet.
                boolean homeless = jobS != null && jobS.isTribe()
                    && jobS.getHomeFor(this.getUUID()) == null;
                boolean hasNoHome = thoughts.has(ThoughtKind.NO_HOME, null);
                if (homeless && !hasNoHome) {
                    thoughts.add(ThoughtKind.NO_HOME, null, now, thoughtSl.random);
                    recomputeHappiness();
                } else if (!homeless && hasNoHome) {
                    thoughts.remove(ThoughtKind.NO_HOME, null);
                    recomputeHappiness();
                }
                // Injury thoughts â€” mutually exclusive, both infinite. <50 % HP â†’ BADLY_INJURED
                // (-10 happiness), 50-75 % â†’ IN_PAIN (-3), â‰¥75 % â†’ neither. Poll-driven so it
                // also clears the moment regen pushes HP back over the threshold; no separate
                // "on hurt" hook needed. Skips entirely on dead/empty-max entities so the
                // %-ratio math never divides by zero.
                float maxHp = getMaxHealth();
                if (maxHp > 0f) {
                    float hpRatio = getHealth() / maxHp;
                    boolean badly = hpRatio < 0.5f;
                    boolean inPain = !badly && hpRatio < 0.75f;
                    boolean hasBadly = thoughts.has(ThoughtKind.I_M_BADLY_INJURED, null);
                    boolean hasPain  = thoughts.has(ThoughtKind.I_M_IN_PAIN, null);
                    if (badly) {
                        if (!hasBadly) { thoughts.add(ThoughtKind.I_M_BADLY_INJURED, null, now, thoughtSl.random); recomputeHappiness(); }
                        if (hasPain)   { thoughts.remove(ThoughtKind.I_M_IN_PAIN, null); recomputeHappiness(); }
                    } else if (inPain) {
                        if (!hasPain)  { thoughts.add(ThoughtKind.I_M_IN_PAIN, null, now, thoughtSl.random); recomputeHappiness(); }
                        if (hasBadly)  { thoughts.remove(ThoughtKind.I_M_BADLY_INJURED, null); recomputeHappiness(); }
                    } else {
                        if (hasBadly)  { thoughts.remove(ThoughtKind.I_M_BADLY_INJURED, null); recomputeHappiness(); }
                        if (hasPain)   { thoughts.remove(ThoughtKind.I_M_IN_PAIN, null); recomputeHappiness(); }
                    }
                }
                // Poison (POISON_PLAN) â€” an expansion stamps the live poison stage on shared
                // persistent data (its poison attachment is server-only and can't be imported here).
                // Mirror it into the synced stage slot (renderer glyph) + the infinite POISONED
                // thought, in lockstep, exactly like the injury poll above. 0 = not poisoned.
                int poisonStage = getPersistentData().getInt(POISON_STAGE_TAG);
                setPoisonStage(poisonStage);
                boolean poisoned = poisonStage > 0;
                boolean hasPoisoned = thoughts.has(ThoughtKind.POISONED, null);
                if (poisoned && !hasPoisoned) {
                    thoughts.add(ThoughtKind.POISONED, null, now, thoughtSl.random);
                    recomputeHappiness();
                } else if (!poisoned && hasPoisoned) {
                    thoughts.remove(ThoughtKind.POISONED, null);
                    recomputeHappiness();
                }
                // Overhead idle/blocked cue: recompute the glanceable work-status verdict here (the
                // same logic the Job tab uses, factored into CitizenWorkStatus.derive) and sync a
                // single "blocked" boolean so the renderer can draw a red "!" above a worker that
                // can't work due to a problem. Only employed citizens can be blocked; unemployed
                // resolve to IDLE (NEUTRAL) so they never show the "!".
                Settlement statusS = getSettlement();
                boolean blocked = false;
                if (statusS != null) {
                    boolean anarchy = statusS.governmentType() == Settlement.Government.NONE;
                    CitizenWorkStatus ws = CitizenWorkStatus.derive(this, statusS, anarchy);
                    blocked = ws.category() == CitizenWorkStatus.Category.BLOCKED;
                }
                setWorkBlocked(blocked);
            }
            // Home auto-assignment every 100 ticks (5s) for citizens without a home. Picks the
            // nearest vacant home in the settlement. No-op when the citizen is already housed or
            // the settlement has no vacant homes.
            if (this.tickCount % 100 == 0) {
                tryAutoAssignHome();
            }
            // Hunter upkeep every 20 ticks: passive yield while no player is near (the activation
            // tier idles the real hunt AI) and the dusk teleport home from a far-out trip. Cheap
            // job-id check first so non-hunters pay nothing.
            if (this.tickCount % 20 == 0 && HunterWorkGoal.JOB_TYPE_ID.equals(getJobType())) {
                HunterOffscreenTicker.tick(this, thoughtSl);
            }
            // Forager upkeep every 20 ticks: passive yield while no player is near (the activation
            // tier idles the real roam/gather AI) and the dusk teleport home from a far-out band
            // trip â€” the forager's counterpart to the hunter's off-screen pass above.
            if (this.tickCount % 20 == 0 && ForagerWorkGoal.JOB_TYPE_ID.equals(getJobType())) {
                ForagerOffscreenTicker.tick(this, thoughtSl);
            }
            // Chunk-quality sampling every 100 ticks (5s) â†’ 240 samples/day. Look up the citizen's
            // current chunk beauty and bump the matching tier counter. Unloaded / unknown chunks
            // read as BLAND so they neither help nor hurt â€” beautyOf returns null in that case
            // (the chunk has no ChunkAppealData entry or its scan hasn't run yet), so we coalesce
            // here rather than at every call site.
            if (this.tickCount % 100 == 0) {
                long packed = new ChunkPos(this.blockPosition()).toLong();
                ChunkBeauty b = ChunkBeautyManager.beautyOf(thoughtSl, packed);
                if (b == null) b = ChunkBeauty.BLAND;
                chunkSamples[b.ordinal()]++;
            }
            // Starvation damage â€” 1 HP every 6 s (120 ticks) while STARVING is active. Using
            // tickCount (per-entity counter) staggers the damage across citizens so the whole
            // settlement doesn't take a synchronised hit on the same tick; vanilla's starve()
            // damage source gives the standard "Citizen starved to death" message + no armour
            // mitigation, matching the player starvation feel.
            if (this.tickCount % 120 == 0 && thoughts.has(ThoughtKind.STARVING, null)) {
                this.hurt(this.damageSources().starve(), 1.0f);
            }
            // Natural regen — heal while the settlement is fed (reserve not empty). Anarchy never
            // starves (consumption is 0), so early citizens still get the casual immigration-era
            // regen; once a government is enacted a starving town can't heal injuries away.
            if (this.tickCount % 140 == 0 && this.getHealth() < this.getMaxHealth()) {
                Settlement regenS = getSettlement();
                if (regenS != null && !regenS.isStarving()) {
                    this.heal(1.0f);
                }
            }
            // Random child happy-thought roll. Only fires on children; fires every minute with a
            // 20% chance, so over a 3-day childhood a child accumulates ~50â€“60 happy thought
            // hits â€” enough to keep them happy most of the time, sparse enough not to be noise.
            if (isChild() && this.tickCount % CHILD_THOUGHT_INTERVAL == 0
                && thoughtSl.random.nextDouble() < CHILD_THOUGHT_CHANCE) {
                ThoughtKind[] pool = ThoughtKind.CHILD_FLAVOUR_THOUGHTS;
                ThoughtKind kind = pool[thoughtSl.random.nextInt(pool.length)];
                thoughts.add(kind, null, now, thoughtSl.random);
                recomputeHappiness();
            }
            // Daily evaluation at dawn. Two paths:
            //   - Homed citizen â†’ evaluate home appeal (chunk samples are tracked but not applied).
            //   - Homeless citizen â†’ evaluate chunk-quality samples as before.
            // Either way, reset counters at the day boundary so a citizen who becomes homeless
            // mid-day starts fresh on the next dawn.
            long today = thoughtSl.getDayTime() / 24_000L;
            if (today != lastChunkEvalDay) {
                if (lastChunkEvalDay != -1L) {
                    Settlement evalS = getSettlement();
                    com.bannerbound.core.api.settlement.Home home = evalS != null
                        ? evalS.getHomeFor(this.getUUID()) : null;
                    if (home != null && home.valid()) {
                        evaluateDailyHomeQuality(thoughtSl, evalS, home, now);
                    } else {
                        evaluateDailyChunkQuality(thoughtSl, now);
                    }
                    // Step 12: dawn full-day strike. Only rolled when compliance â‰¤ 30 (the
                    // table returns 0 above that, but gating here keeps the RNG noise out
                    // of happy-citizen frames). On hit, drop a NO_WORK_TODAY thought that
                    // blocks WorkGoal for the full day; on miss, do nothing. Skipped entirely
                    // in anarchy â€” there compliance governs job-switch consent, not work
                    // refusal, and self-organized gatherers always work willingly.
                    if (!isAnarchy() && this.compliance <= 30) {
                        double strikeChance = com.bannerbound.core.api.settlement.ComplianceTables
                            .refuseFullDay(this.compliance);
                        if (strikeChance > 0 && thoughtSl.random.nextDouble() < strikeChance) {
                            thoughts.add(ThoughtKind.NO_WORK_TODAY, null, now, thoughtSl.random);
                            recomputeHappiness();
                        }
                    }
                    // Step 13 v2: coup checking moved to the settlement level â€” see
                    // SettlementManager.dawnCoupCheck, called from ImmigrationManager.tickAll
                    // on day boundaries. No per-citizen roll here anymore.
                }
                java.util.Arrays.fill(chunkSamples, 0);
                lastChunkEvalDay = today;
            }
        }

        // Stuck watchdog: if the pathfinder thinks it's heading somewhere but our position
        // hasn't actually moved for ~16s, teleport back to the town hall. Catches "fell in a
        // hole I can't jump out of" and "blocked by a wall the player built between me and my
        // tree" â€” both would otherwise lock the citizen forever.
        if (this.tickCount % STUCK_SAMPLE_INTERVAL == 0) {
            double dx = this.getX() - stuckLastX;
            double dy = this.getY() - stuckLastY;
            double dz = this.getZ() - stuckLastZ;
            boolean trying = !this.getNavigation().isDone();
            if (trying && (dx * dx + dy * dy + dz * dz) < STUCK_RADIUS_SQ) {
                stuckTicks += STUCK_SAMPLE_INTERVAL;
                if (stuckTicks >= STUCK_TICK_THRESHOLD) {
                    recallToTownHall();
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }
            stuckLastX = this.getX();
            stuckLastY = this.getY();
            stuckLastZ = this.getZ();
        }
    }

    /**
     * Reads the previous day's per-tier sample counts and adds the matching daily chunk-quality
     * thought if any of the four named brackets captured &gt; 75 % of the samples. Buckets:
     * <ul>
     *   <li><b>HATE</b> ({@code -25}) â€” atrocious + repulsive</li>
     *   <li><b>UNCOMFORTABLE</b> ({@code -10}) â€” disgusting + unappealing</li>
     *   <li><b>LIKE</b> ({@code +5}) â€” pleasant + attractive + stunning</li>
     *   <li><b>LOVE</b> ({@code +15}) â€” breathtaking</li>
     * </ul>
     * Bland samples count toward the total but match no bucket â€” a fully bland day adds no
     * thought. If nothing crosses 75 % the citizen also gets no thought (a mixed day is neutral
     * by design). The thought lasts one full day (24 000 ticks).
     */
    private void evaluateDailyChunkQuality(ServerLevel sl, long now) {
        int total = 0;
        for (int s : chunkSamples) total += s;
        if (total <= 0) return;
        int hate = chunkSamples[ChunkBeauty.ATROCIOUS.ordinal()]
                 + chunkSamples[ChunkBeauty.REPULSIVE.ordinal()];
        int uncomfortable = chunkSamples[ChunkBeauty.DISGUSTING.ordinal()]
                          + chunkSamples[ChunkBeauty.UNAPPEALING.ordinal()];
        int like = chunkSamples[ChunkBeauty.PLEASANT.ordinal()]
                 + chunkSamples[ChunkBeauty.ATTRACTIVE.ordinal()]
                 + chunkSamples[ChunkBeauty.STUNNING.ordinal()];
        int love = chunkSamples[ChunkBeauty.BREATHTAKING.ordinal()];
        // 75 % threshold using integer math: bucket * 4 > total * 3 iff bucket / total > 0.75.
        ThoughtKind kind = null;
        if (love * 4 > total * 3) kind = ThoughtKind.LOVE_HERE;
        else if (like * 4 > total * 3) kind = ThoughtKind.LIKE_HERE;
        else if (hate * 4 > total * 3) kind = ThoughtKind.HATE_HERE;
        else if (uncomfortable * 4 > total * 3) kind = ThoughtKind.UNCOMFORTABLE_HERE;
        if (kind == null) return;
        // Clear any of the other three daily buckets first so a citizen who moves from a
        // breathtaking spot to a disgusting one doesn't carry "I love it here!" alongside the
        // new "I feel uncomfortable here." â€” at most one daily-chunk thought is active.
        for (ThoughtKind k : new ThoughtKind[]{
            ThoughtKind.LOVE_HERE, ThoughtKind.LIKE_HERE,
            ThoughtKind.UNCOMFORTABLE_HERE, ThoughtKind.HATE_HERE}) {
            if (k != kind) thoughts.remove(k, null);
        }
        thoughts.add(kind, null, now, sl.random);
        recomputeHappiness();
    }

    /**
     * Home-based version of the daily evaluator. Reads the home's freshly recomputed appeal
     * (via {@link com.bannerbound.core.api.settlement.HouseAppealData#scoreOf}) and applies the
     * matching {@code *_HOME} thought. The chunk-quality buckets aren't touched on this path â€”
     * homed citizens deliberately ignore where their settlement happened to spawn.
     *
     * <p>Clears every other {@code *_HOME} thought first so only one is ever active. Also
     * clears any residual {@code *_HERE} thoughts that may have lingered from before the
     * citizen moved in.
     */
    private void evaluateDailyHomeQuality(ServerLevel sl, Settlement settlement,
                                           com.bannerbound.core.api.settlement.Home home, long now) {
        // Home happiness = appeal + met demands (computed in Homes.validate). Re-validate so a
        // same-day appeal/demand change is reflected at dawn, then read the combined value: this is
        // what unifies "pretty walls" and "stocked, lit, comfortable" into one mood signal â€” unmet
        // demands now make residents grumpy, not just ugliness.
        com.bannerbound.core.api.settlement.Homes.validate(sl, home);
        ThoughtKind kind = com.bannerbound.core.api.settlement.HomeDemand.moodThoughtFor(
            home.cachedHomeHappiness());
        // Always clear stale chunk *_HERE thoughts on this path â€” homed citizens shouldn't
        // display them, and a citizen who just moved in carries them until their first dawn.
        for (ThoughtKind k : new ThoughtKind[]{
            ThoughtKind.LOVE_HERE, ThoughtKind.LIKE_HERE,
            ThoughtKind.UNCOMFORTABLE_HERE, ThoughtKind.HATE_HERE}) {
            thoughts.remove(k, null);
        }
        // Clear all *_HOME thoughts first; we then re-add at most one. Same "at most one active"
        // invariant as the chunk path.
        for (ThoughtKind k : new ThoughtKind[]{
            ThoughtKind.LOVE_HOME, ThoughtKind.LIKE_HOME, ThoughtKind.NICE_HOME,
            ThoughtKind.UNCOMFORTABLE_HOME, ThoughtKind.HATE_HOME}) {
            thoughts.remove(k, null);
        }
        if (kind != null) thoughts.add(kind, null, now, sl.random);
        recomputeHappiness();
    }

    /** Auto-home assignment: scans the citizen's settlement for the nearest home with vacancy and
     *  self-registers as a resident. No-op if homed already, no settlement, or no vacant homes.
     *  Called every 100 ticks (5 s) from {@code aiStep}. */
    private void tryAutoAssignHome() {
        Settlement s = getSettlement();
        if (s == null) return;
        // Outpost workers LIVE at the outpost — they hold no settlement house. While the residence
        // is a LIVE working claim, evict any house (frees the bed for townsfolk) and never auto-home.
        // If the outpost was lost (residence no longer a working claim), self-heal: clear the stale
        // site and fall through to normal town homing.
        if (this.outpostSite != null) {
            if (s.workingClaims().contains(new ChunkPos(this.outpostSite).toLong())) {
                com.bannerbound.core.api.settlement.Home home = s.getHomeFor(this.getUUID());
                if (home != null && this.level() instanceof ServerLevel sl && sl.getServer() != null) {
                    home.removeResident(this.getUUID());
                    SettlementData.get(sl.getServer().overworld()).setDirty();
                }
                return;
            }
            this.outpostSite = null;   // outpost gone → resume settlement life
        }
        if (s.getHomeFor(this.getUUID()) != null) return; // already housed
        java.util.List<com.bannerbound.core.api.settlement.Home> options = s.homesWithVacancy();
        if (options.isEmpty()) return;
        com.bannerbound.core.api.settlement.Home nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (com.bannerbound.core.api.settlement.Home h : options) {
            double d2 = this.distanceToSqr(
                h.pos().getX() + 0.5, h.pos().getY() + 0.5, h.pos().getZ() + 0.5);
            if (d2 < bestSq) {
                bestSq = d2;
                nearest = h;
            }
        }
        if (nearest != null && nearest.addResident(this.getUUID())) {
            // Mark settlement dirty so the new resident persists. Also nudge the NO_HOME poll to
            // re-evaluate next tick by removing the thought immediately (cheap, idempotent).
            if (this.level() instanceof ServerLevel sl && sl.getServer() != null) {
                SettlementData.get(sl.getServer().overworld()).setDirty();
            }
            if (thoughts.remove(ThoughtKind.NO_HOME, null)) recomputeHappiness();
        }
    }

    /** Tag (gametime) marking a DELIBERATE server-side position jump. Antiquity's rope-fence clamp
     *  keeps every entity on the side of a rope it was on last tick, so an untagged jump that crosses
     *  a rope reads as an illegal crossing and gets shoved straight back — cancelling the teleport.
     *  The literal is mirrored in Antiquity's {@code RopeFenceCollision} (it can't import Core). */
    public static final String TELEPORT_AT_KEY = "BannerboundTeleportAt";

    /** Every intentional position jump of an ALREADY-EXISTING entity (teleportTo / moveTo-snap) must
     *  call this in the same tick, or a rope fence between the old and new spot bounces it back.
     *  Freshly spawned entities don't need it — they have no previous-side record yet. */
    public static void tagDeliberateTeleport(net.minecraft.world.entity.Entity e) {
        e.getPersistentData().putLong(TELEPORT_AT_KEY, e.level().getGameTime());
    }

    /**
     * Teleports the citizen to (or near) their settlement's town hall. No-op if the citizen has
     * no settlement, no town hall set, or isn't on the server. Used by the stuck watchdog and by
     * the town hall "Recall" button. Stops navigation so they don't immediately wander back into
     * whatever was stuck.
     */
    public void recallToTownHall() {
        Settlement s = getSettlement();
        if (s == null || s.townHallPos() == null) return;
        if (!(this.level() instanceof ServerLevel sl)) return;
        BlockPos th = s.townHallPos();
        BlockPos landing = findRecallLandingPos(sl, th);
        this.getNavigation().stop();
        this.teleportTo(sl, landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5,
            java.util.Set.of(), this.getYRot(), this.getXRot());
        tagDeliberateTeleport(this);
        // Reset stuck samples so we don't re-trigger immediately if the destination is also funky.
        this.stuckLastX = this.getX();
        this.stuckLastY = this.getY();
        this.stuckLastZ = this.getZ();
        this.stuckTicks = 0;
    }

    /** Cooldown (in game ticks) between two "cannot reach" broadcasts from the same citizen.
     *  60s prevents one citizen looping on an unreachable selection from spamming the chat. */
    private static final int CANNOT_REACH_COOLDOWN_TICKS = 1200;
    /** Last tick this citizen broadcast a "cannot reach" message. */
    private long cannotReachLastTick = Long.MIN_VALUE / 2;

    /**
     * Tells every online member of this citizen's settlement that they've abandoned a work
     * target â€” typically because the pathfinder couldn't reach it. Throttled per-citizen so a
     * looping abandon doesn't spam the chat. No-op if the citizen has no settlement, no online
     * members are around, or the cooldown hasn't elapsed.
     */
    public void broadcastCannotReach(BlockPos target) {
        if (target == null) return;
        if (!(this.level() instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        if (now - cannotReachLastTick < CANNOT_REACH_COOLDOWN_TICKS) return;
        cannotReachLastTick = now;

        Settlement s = getSettlement();
        if (s == null) return;
        MinecraftServer server = sl.getServer();
        if (server == null) return;

        Component name = this.getDisplayName();
        Component msg = Component.translatable("bannerbound.citizen.cannot_reach",
            name, target.getX(), target.getY(), target.getZ())
            .withStyle(net.minecraft.ChatFormatting.YELLOW);
        for (UUID memberId : s.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(memberId);
            if (p != null) p.sendSystemMessage(msg);
        }
    }

    /**
     * Picks a tile next to the town hall (campfire) that's safe to stand on: not the campfire
     * itself (we'd take damage), air with solid ground below, ideally walkable. Scans the 8
     * neighbors at the campfire's Y first, then the same neighbors at +1Y in case the campfire
     * sits in a depression. Falls back to the campfire-above position only if nothing better
     * exists â€” better to spawn on top than to crash.
     */
    private BlockPos findRecallLandingPos(ServerLevel sl, BlockPos townHall) {
        int[] dxz = {-1, 0, 1};
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx : dxz) {
                for (int dz : dxz) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos candidate = new BlockPos(townHall.getX() + dx, townHall.getY() + dy, townHall.getZ() + dz);
                    if (sl.getBlockState(candidate).isAir()
                            && sl.getBlockState(candidate.above()).isAir()
                            && !sl.getBlockState(candidate.below()).isAir()) {
                        return candidate;
                    }
                }
            }
        }
        return townHall.above();
    }

    private void recomputeSpeedModifier() {
        net.minecraft.world.entity.ai.attributes.AttributeInstance attr =
            this.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (attr == null) return;
        // Normalize the base value: citizens loaded from saves made with an older attribute
        // supplier (e.g. base 0.6 or 0.4) would otherwise keep their stale base and report
        // different effective speeds than freshly-spawned citizens. Force the canonical base
        // here so research bonuses land on the same baseline for everyone.
        if (Math.abs(attr.getBaseValue() - BASE_MOVEMENT_SPEED) > 1e-6) {
            attr.setBaseValue(BASE_MOVEMENT_SPEED);
        }
        // Also clears any stale *permanent* modifier from an earlier mod version that used
        // addPermanentModifier â€” old saves carried that into NBT and it survived load.
        attr.removeModifier(SPEED_MODIFIER_ID);

        double additive = 0.0; // flat add to base speed
        double multiplied = 0.0; // % of base on top
        Settlement s = getSettlement();
        if (s != null) {
            additive += s.bonusCitizenSpeed();
            additive += s.faithEffects().citizenSpeed(); // JOURNEY/WAR god passives
            // Dirt-path tile boost now belongs to the Roads policy (was the PAVING research's
            // 30%). Roads is unlocked by a culture node that requires the PAVING science node,
            // so the cross-tree prereq still gates it â€” but the speed only applies while the
            // policy is actively confirmed.
            if (s.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.ROADS)) {
                BlockPos below = this.blockPosition().below();
                if (this.level().getBlockState(below).is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) {
                    multiplied += com.bannerbound.core.api.settlement.PolicyEffects.ROADS_SPEED_BONUS;
                }
            }
        }
        // Mood: a happy citizen moves with a spring in their step, a miserable one drags their feet.
        // Green +15%, red -10% (yellow neutral). Movement uses its OWN multiplier so the walk
        // penalty stays gentle while work/XP keep the harsher -30% red penalty.
        multiplied += happinessSpeedMultiplier() - 1.0f;
        double total = additive + (attr.getBaseValue() * multiplied);
        if (Math.abs(total) > 1e-6) {
            // Transient (not persisted) â€” we recompute every 10 ticks anyway, and keeping it
            // out of NBT avoids stale modifiers from older mod versions surviving across loads.
            attr.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                SPEED_MODIFIER_ID, total,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        }
    }
}
