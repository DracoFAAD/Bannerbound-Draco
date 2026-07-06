package com.bannerbound.core.api.settlement;

import com.bannerbound.core.api.research.ResearchDefinition;
import com.bannerbound.core.api.research.data.CultureTreeLoader;
import com.bannerbound.core.api.research.data.ResearchTreeLoader;
import com.bannerbound.core.social.Conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

/**
 * A single bannerbound's per-settlement state â€” its identity, members, claimed chunks, town
 * hall position, tablet bookkeeping, era, and complete research tree progress (completed +
 * progress map + active research + queue + science rate).
 * <p>
 * Server-owned. Persisted via {@link SettlementData}'s NBT (this class's {@link #save()} /
 * {@link #load(net.minecraft.nbt.CompoundTag)} handle the per-settlement subset). When fields
 * here are added, also update both methods AND the StreamCodec of any payload that ships them
 * to clients (typically {@link com.bannerbound.core.network.ResearchStateSyncPayload}).
 */
public final class Settlement {
    public static final double DEFAULT_SCIENCE_PER_SECOND = 0.1;
    /** Science per second each citizen contributes on top of the base rate. */
    public static final double SCIENCE_PER_POPULATION = 0.03;

    public static final double DEFAULT_FOOD_PER_SECOND = 0.1;
    public static final double DEFAULT_CULTURE_PER_SECOND = 0.05;
    public static final double BASE_IMMIGRATION_FOOD_COST = 5.0;
    public static final double BASE_IMMIGRATION_CULTURE_COST = 50.0;

    private final UUID id;
    /** Capital / settlement name. Until multi-settlement factions arrive, this is also what
     *  the player types on the founding screen. */
    private final String name;
    /** Stable seed for this settlement's generated language. */
    private final long languageSeed;
    /** Political identity name. Defaults to {@link #name}; Medieval+ systems may later let this
     *  diverge from the capital/settlement name without changing town-level data. */
    private String factionName;
    private final SettlementColor color;
    private final UUID owner;
    private final Set<UUID> members;
    private final Set<Long> claimedChunks;
    /** Outpost working claims â€” inline-initialized (not a constructor param) so the two big
     *  constructors stay untouched; loaded post-construction like {@code currentToolAge}. */
    private final Set<Long> workingClaims = new LinkedHashSet<>();
    /** Working-claim chunk (packed) â†’ the faction banner block that established that outpost.
     *  Recorded when an outpost is established, cleared when the claim drops. Lets the once-a-second
     *  banner sweep catch an outpost banner removed with NO break event (explosion, piston,
     *  {@code /setblock}) â€” a vanilla banner only fires a break event on player breaks, so without
     *  this the loud "outpost fallen" alarm would miss those removals. Inline field like
     *  {@code workingClaims}; loaded post-construction. */
    private final java.util.Map<Long, BlockPos> outpostBanners = new java.util.LinkedHashMap<>();
    private BlockPos townHallPos;
    /** The settlement's FACTION BANNER â€” the block the settlement is bound to (the mod's name).
     *  Auto-generated beside the campfire at founding; freely relocatable by members. While it's
     *  null (taken down / destroyed) the town hall menu refuses to open and ALL citizen labor
     *  halts (see WorkGoal) â€” and, later, a fallen banner is the conquest condition. Inline
     *  field like {@code workingClaims} so the two big constructors stay untouched. */
    private BlockPos bannerPos;
    /** Heraldry banner design: pattern layers over the faction base color, bottom-up â€” the
     *  order vanilla banners stack them. Empty until edited in the banner editor (Heraldry
     *  culture research). Each layer occupies one earned Heraldry point while it exists.
     *  Inline field like {@code workingClaims}; loaded post-construction. */
    private final List<BannerLayer> bannerDesign = new ArrayList<>();
    /** Banner-driven identity: every dye holding â‰¥5% of the faction banner, most-present
     *  first â€” AS MANY colors as the design actually has (1..n). Recomputed on every design
     *  save (see {@link FactionBanner#identityDyes}). Empty = no design yet â†’ fall back to
     *  the founding color's dye. The founding {@link #color} stays the unique server slot
     *  underneath. Stored as DyeColor ids. */
    private final List<Integer> identityDyeIds = new ArrayList<>();
    private int tabletsIssued;

    /** One Heraldry design layer: a vanilla banner-pattern registry id (e.g.
     *  "minecraft:stripe_top") + a {@code DyeColor} id. Stored as plain data, resolved to
     *  registry holders only when applied (FactionBanner) so Settlement stays level-free. */
    public record BannerLayer(String patternId, int colorId) {}
    private Era age;

    private final Set<String> completedResearches;
    private final Set<String> knownItems;

    private final Map<String, Double> researchProgress;
    /** Insight keys are tree-qualified ("science|namespace:id") to avoid cross-tree collisions. */
    private final Map<String, Integer> insightCounters = new HashMap<>();
    private final Set<String> firedInsights = new HashSet<>();
    private final List<String> researchQueue;
    private String activeResearch;
    private double sciencePerSecond;

    // â”€â”€â”€ Culture research (Step 8) â€” twin of the science fields above â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Same shape, separate state so the Culture tree progresses independently. Mutual
    // exclusion (one active research total across both trees) is enforced in the managers'
    // tryStart paths, not by these fields.
    private final Set<String> completedCultureResearches = new HashSet<>();
    private final Map<String, Double> cultureResearchProgress = new HashMap<>();
    private final List<String> cultureResearchQueue = new ArrayList<>();
    private String activeCultureResearch = null;

    private final List<Citizen> citizens;
    /** In-progress conversations between citizens. Transient â€” never persisted: a save mid-chat
     *  is dropped on load, citizens fall back to patrolling, and a fresh conversation may spawn.
     *  Both participating {@code ConversationGoal}s look up their shared {@link Conversation}
     *  here via {@link #findActiveConversationFor(UUID)}. */
    private final transient List<Conversation> activeConversations = new ArrayList<>();
    private double foodPerSecond;
    private double culturePerSecond;
    private double foodStored;
    private double cultureStored;
    /** Transient food stat fields, kept for the town-hall stats/payload. {@code storedFoodValue} is a
     *  descriptive "how much food is stored" readout (optional pantry scan); {@code storedFoodPerSecond}
     *  is the per-source production rate. Neither drives consumption — food is per-citizen now. */
    private transient double storedFoodValue = 0.0;
    private transient double storedFoodPerSecond = 0.0;
    private double bonusFoodCapacity;
    private double bonusCultureCapacity;
    private double bonusCitizenSpeed;
    /** Id of the current tool age (e.g. "stone"). Empty when no age has been researched. */
    private String currentToolAge = "";
    /** Total chunk-claim expansions this settlement has ever performed. <b>Never reset</b> â€” each
     *  era adds its own allowance on top of whatever is left unused from earlier eras, so the
     *  cumulative cap is the sum of every reached era's {@code maxExpansions} (see
     *  {@code TerritoryService}). Leftover earlier-era expansions are consumed first and still
     *  cost that era's prices. */
    private int expansionsUsed = 0;
    /** Hard ceiling on citizens that arrive via the food/culture immigration accumulator. Reaching
     *  this number locks {@code ImmigrationManager} out for the rest of the settlement's life;
     *  further population growth comes only from births (procreation loop). Decoupled from
     *  {@link #populationMaximum} on purpose â€” the user's design says spare beds let citizens
     *  <i>reproduce</i>, they don't let new immigrants arrive. */
    /**
     * Ancient-era immigration floor â€” kept as a public constant so client-side defaults
     * ({@code ClientPopulationState}, fresh-settlement broadcasts) have a sensible starting
     * value before the server's first {@link ImmigrationManager#broadcastState} arrives.
     *
     * <p><b>This is no longer a hard lifetime cap.</b> The gate that actually drives immigration
     * is {@link #immigrationFloor()} (era-keyed) compared against {@link #population()} â€” so a
     * settlement whose citizens all died drops below the floor and immigrates again instead of
     * soft-locking. The constant is just the Antiquity numeric value; later eras read their own
     * floor through {@link Era#immigrationFloor()}.
     */
    public static final int IMMIGRATION_CAP = 7;
    /** Lifetime count of immigrants. Incremented exactly once per successful
     *  {@code ImmigrationManager.spawnNewCitizen}; never decremented (deaths and births don't
     *  open immigration back up). Persisted as {@code "ImmigratedCount"} â€” old saves load with
     *  {@code population()} as a sane default so existing settlements cap immediately rather
     *  than getting 7 free immigrants on the next world boot. */
    private int immigratedCount = 0;
    /** Highest concurrent population this settlement has ever held â€” the "full tribe" high-water mark.
     *  Only ever increases. Persisted as {@code "PeakPopulation"}; old saves default to the current roster
     *  size on load. Anchors {@link #targetFoodConsumptionPerSecond()} so the starvation crisis keeps
     *  demanding food for the tribe the settlement actually grew into, not for whoever survives a die-off. */
    private int peakPopulation = 0;
    /** Culture styles chosen for this settlement â€” they layer block-appeal overrides onto the base
     *  table (see {@code AppealResolver}). One style is picked at founding; the list form leaves
     *  room for the future culture menu to combine several. */
    private final List<String> cultureStyles = new ArrayList<>();
    /** Transient: members who have voted YES to disband during the active disband vote. Empty
     *  when no vote is in progress. Not persisted â€” vote state should not survive a restart. */
    private final Set<UUID> disbandVotes = new HashSet<>();
    /** Wall-clock ms when the current disband vote started, or -1 if no active vote. Used for
     *  the 3-minute expiry check. Transient â€” not persisted. */
    private long disbandVoteStartedMs = -1L;

    /**
     * Political form. {@link #NONE} is the anarchy state: workstation assignments are
     * rejected and citizens freelance-gather instead of working assigned jobs. The other two
     * are chosen via a settlement vote once the Code of Laws prompt fires (see
     * {@link #codeOfLawsPromptShown}). {@link #COUNCIL} = every member is an equal leader;
     * {@link #CHIEFDOM} = one elected player ({@link #chiefPlayerId}) holds exclusive
     * leadership and gets the crown glyph in their nametag.
     */
    public enum Government { NONE, COUNCIL, CHIEFDOM }

    /** Current government â€” never null; defaults to NONE (anarchy). Persisted as
     *  {@code "Government"} ordinal. Old saves load as NONE. */
    private Government governmentType = Government.NONE;
    /** Elected Chief's player UUID under CHIEFDOM, else {@code null}. Persisted as
     *  {@code "ChiefPlayer"}. Used by leader-channel resentment + Chief-only menu gates. */
    private UUID chiefPlayerId = null;
    /** Game tick at which the current chief was seated (see {@code SettlementManager.enactChief});
     *  {@code -1} when there's no chief. Persisted as {@code "ChiefSinceTick"}. Drives the Step-
     *  Down cooldown â€” a freshly-elected chief can't resign for {@code
     *  SettlementManager.CHIEF_STEP_DOWN_COOLDOWN_TICKS} (anti-cheese). */
    private long chiefSinceTick = -1L;
    /** One-shot flag: true after the "It is time to enact the code of laws." chat broadcast
     *  has fired. Prevents re-broadcasting on world reload or after a population dip + refill.
     *  Persisted as {@code "CodeOfLawsPromptShown"}. */
    private boolean codeOfLawsPromptShown = false;
    /** Highest {@link SettlementStage} this settlement has announced (chat + fireworks). Used to fire
     *  the stage-up celebration exactly once when growth crosses a new stage. Persisted as
     *  {@code "AnnouncedStage"} (ordinal). */
    private SettlementStage lastAnnouncedStage = SettlementStage.HEARTH;
    /** Transient: per-voter pick for the Choose-Government vote. Keyed by voting player UUID,
     *  value = the option they picked. Empty when no vote is in progress. NOT persisted â€”
     *  mid-vote saves drop the tally (same model as {@link #disbandVotes}). */
    private final Map<UUID, Government> governmentVotes = new HashMap<>();
    /** Wall-clock ms when the current Choose-Government vote started, or -1 if no active
     *  vote. Used for the 5-minute expiry check. Transient. */
    private long governmentVoteStartedMs = -1L;

    // â”€â”€ Faith (FAITH_PLAN.md) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** The faith this settlement follows, or {@code null}. The {@link
     *  com.bannerbound.core.api.faith.Faith} itself lives in FaithData (cross-faction);
     *  membership is mirrored on both sides. Persisted as {@code "FaithId"}. */
    private UUID faithId = null;
    /** Devotion stockpile â€” the third meter after food/culture. The RATE is derived each
     *  tick from believers (population), never stored. Persisted as {@code "DevotionStored"}. */
    private double devotionStored = 0.0;
    /** Set by the Spiritualism culture feature ({@code bannerbound.unlock_faith_founding}).
     *  The founding window is open while {@code faithFoundingUnlocked && faithId == null}.
     *  Persisted as {@code "FaithFoundingUnlocked"}. */
    private boolean faithFoundingUnlocked = false;
    /** Transient: per-voter pick for the Choose-Faith vote. Value = option key:
     *  {@code "found:ASTROLOGY"}, {@code "found:TOTEMIC"} or {@code "adopt:<faithUuid>"}.
     *  Same drop-on-reload model as {@link #governmentVotes}. LINKED â€” iteration order is
     *  cast order, so "earliest proposal names the faith" is well-defined. */
    private final Map<UUID, String> faithVotes = new java.util.LinkedHashMap<>();
    /** Transient: proposed faith name per voter â€” only meaningful for {@code found:*} picks;
     *  the winning option's earliest proposal names the faith. */
    private final Map<UUID, String> faithNameProposals = new java.util.LinkedHashMap<>();
    /** Game time before which a settlement that abandoned its faith cannot choose another
     *  (FAITH_PLAN: apostasy cooldown â€” flip-flopping must never be optimal). Persisted as
     *  {@code "FaithRejoinAfterGameTime"}. */
    private long faithRejoinAfterGameTime = 0L;
    /** Transient: members who clicked Abandon Faith under COUNCIL (yes-only vote; closing
     *  the screen = abstain). Same drop-on-reload model as {@link #governmentVotes}. */
    private final java.util.Set<UUID> abandonFaithVotes = new HashSet<>();

    public long faithRejoinAfterGameTime() {
        return faithRejoinAfterGameTime;
    }

    public void setFaithRejoinAfterGameTime(long gameTime) {
        this.faithRejoinAfterGameTime = gameTime;
    }

    public java.util.Set<UUID> abandonFaithVotes() {
        return abandonFaithVotes;
    }

    /** Transient: the faith's pantheon passives applied to THIS settlement. Never
     *  persisted â€” {@link com.bannerbound.core.api.faith.FaithManager} recomputes it from
     *  the live (shared) pantheon every second and on every pantheon change. */
    private final transient com.bannerbound.core.api.faith.FaithEffectBundle faithEffects =
        new com.bannerbound.core.api.faith.FaithEffectBundle();

    public com.bannerbound.core.api.faith.FaithEffectBundle faithEffects() {
        return faithEffects;
    }

    public UUID faithId() {
        return faithId;
    }

    public void setFaithId(UUID faithId) {
        this.faithId = faithId;
    }

    public boolean hasFaith() {
        return faithId != null;
    }

    public double devotionStored() {
        return devotionStored;
    }

    public void setDevotionStored(double devotionStored) {
        this.devotionStored = Math.max(0.0, devotionStored);
    }

    public boolean faithFoundingUnlocked() {
        return faithFoundingUnlocked;
    }

    public void setFaithFoundingUnlocked(boolean unlocked) {
        this.faithFoundingUnlocked = unlocked;
    }

    /** The Choose-Faith window: Spiritualism researched, no faith adopted yet. */
    public boolean faithChoiceWindowOpen() {
        return faithFoundingUnlocked && faithId == null;
    }

    public Map<UUID, String> faithVotes() {
        return java.util.Collections.unmodifiableMap(faithVotes);
    }

    public void castFaithVote(UUID player, String optionKey, String proposedName) {
        faithVotes.put(player, optionKey);
        if (proposedName != null && !proposedName.isBlank()) {
            faithNameProposals.put(player, proposedName.trim());
        }
    }

    public void clearFaithVote() {
        faithVotes.clear();
        faithNameProposals.clear();
        abandonFaithVotes.clear();
    }

    public int faithVoteCountFor(String optionKey) {
        int n = 0;
        for (String pick : faithVotes.values()) {
            if (pick.equals(optionKey)) n++;
        }
        return n;
    }

    /** The winning option's name: earliest proposal among its voters, or {@code null}. */
    public String faithNameProposalFor(String optionKey) {
        for (Map.Entry<UUID, String> vote : faithVotes.entrySet()) {
            if (vote.getValue().equals(optionKey)) {
                String name = faithNameProposals.get(vote.getKey());
                if (name != null && !name.isBlank()) return name;
            }
        }
        return null;
    }

    /** Transient: per-voter pick for the Chief-election sub-vote that fires after CHIEFDOM is
     *  selected. Keyed by voting player UUID, value = the candidate player UUID they picked.
     *  Empty when no election is in progress. NOT persisted â€” election restarts on reload
     *  (covered by {@link #chiefdomElectionWindowOpen()} re-opening when chiefPlayerId is
     *  still null on load). */
    private final Map<UUID, UUID> chiefNominations = new HashMap<>();
    /** Wall-clock ms when the current Chief election started, or -1 if no active election.
     *  5-minute expiry, same shape as the choose-government vote. Transient. */
    private long chiefElectionStartedMs = -1L;
    /** Transient: chief winner waiting to be enacted after the tribe-vote reveal animation
     *  completes. {@code null} = no pending enactment; otherwise the {@code chiefPlayerId}
     *  to install at {@link #pendingChiefEnactTick}. */
    private UUID pendingChiefId = null;
    /** Game-tick on which the pending chief installation should fire â€” checked each tick by
     *  {@code ImmigrationManager.tickAll}. */
    private long pendingChiefEnactTick = 0L;
    /** Transient: government type waiting to be enacted after the Choose-Government tie-break
     *  tribe-vote reveal completes. {@code null} = no pending enactment. Mirrors the chief
     *  pending pair above; both share {@link com.bannerbound.core.api.settlement.ImmigrationManager#tickAll}
     *  as their drainage tick. */
    private Government pendingGovernmentType = null;
    /** Game-tick on which the pending government enactment should fire. */
    private long pendingGovernmentEnactTick = 0L;
    /** Transient: a tribe-backed (Opinionated Crowd) disband whose confirming citizen reveal is
     *  animating; the actual dissolution fires at {@link #pendingDisbandEnactTick}. Scheduled so
     *  the settlement doesn't dissolve the instant the reveal opens (which would close it). */
    private boolean pendingDisband = false;
    private long pendingDisbandEnactTick = 0L;

    /** Transient (lost on restart): per-research-id suggesters from non-chief members of a
     *  Chiefdom. LinkedHashSet preserves click order so the badge face row reads in the
     *  order suggesters arrived. Keyed by research id; cleared whenever the chief enacts
     *  that research OR the suggester left-clicks the same node again to retract. */
    private final transient java.util.Map<String, java.util.LinkedHashSet<UUID>> scienceSuggestions = new java.util.HashMap<>();
    private final transient java.util.Map<String, java.util.LinkedHashSet<UUID>> cultureSuggestions = new java.util.HashMap<>();
    /** Transient per-chunk Council vote map: chunk â†’ (voter â†’ wall-clock ms cast time). Used
     *  for both the threshold check (count entries) and the 5-min auto-expiry sweep. */
    private final transient java.util.Map<Long, java.util.LinkedHashMap<UUID, Long>> expansionVotes = new java.util.HashMap<>();
    /** Transient per-chunk Chiefdom suggestion map: chunk â†’ suggesters in click order. Same
     *  shape as the research-suggestion maps above. Cleared when the chief claims the chunk. */
    private final transient java.util.Map<Long, java.util.LinkedHashSet<UUID>> expansionSuggestions = new java.util.HashMap<>();
    /** Chiefdom non-chief exile suggestions: citizen UUID â†’ suggesters in click order (same shape
     *  as {@link #scienceSuggestions}). Transient. Cleared when the chief exiles or ignores. */
    private final transient java.util.Map<UUID, java.util.LinkedHashSet<UUID>> exileSuggestions = new java.util.HashMap<>();
    /** Chiefdom non-chief registration-tablet suggesters (a tablet has no per-id key â€” one shared
     *  request). Transient. Cleared when the chief issues a tablet or ignores. */
    private final transient java.util.LinkedHashSet<UUID> tabletSuggestions = new java.util.LinkedHashSet<>();

    // â”€â”€â”€ Policies â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** Confirmed active policies, by id. NBT-persisted ({@code "ActivePolicies"}). Capacity is
     *  {@link Era#activePolicySlots()} for the settlement's current era. */
    private final List<String> activePolicies = new ArrayList<>();
    /** The policy change currently proposed and awaiting confirmation (Council vote) or the
     *  chief's direct confirm. Transient â€” a save mid-proposal drops it, players re-propose. */
    private transient PolicyChange pendingPolicyChange = null;
    /** Council confirm-vote tally for {@link #pendingPolicyChange}: voter â†’ agree/disagree.
     *  Transient. Resolved when every online member has voted (>50% Agree enacts). */
    private final transient java.util.Map<UUID, Boolean> policyConfirmVotes = new java.util.HashMap<>();
    /** Chiefdom non-chief policy suggestions: policy id â†’ suggesters in click order. Same
     *  shape as {@link #scienceSuggestions}. Transient. */
    private final transient java.util.Map<String, java.util.LinkedHashSet<UUID>> policySuggestions = new java.util.HashMap<>();
    /** Game-tick at which the Opinionated-Crowd bonus (+25 compliance / -10 resentment)
     *  expires. 0 = no active bonus. NBT-persisted ({@code "PolicyOpinionatedBonusExpiry"}) so
     *  the timer survives reload. */
    private long policyOpinionatedBonusExpiry = 0L;

    /** A proposed delta to the active-policy set: add a policy into {@code slotIndex}, and/or
     *  remove an existing one. Either id may be null (pure add, pure remove). */
    public record PolicyChange(int slotIndex, String addPolicyId, String removePolicyId) {}

    // â”€â”€â”€ Labor priorities (settlement-wide gatherer-job allocation) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** Player-set priority ORDER of gatherer job ids (forester/fisher/forager). Higher in the list =
     *  more workers (linear weights). May be empty/partial; unlocked jobs not listed fall in after
     *  the listed ones in their default order. NBT-persisted ({@code "LaborPriority"}). */
    private final List<String> laborPriority = new ArrayList<>();
    /** Gatherer job ids the player has switched OFF (zero workers). NBT-persisted ({@code "LaborDisabled"}). */
    private final java.util.Set<String> laborDisabled = new java.util.HashSet<>();
    /** Whether the settlement auto-distributes citizens across gatherer jobs. Effectively always on in
     *  anarchy; a council member / chief may switch it off under a government to assign manually.
     *  Default true. NBT-persisted ({@code "LaborAutoAssign"}). */
    private boolean laborAutoAssign = true;
    /** Per-job worker CAP, keyed by gatherer job id. {@code -1} (or an absent entry) means "no limit" â€”
     *  the weighted distribution staffs the job freely; a value {@code >= 0} caps that job at N workers
     *  and the surplus cascades to other unlocked gatherers. Only settable under a government; anarchy
     *  leaves every job uncapped. NBT-persisted ({@code "LaborCaps"}). */
    private final Map<String, Integer> laborCaps = new HashMap<>();
    /** Settlement-wide DEFAULT depot (a chest / Antiquity basket / Stockpile block) that gatherers
     *  without their own marked drop-off use for both depositing output AND remote tool pickup. Set in
     *  the Town Hall Labor tab (government only). {@code null} = none set. NBT-persisted ("PreferredStorage"). */
    private BlockPos preferredStoragePos = null;

    // â”€â”€â”€ Palettes (culture appeal bundles â€” mirror the Policies state above) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** Confirmed active palettes, by id. NBT-persisted ({@code "ActivePalettes"}). Capacity is
     *  {@link Era#activePaletteSlots()} for the settlement's current era. While active, a palette
     *  ADDS its per-block appeal bonus on top of the resolved appeal (see {@link AppealResolver}). */
    private final List<String> activePalettes = new ArrayList<>();
    /** Pending palette change awaiting a Council vote / chief confirm. Transient (twin of
     *  {@link #pendingPolicyChange}). */
    private transient PaletteChange pendingPaletteChange = null;
    /** Council confirm-vote tally for {@link #pendingPaletteChange}. Transient. */
    private final transient java.util.Map<UUID, Boolean> paletteConfirmVotes = new java.util.HashMap<>();
    /** Chiefdom non-chief palette suggestions: palette id â†’ suggesters in click order. Transient. */
    private final transient java.util.Map<String, java.util.LinkedHashSet<UUID>> paletteSuggestions = new java.util.HashMap<>();

    /** A proposed delta to the active-palette set â€” twin of {@link PolicyChange}. */
    public record PaletteChange(int slotIndex, String addPaletteId, String removePaletteId) {}
    /** Step 13 v2: last in-game day on which the settlement-level dawn coup check ran.
     *  Compared against {@code getDayTime() / 24000L} to detect day boundaries. Transient
     *  because a save/load mid-day shouldn't double-fire the check on reload. */
    private transient long lastCoupCheckDay = -1L;
    /** Last in-game day on which the dusk pre-warning pass ran (impending coup / strikes).
     *  Transient, same rationale as {@link #lastCoupCheckDay}. */
    private transient long lastDuskWarnDay = -1L;
    /** Last in-game hour ({@code gameTime / 1000}) the policy upkeep tick ran. Transient â€” a
     *  reload just re-runs the upkeep on the next hour boundary. */
    private transient long lastPolicyHour = -1L;
    /** Step 13 v2: when the coup CONDITION is met but the player count is too low for the
     *  tribe vote to have anyone to elect (i.e. singleplayer Chiefdom), this flag stays
     *  raised and the per-citizen compliance tick doubles its erosion rate so the player
     *  still feels the pressure. Cleared when the condition drops. */
    private transient boolean coupSuppressed = false;
    /** Step 15: temporary stand-in chief while the real {@link #chiefPlayerId} is offline.
     *  Recomputed by {@code SettlementManager.recomputeRegent} on login/logout events and
     *  hourly. Routine-acting authority only â€” {@link #canActWeighty} still requires the
     *  actual seated Chief. Transient because regency is derived purely from presence;
     *  re-evaluating on every load keeps the algorithm authoritative. */
    private transient UUID regentPlayerId = null;
    private final Map<Long, Workstation> workstations;
    /** Registered homes in the settlement, keyed by home id. Homes have no anchor block now (they
     *  are defined purely by the Housing Orders rod's HOME selections), so â€” like {@link #workshops}
     *  â€” they key by id rather than pos; their box geometry lives in {@code BlockSelectionRegistry}
     *  (kind HOME). */
    private final Map<UUID, Home> homes = new HashMap<>();
    /** Registered stockpiles (Stockpile Blocks) in the settlement, keyed by {@code pos.asLong()}.
     *  Parallel to {@link #homes} â€” community storage rather than residence. */
    private final Map<Long, Stockpile> stockpiles = new HashMap<>();
    /** Crafter workshops (Workshop Orders rod selections), keyed by workshop id. Workshops have
     *  no anchor block, so unlike homes/stockpiles they key by id rather than pos; their box
     *  geometry lives in {@code BlockSelectionRegistry} (kind WORKSHOP). See CRAFTER_PLAN.md. */
    private final Map<UUID, Workshop> workshops = new HashMap<>();
    /** Timed status effects active on the settlement. Driven by gameplay events (e.g. a fisher
     *  catching a fish appends a +0.03 food/s effect). Iterated each tick to decrement remaining
     *  time; expired entries are removed in-place. Order is insertion order â€” new effects are
     *  appended at the end, which matches the Statuses-tab "newest at bottom" rendering. */
    private final List<StatusEffect> statusEffects = new ArrayList<>();
    /** Generic journal entries scoped to this settlement. Crises publish here, tutorials can
     *  publish to player-scoped data instead. */
    private final Map<UUID, com.bannerbound.core.journal.JournalEntry> journalEntries = new LinkedHashMap<>();
    /** Passive food source rates refreshed by subsystem scans (farms, livestock). */
    private final Map<String, Double> passiveFoodSourceRates = new HashMap<>();
    /** Timed worker-production pulses (fishers, hunters, foragers) so crisis objectives can ask
     *  for a surplus by source, not just a global food/sec number. */
    private final List<FoodSourcePulse> foodSourcePulses = new ArrayList<>();
    /** Lifetime food-stuff produced per source ("farming", "fishing", "livestock", â€¦), credited at
     *  harvest/catch/cull. A statistic only (NOT net food); powers crisis "produce from your source"
     *  objectives and town-hall stats. See {@link #addFoodProduced}. */
    private final Map<String, Double> foodProducedBySource = new HashMap<>();
    /** Off-screen ore accrued PER OUTPOST (chunk-pos-as-string → ore count), dead-reckoned by
     *  {@code GhostStockerManager} while that outpost is UNLOADED (so it never double-counts the real
     *  loaded miner). A ghost stocker collects this on a round trip and delivers it to the stockpile;
     *  the produced item is derived from the chunk. Persisted so a long absence never loses the yield. */
    private final Map<String, Integer> outpostAccrued = new HashMap<>();
    /** Per-source production RATE (food-stuff/sec, smoothed) and the last cumulative snapshot used to
     *  derive it. Transient â€” rebuilt at runtime by {@link #tickFoodEconomyStats}. */
    private final Map<String, Double> foodProductionRate = new HashMap<>();
    private final Map<String, Double> lastProducedSnapshot = new HashMap<>();
    /** First game-tick net food has been continuously â‰¥0 (âˆ’1 while it's negative). Transient; powers the
     *  "sustained surplus" crisis gate. */
    private transient long netFoodOkSinceTick = -1L;
    private static final double PRODUCTION_RATE_ALPHA = 0.1; // EMA smoothing (~10s time constant at 1 Hz)
    /** Active scripted crisis for this settlement, if any. */
    private com.bannerbound.core.crisis.CrisisState activeCrisis = null;
    private final Set<String> completedCrises = new HashSet<>();
    private final Set<String> failedCrises = new HashSet<>();

    private record FoodSourcePulse(UUID instanceId, String source, double amount, int remainingTicks) {
        FoodSourcePulse tickDown() {
            return new FoodSourcePulse(instanceId, source, amount, remainingTicks - 1);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", instanceId);
            tag.putString("Source", source);
            tag.putDouble("Amount", amount);
            tag.putInt("Ticks", remainingTicks);
            return tag;
        }

        static FoodSourcePulse load(CompoundTag tag) {
            return new FoodSourcePulse(
                tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID(),
                tag.getString("Source"),
                tag.getDouble("Amount"),
                tag.getInt("Ticks")
            );
        }
    }

    public Settlement(UUID id, String name, SettlementColor color, UUID owner) {
        this.id = id;
        this.name = name;
        this.languageSeed = com.bannerbound.core.language.SettlementLanguage.deriveSeed(id, name);
        this.factionName = name;
        this.color = color;
        this.owner = owner;
        this.members = new HashSet<>();
        this.members.add(owner);
        this.claimedChunks = new LinkedHashSet<>();
        this.townHallPos = null;
        this.tabletsIssued = 0;
        this.age = Era.ANCIENT;
        this.completedResearches = new HashSet<>();
        this.knownItems = new HashSet<>();
        this.researchProgress = new HashMap<>();
        this.researchQueue = new ArrayList<>();
        this.activeResearch = null;
        this.sciencePerSecond = DEFAULT_SCIENCE_PER_SECOND;
        this.citizens = new ArrayList<>();
        this.foodPerSecond = DEFAULT_FOOD_PER_SECOND;
        this.culturePerSecond = DEFAULT_CULTURE_PER_SECOND;
        this.foodStored = 0.0;
        this.cultureStored = 0.0;
        this.bonusFoodCapacity = 0.0;
        this.bonusCultureCapacity = 0.0;
        this.bonusCitizenSpeed = 0.0;
        this.workstations = new HashMap<>();
    }

    private Settlement(UUID id, String name, SettlementColor color, UUID owner, Set<UUID> members,
                       Set<Long> claimedChunks, BlockPos townHallPos, int tabletsIssued, Era age,
                       Set<String> completedResearches, Set<String> knownItems, Map<String, Double> researchProgress,
                       List<String> researchQueue, String activeResearch, double sciencePerSecond,
                       List<Citizen> citizens, double foodPerSecond, double culturePerSecond,
                       double foodStored, double cultureStored,
                       double bonusFoodCapacity, double bonusCultureCapacity,
                       double bonusCitizenSpeed,
                       Map<Long, Workstation> workstations,
                       long languageSeed) {
        this.id = id;
        this.name = name;
        this.languageSeed = languageSeed;
        this.factionName = name;
        this.color = color;
        this.owner = owner;
        this.members = members;
        this.claimedChunks = claimedChunks;
        this.townHallPos = townHallPos;
        this.tabletsIssued = tabletsIssued;
        this.age = age;
        this.completedResearches = completedResearches;
        this.researchProgress = researchProgress;
        this.researchQueue = researchQueue;
        this.activeResearch = activeResearch;
        this.sciencePerSecond = sciencePerSecond;
        this.citizens = citizens;
        this.foodPerSecond = foodPerSecond;
        this.culturePerSecond = culturePerSecond;
        this.foodStored = foodStored;
        this.cultureStored = cultureStored;
        this.bonusFoodCapacity = bonusFoodCapacity;
        this.bonusCultureCapacity = bonusCultureCapacity;
        this.bonusCitizenSpeed = bonusCitizenSpeed;
        this.workstations = workstations;
        this.knownItems = knownItems;
    }

    public UUID id() { return id; }
    public long languageSeed() { return languageSeed; }
    /** Capital / settlement name. Kept as {@code name()} for existing callsites and save data. */
    public String name() { return name; }
    public String settlementName() { return name; }
    public String capitalName() { return name; }
    public String factionName() {
        return (factionName == null || factionName.isBlank()) ? name : factionName;
    }
    public void setFactionName(String factionName) {
        if (factionName != null && !factionName.isBlank()) {
            this.factionName = factionName.trim();
        }
    }
    public boolean matchesName(String query) {
        if (query == null) return false;
        String q = query.trim();
        return name.equalsIgnoreCase(q) || factionName().equalsIgnoreCase(q);
    }
    public SettlementColor color() { return color; }
    public UUID owner() { return owner; }
    public Set<UUID> members() { return members; }

    public boolean removeMember(UUID playerId) { return members.remove(playerId); }
    public boolean isEmpty() { return members.isEmpty(); }

    public Set<Long> claimedChunks() { return claimedChunks; }
    public void addClaim(long packedChunkPos) { claimedChunks.add(packedChunkPos); }
    public void removeClaim(long packedChunkPos) { claimedChunks.remove(packedChunkPos); }

    /** Chunks held by a WORKING claim (an Outpost Banner): exclusive â€” no other settlement may
     *  claim or work them â€” but NOT protective (no grief protection; the banner is breakable and
     *  breaking it drops the claim â€” that's how an outpost is conquered). Never counted as
     *  territory expansions. See MINER_PLAN.md phase 4. */
    public Set<Long> workingClaims() { return workingClaims; }

    /** The faction banner block that established the outpost on this working-claim chunk, or
     *  {@code null} (no outpost there, or a legacy claim saved before banners were recorded). */
    public BlockPos outpostBannerPos(long packedChunkPos) { return outpostBanners.get(packedChunkPos); }
    public void setOutpostBanner(long packedChunkPos, BlockPos bannerPos) {
        outpostBanners.put(packedChunkPos, bannerPos.immutable());
    }
    public void removeOutpostBanner(long packedChunkPos) { outpostBanners.remove(packedChunkPos); }

    public BlockPos townHallPos() { return townHallPos; }
    public void setTownHallPos(BlockPos pos) { this.townHallPos = pos; }
    public boolean hasTownHall() { return townHallPos != null; }

    /** Where the faction banner stands, or null while it's down. See {@link FactionBanner}. */
    public BlockPos bannerPos() { return bannerPos; }
    public void setBannerPos(BlockPos pos) { this.bannerPos = pos; }
    /** True while the faction banner is raised. Gates the town hall menu AND all citizen work. */
    public boolean hasFactionBanner() { return bannerPos != null; }

    /** The Heraldry design layers (live list â€” callers treat as read-only). */
    public List<BannerLayer> bannerDesign() { return bannerDesign; }
    public void setBannerDesign(List<BannerLayer> layers) {
        bannerDesign.clear();
        bannerDesign.addAll(layers);
    }

    /** Replaces the identity dye list (ranked DyeColor ids); empty = founding fallback. */
    public void setIdentityDyes(List<Integer> dyeIds) {
        identityDyeIds.clear();
        identityDyeIds.addAll(dyeIds);
    }

    // â”€â”€â”€ THE settlement color, post-Heraldry â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Every renderer/message/team that shows "the settlement's color" goes through these,
    // NOT color().rgb()/formatting(): the banner's dominant dye IS the settlement's color
    // once a design is saved ("don't fight it â€” become it"). The founding SettlementColor
    // remains only the unique per-server slot (founding screen, identity-table key).

    /** ALL identity colors as 0xRRGGBB, most-present first â€” as many as the banner has
     *  (never empty: founding rgb when undesigned). The source list for gradients/trim. */
    public List<Integer> identityRgbList() {
        if (identityDyeIds.isEmpty()) {
            return List.of(color.rgb());
        }
        List<Integer> out = new ArrayList<>(identityDyeIds.size());
        for (int id : identityDyeIds) {
            out.add(net.minecraft.world.item.DyeColor.byId(id).getTextureDiffuseColor() & 0xFFFFFF);
        }
        return out;
    }

    /** 0xRRGGBB of the primary identity color; the vivid founding rgb until designed. */
    public int identityRgb() {
        return identityDyeIds.isEmpty()
            ? color.rgb()
            : (net.minecraft.world.item.DyeColor.byId(identityDyeIds.get(0)).getTextureDiffuseColor()
                & 0xFFFFFF);
    }

    /** Chat/team color of the settlement â€” nearest ChatFormatting to the primary identity
     *  dye, the founding formatting until designed. */
    public net.minecraft.ChatFormatting identityFormatting() {
        return identityDyeIds.isEmpty()
            ? color.formatting()
            : FactionBanner.formattingFor(
                net.minecraft.world.item.DyeColor.byId(identityDyeIds.get(0)));
    }

    public int tabletsIssued() { return tabletsIssued; }
    /** Lifetime registration-document allowance, derived from the era (+1 per age). A settlement
     *  issues at most this many tablets/papers total; advancing an age raises the cap by one. */
    public int tabletCapacity() { return age.registrationDocumentSlots(); }
    public boolean canIssueTablet() { return tabletsIssued < tabletCapacity(); }
    public void incrementTabletsIssued() { this.tabletsIssued++; }

    public Era age() { return age; }
    public void setAge(Era age) {
        // The expansion counter is deliberately NOT reset on promotion: a new era adds its
        // allowance on top of any expansions left unused in earlier eras.
        this.age = age;
    }

    public int expansionsUsed() { return expansionsUsed; }
    public void incrementExpansionsUsed() { this.expansionsUsed++; }
    /** Direct setter used by save/load only â€” game code should call {@link #incrementExpansionsUsed}. */
    public void setExpansionsUsed(int value) { this.expansionsUsed = value; }

    /** Live, mutable list of this settlement's culture styles (style ids). */
    public List<String> cultureStyles() { return cultureStyles; }
    /** Replaces all culture styles with the single given one. Used at founding. */
    public void setCultureStyle(String styleId) {
        cultureStyles.clear();
        if (styleId != null && !styleId.isBlank()) cultureStyles.add(styleId);
    }
    /** Adds a culture style without removing existing ones (future culture-menu combining). */
    public void addCultureStyle(String styleId) {
        if (styleId != null && !styleId.isBlank() && !cultureStyles.contains(styleId)) {
            cultureStyles.add(styleId);
        }
    }

    // â”€â”€â”€ Disband voting â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public Set<UUID> disbandVotes() { return disbandVotes; }
    public int disbandVoteCount() { return disbandVotes.size(); }
    public boolean hasDisbandVoted(UUID id) { return disbandVotes.contains(id); }
    public long disbandVoteStartedMs() { return disbandVoteStartedMs; }
    public boolean isDisbandVoteActive() { return disbandVoteStartedMs > 0L; }
    /** Records a "yes" vote from {@code id}. If this is the first vote of a new round, stamps
     *  the start time so the 3-minute expiry can be enforced. */
    public void addDisbandVote(UUID id, long startMs) {
        if (disbandVotes.isEmpty()) disbandVoteStartedMs = startMs;
        disbandVotes.add(id);
    }
    /** Wipes vote state (expired round, vote passed, or settlement state changed). */
    public void clearDisbandVote() {
        disbandVotes.clear();
        disbandVoteStartedMs = -1L;
    }

    // â”€â”€â”€ Government / Code of Laws â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public Government governmentType() { return governmentType; }
    public void setGovernmentType(Government g) { this.governmentType = g == null ? Government.NONE : g; }
    public UUID chiefPlayerId() { return chiefPlayerId; }
    public void setChiefPlayerId(UUID id) {
        this.chiefPlayerId = id;
        // Vacating the seat clears the step-down cooldown anchor; seating a chief sets it via
        // setChiefSinceTick (callers have the game tick).
        if (id == null) this.chiefSinceTick = -1L;
    }
    /** Game tick the current chief was seated, or {@code -1} if there's no chief / it predates
     *  the cooldown feature (treated as "no cooldown"). */
    public long chiefSinceTick() { return chiefSinceTick; }
    public void setChiefSinceTick(long t) { this.chiefSinceTick = t; }
    public boolean codeOfLawsPromptShown() { return codeOfLawsPromptShown; }
    public void setCodeOfLawsPromptShown(boolean v) { this.codeOfLawsPromptShown = v; }

    /** Current growth stage, derived from population + government. Village at
     *  {@link SettlementStage#VILLAGE_THRESHOLD}+, else Tribe once a government is enacted, else Hearth. */
    public SettlementStage stage() {
        if (population() >= SettlementStage.VILLAGE_THRESHOLD) return SettlementStage.VILLAGE;
        if (governmentType != Government.NONE) return SettlementStage.TRIBE;
        return SettlementStage.HEARTH;
    }

    public SettlementStage lastAnnouncedStage() { return lastAnnouncedStage; }
    public void setLastAnnouncedStage(SettlementStage s) { this.lastAnnouncedStage = s; }

    /** Players who count as "leaders" under the current government for
     *  resentment-attribution + menu-permission purposes.
     *  <ul>
     *    <li>{@link Government#NONE} â†’ empty (anarchy has no leader to resent).</li>
     *    <li>{@link Government#COUNCIL} â†’ every member (collective leadership).</li>
     *    <li>{@link Government#CHIEFDOM} â†’ the chief only ({@link #chiefPlayerId}), or
     *        empty if Chiefdom is declared but the election hasn't completed yet.</li>
     *  </ul>
     */
    public Set<UUID> leaderPlayerIds() {
        return switch (governmentType) {
            case NONE -> Collections.emptySet();
            case COUNCIL -> Collections.unmodifiableSet(members);
            case CHIEFDOM -> effectiveChiefId() == null
                ? Collections.emptySet()
                : Collections.singleton(effectiveChiefId());
        };
    }

    /** Step 15: the player citizens currently treat AS the chief â€” the regent if regency is
     *  active, otherwise the seated chief. Returns {@code null} if Chiefdom has been declared
     *  but no chief is elected yet AND no regent has been picked. Drives both the resentment
     *  channel (unhappy citizens blame whoever's actually running things, not the absent
     *  real chief) and the routine-authority check ({@link #canActAsChief}). */
    public UUID effectiveChiefId() {
        if (regentPlayerId != null) return regentPlayerId;
        return chiefPlayerId;
    }

    // â”€â”€â”€ Suggestion state (Chiefdom non-chief click â†’ suggestion to chief) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Toggle {@code player}'s suggestion on {@code researchId} for the science tree.
     *  Returns true if the suggestion was ADDED, false if RETRACTED. The caller (server
     *  payload handler) uses the return value to decide between a "suggested" or
     *  "withdrew suggestion" broadcast. */
    public boolean toggleScienceSuggestion(String researchId, UUID player) {
        return toggleSuggestion(scienceSuggestions, researchId, player);
    }
    public boolean toggleCultureSuggestion(String researchId, UUID player) {
        return toggleSuggestion(cultureSuggestions, researchId, player);
    }
    private static boolean toggleSuggestion(java.util.Map<String, java.util.LinkedHashSet<UUID>> map,
                                             String key, UUID player) {
        if (key == null || player == null) return false;
        java.util.LinkedHashSet<UUID> set = map.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>());
        if (set.contains(player)) {
            set.remove(player);
            if (set.isEmpty()) map.remove(key);
            return false;
        }
        set.add(player);
        return true;
    }

    /** Suggesters of {@code researchId} on the science tree, in click order. Empty set if
     *  none. Returned set is a defensive copy â€” mutation by callers is harmless. */
    public java.util.LinkedHashSet<UUID> scienceSuggesters(String researchId) {
        java.util.LinkedHashSet<UUID> s = scienceSuggestions.get(researchId);
        return s == null ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(s);
    }
    public java.util.LinkedHashSet<UUID> cultureSuggesters(String researchId) {
        java.util.LinkedHashSet<UUID> s = cultureSuggestions.get(researchId);
        return s == null ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(s);
    }
    /** Full snapshot of the science suggestion map â€” used by the sync payload to broadcast
     *  state to all settlement members after a toggle/enact. */
    public java.util.Map<String, java.util.LinkedHashSet<UUID>> allScienceSuggestions() {
        return scienceSuggestions;
    }
    public java.util.Map<String, java.util.LinkedHashSet<UUID>> allCultureSuggestions() {
        return cultureSuggestions;
    }
    /** True if ANY suggestion (science/culture/policy/palette/exile/tablet) is currently
     *  outstanding. Used to gate the once-per-second live suggestion re-broadcast so a settlement
     *  with nothing pending pays no packet cost. */
    public boolean hasAnySuggestions() {
        return !scienceSuggestions.isEmpty() || !cultureSuggestions.isEmpty()
            || !policySuggestions.isEmpty() || !paletteSuggestions.isEmpty()
            || !exileSuggestions.isEmpty() || !tabletSuggestions.isEmpty();
    }
    /** Wipe every suggester on {@code researchId} â€” called when the chief enacts the
     *  research so the marker doesn't linger after the suggestion has been "honoured". */
    public void clearScienceSuggestions(String researchId) {
        if (researchId != null) scienceSuggestions.remove(researchId);
    }
    public void clearCultureSuggestions(String researchId) {
        if (researchId != null) cultureSuggestions.remove(researchId);
    }

    // â”€â”€â”€ Exile / tablet suggestions (Chiefdom non-chief) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** Toggle {@code player}'s exile suggestion for {@code citizenUuid}; true = added, false = retracted. */
    public boolean toggleExileSuggestion(UUID citizenUuid, UUID player) {
        if (citizenUuid == null || player == null) return false;
        java.util.LinkedHashSet<UUID> set =
            exileSuggestions.computeIfAbsent(citizenUuid, k -> new java.util.LinkedHashSet<>());
        if (set.contains(player)) {
            set.remove(player);
            if (set.isEmpty()) exileSuggestions.remove(citizenUuid);
            return false;
        }
        set.add(player);
        return true;
    }
    public java.util.Map<UUID, java.util.LinkedHashSet<UUID>> allExileSuggestions() {
        return exileSuggestions;
    }
    public void clearExileSuggestions(UUID citizenUuid) {
        if (citizenUuid != null) exileSuggestions.remove(citizenUuid);
    }
    /** Toggle {@code player}'s tablet-issue suggestion; true = added, false = retracted. */
    public boolean toggleTabletSuggestion(UUID player) {
        if (player == null) return false;
        if (tabletSuggestions.contains(player)) { tabletSuggestions.remove(player); return false; }
        tabletSuggestions.add(player);
        return true;
    }
    public java.util.LinkedHashSet<UUID> tabletSuggesters() {
        return new java.util.LinkedHashSet<>(tabletSuggestions);
    }
    public void clearTabletSuggestions() {
        tabletSuggestions.clear();
    }

    // â”€â”€â”€ Labor-priority accessors â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** Player-set gatherer-job priority order (read-only view; edit via {@link #setLaborConfig}). */
    public List<String> laborPriority() { return java.util.Collections.unmodifiableList(laborPriority); }
    /** Gatherer job ids switched off (zero workers; read-only view). */
    public java.util.Set<String> laborDisabled() { return java.util.Collections.unmodifiableSet(laborDisabled); }
    public boolean isLaborJobDisabled(String jobId) { return laborDisabled.contains(jobId); }
    public boolean laborAutoAssign() { return laborAutoAssign; }
    public void setLaborAutoAssign(boolean v) { this.laborAutoAssign = v; }
    /** Worker cap for a gatherer job: {@code -1} = no limit (the default for any unset job). */
    public int laborCap(String jobId) { return laborCaps.getOrDefault(jobId, -1); }
    /** Replace the priority order + disabled set in one shot (from a Town Hall edit). */
    public void setLaborConfig(List<String> order, java.util.Collection<String> disabled) {
        laborPriority.clear();
        if (order != null) laborPriority.addAll(order);
        laborDisabled.clear();
        if (disabled != null) laborDisabled.addAll(disabled);
    }
    /** Replace the per-job worker caps (from a Town Hall edit). Entries of {@code -1} are dropped so
     *  "no limit" is the absence of a key. */
    public void setLaborCaps(Map<String, Integer> caps) {
        laborCaps.clear();
        if (caps != null) {
            for (Map.Entry<String, Integer> e : caps.entrySet()) {
                if (e.getValue() != null && e.getValue() >= 0) laborCaps.put(e.getKey(), e.getValue());
            }
        }
    }
    /** Settlement-wide default depot for gatherers without their own marked drop-off ({@code null} = none). */
    @org.jetbrains.annotations.Nullable
    public BlockPos preferredStoragePos() { return preferredStoragePos; }
    public void setPreferredStoragePos(@org.jetbrains.annotations.Nullable BlockPos pos) { this.preferredStoragePos = pos; }


    // â”€â”€â”€ Policy accessors â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** Live active-policy list (mutable â€” callers should go through {@link #addActivePolicy}
     *  / {@link #removeActivePolicy} rather than mutating directly). */
    public List<String> activePolicies() { return activePolicies; }
    /** O(1) check used by goal/tick hooks: is {@code policyId} currently active + confirmed? */
    public boolean hasPolicy(String policyId) { return activePolicies.contains(policyId); }
    // ─── Typed policy slots (POLICY_PLAN.md) ─────────────────────────────────────────────────
    // Slot count is government base layout + research grants — NOT era. Each global policy fits a
    // typed slot of its PolicyType; each government also has one signature slot for its exclusive
    // policy. Era.activePolicySlots() no longer drives policies.

    /** The base typed slot layout a government grants before any research. NONE = none. */
    public static List<PolicyType> governmentBaseLayout(Government gov) {
        if (gov == Government.CHIEFDOM) {
            return List.of(PolicyType.ECONOMIC, PolicyType.CULTURAL,
                PolicyType.SCIENTIFIC, PolicyType.MILITARISTIC);
        }
        if (gov == Government.COUNCIL) {
            // Council drops the Militaristic slot and gains a second Cultural.
            return List.of(PolicyType.ECONOMIC, PolicyType.CULTURAL,
                PolicyType.CULTURAL, PolicyType.SCIENTIFIC);
        }
        return List.of();
    }

    /** Extra typed slots granted by completed research (both trees) via {@code unlocks.policy_slot}
     *  → {@code unlock.policy_slot.<TYPE>} flags. One entry per granted slot; duplicates expected. */
    public List<PolicyType> researchGrantedPolicySlots() {
        List<PolicyType> out = new ArrayList<>();
        collectPolicySlotFlags(completedResearches(), false, out);
        collectPolicySlotFlags(completedCultureResearches(), true, out);
        return out;
    }

    private static void collectPolicySlotFlags(java.util.Collection<String> researchIds,
                                               boolean culture, List<PolicyType> out) {
        for (String id : researchIds) {
            com.bannerbound.core.api.research.ResearchDefinition def = culture
                ? com.bannerbound.core.api.research.data.CultureTreeLoader.get(id)
                : com.bannerbound.core.api.research.data.ResearchTreeLoader.get(id);
            if (def == null) continue;
            for (String flag : def.unlocksFlags()) {
                if (flag.startsWith("unlock.policy_slot.")) {
                    PolicyType t = PolicyType.byName(flag.substring("unlock.policy_slot.".length()));
                    if (t != null) out.add(t);
                }
            }
        }
    }

    /** The full ordered list of typed slots (government base + research grants), excluding the
     *  signature slot. */
    public List<PolicyType> policyTypeSlots() {
        List<PolicyType> out = new ArrayList<>(governmentBaseLayout(governmentType));
        out.addAll(researchGrantedPolicySlots());
        return out;
    }

    /** Whether this government has a signature slot (any government but anarchy). */
    public boolean hasSignatureSlot() {
        return governmentType != Government.NONE
            && PolicyRegistry.signaturePolicyFor(governmentType) != null;
    }

    /** The ordered slot-type names sent to the client: each typed slot's {@link PolicyType} name,
     *  then the literal {@code "SIGNATURE"} if this government has a signature slot. The UI renders
     *  one slot per entry. */
    public List<String> policySlotTypeNames() {
        List<String> out = new ArrayList<>();
        for (PolicyType t : policyTypeSlots()) out.add(t.name());
        if (hasSignatureSlot()) out.add("SIGNATURE");
        return out;
    }

    /** Total number of policy slots (typed + signature). */
    public int policySlotCapacity() {
        return policyTypeSlots().size() + (hasSignatureSlot() ? 1 : 0);
    }

    /** Whether {@code policyId} would fit a free slot given the {@code active} set: a signature
     *  policy needs its government + an empty signature slot; a global policy needs a free typed
     *  slot of its {@link PolicyType}. */
    public boolean hasFreeSlotForIn(java.util.Collection<String> active, String policyId) {
        PolicyRegistry.Policy p = PolicyRegistry.get(policyId);
        if (p == null) return false;
        if (PolicyRegistry.isSignature(policyId)) {
            if (p.governmentType() != governmentType || !hasSignatureSlot()) return false;
            for (String a : active) if (PolicyRegistry.isSignature(a)) return false; // slot taken
            return true;
        }
        PolicyType t = p.type();
        int used = 0;
        for (String a : active) {
            PolicyRegistry.Policy ap = PolicyRegistry.get(a);
            if (ap != null && !PolicyRegistry.isSignature(a) && ap.type() == t) used++;
        }
        int cap = 0;
        for (PolicyType st : policyTypeSlots()) if (st == t) cap++;
        return used < cap;
    }

    /** Server-side validation for a proposed change: simulate the remove (+ any mutually-exclusive
     *  eviction) then check the add fits a free typed/signature slot. */
    public boolean canEnactProposal(String addId, String removeId) {
        java.util.LinkedHashSet<String> hyp = new java.util.LinkedHashSet<>(activePolicies);
        if (removeId != null) hyp.remove(removeId);
        String ex = PolicyRegistry.exclusiveWith(addId);
        if (ex != null) hyp.remove(ex);
        if (addId == null) return true;          // pure remove always allowed
        if (hyp.contains(addId)) return false;   // already active
        return hasFreeSlotForIn(hyp, addId);
    }

    /** Adds a confirmed policy if it fits a free typed/signature slot and isn't already active.
     *  Returns true on success. */
    public boolean addActivePolicy(String policyId) {
        if (policyId == null || activePolicies.contains(policyId)) return false;
        if (!hasFreeSlotForIn(activePolicies, policyId)) return false;
        activePolicies.add(policyId);
        return true;
    }
    public boolean removeActivePolicy(String policyId) { return activePolicies.remove(policyId); }

    public PolicyChange pendingPolicyChange() { return pendingPolicyChange; }
    /** Sets the proposed change + resets the confirm-vote tally (a new proposal starts a fresh
     *  vote). Pass null to clear. */
    public void setPendingPolicyChange(PolicyChange change) {
        this.pendingPolicyChange = change;
        this.policyConfirmVotes.clear();
    }
    public java.util.Map<UUID, Boolean> policyConfirmVotes() { return policyConfirmVotes; }
    public void castPolicyConfirmVote(UUID voter, boolean agree) {
        if (voter != null) policyConfirmVotes.put(voter, agree);
    }
    /** Drops the pending change + its votes â€” after enact, discard, or chief-seat change. */
    public void clearPolicyChangeState() {
        this.pendingPolicyChange = null;
        this.policyConfirmVotes.clear();
    }

    /** Toggle a Chiefdom non-chief's suggestion on {@code policyId}. Returns true if ADDED. */
    public boolean togglePolicySuggestion(String policyId, UUID player) {
        return toggleSuggestion(policySuggestions, policyId, player);
    }
    public java.util.LinkedHashSet<UUID> policySuggesters(String policyId) {
        java.util.LinkedHashSet<UUID> s = policySuggestions.get(policyId);
        return s == null ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(s);
    }
    public java.util.Map<String, java.util.LinkedHashSet<UUID>> allPolicySuggestions() {
        return policySuggestions;
    }
    public void clearPolicySuggestions(String policyId) {
        if (policyId != null) policySuggestions.remove(policyId);
    }

    // â”€â”€â”€ Palette accessors (mirror the Policy accessors above) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public List<String> activePalettes() { return activePalettes; }
    public boolean hasPalette(String paletteId) { return activePalettes.contains(paletteId); }
    /** Active-palette capacity for the current era (Antiquity 1, Medieval 2, ...). */
    public int paletteSlotCapacity() { return age().activePaletteSlots(); }
    public boolean addActivePalette(String paletteId) {
        if (paletteId == null || activePalettes.contains(paletteId)) return false;
        if (activePalettes.size() >= paletteSlotCapacity()) return false;
        activePalettes.add(paletteId);
        return true;
    }
    public boolean removeActivePalette(String paletteId) { return activePalettes.remove(paletteId); }

    public PaletteChange pendingPaletteChange() { return pendingPaletteChange; }
    public void setPendingPaletteChange(PaletteChange change) {
        this.pendingPaletteChange = change;
        this.paletteConfirmVotes.clear();
    }
    public java.util.Map<UUID, Boolean> paletteConfirmVotes() { return paletteConfirmVotes; }
    public void castPaletteConfirmVote(UUID voter, boolean agree) {
        if (voter != null) paletteConfirmVotes.put(voter, agree);
    }
    public void clearPaletteChangeState() {
        this.pendingPaletteChange = null;
        this.paletteConfirmVotes.clear();
    }
    public boolean togglePaletteSuggestion(String paletteId, UUID player) {
        return toggleSuggestion(paletteSuggestions, paletteId, player);
    }
    public java.util.Map<String, java.util.LinkedHashSet<UUID>> allPaletteSuggestions() {
        return paletteSuggestions;
    }
    public void clearPaletteSuggestions(String paletteId) {
        if (paletteId != null) paletteSuggestions.remove(paletteId);
    }

    public long policyOpinionatedBonusExpiry() { return policyOpinionatedBonusExpiry; }
    public void setPolicyOpinionatedBonusExpiry(long gameTick) {
        this.policyOpinionatedBonusExpiry = gameTick;
    }
    /** True while the Opinionated-Crowd bonus window is open at {@code nowTick}. */
    public boolean isOpinionatedBonusActive(long nowTick) {
        return policyOpinionatedBonusExpiry > nowTick;
    }

    // â”€â”€â”€ Expansion vote / suggestion (Council vote + Chiefdom non-chief suggestion) â”€â”€â”€â”€â”€â”€â”€

    /** Toggle {@code player}'s expansion-vote on {@code packedChunkPos} (Council mode). The
     *  cast-time is recorded for the 5-min auto-expiry sweep. Returns true if the vote was
     *  ADDED, false if RETRACTED. */
    public boolean toggleExpansionVote(long packedChunkPos, UUID player, long nowMs) {
        if (player == null) return false;
        java.util.LinkedHashMap<UUID, Long> votes = expansionVotes.computeIfAbsent(
            packedChunkPos, k -> new java.util.LinkedHashMap<>());
        if (votes.containsKey(player)) {
            votes.remove(player);
            if (votes.isEmpty()) expansionVotes.remove(packedChunkPos);
            return false;
        }
        votes.put(player, nowMs);
        return true;
    }
    public java.util.LinkedHashMap<UUID, Long> expansionVotesFor(long packedChunkPos) {
        java.util.LinkedHashMap<UUID, Long> v = expansionVotes.get(packedChunkPos);
        return v == null
            ? new java.util.LinkedHashMap<>()
            : new java.util.LinkedHashMap<>(v);
    }
    public java.util.Map<Long, java.util.LinkedHashMap<UUID, Long>> allExpansionVotes() {
        return expansionVotes;
    }
    public void clearExpansionVotes(long packedChunkPos) {
        expansionVotes.remove(packedChunkPos);
    }
    /** Sweep all chunk-vote entries older than {@code expiryMs} relative to {@code nowMs}.
     *  Called by the vote handler before each new cast so a stale vote doesn't cling.
     *  <p>Two-level {@code removeIf}: the inner one drops expired voter entries, the outer
     *  drops chunk maps that ended up empty. Safe because both {@code HashMap.entrySet()}
     *  and {@code LinkedHashMap.entrySet()} support concurrent modification via removeIf â€”
     *  it's the same pattern Java uses for {@code Collection.removeIf} delegation. */
    public void expireExpansionVotes(long nowMs, long expiryMs) {
        expansionVotes.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(e -> nowMs - e.getValue() > expiryMs);
            return entry.getValue().isEmpty();
        });
    }

    /** Toggle {@code player}'s Chiefdom suggestion on {@code packedChunkPos}. Returns true
     *  on ADD, false on RETRACT. */
    public boolean toggleExpansionSuggestion(long packedChunkPos, UUID player) {
        if (player == null) return false;
        java.util.LinkedHashSet<UUID> s = expansionSuggestions.computeIfAbsent(
            packedChunkPos, k -> new java.util.LinkedHashSet<>());
        if (s.contains(player)) {
            s.remove(player);
            if (s.isEmpty()) expansionSuggestions.remove(packedChunkPos);
            return false;
        }
        s.add(player);
        return true;
    }
    public java.util.LinkedHashSet<UUID> expansionSuggestersFor(long packedChunkPos) {
        java.util.LinkedHashSet<UUID> s = expansionSuggestions.get(packedChunkPos);
        return s == null ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(s);
    }
    public java.util.Map<Long, java.util.LinkedHashSet<UUID>> allExpansionSuggestions() {
        return expansionSuggestions;
    }
    public void clearExpansionSuggestions(long packedChunkPos) {
        expansionSuggestions.remove(packedChunkPos);
    }

    /** Step 13 v2 â€” settlement-level coup state. */
    public long lastCoupCheckDay() { return lastCoupCheckDay; }
    public void setLastCoupCheckDay(long day) { this.lastCoupCheckDay = day; }
    public long lastDuskWarnDay() { return lastDuskWarnDay; }
    public void setLastDuskWarnDay(long day) { this.lastDuskWarnDay = day; }
    public long lastPolicyHour() { return lastPolicyHour; }
    public void setLastPolicyHour(long hour) { this.lastPolicyHour = hour; }
    public boolean isCoupSuppressed() { return coupSuppressed; }
    public void setCoupSuppressed(boolean v) { this.coupSuppressed = v; }

    /** Routine Chief authority: can {@code player} act <i>as</i> the Chief for low-stakes
     *  actions (start/queue research, browse menus, future workstation assignments). In a
     *  COUNCIL government every member returns true (no Chief gate exists). In CHIEFDOM the
     *  seated Chief AND the current regent both qualify â€” that's the Step 15 split. */
    public boolean canActAsChief(UUID player) {
        if (player == null) return false;
        return switch (governmentType) {
            case NONE -> false;
            case COUNCIL -> members.contains(player);
            case CHIEFDOM -> player.equals(chiefPlayerId) || player.equals(regentPlayerId);
        };
    }

    /** Weighty Chief authority: irreversible / high-stakes actions (Disband, Expand Territory,
     *  declare war when that ships). Strict â€” only the actual seated Chief, never the regent.
     *  This is the gate that prevents an absent Chief from having the settlement disbanded
     *  out from under them by a stand-in. */
    public boolean canActWeighty(UUID player) {
        if (player == null) return false;
        return switch (governmentType) {
            case NONE -> false;
            case COUNCIL -> members.contains(player);
            case CHIEFDOM -> player.equals(chiefPlayerId);
        };
    }

    // â”€â”€â”€ Regent accessors (Step 15) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public UUID regentPlayerId() { return regentPlayerId; }
    public void setRegentPlayerId(UUID id) { this.regentPlayerId = id; }
    /** True iff a regent is currently acting (chief is offline / chair is vacant). */
    public boolean hasActiveRegent() { return regentPlayerId != null; }

    /** True while the Choose-Government menu prompt is actionable for the player. The window
     *  opens when the code-of-laws prompt has fired AND population still meets the era's
     *  immigration floor AND no government has been chosen yet. It auto-closes the tick a
     *  government is enacted, or when population dips below the floor (so any active vote
     *  retracts cleanly â€” see {@code ImmigrationManager}'s pull-back path). */
    public boolean governmentChoiceWindowOpen() {
        return governmentType == Government.NONE
            && codeOfLawsPromptShown
            && population() >= immigrationFloor();
    }

    // â”€â”€â”€ Government voting (Choose-Government) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public Map<UUID, Government> governmentVotes() {
        return Collections.unmodifiableMap(governmentVotes);
    }
    public int governmentVoteCountFor(Government g) {
        int n = 0;
        for (Government v : governmentVotes.values()) if (v == g) n++;
        return n;
    }
    public long governmentVoteStartedMs() { return governmentVoteStartedMs; }
    public boolean isGovernmentVoteActive() { return governmentVoteStartedMs > 0L; }
    /** Records {@code voter}'s pick for the active Choose-Government vote, overwriting any
     *  previous pick from the same voter (vote can be changed mid-round). Stamps the start
     *  time on the first vote of a new round. */
    public void castGovernmentVote(UUID voter, Government pick, long startMs) {
        if (governmentVotes.isEmpty()) governmentVoteStartedMs = startMs;
        governmentVotes.put(voter, pick);
    }
    /** Wipes vote state (expired round, vote passed, or government-choice window closed). */
    public void clearGovernmentVote() {
        governmentVotes.clear();
        governmentVoteStartedMs = -1L;
    }

    // â”€â”€â”€ Chief election (Chiefdom only, after government type is set) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    /** True while the Chiefdom government has been declared but no chief is elected yet.
     *  Symmetric to {@link #governmentChoiceWindowOpen()} â€” both flag periods where players
     *  are mid-decision and the invulnerability listener + UI routing should kick in. */
    public boolean chiefdomElectionWindowOpen() {
        return governmentType == Government.CHIEFDOM && chiefPlayerId == null;
    }
    public Map<UUID, UUID> chiefNominations() {
        return Collections.unmodifiableMap(chiefNominations);
    }
    /** Number of voters who nominated {@code candidate}. */
    public int chiefNominationCountFor(UUID candidate) {
        int n = 0;
        for (UUID c : chiefNominations.values()) if (candidate.equals(c)) n++;
        return n;
    }
    public long chiefElectionStartedMs() { return chiefElectionStartedMs; }
    public boolean isChiefElectionActive() { return chiefElectionStartedMs > 0L; }
    /** Records {@code voter}'s nomination of {@code candidate}, overwriting any previous
     *  pick. Stamps the start time on the first nomination of a new round. */
    public void castChiefNomination(UUID voter, UUID candidate, long startMs) {
        if (chiefNominations.isEmpty()) chiefElectionStartedMs = startMs;
        chiefNominations.put(voter, candidate);
    }
    /** Wipes election state (expired round, chief elected, or window closed). */
    public void clearChiefElection() {
        chiefNominations.clear();
        chiefElectionStartedMs = -1L;
    }

    /** Schedule {@code chiefId} to be installed as Chief at game-tick {@code enactTick}.
     *  Used by the tribe-vote reveal so the actual enactment fires *after* the animation
     *  completes. */
    public void schedulePendingChief(UUID chiefId, long enactTick) {
        this.pendingChiefId = chiefId;
        this.pendingChiefEnactTick = enactTick;
    }
    public UUID pendingChiefId() { return pendingChiefId; }
    public long pendingChiefEnactTick() { return pendingChiefEnactTick; }
    public void clearPendingChief() {
        this.pendingChiefId = null;
        this.pendingChiefEnactTick = 0L;
    }

    /** Schedule {@code type} to be installed as the government type at game-tick
     *  {@code enactTick}. Used by the Choose-Government tribe-vote reveal so the actual
     *  enactment fires after the animation completes. */
    public void schedulePendingGovernment(Government type, long enactTick) {
        this.pendingGovernmentType = type;
        this.pendingGovernmentEnactTick = enactTick;
    }
    public Government pendingGovernmentType() { return pendingGovernmentType; }
    public long pendingGovernmentEnactTick() { return pendingGovernmentEnactTick; }
    public void clearPendingGovernment() {
        this.pendingGovernmentType = null;
        this.pendingGovernmentEnactTick = 0L;
    }

    /** Schedule a tribe-backed disband to dissolve the settlement at {@code enactTick}, after
     *  the Opinionated-Crowd confirming reveal finishes animating. */
    public void schedulePendingDisband(long enactTick) {
        this.pendingDisband = true;
        this.pendingDisbandEnactTick = enactTick;
    }
    public boolean hasPendingDisband() { return pendingDisband; }
    public long pendingDisbandEnactTick() { return pendingDisbandEnactTick; }
    public void clearPendingDisband() {
        this.pendingDisband = false;
        this.pendingDisbandEnactTick = 0L;
    }

    public Set<String> completedResearches() { return completedResearches; }
    public Set<String> knownItems() { return knownItems; }

    public static Set<String> computeKnownItems(Set<String> completedResearches) {
        Set<String> out = new HashSet<>();
        for (String id : completedResearches) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def != null) {
                out.addAll(def.unlocksItems());
            }
        }
        return out;
    }

    public static Set<String> computeKnownCultureItems(Set<String> completedCultureResearches) {
        Set<String> out = new HashSet<>();
        for (String id : completedCultureResearches) {
            ResearchDefinition def = CultureTreeLoader.get(id);
            if (def != null) {
                out.addAll(def.unlocksItems());
            }
        }
        return out;
    }

    public Set<String> computeKnownItems() {
        return computeKnownItems(completedResearches());
    }

    public Set<String> computeKnownCultureItems() {
        return computeKnownCultureItems(completedCultureResearches());
    }

    public void recomputeKnownItems() {
        Set<String> knownItems = computeKnownItems();
        knownItems.addAll(computeKnownCultureItems());
    }

    public boolean hasCompletedResearch(String id) { return completedResearches.contains(id); }
    public void markResearchComplete(String id) {
        completedResearches.add(id);
        researchProgress.remove(id);

        ResearchDefinition def = ResearchTreeLoader.get(id);
        if (def != null) {
            knownItems.addAll(def.unlocksItems());
        }
    }

    public Map<String, Double> researchProgress() { return researchProgress; }
    public double getResearchProgress(String id) {
        Double v = researchProgress.get(id);
        return v == null ? 0.0 : v;
    }
    public void setResearchProgress(String id, double value) {
        researchProgress.put(id, value);
    }

    public int insightCount(String key) { return insightCounters.getOrDefault(key, 0); }
    public void setInsightCount(String key, int value) { insightCounters.put(key, Math.max(0, value)); }
    public boolean hasFiredInsight(String key) { return firedInsights.contains(key); }
    public void markInsightFired(String key) { firedInsights.add(key); }
    public Set<String> firedInsights() { return java.util.Collections.unmodifiableSet(firedInsights); }

    /** Mutable ordered list of research IDs queued behind the active research. */
    public List<String> researchQueue() { return researchQueue; }

    public String activeResearch() { return activeResearch; }
    public void setActiveResearch(String id) { this.activeResearch = id; }

    public double sciencePerSecond() { return sciencePerSecond; }
    public void setSciencePerSecond(double v) { this.sciencePerSecond = v; }

    /** Effective science rate: the base/research-modified rate plus {@link #SCIENCE_PER_POPULATION}
     *  for every citizen. This is what research actually accumulates at and what the GUI shows. */
    public double effectiveSciencePerSecond() {
        return sciencePerSecond + SCIENCE_PER_POPULATION * population() + faithEffects.science();
    }

    // â”€â”€â”€ Culture-research accessors â€” mirror the science block above â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    public Set<String> completedCultureResearches() { return completedCultureResearches; }
    public boolean hasCompletedCultureResearch(String id) { return completedCultureResearches.contains(id); }
    /** Cross-tree completion check: true if {@code id} is complete in EITHER the science or
     *  culture tree. Used for prerequisite resolution so a culture node can require a science
     *  node and vice versa (e.g. Roads policy requires Science PAVING). */
    public boolean hasCompletedResearchEitherTree(String id) {
        return completedResearches.contains(id) || completedCultureResearches.contains(id);
    }
    public void markCultureResearchComplete(String id) {
        completedCultureResearches.add(id);
        cultureResearchProgress.remove(id);
    }
    public Map<String, Double> cultureResearchProgress() { return cultureResearchProgress; }
    public double getCultureResearchProgress(String id) {
        Double v = cultureResearchProgress.get(id);
        return v == null ? 0.0 : v;
    }
    public void setCultureResearchProgress(String id, double value) {
        cultureResearchProgress.put(id, value);
    }
    public List<String> cultureResearchQueue() { return cultureResearchQueue; }
    public String activeCultureResearch() { return activeCultureResearch; }
    public void setActiveCultureResearch(String id) { this.activeCultureResearch = id; }

    /** Effective culture/s â€” the single source of truth for "what number does the town
     *  hall show and the culture tree drain at?". Sums the base field, settlement status
     *  effects, and chunk-beauty appeal (which needs {@link ServerLevel} for the per-chunk
     *  lookup, hence the parameter). Mirrors the shape of {@link #effectiveFoodPerSecond()}
     *  but takes a level because food bonuses are level-independent and culture's aren't. */
    public double effectiveCulturePerSecond(net.minecraft.server.level.ServerLevel level) {
        double statusBonus = statusBonusFor(StatusEffectIcon.CULTURE);
        double appealCulture = level == null ? 0.0
            : com.bannerbound.core.api.settlement.ChunkBeautyManager.cultureBonus(level, this);
        return culturePerSecond + statusBonus + appealCulture + faithEffects.culture();
    }

    public List<Citizen> citizens() { return citizens; }
    public int population() { return citizens.size(); }
    public void addCitizen(Citizen c) { citizens.add(c); }
    public boolean removeCitizen(UUID entityId) {
        return citizens.removeIf(c -> c.entityId().equals(entityId));
    }

    /** Updates a roster citizen's stored name (used when an entity bakes its name into the
     *  settlement language). Returns true if a matching roster entry was found and changed. */
    public boolean renameCitizen(UUID entityId, String name) {
        for (int i = 0; i < citizens.size(); i++) {
            Citizen c = citizens.get(i);
            if (c.entityId().equals(entityId)) {
                if (c.name().equals(name)) return false;
                citizens.set(i, new Citizen(entityId, name));
                return true;
            }
        }
        return false;
    }

    /** Lifetime immigrant count â€” purely a stats field now (used by
     *  {@link #foodConsumptionPerSecond} as the grace-period gate). Immigration is no longer
     *  capped by this; see {@link #immigrationFloor()} for the actual gate. */
    public int immigratedCount() { return immigratedCount; }
    /** Bumps the lifetime immigrant counter by one. Called from
     *  {@code ImmigrationManager.spawnNewCitizen} immediately after a successful spawn so the
     *  consumption grace-period gate ({@code immigratedCount < immigrationFloor}) flips off once
     *  the floor has ever been crossed. */
    public void recordImmigration() { this.immigratedCount++; }

    /** Era-keyed population floor that immigration tops the settlement up to. Antiquity = 7;
     *  later eras scale (see {@link Era#immigrationFloor()}). Immigration fires whenever
     *  {@code population() < immigrationFloor()}, so a settlement whose original immigrants all
     *  died automatically refills instead of permanently soft-locking. The same number is the
     *  floor of {@link #populationMaximum()} (so the pop-max never drops below the immigration
     *  baseline even with no beds yet) and the threshold of the food-consumption grace period
     *  (no consumption while lifetime immigration is still ramping up to the floor). */
    public int immigrationFloor() {
        return age().immigrationFloor();
    }

    /**
     * Population ceiling that gates lovemaking + births. Sits at {@link #immigrationFloor()}
     * (the era's immigration baseline) and grows by 1 per <i>true spare bed</i> â€” defined as a
     * bed in a valid home whose home has slack <b>AND</b> the settlement has no homeless
     * citizens overall. If even one citizen is homeless, spare beds don't count (the floor stays
     * at {@code immigrationFloor} or the housed count, whichever's higher) because the player
     * needs to house the displaced before the bed system pretends to support reproduction.
     *
     * <p>Equivalent formulation when no one is homeless: max = total valid-home beds. When
     * someone is homeless: max = max(immigrationFloor, housedCount). The latter keeps already-
     * grown settlements from collapsing their cap when a fresh immigrant arrives un-homed.
     */
    public int populationMaximum() {
        int beds = 0;
        int housed = 0;
        for (Home h : homes.values()) {
            if (!h.valid()) continue;
            beds += h.bedCount();
            housed += h.residents().size();
        }
        int homeless = population() - housed;
        int effective = (homeless > 0) ? housed : beds;
        return Math.max(immigrationFloor(), effective);
    }

    /** Translation key for the settlement's display title. Tribe once a government has been
     *  enacted (Council or Chiefdom) OR population reaches 8; otherwise Hearth. Enacting a
     *  government is itself a "moved past the campfire-only stage" signal, so it promotes the
     *  title even before the size threshold. The town hall screen renders this just below the
     *  era name. */
    public String titleKey() {
        if (population() >= SettlementStage.VILLAGE_THRESHOLD) {
            return "bannerbound.settlement.title.village";
        }
        return isTribe()
            ? "bannerbound.settlement.title.tribe"
            : "bannerbound.settlement.title.hearth";
    }

    /** True once the settlement has grown past the campfire-only <b>Hearth</b> stage into the
     *  <b>Tribe</b> stage: a government has been enacted (Council/Chiefdom) OR population has
     *  reached 8. The shared "is this a real tribe yet?" gate behind work conversations, the
     *  UNEMPLOYED/NO_HOME thoughts, tribe-stage research, and hearth-vs-tribe compliance. */
    public boolean isTribe() {
        return governmentType != Government.NONE || population() >= 8;
    }

    /** Real-time seconds in one in-game day (24000 ticks / 20 tps). */
    private static final double SECONDS_PER_GAME_DAY = 1200.0;

    /** Raw per-second appetite of the population (the "drain" the town hall shows). Gated on
     *  government enactment: anarchy ({@link Government#NONE}) eats nothing, giving a fresh settlement
     *  runway to build food infrastructure during the immigration + code-of-laws phase before survival
     *  pressure kicks in. The instant a government is enacted, consumption switches on for good. */
    public double foodConsumptionPerSecond() {
        if (dormant) return 0.0;   // frozen while every member is offline — no appetite to drain
        if (governmentType == Government.NONE) return 0.0;
        return population() * com.bannerbound.core.Config.FOOD_PER_CITIZEN_PER_DAY.get()
            / SECONDS_PER_GAME_DAY;
    }

    /** Records a new population high-water mark. Cheap; called once/sec from the immigration tick (and
     *  any growth path can call it). {@link #peakPopulation} only ever rises. */
    public void notePopulationPeak() {
        if (population() > peakPopulation) peakPopulation = population();
    }

    /** The settlement's "full tribe" size for food-security purposes: the largest population it has ever
     *  sustained ({@link #peakPopulation}), but never below the current roster and never above the era
     *  immigration floor ({@link #immigrationFloor()}). A founding tribe that has only ever held 3 asks
     *  for 3 mouths; a tribe that grew to 7 and then starved down to 1 still asks for 7. */
    public int targetPopulation() {
        return Math.max(population(), Math.min(peakPopulation, immigrationFloor()));
    }

    /** Consumption the settlement WOULD have at its {@link #targetPopulation() full-tribe target}, in
     *  food/sec â€” the bar the starvation crisis's food_sustained objective is measured against. Anchored
     *  to the tribe's high-water mark so a die-off can't trivially "solve" hunger by shrinking the mouths
     *  to feed. Gated to 0 under anarchy exactly like {@link #foodConsumptionPerSecond()}. NOTE: the
     *  immigration surplus gate deliberately uses the LIVE {@link #foodConsumptionPerSecond()} instead, so
     *  a wiped-out settlement can still regrow at low population. */
    public double targetFoodConsumptionPerSecond() {
        if (governmentType == Government.NONE) return 0.0;
        return targetPopulation() * com.bannerbound.core.Config.FOOD_PER_CITIZEN_PER_DAY.get()
            / SECONDS_PER_GAME_DAY;
    }

    /** Net per-second appetite after passive food bonuses (banners / faith) — what actually drains the
     *  reserve. Status/faith "food" bonuses slow consumption rather than adding abstract food. ≥ 0. */
    public double effectiveConsumptionPerSecond() {
        double raw = foodConsumptionPerSecond();
        if (raw <= 0.0) return 0.0;
        double bonus = statusBonusFor(StatusEffectIcon.FOOD) + faithEffects.food();
        return Math.max(0.0, raw - bonus);
    }

    public double storedFoodValue() { return storedFoodValue; }
    public void setStoredFoodValue(double v) { this.storedFoodValue = Math.max(0.0, v); }
    public double storedFoodPerSecond() { return storedFoodPerSecond; }
    public void setStoredFoodPerSecond(double v) { this.storedFoodPerSecond = Math.max(0.0, v); }

    // ─── Food reserve (abstract settlement bar) ──────────────────────────────────────────────────

    /** Live food reserve the player sees: the accumulated abstract food bar (never negative). */
    public double reserve() { return Math.max(0.0, foodStored); }

    /** Real-time seconds of food left. The buffer drains at the NET deficit (consumption − passive
     *  stored-food income); a surplus (net ≥ 0) means it holds or grows, so food never "runs out". */
    public double reserveSeconds() {
        double net = effectiveFoodPerSecond();   // income − consumption
        if (net >= 0.0) return Double.POSITIVE_INFINITY;
        return reserve() / (-net);
    }

    /** In-game days of food left at the current drain (∞ when nothing is being consumed). */
    public double reserveDays() {
        double secs = reserveSeconds();
        return Double.isInfinite(secs) ? secs : secs / SECONDS_PER_GAME_DAY;
    }

    /** Starvation = the food bar is empty while citizens are still consuming. */
    public boolean isStarving() {
        return effectiveConsumptionPerSecond() > 0.0 && reserve() <= 0.0;
    }

    /** Net food/sec trend (passive stored-food income − consumption): the rate the bar changes at.
     *  Under anarchy there's no consumption, so the base trickle + bonuses are reported (drives
     *  early-game growth toward the immigration floor). */
    public double effectiveFoodPerSecond() {
        if (governmentType == Government.NONE) {
            // Anarchy: nothing is eaten yet, but the pioneering trickle AND any passive stored-food
            // income both count — stocking a larder still speeds early growth ("food in storage is
            // food/sec" holds even pre-government); there's just no consumption draining it.
            return foodPerSecond + storedFoodPerSecond
                + statusBonusFor(StatusEffectIcon.FOOD) + faithEffects.food();
        }
        return storedFoodPerSecond - effectiveConsumptionPerSecond();
    }

    // â”€â”€â”€ Active conversations (transient, server-only) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    public List<Conversation> activeConversations() { return activeConversations; }

    /** Returns the in-progress conversation involving {@code citizenId}, or null. Cheap linear
     *  scan â€” the list typically holds 0â€“3 entries even in busy settlements. */
    public Conversation findActiveConversationFor(UUID citizenId) {
        for (Conversation c : activeConversations) {
            if (c.isParticipant(citizenId)) return c;
        }
        return null;
    }

    public void startConversation(Conversation c) {
        activeConversations.add(c);
    }

    /** Removes the conversation if still present. Idempotent â€” safe to call from both
     *  participants' {@code stop()} without double-remove bugs. */
    public void endConversation(Conversation c) {
        activeConversations.remove(c);
    }

    public double foodPerSecond() { return foodPerSecond; }
    public void setFoodPerSecond(double v) { this.foodPerSecond = v; }
    public double culturePerSecond() { return culturePerSecond; }
    public void setCulturePerSecond(double v) { this.culturePerSecond = v; }

    public double foodStored() { return foodStored; }
    public void setFoodStored(double v) { this.foodStored = v; }

    /** Last food-warning bucket broadcast to members (see
     *  {@link com.bannerbound.core.network.SettlementFoodWarningPayload}). Transient: defaults to
     *  -1 on load so the first per-second food tick always broadcasts the current bucket once. */
    private transient int lastFoodWarningLevel = -1;
    public int lastFoodWarningLevel() { return lastFoodWarningLevel; }
    public void setLastFoodWarningLevel(int level) { this.lastFoodWarningLevel = level; }
    public double cultureStored() { return cultureStored; }
    public void setCultureStored(double v) { this.cultureStored = v; }

    /** Game-tick the last immigrant arrived (transient â€” paces immigration via the
     *  {@code immigrationMinSecondsBetween} config; not persisted, so a reload just lets the next
     *  immigrant arrive immediately, which is harmless). MUST default to 0, not Long.MIN_VALUE:
     *  {@code now - Long.MIN_VALUE} overflows to a negative number, which would make the cooldown
     *  look "not elapsed" forever and freeze immigration. With 0, {@code now - 0 = now} is always a
     *  valid non-negative elapsed time. */
    private transient long lastImmigrationTick = 0L;
    public long lastImmigrationTick() { return lastImmigrationTick; }
    public void setLastImmigrationTick(long tick) { this.lastImmigrationTick = tick; }

    /** Transient: true when NO member is currently online — recomputed every tick in
     *  {@code SettlementManager.refreshDormancy}, which runs at the top of the server tick
     *  ({@code ResearchEvents.onServerTick}) BEFORE every per-settlement ticker so all consumers
     *  read a fresh value. While dormant the settlement is frozen "in amber": food consumption,
     *  spoilage, citizen hunger/starvation, research/culture, crises, home upkeep, AND growth
     *  (culture + immigration) are all paused, so a 24/7 dedicated server neither penalizes nor
     *  advances a tribe whose every member is logged off. NOT persisted (never written in
     *  {@link #save}); defaults false on construction/load, refreshed on the first tick. */
    private transient boolean dormant = false;
    public boolean isDormant() { return dormant; }
    public void setDormant(boolean v) { this.dormant = v; }

    /** Food required to attract the next citizen given current population. */
    public double nextFoodCost() {
        return BASE_IMMIGRATION_FOOD_COST * (population() + 1);
    }

    /** Culture required to attract the next citizen given current population. */
    public double nextCultureCost() {
        return BASE_IMMIGRATION_CULTURE_COST * (population() + 1);
    }

    public double bonusFoodCapacity() { return bonusFoodCapacity; }
    public void addBonusFoodCapacity(double delta) {
        this.bonusFoodCapacity = Math.max(0.0, this.bonusFoodCapacity + delta);
    }

    public double bonusCultureCapacity() { return bonusCultureCapacity; }
    public void addBonusCultureCapacity(double delta) {
        this.bonusCultureCapacity = Math.max(0.0, this.bonusCultureCapacity + delta);
    }

    public double bonusCitizenSpeed() { return bonusCitizenSpeed; }
    public void addBonusCitizenSpeed(double delta) {
        this.bonusCitizenSpeed = Math.max(0.0, this.bonusCitizenSpeed + delta);
    }

    // â”€â”€â”€ Tool age â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    public String getCurrentToolAge() { return currentToolAge; }
    public void setCurrentToolAge(String id) { this.currentToolAge = id == null ? "" : id; }

    /**
     * Resolves the {@link net.minecraft.world.item.Item} assigned to {@code role} (axe / shovel /
     * etc.) for the current tool age, or {@link net.minecraft.world.item.Items#AIR} when no age
     * is unlocked or the age doesn't define this role.
     */
    public net.minecraft.world.item.Item getToolForRole(String role) {
        if (currentToolAge.isEmpty()) return net.minecraft.world.item.Items.AIR;
        com.bannerbound.core.api.research.ToolAge age =
            com.bannerbound.core.api.research.data.ToolAgeLoader.get(currentToolAge);
        if (age == null) return net.minecraft.world.item.Items.AIR;
        return age.tools().getOrDefault(role, net.minecraft.world.item.Items.AIR);
    }

    /**
     * Returns the {@code chop_ticks} override for the current tool age, or {@code defaultTicks}
     * when no age is unlocked or the age doesn't override it.
     */
    public int getChopTicksOrDefault(int defaultTicks) {
        if (currentToolAge.isEmpty()) return defaultTicks;
        com.bannerbound.core.api.research.ToolAge age =
            com.bannerbound.core.api.research.data.ToolAgeLoader.get(currentToolAge);
        if (age == null) return defaultTicks;
        return age.chopTicks().orElse(defaultTicks);
    }

    /**
     * Returns the {@code mine_speed} (ticks per digger-mined block) override for the current
     * tool age, or {@code defaultTicks} when no age is unlocked or the age doesn't override it.
     */
    public int getMineTicksOrDefault(int defaultTicks) {
        if (currentToolAge.isEmpty()) return defaultTicks;
        com.bannerbound.core.api.research.ToolAge age =
            com.bannerbound.core.api.research.data.ToolAgeLoader.get(currentToolAge);
        if (age == null) return defaultTicks;
        return age.mineTicks().orElse(defaultTicks);
    }

    /**
     * Returns the {@code harvest_speed} (ticks per farmer till/plant/harvest action) override
     * for the current tool age, or {@code defaultTicks} when no age is unlocked or the age
     * doesn't override it.
     */
    public int getHarvestTicksOrDefault(int defaultTicks) {
        if (currentToolAge.isEmpty()) return defaultTicks;
        com.bannerbound.core.api.research.ToolAge age =
            com.bannerbound.core.api.research.data.ToolAgeLoader.get(currentToolAge);
        if (age == null) return defaultTicks;
        return age.harvestTicks().orElse(defaultTicks);
    }

    /** Half-hearts a citizen's sword deals per swing in {@code CitizenCombatGoal}. Falls back to
     *  {@code defaultDamage} when no age is unlocked or the age has no weapon-damage override. */
    public double getWeaponDamageOrDefault(double defaultDamage) {
        if (currentToolAge.isEmpty()) return defaultDamage;
        com.bannerbound.core.api.research.ToolAge age =
            com.bannerbound.core.api.research.data.ToolAgeLoader.get(currentToolAge);
        return age == null ? defaultDamage : age.weaponDamage();
    }

    /** Swings per second when a citizen wields the age's sword. Combat cooldown =
     *  {@code 20 / value} ticks per swing. Falls back to {@code defaultAttackSpeed} when no age
     *  is unlocked or the age has no attack-speed override. */
    public double getWeaponAttackSpeedOrDefault(double defaultAttackSpeed) {
        if (currentToolAge.isEmpty()) return defaultAttackSpeed;
        com.bannerbound.core.api.research.ToolAge age =
            com.bannerbound.core.api.research.data.ToolAgeLoader.get(currentToolAge);
        return age == null ? defaultAttackSpeed : age.weaponAttackSpeed();
    }

    /**
     * Effective stockpile cap: defaults to exactly the next-citizen requirement (so by default
     * nothing overflows), plus any bonus capacity granted by research like Food Preservation.
     */
    public double foodCap() {
        return nextFoodCost() + bonusFoodCapacity;
    }

    public double cultureCap() {
        return nextCultureCost() + bonusCultureCapacity;
    }

    public Map<Long, Workstation> workstations() { return workstations; }
    public Workstation getWorkstation(BlockPos pos) { return workstations.get(pos.asLong()); }
    public void putWorkstation(Workstation ws) { workstations.put(ws.pos().asLong(), ws); }
    public Workstation removeWorkstation(BlockPos pos) { return workstations.remove(pos.asLong()); }

    /** Returns the workstation a given citizen is assigned to, or null. */
    public Workstation getWorkstationFor(UUID citizenId) {
        if (citizenId == null) return null;
        for (Workstation ws : workstations.values()) {
            if (citizenId.equals(ws.assignedCitizenId())) return ws;
        }
        return null;
    }

    /** Citizens with no workstation assignment â€” the list shown when picking a worker to hire. */
    public List<Citizen> unemployedCitizens() {
        Set<UUID> employed = new HashSet<>();
        for (Workstation ws : workstations.values()) {
            if (ws.assignedCitizenId() != null) employed.add(ws.assignedCitizenId());
        }
        List<Citizen> out = new ArrayList<>();
        for (Citizen c : citizens) {
            if (!employed.contains(c.entityId())) out.add(c);
        }
        return out;
    }

    // â”€â”€â”€ Homes â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Parallel API to the workstation accessors above. Same naming pattern, same persistence
    // shape â€” anything you can do to a workstation you can do to a home.

    public Map<UUID, Home> homes() { return homes; }
    public void putHome(Home h) { homes.put(h.id(), h); }
    public Home removeHome(UUID homeId) { return homeId == null ? null : homes.remove(homeId); }

    /** Lookup by stable home id. The Housing Orders rod stores the bound home's id in its data
     *  components; the box geometry in {@code BlockSelectionRegistry} also keys by id. O(1). */
    public Home getHomeById(UUID homeId) {
        return homeId == null ? null : homes.get(homeId);
    }

    // â”€â”€â”€ Workshops â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Crafter workshops (see CRAFTER_PLAN.md). Keyed by id (no anchor block); the box geometry
    // lives in BlockSelectionRegistry kind WORKSHOP.

    public Map<UUID, Workshop> workshops() { return workshops; }
    public Workshop getWorkshop(UUID workshopId) { return workshopId == null ? null : workshops.get(workshopId); }
    public void putWorkshop(Workshop w) { workshops.put(w.id(), w); }
    public Workshop removeWorkshop(UUID workshopId) { return workshops.remove(workshopId); }

    /** Returns the home a given citizen lives in, or null. */
    public Home getHomeFor(UUID citizenId) {
        if (citizenId == null) return null;
        for (Home h : homes.values()) {
            if (h.residents().contains(citizenId)) return h;
        }
        return null;
    }

    /** Homes that can take at least one more resident â€” used by the citizen auto-assignment
     *  poll in {@code CitizenEntity.aiStep} to find a place to move in. */
    public List<Home> homesWithVacancy() {
        List<Home> out = new ArrayList<>();
        for (Home h : homes.values()) {
            if (h.hasVacancy()) out.add(h);
        }
        return out;
    }

    /** Citizens with no assigned home. Mirrors {@link #unemployedCitizens}. */
    public List<Citizen> homelessCitizens() {
        Set<UUID> housed = new HashSet<>();
        for (Home h : homes.values()) {
            housed.addAll(h.residents());
        }
        List<Citizen> out = new ArrayList<>();
        for (Citizen c : citizens) {
            if (!housed.contains(c.entityId())) out.add(c);
        }
        return out;
    }

    // â”€â”€â”€ Stockpiles â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Parallel API to the home/workstation accessors. Community-storage buildings.

    public Map<Long, Stockpile> stockpiles() { return stockpiles; }
    public Stockpile getStockpile(BlockPos pos) { return stockpiles.get(pos.asLong()); }
    public void putStockpile(Stockpile s) { stockpiles.put(s.pos().asLong(), s); }
    public Stockpile removeStockpile(BlockPos pos) { return stockpiles.remove(pos.asLong()); }

    /** Lookup by stable stockpile id (not pos). O(N) over the stockpile count â€” fine for typical
     *  settlement sizes. */
    public Stockpile getStockpileById(UUID stockpileId) {
        if (stockpileId == null) return null;
        for (Stockpile s : stockpiles.values()) {
            if (stockpileId.equals(s.id())) return s;
        }
        return null;
    }

    public String teamName() { return "civ_" + id.toString().substring(0, 8); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putLong("LanguageSeed", languageSeed);
        tag.putString("FactionName", factionName());
        tag.putInt("Color", color.ordinal());
        tag.putUUID("Owner", owner);
        ListTag memberList = new ListTag();
        for (UUID member : members) {
            memberList.add(NbtUtils.createUUID(member));
        }
        tag.put("Members", memberList);
        long[] claimsArr = new long[claimedChunks.size()];
        int i = 0;
        for (long c : claimedChunks) {
            claimsArr[i++] = c;
        }
        tag.putLongArray("Claims", claimsArr);
        if (!workingClaims.isEmpty()) {
            long[] workArr = new long[workingClaims.size()];
            int wi = 0;
            for (long c : workingClaims) workArr[wi++] = c;
            tag.putLongArray("WorkingClaims", workArr);
        }
        if (!outpostBanners.isEmpty()) {
            ListTag bannerList = new ListTag();
            for (java.util.Map.Entry<Long, BlockPos> e : outpostBanners.entrySet()) {
                CompoundTag ob = new CompoundTag();
                ob.putLong("C", e.getKey());
                ob.putLong("B", e.getValue().asLong());
                bannerList.add(ob);
            }
            tag.put("OutpostBanners", bannerList);
        }
        if (townHallPos != null) {
            CompoundTag th = new CompoundTag();
            th.putInt("X", townHallPos.getX());
            th.putInt("Y", townHallPos.getY());
            th.putInt("Z", townHallPos.getZ());
            tag.put("TownHall", th);
        }
        if (bannerPos != null) {
            CompoundTag fb = new CompoundTag();
            fb.putInt("X", bannerPos.getX());
            fb.putInt("Y", bannerPos.getY());
            fb.putInt("Z", bannerPos.getZ());
            tag.put("FactionBanner", fb);
        }
        if (!bannerDesign.isEmpty()) {
            ListTag designList = new ListTag();
            for (BannerLayer layer : bannerDesign) {
                CompoundTag l = new CompoundTag();
                l.putString("Pattern", layer.patternId());
                l.putInt("Color", layer.colorId());
                designList.add(l);
            }
            tag.put("BannerDesign", designList);
        }
        if (!identityDyeIds.isEmpty()) {
            int[] dyeArr = new int[identityDyeIds.size()];
            for (int di = 0; di < dyeArr.length; di++) dyeArr[di] = identityDyeIds.get(di);
            tag.putIntArray("IdentityDyes", dyeArr);
        }
        tag.putInt("TabletsIssued", tabletsIssued);
        tag.putInt("Age", age.ordinal());
        tag.putInt("ExpansionsUsed", expansionsUsed);
        tag.putInt("ImmigratedCount", immigratedCount);
        tag.putInt("PeakPopulation", peakPopulation);

        // Political state: omit defaults so existing saves stay compact / unchanged when no
        // government has been chosen. Government vote tally is transient (server-only) by
        // design â€” a mid-vote save loses the round, same model as disbandVotes.
        if (governmentType != Government.NONE) {
            tag.putInt("Government", governmentType.ordinal());
        }
        if (chiefPlayerId != null) {
            tag.putUUID("ChiefPlayer", chiefPlayerId);
            tag.putLong("ChiefSinceTick", chiefSinceTick);
        }
        if (codeOfLawsPromptShown) {
            tag.putBoolean("CodeOfLawsPromptShown", true);
        }
        if (faithId != null) {
            tag.putUUID("FaithId", faithId);
        }
        if (devotionStored > 0.0) {
            tag.putDouble("DevotionStored", devotionStored);
        }
        if (faithFoundingUnlocked) {
            tag.putBoolean("FaithFoundingUnlocked", true);
        }
        if (faithRejoinAfterGameTime > 0L) {
            tag.putLong("FaithRejoinAfterGameTime", faithRejoinAfterGameTime);
        }
        if (lastAnnouncedStage != SettlementStage.HEARTH) {
            tag.putInt("AnnouncedStage", lastAnnouncedStage.ordinal());
        }

        ListTag completed = new ListTag();
        for (String r : completedResearches) {
            completed.add(StringTag.valueOf(r));
        }
        tag.put("CompletedResearches", completed);

        CompoundTag progress = new CompoundTag();
        for (Map.Entry<String, Double> e : researchProgress.entrySet()) {
            progress.putDouble(e.getKey(), e.getValue());
        }
        tag.put("ResearchProgress", progress);

        CompoundTag insightCounts = new CompoundTag();
        for (Map.Entry<String, Integer> e : insightCounters.entrySet()) {
            insightCounts.putInt(e.getKey(), e.getValue());
        }
        tag.put("InsightCounters", insightCounts);
        ListTag firedInsightList = new ListTag();
        for (String key : firedInsights) firedInsightList.add(StringTag.valueOf(key));
        tag.put("FiredInsights", firedInsightList);

        ListTag queueList = new ListTag();
        for (String r : researchQueue) {
            queueList.add(StringTag.valueOf(r));
        }
        tag.put("ResearchQueue", queueList);

        if (activeResearch != null) {
            tag.putString("ActiveResearch", activeResearch);
        }
        tag.putDouble("SciencePerSecond", sciencePerSecond);

        // Culture-research persistence â€” same shape as the science block above, separate keys.
        ListTag completedCulture = new ListTag();
        for (String r : completedCultureResearches) completedCulture.add(StringTag.valueOf(r));
        tag.put("CompletedCultureResearches", completedCulture);
        CompoundTag cultureProgress = new CompoundTag();
        for (Map.Entry<String, Double> e : cultureResearchProgress.entrySet()) {
            cultureProgress.putDouble(e.getKey(), e.getValue());
        }
        tag.put("CultureResearchProgress", cultureProgress);
        ListTag cultureQueueList = new ListTag();
        for (String r : cultureResearchQueue) cultureQueueList.add(StringTag.valueOf(r));
        tag.put("CultureResearchQueue", cultureQueueList);
        if (activeCultureResearch != null) {
            tag.putString("ActiveCultureResearch", activeCultureResearch);
        }

        // Policies: active list + opinionated-crowd bonus expiry. Pending change + confirm
        // votes + suggestions are transient (intentionally lost on reload).
        ListTag policyList = new ListTag();
        for (String p : activePolicies) policyList.add(StringTag.valueOf(p));
        tag.put("ActivePolicies", policyList);
        if (policyOpinionatedBonusExpiry > 0L) {
            tag.putLong("PolicyOpinionatedBonusExpiry", policyOpinionatedBonusExpiry);
        }
        // Active palettes â€” only the confirmed list persists (pending change / votes / suggestions
        // are transient, like policies).
        ListTag paletteList = new ListTag();
        for (String p : activePalettes) paletteList.add(StringTag.valueOf(p));
        tag.put("ActivePalettes", paletteList);

        // Labor priorities: ordered gatherer-job list + disabled set + auto-assign flag.
        ListTag laborList = new ListTag();
        for (String j : laborPriority) laborList.add(StringTag.valueOf(j));
        tag.put("LaborPriority", laborList);
        ListTag laborDisabledList = new ListTag();
        for (String j : laborDisabled) laborDisabledList.add(StringTag.valueOf(j));
        tag.put("LaborDisabled", laborDisabledList);
        tag.putBoolean("LaborAutoAssign", laborAutoAssign);
        CompoundTag laborCapsTag = new CompoundTag();
        for (Map.Entry<String, Integer> e : laborCaps.entrySet()) laborCapsTag.putInt(e.getKey(), e.getValue());
        tag.put("LaborCaps", laborCapsTag);
        if (preferredStoragePos != null) tag.putLong("PreferredStorage", preferredStoragePos.asLong());

        ListTag citizensList = new ListTag();
        for (Citizen c : citizens) {
            citizensList.add(c.save());
        }
        tag.put("Citizens", citizensList);
        tag.putDouble("FoodPerSecond", foodPerSecond);
        tag.putDouble("CulturePerSecond", culturePerSecond);
        tag.putDouble("FoodStored", foodStored);
        tag.putDouble("CultureStored", cultureStored);
        tag.putDouble("BonusFoodCapacity", bonusFoodCapacity);
        tag.putDouble("BonusCultureCapacity", bonusCultureCapacity);
        tag.putDouble("BonusCitizenSpeed", bonusCitizenSpeed);
        if (!currentToolAge.isEmpty()) {
            tag.putString("CurrentToolAge", currentToolAge);
        }
        if (!cultureStyles.isEmpty()) {
            ListTag styleList = new ListTag();
            for (String style : cultureStyles) {
                styleList.add(StringTag.valueOf(style));
            }
            tag.put("CultureStyles", styleList);
        }

        ListTag wsList = new ListTag();
        for (Workstation ws : workstations.values()) {
            wsList.add(ws.save());
        }
        tag.put("Workstations", wsList);

        // Homes â€” omit the tag entirely when empty so pre-housing saves stay compact and
        // unmodified by this code path.
        if (!homes.isEmpty()) {
            ListTag homeList = new ListTag();
            for (Home h : homes.values()) {
                homeList.add(h.save());
            }
            tag.put("Homes", homeList);
        }

        // Workshops â€” same compact-when-empty rule as Homes above.
        if (!workshops.isEmpty()) {
            ListTag wkList = new ListTag();
            for (Workshop w : workshops.values()) {
                wkList.add(w.save());
            }
            tag.put("Workshops", wkList);
        }

        // Stockpiles â€” same compact-when-empty rule as Homes above.
        if (!stockpiles.isEmpty()) {
            ListTag spList = new ListTag();
            for (Stockpile s : stockpiles.values()) {
                spList.add(s.save());
            }
            tag.put("Stockpiles", spList);
        }

        if (!journalEntries.isEmpty()) {
            ListTag journalList = new ListTag();
            for (com.bannerbound.core.journal.JournalEntry entry : journalEntries.values()) {
                journalList.add(entry.save());
            }
            tag.put("JournalEntries", journalList);
        }
        if (activeCrisis != null) {
            tag.put("ActiveCrisis", activeCrisis.save());
        }
        if (!completedCrises.isEmpty()) {
            ListTag list = new ListTag();
            for (String id : completedCrises) list.add(StringTag.valueOf(id));
            tag.put("CompletedCrises", list);
        }
        if (!failedCrises.isEmpty()) {
            ListTag list = new ListTag();
            for (String id : failedCrises) list.add(StringTag.valueOf(id));
            tag.put("FailedCrises", list);
        }
        if (!foodSourcePulses.isEmpty()) {
            ListTag sourceList = new ListTag();
            for (FoodSourcePulse pulse : foodSourcePulses) sourceList.add(pulse.save());
            tag.put("FoodSourcePulses", sourceList);
        }
        if (!foodProducedBySource.isEmpty()) {
            CompoundTag producedTag = new CompoundTag();
            for (Map.Entry<String, Double> e : foodProducedBySource.entrySet()) {
                producedTag.putDouble(e.getKey(), e.getValue());
            }
            tag.put("FoodProducedBySource", producedTag);
        }
        if (!outpostAccrued.isEmpty()) {
            CompoundTag oa = new CompoundTag();
            for (Map.Entry<String, Integer> e : outpostAccrued.entrySet()) {
                oa.putInt(e.getKey(), e.getValue());
            }
            tag.put("OutpostAccrued", oa);
        }

        ListTag statusList = new ListTag();
        for (StatusEffect e : statusEffects) {
            statusList.add(e.save());
        }
        tag.put("StatusEffects", statusList);
        return tag;
    }

    public static Settlement load(CompoundTag tag) {
        UUID id = tag.getUUID("Id");
        String name = tag.getString("Name");
        String factionName = tag.contains("FactionName") ? tag.getString("FactionName") : name;
        SettlementColor color = SettlementColor.byIndex(tag.getInt("Color"));
        UUID owner = tag.getUUID("Owner");
        Set<UUID> members = new HashSet<>();
        ListTag memberList = tag.getList("Members", 11);
        for (int i = 0; i < memberList.size(); i++) {
            members.add(NbtUtils.loadUUID(memberList.get(i)));
        }
        if (members.isEmpty()) {
            members.add(owner);
        }
        Set<Long> claims = new LinkedHashSet<>();
        long[] claimsArr = tag.getLongArray("Claims");
        for (long c : claimsArr) {
            claims.add(c);
        }
        BlockPos townHallPos = null;
        if (tag.contains("TownHall")) {
            CompoundTag th = tag.getCompound("TownHall");
            townHallPos = new BlockPos(th.getInt("X"), th.getInt("Y"), th.getInt("Z"));
        }
        // Lifetime documents issued. Capacity is no longer stored â€” it's derived from the era
        // (see tabletCapacity()), so any legacy "TabletCapacity" tag is simply ignored on load.
        int tabletsIssued = tag.contains("TabletsIssued")
            ? tag.getInt("TabletsIssued")
            : (tag.getBoolean("IssuedTablet") ? 1 : 0);
        Era age = tag.contains("Age")
            ? Era.fromOrdinalOrDefault(tag.getInt("Age"))
            : Era.ANCIENT;

        Set<String> completed = new HashSet<>();
        ListTag completedList = tag.getList("CompletedResearches", Tag.TAG_STRING);
        for (int i = 0; i < completedList.size(); i++) {
            completed.add(completedList.getString(i));
        }

        Set<String> knownItems = computeKnownItems(completed);

        Map<String, Double> progress = new HashMap<>();
        if (tag.contains("ResearchProgress")) {
            CompoundTag p = tag.getCompound("ResearchProgress");
            for (String key : p.getAllKeys()) {
                progress.put(key, p.getDouble(key));
            }
        }
        List<String> queue = new ArrayList<>();
        if (tag.contains("ResearchQueue")) {
            ListTag q = tag.getList("ResearchQueue", Tag.TAG_STRING);
            for (int i = 0; i < q.size(); i++) {
                queue.add(q.getString(i));
            }
        }
        String active = tag.contains("ActiveResearch") ? tag.getString("ActiveResearch") : null;
        if (active != null && active.isEmpty()) {
            active = null;
        }
        double sciPerSec = tag.contains("SciencePerSecond")
            ? tag.getDouble("SciencePerSecond")
            : DEFAULT_SCIENCE_PER_SECOND;

        List<Citizen> citizens = new ArrayList<>();
        if (tag.contains("Citizens")) {
            ListTag cList = tag.getList("Citizens", Tag.TAG_COMPOUND);
            for (int i = 0; i < cList.size(); i++) {
                citizens.add(Citizen.load(cList.getCompound(i)));
            }
        }
        double foodPerSec = tag.contains("FoodPerSecond")
            ? tag.getDouble("FoodPerSecond") : DEFAULT_FOOD_PER_SECOND;
        double culturePerSec = tag.contains("CulturePerSecond")
            ? tag.getDouble("CulturePerSecond") : DEFAULT_CULTURE_PER_SECOND;
        double foodStored = tag.contains("FoodStored") ? tag.getDouble("FoodStored") : 0.0;
        double cultureStored = tag.contains("CultureStored") ? tag.getDouble("CultureStored") : 0.0;
        double bonusFoodCap = tag.contains("BonusFoodCapacity") ? tag.getDouble("BonusFoodCapacity") : 0.0;
        double bonusCultureCap = tag.contains("BonusCultureCapacity") ? tag.getDouble("BonusCultureCapacity") : 0.0;
        double bonusCitizenSpd = tag.contains("BonusCitizenSpeed") ? tag.getDouble("BonusCitizenSpeed") : 0.0;
        long languageSeed = tag.contains("LanguageSeed")
            ? tag.getLong("LanguageSeed")
            : com.bannerbound.core.language.SettlementLanguage.deriveSeed(id, name);

        Map<Long, Workstation> workstations = new HashMap<>();
        if (tag.contains("Workstations")) {
            ListTag wsList = tag.getList("Workstations", Tag.TAG_COMPOUND);
            for (int i = 0; i < wsList.size(); i++) {
                Workstation ws = Workstation.load(wsList.getCompound(i));
                workstations.put(ws.pos().asLong(), ws);
            }
        }

        Settlement settlement = new Settlement(id, name, color, owner, members, claims, townHallPos, tabletsIssued, age,
            completed, knownItems, progress, queue, active, sciPerSec,
            citizens, foodPerSec, culturePerSec, foodStored, cultureStored,
            bonusFoodCap, bonusCultureCap, bonusCitizenSpd, workstations, languageSeed);

        settlement.setFactionName(factionName);

        if (tag.contains("InsightCounters")) {
            CompoundTag counts = tag.getCompound("InsightCounters");
            for (String key : counts.getAllKeys()) settlement.insightCounters.put(key, counts.getInt(key));
        }
        ListTag firedInsightList = tag.getList("FiredInsights", Tag.TAG_STRING);
        for (int i = 0; i < firedInsightList.size(); i++) {
            settlement.firedInsights.add(firedInsightList.getString(i));
        }
        if (tag.contains("CurrentToolAge")) {
            settlement.currentToolAge = tag.getString("CurrentToolAge");
        }
        for (long c : tag.getLongArray("WorkingClaims")) {
            settlement.workingClaims.add(c);
        }
        if (tag.contains("OutpostBanners")) {
            ListTag bannerList = tag.getList("OutpostBanners", Tag.TAG_COMPOUND);
            for (int i = 0; i < bannerList.size(); i++) {
                CompoundTag ob = bannerList.getCompound(i);
                settlement.outpostBanners.put(ob.getLong("C"), BlockPos.of(ob.getLong("B")));
            }
        }
        if (tag.contains("FactionBanner")) {
            CompoundTag fb = tag.getCompound("FactionBanner");
            settlement.bannerPos = new BlockPos(fb.getInt("X"), fb.getInt("Y"), fb.getInt("Z"));
        }
        if (tag.contains("BannerDesign")) {
            ListTag designList = tag.getList("BannerDesign", Tag.TAG_COMPOUND);
            for (int i = 0; i < designList.size(); i++) {
                CompoundTag l = designList.getCompound(i);
                settlement.bannerDesign.add(new BannerLayer(l.getString("Pattern"), l.getInt("Color")));
            }
        }
        if (tag.contains("IdentityDyes")) {
            for (int dyeId : tag.getIntArray("IdentityDyes")) settlement.identityDyeIds.add(dyeId);
        } else if (tag.contains("PrimaryDye")) {
            // Legacy fixed-3 save keys (one session's worth) â†’ fold into the list.
            settlement.identityDyeIds.add(tag.getInt("PrimaryDye"));
            if (tag.contains("SecondaryDye")
                    && tag.getInt("SecondaryDye") != tag.getInt("PrimaryDye")) {
                settlement.identityDyeIds.add(tag.getInt("SecondaryDye"));
            }
            if (tag.contains("TertiaryDye")
                    && !settlement.identityDyeIds.contains(tag.getInt("TertiaryDye"))) {
                settlement.identityDyeIds.add(tag.getInt("TertiaryDye"));
            }
        }
        // "ExpansionsInEra" is the pre-carry-over save key; still read it so existing worlds
        // keep their expansion count.
        if (tag.contains("ExpansionsUsed")) {
            settlement.expansionsUsed = tag.getInt("ExpansionsUsed");
        } else if (tag.contains("ExpansionsInEra")) {
            settlement.expansionsUsed = tag.getInt("ExpansionsInEra");
        }
        // Backward compat: settlements saved before the immigration cap shipped have no
        // ImmigratedCount tag â€” count their existing roster as already-immigrated so the cap
        // applies immediately on load (no surprise 7-free-immigrants window).
        if (tag.contains("ImmigratedCount")) {
            settlement.immigratedCount = tag.getInt("ImmigratedCount");
        } else {
            settlement.immigratedCount = settlement.citizens.size();
        }
        // Population high-water mark for the starvation crisis. Old saves (pre-fix) have no tag — seed it
        // from the current roster so the crisis at least demands food for who's here (lost peak history
        // can't be recovered, but new growth re-establishes the mark via notePopulationPeak).
        if (tag.contains("PeakPopulation")) {
            settlement.peakPopulation = tag.getInt("PeakPopulation");
        } else {
            settlement.peakPopulation = settlement.citizens.size();
        }
        if (tag.contains("CultureStyles")) {
            ListTag styleList = tag.getList("CultureStyles", Tag.TAG_STRING);
            for (int i = 0; i < styleList.size(); i++) {
                settlement.cultureStyles.add(styleList.getString(i));
            }
        }
        // Political state â€” tolerant load. Missing tags = defaults (NONE / null / false), so
        // pre-Code-of-Laws saves load unchanged into the new fields.
        if (tag.contains("Government")) {
            int ord = tag.getInt("Government");
            Government[] vals = Government.values();
            if (ord >= 0 && ord < vals.length) {
                settlement.governmentType = vals[ord];
            }
        }
        if (tag.hasUUID("ChiefPlayer")) {
            settlement.chiefPlayerId = tag.getUUID("ChiefPlayer");
            // Missing tick (pre-feature save) â†’ -1 = "no cooldown" so existing chiefs aren't locked.
            settlement.chiefSinceTick = tag.contains("ChiefSinceTick") ? tag.getLong("ChiefSinceTick") : -1L;
        }
        if (tag.contains("AnnouncedStage")) {
            int ord = tag.getInt("AnnouncedStage");
            SettlementStage[] v = SettlementStage.values();
            if (ord >= 0 && ord < v.length) settlement.lastAnnouncedStage = v[ord];
        }
        if (tag.contains("CodeOfLawsPromptShown")) {
            settlement.codeOfLawsPromptShown = tag.getBoolean("CodeOfLawsPromptShown");
        }
        if (tag.hasUUID("FaithId")) {
            settlement.faithId = tag.getUUID("FaithId");
        }
        if (tag.contains("DevotionStored")) {
            settlement.devotionStored = tag.getDouble("DevotionStored");
        }
        if (tag.contains("FaithFoundingUnlocked")) {
            settlement.faithFoundingUnlocked = tag.getBoolean("FaithFoundingUnlocked");
        }
        if (tag.contains("FaithRejoinAfterGameTime")) {
            settlement.faithRejoinAfterGameTime = tag.getLong("FaithRejoinAfterGameTime");
        }
        // Culture-research state â€” pre-Step-8 saves simply have no tags here, which leaves
        // the default-initialised empty collections from the constructor in place.
        if (tag.contains("CompletedCultureResearches")) {
            ListTag cList = tag.getList("CompletedCultureResearches", Tag.TAG_STRING);
            for (int i = 0; i < cList.size(); i++) {
                settlement.completedCultureResearches.add(cList.getString(i));
            }

            // add to knownItems
            for (String cultureResearchID : settlement.completedCultureResearches()) {
                ResearchDefinition def = CultureTreeLoader.get(cultureResearchID);
                if (def != null) settlement.knownItems.addAll(def.unlocksItems());
            }
        }
        if (tag.contains("CultureResearchProgress")) {
            CompoundTag p = tag.getCompound("CultureResearchProgress");
            for (String key : p.getAllKeys()) {
                settlement.cultureResearchProgress.put(key, p.getDouble(key));
            }
        }
        if (tag.contains("CultureResearchQueue")) {
            ListTag q = tag.getList("CultureResearchQueue", Tag.TAG_STRING);
            for (int i = 0; i < q.size(); i++) {
                settlement.cultureResearchQueue.add(q.getString(i));
            }
        }
        if (tag.contains("ActiveCultureResearch")) {
            String ac = tag.getString("ActiveCultureResearch");
            if (ac != null && !ac.isEmpty()) settlement.activeCultureResearch = ac;
        }
        if (tag.contains("ActivePalettes")) {
            ListTag pal = tag.getList("ActivePalettes", Tag.TAG_STRING);
            for (int i = 0; i < pal.size(); i++) {
                settlement.activePalettes.add(pal.getString(i));
            }
        }
        if (tag.contains("ActivePolicies")) {
            ListTag pl = tag.getList("ActivePolicies", Tag.TAG_STRING);
            for (int i = 0; i < pl.size(); i++) {
                settlement.activePolicies.add(pl.getString(i));
            }
        }
        if (tag.contains("LaborPriority")) {
            ListTag ll = tag.getList("LaborPriority", Tag.TAG_STRING);
            for (int i = 0; i < ll.size(); i++) settlement.laborPriority.add(ll.getString(i));
        }
        if (tag.contains("LaborDisabled")) {
            ListTag ld = tag.getList("LaborDisabled", Tag.TAG_STRING);
            for (int i = 0; i < ld.size(); i++) settlement.laborDisabled.add(ld.getString(i));
        }
        settlement.laborAutoAssign = !tag.contains("LaborAutoAssign") || tag.getBoolean("LaborAutoAssign");
        if (tag.contains("LaborCaps")) {
            CompoundTag lc = tag.getCompound("LaborCaps");
            for (String key : lc.getAllKeys()) settlement.laborCaps.put(key, lc.getInt(key));
        }
        if (tag.contains("PreferredStorage")) {
            settlement.preferredStoragePos = BlockPos.of(tag.getLong("PreferredStorage"));
        }
        if (tag.contains("PolicyOpinionatedBonusExpiry")) {
            settlement.policyOpinionatedBonusExpiry = tag.getLong("PolicyOpinionatedBonusExpiry");
        }
        if (tag.contains("StatusEffects")) {
            ListTag statusList = tag.getList("StatusEffects", Tag.TAG_COMPOUND);
            for (int i = 0; i < statusList.size(); i++) {
                settlement.statusEffects.add(StatusEffect.load(statusList.getCompound(i)));
            }
        }
        if (tag.contains("JournalEntries")) {
            ListTag journalList = tag.getList("JournalEntries", Tag.TAG_COMPOUND);
            for (int i = 0; i < journalList.size(); i++) {
                com.bannerbound.core.journal.JournalEntry entry =
                    com.bannerbound.core.journal.JournalEntry.load(journalList.getCompound(i));
                settlement.journalEntries.put(entry.instanceId(), entry);
            }
        }
        if (tag.contains("ActiveCrisis")) {
            settlement.activeCrisis =
                com.bannerbound.core.crisis.CrisisState.load(tag.getCompound("ActiveCrisis"));
        }
        if (tag.contains("CompletedCrises")) {
            ListTag list = tag.getList("CompletedCrises", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) settlement.completedCrises.add(list.getString(i));
        }
        if (tag.contains("FailedCrises")) {
            ListTag list = tag.getList("FailedCrises", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) settlement.failedCrises.add(list.getString(i));
        }
        if (tag.contains("FoodSourcePulses")) {
            ListTag sourceList = tag.getList("FoodSourcePulses", Tag.TAG_COMPOUND);
            for (int i = 0; i < sourceList.size(); i++) {
                FoodSourcePulse pulse = FoodSourcePulse.load(sourceList.getCompound(i));
                if (pulse.remainingTicks() > 0) settlement.foodSourcePulses.add(pulse);
            }
        }
        if (tag.contains("FoodProducedBySource")) {
            CompoundTag producedTag = tag.getCompound("FoodProducedBySource");
            for (String key : producedTag.getAllKeys()) {
                settlement.foodProducedBySource.put(key, producedTag.getDouble(key));
            }
        }
        if (tag.contains("OutpostAccrued")) {
            CompoundTag oa = tag.getCompound("OutpostAccrued");
            for (String key : oa.getAllKeys()) {
                settlement.outpostAccrued.put(key, oa.getInt(key));
            }
        }
        if (tag.contains("Homes")) {
            ListTag homeList = tag.getList("Homes", Tag.TAG_COMPOUND);
            for (int i = 0; i < homeList.size(); i++) {
                Home h = Home.load(homeList.getCompound(i));
                settlement.homes.put(h.id(), h);
            }
        }
        if (tag.contains("Workshops")) {
            ListTag wkList = tag.getList("Workshops", Tag.TAG_COMPOUND);
            for (int i = 0; i < wkList.size(); i++) {
                Workshop w = Workshop.load(wkList.getCompound(i));
                settlement.workshops.put(w.id(), w);
            }
        }
        if (tag.contains("Stockpiles")) {
            ListTag spList = tag.getList("Stockpiles", Tag.TAG_COMPOUND);
            for (int i = 0; i < spList.size(); i++) {
                Stockpile s = Stockpile.load(spList.getCompound(i));
                settlement.stockpiles.put(s.pos().asLong(), s);
            }
        }
        return settlement;
    }

    // â”€â”€â”€ Status effects â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Append a new effect. Lives in insertion order so the Statuses tab can render newest at
     *  the bottom. Caller is responsible for broadcasting via SettlementManager. */
    public void addStatusEffect(StatusEffect effect) {
        statusEffects.add(effect);
    }

    public List<com.bannerbound.core.journal.JournalEntry> journalEntries() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(journalEntries.values()));
    }

    public void putJournalEntry(com.bannerbound.core.journal.JournalEntry entry) {
        if (entry != null) journalEntries.put(entry.instanceId(), entry);
    }

    public boolean removeJournalEntry(UUID instanceId) {
        return journalEntries.remove(instanceId) != null;
    }

    public com.bannerbound.core.journal.JournalEntry journalEntry(UUID instanceId) {
        return journalEntries.get(instanceId);
    }

    public com.bannerbound.core.journal.JournalEntry findJournalEntry(String sourceType, String sourceId) {
        for (com.bannerbound.core.journal.JournalEntry entry : journalEntries.values()) {
            if (entry.sourceType().equals(sourceType) && entry.sourceId().equals(sourceId)) return entry;
        }
        return null;
    }

    public com.bannerbound.core.crisis.CrisisState activeCrisis() {
        return activeCrisis;
    }

    public void setActiveCrisis(com.bannerbound.core.crisis.CrisisState activeCrisis) {
        this.activeCrisis = activeCrisis;
    }

    public Set<String> completedCrises() {
        return java.util.Collections.unmodifiableSet(completedCrises);
    }

    public Set<String> failedCrises() {
        return java.util.Collections.unmodifiableSet(failedCrises);
    }

    public void markCrisisResolved(String crisisId, boolean failed) {
        if (crisisId == null || crisisId.isBlank()) return;
        completedCrises.add(crisisId);
        if (failed) failedCrises.add(crisisId);
    }

    public void setPassiveFoodSourceRate(String source, double rate) {
        if (source == null || source.isBlank()) return;
        if (Math.abs(rate) < 0.000_001) passiveFoodSourceRates.remove(source);
        else passiveFoodSourceRates.put(source, rate);
    }

    public double foodSourceRate(String source) {
        double total = passiveFoodSourceRates.getOrDefault(source, 0.0);
        for (FoodSourcePulse pulse : foodSourcePulses) {
            if (pulse.source().equals(source)) total += pulse.amount();
        }
        return total;
    }

    public Map<String, Double> foodSourceRates() {
        Map<String, Double> rates = new HashMap<>(passiveFoodSourceRates);
        for (FoodSourcePulse pulse : foodSourcePulses) {
            rates.merge(pulse.source(), pulse.amount(), Double::sum);
        }
        return java.util.Collections.unmodifiableMap(rates);
    }

    public boolean tickFoodSourcePulses() {
        if (foodSourcePulses.isEmpty()) return false;
        boolean changed = false;
        for (int i = foodSourcePulses.size() - 1; i >= 0; i--) {
            FoodSourcePulse pulse = foodSourcePulses.get(i);
            FoodSourcePulse next = pulse.tickDown();
            if (next.remainingTicks() <= 0) {
                foodSourcePulses.remove(i);
                changed = true;
            } else {
                foodSourcePulses.set(i, next);
            }
        }
        return changed;
    }

    /** Lifetime running total of food-stuff this settlement has <b>produced</b> from {@code source}
     *  ("farming", "fishing", "livestock", â€¦), credited at the moment of harvest/catch/cull with the
     *  count of food items yielded. A pure statistic â€” it does NOT feed net food (the larder scan is
     *  the live food) â€” read by crisis objectives and town-hall stats. Monotonic; never decreases. */
    public void addFoodProduced(String source, double amount) {
        if (source == null || source.isBlank() || amount <= 0.0) return;
        foodProducedBySource.merge(source, amount, Double::sum);
    }

    /** Lifetime food-stuff produced from {@code source} (0 if that source has produced nothing). */
    public double foodProducedFrom(String source) {
        return foodProducedBySource.getOrDefault(source, 0.0);
    }

    /** Accrue off-screen ore for the outpost in {@code chunk} (clamped at {@code cap} so an
     *  endlessly-away player can't bank an unbounded pile). */
    public void addOutpostAccrued(long chunk, int amount, int cap) {
        if (amount <= 0) return;
        outpostAccrued.merge(Long.toString(chunk), amount, (a, b) -> Math.min(cap, a + b));
    }

    /** Ore currently accrued (undelivered) at the outpost in {@code chunk}. */
    public int outpostAccrued(long chunk) {
        return outpostAccrued.getOrDefault(Long.toString(chunk), 0);
    }

    /** Remove {@code amount} of accrued ore from {@code chunk} once a ghost stocker has DELIVERED it
     *  to the stockpile (decrement-on-delivery, so a haul lost to a restart never loses the ore). */
    public void takeOutpostAccrued(long chunk, int amount) {
        if (amount <= 0) return;
        String k = Long.toString(chunk);
        int next = outpostAccrued.getOrDefault(k, 0) - amount;
        if (next <= 0) outpostAccrued.remove(k); else outpostAccrued.put(k, next);
    }

    /** Read-only snapshot of every outpost's accrued ore (chunk-as-string → count). */
    public Map<String, Integer> outpostAccruedAll() {
        return java.util.Collections.unmodifiableMap(new HashMap<>(outpostAccrued));
    }

    /** Read-only snapshot of every source's lifetime production total (for stats / baselines). */
    public Map<String, Double> foodProducedTotals() {
        return java.util.Collections.unmodifiableMap(new HashMap<>(foodProducedBySource));
    }

    /** Smoothed production rate (food-stuff/sec) from {@code source} right now (0 if idle/unknown). */
    public double foodProductionRate(String source) {
        return source == null ? 0.0 : foodProductionRate.getOrDefault(source, 0.0);
    }

    /** Read-only snapshot of every source's current production rate (food-stuff/sec) for stats. */
    public Map<String, Double> foodProductionRates() {
        return java.util.Collections.unmodifiableMap(new HashMap<>(foodProductionRate));
    }

    /** TOTAL food production flow (food-stuff/sec) summed across every source â€” the whole settlement's
     *  harvest/catch/cull rate into storage, not any one source. Used by the starvation crisis's
     *  food_sustained gate: no single source can reach a full tribe's appetite alone (2 spear fishers
     *  â‰ˆ 0.27 vs a 7-mouth 0.875 food/s), but a mixed food economy can. It's a flow (the EMA of
     *  {@code addFoodProduced}), so a one-time stockpile never passes â€” real, ongoing production does. */
    public double totalFoodProductionRate() {
        double sum = 0.0;
        for (double r : foodProductionRate.values()) sum += r;
        return sum;
    }

    /** Game-ticks net food has been continuously â‰¥0 (0 if it's currently negative). */
    public long netFoodStableTicks(long gameTick) {
        return netFoodOkSinceTick < 0 ? 0L : Math.max(0L, gameTick - netFoodOkSinceTick);
    }

    /** Once-a-second update of the per-source production-rate EMAs and the net-food-stability stamp.
     *  Called from the immigration broadcast tick; both are derived stats, never persisted. */
    public void tickFoodEconomyStats(long gameTick) {
        java.util.Set<String> sources = new HashSet<>(foodProducedBySource.keySet());
        sources.addAll(foodProductionRate.keySet());
        for (String src : sources) {
            double total = foodProducedBySource.getOrDefault(src, 0.0);
            double inst = Math.max(0.0, total - lastProducedSnapshot.getOrDefault(src, total)); // produced this second
            double ema = foodProductionRate.getOrDefault(src, 0.0);
            ema += PRODUCTION_RATE_ALPHA * (inst - ema);
            if (ema < 1.0e-4) foodProductionRate.remove(src); else foodProductionRate.put(src, ema);
            lastProducedSnapshot.put(src, total);
        }
        // storedFoodPerSecond is NOT summed here: it is the PASSIVE stored-food income
        // (storedFoodValue × STORED_FOOD_RATE_PER_VALUE, set by LarderService) — a STOCK measure that
        // feeds the immigration surplus gate, not a production flow. The crisis food_sustained gate uses
        // totalFoodProductionRate() (this per-source flow) instead, so a one-time stockpile can't pass it.
        if (effectiveFoodPerSecond() >= 0.0) {
            if (netFoodOkSinceTick < 0) netFoodOkSinceTick = gameTick;
        } else {
            netFoodOkSinceTick = -1L;
        }
    }

    private void addOrRenewFoodSourcePulse(UUID instanceId, String source, double amount, int ticks) {
        if (source == null || source.isBlank() || ticks <= 0 || Math.abs(amount) < 0.000_001) return;
        foodSourcePulses.removeIf(p -> p.instanceId().equals(instanceId));
        foodSourcePulses.add(new FoodSourcePulse(instanceId, source, amount, ticks));
    }

    /** Add {@code effect}, first removing any existing effect with the same {@code instanceId} â€” so a
     *  per-source effect (keyed by a stable id) is renewed to full duration instead of stacking. */
    public void addOrRenewStatusEffect(StatusEffect effect) {
        statusEffects.removeIf(e -> e.instanceId().equals(effect.instanceId()));
        statusEffects.add(effect);
    }

    /** Remove every active effect with the given translation key (a one-of-a-kind alert keyed by
     *  message, not instance id â€” e.g. the "banner lost" warning cleared when the banner is raised
     *  again). Returns true if any were removed, so callers can decide whether to broadcast. */
    public boolean removeStatusEffectsByKey(String translationKey) {
        return statusEffects.removeIf(e -> e.translationKey().equals(translationKey));
    }

    /** Read-only view for ticker / UI sync. Mutations go through {@link #addStatusEffect} /
     *  {@link #tickStatusEffects}. */
    public List<StatusEffect> statusEffects() {
        return java.util.Collections.unmodifiableList(statusEffects);
    }

    /** Decrement every active effect once and remove expired ones in place. Returns true if the
     *  list changed (an effect was removed), so callers can decide whether to broadcast. */
    public boolean tickStatusEffects() {
        if (statusEffects.isEmpty()) return false;
        boolean changed = false;
        java.util.Iterator<StatusEffect> it = statusEffects.iterator();
        while (it.hasNext()) {
            StatusEffect e = it.next();
            if (e.tickDown()) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    /** Sum of {@code iconValue} for every active effect with the given icon type. Used by the
     *  food/culture/science ticker to apply the bonus from fishing, festivals, etc. */
    public double statusBonusFor(StatusEffectIcon icon) {
        double sum = 0.0;
        for (StatusEffect e : statusEffects) {
            if (e.icon() == icon) sum += e.iconValue();
        }
        return sum;
    }
}
