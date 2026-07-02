package com.bannerbound.core.social;

import net.minecraft.resources.ResourceLocation;

/**
 * Catalogue of every per-citizen Thought the game can produce. Each kind ships its default
 * happiness modifier, its label translation key, and the duration range a fresh thought of this
 * kind rolls into. Centralising the table here means new thoughts are added in one place — the
 * trigger code only refers to the enum value, never to the magic numbers.
 *
 * <p>Two flavours of thought:
 * <ul>
 *   <li><b>Single-instance</b> — only one of this kind can exist at a time per citizen (e.g.
 *       {@link #UNEMPLOYED}, {@link #RECENTLY_RAINED}, the daily chunk-quality thoughts).
 *       Adding refreshes the existing entry instead of stacking.</li>
 *   <li><b>Per-partner</b> — multiple instances coexist as long as the partner UUID differs
 *       ({@link #ARGUMENT_WITH}, {@link #FIGHT_WITH}, {@link #GREAT_CONVERSATION_WITH}). The
 *       {@link Thought#otherUuid()} field disambiguates entries of the same kind.</li>
 * </ul>
 *
 * <p>A {@code minDurationTicks} of {@link #INFINITE_DURATION} means the thought has no expiry
 * and persists until the trigger condition resolves (e.g. {@link #UNEMPLOYED} clears once a
 * workstation is assigned). {@link Thoughts#tick} never decays such thoughts.
 */
public enum ThoughtKind implements ThoughtType {
    // Weather (broadcast to every citizen in the dimension when the weather state transitions)
    RECENTLY_RAINED ("bannerbound.thought.recently_rained",          +2, 2_400, 3_600), // 2-3 min
    RECENTLY_STORMED("bannerbound.thought.recently_stormed",         -5, 3_600, 4_800), // 3-4 min

    // Outpost lodging (applied by SleepGoal when a worker beds down at an outpost — a roofed bed
    // on wild land beats walking home every night, but it's no substitute for a real house)
    ROUGH_LODGING   ("bannerbound.thought.rough_lodging",            -4, 10_000, 14_000), // ~half a day

    // Conversation outcomes (added on RESOLVING based on the match count)
    ARGUMENT_WITH          ("bannerbound.thought.argument_with",            -5, 1_200, 2_400), // 1-2 min
    FIGHT_WITH             ("bannerbound.thought.fight_with",              -10, 2_400, 3_600), // 2-3 min
    GREAT_CONVERSATION_WITH("bannerbound.thought.great_conversation_with",  +5, 1_200, 2_400), // 1-2 min

    // Job state (infinite — kept while the citizen has no workstation assigned)
    // -1 sentinel is the same value as INFINITE_DURATION below; inlined here because Java
    // initialises enum constants before static fields, so the constant isn't visible yet.
    // ESCALATING: starts at -5 (a mild "I'd like to be useful") and deepens to -30 over two
    // in-game days of continued joblessness — an ignored grievance festers (see modifierAt).
    UNEMPLOYED("bannerbound.thought.unemployed", -5, -1, -1, -30, 48_000),

    // Daily chunk-quality (one of these four applied at dawn if >75% of yesterday's samples
    // landed in the matching beauty bracket; lasts one full day = 24000 ticks)
    LIKE_HERE         ("bannerbound.thought.like_here",          +5, 24_000, 24_000),
    LOVE_HERE         ("bannerbound.thought.love_here",         +15, 24_000, 24_000),
    UNCOMFORTABLE_HERE("bannerbound.thought.uncomfortable_here",-10, 24_000, 24_000),
    HATE_HERE         ("bannerbound.thought.hate_here",         -25, 24_000, 24_000),

    // Per-home thoughts. Daily eval reads the home's appeal (NOT the chunk's) and applies one
    // of LIKE/LOVE/UNCOMFORTABLE/HATE_HOME mirroring the *_HERE buckets. NO_HOME is infinite
    // (kept while the citizen has no assigned home, just like UNEMPLOYED). NICE_HOME is the
    // user-spec'd "Nice housing" label — same +5 as LIKE_HOME, lets the daily eval emit a
    // distinct label later if play-testing wants to split them.
    // ESCALATING: -5 the day they go homeless, deepening to -40 over two in-game days. This is
    // the "starts at -5 but accumulates to -40 if not addressed quickly" grievance — the engine
    // behind the "Citizens demand housing" crisis.
    NO_HOME           ("bannerbound.thought.no_home",            -5, -1, -1, -40, 48_000),
    NICE_HOME         ("bannerbound.thought.nice_home",          +5, 24_000, 24_000),
    LIKE_HOME         ("bannerbound.thought.like_home",          +5, 24_000, 24_000),
    LOVE_HOME         ("bannerbound.thought.love_home",         +15, 24_000, 24_000),
    UNCOMFORTABLE_HOME("bannerbound.thought.uncomfortable_home",-10, 24_000, 24_000),
    HATE_HOME         ("bannerbound.thought.hate_home",         -25, 24_000, 24_000),

    // Settlement-wide research: broadcast to every citizen when ResearchManager.completeResearch
    // fires. 5 in-game minutes (6000 ticks) is short enough that a busy research queue feels
    // sustained but doesn't make a long-idle settlement permanently +5.
    PROGRESSED_RECENTLY("bannerbound.thought.progressed_recently", +5, 6_000, 6_000),

    // Per-partner — added to mother + father at birth, keyed to the newborn's UUID so a couple
    // who has several children stacks one entry per child (the per-partner kind below).
    MY_CHILD_BORN("bannerbound.thought.my_child_born", +25, 24_000, 24_000), // 20 min

    // Settlement-wide birth broadcast: every citizen gets the same single-instance thought.
    NEW_CHILD_IN_SETTLEMENT("bannerbound.thought.new_child_in_settlement", +15, 12_000, 12_000), // 10 min

    // Random "child" thoughts — rolled in the per-tick child poll. Each lasts 3 min; chance per
    // 1-min roll is 20%, so on average ~1 active at a time. New flavours just need a new enum
    // entry + lang key; the poll picks uniformly from CHILD_FLAVOUR_THOUGHTS below.
    CHILD_SAW_BIRD     ("bannerbound.thought.child_saw_bird",      +5, 3_600, 3_600),
    CHILD_CURIOUS      ("bannerbound.thought.child_curious",       +5, 3_600, 3_600),
    CHILD_MUDDY_PUDDLE ("bannerbound.thought.child_muddy_puddle",  +5, 3_600, 3_600),
    CHILD_FOUND_PEBBLE ("bannerbound.thought.child_found_pebble",  +5, 3_600, 3_600),

    // Per-partner death thoughts — added in CitizenLifecycleEvents.onCitizenDeath to every
    // citizen who had a relationship with the deceased. The tier of that relationship picks
    // which of these kinds fires (see BabyMakingManager / lifecycle dispatch tables). All 5 min
    // duration. The dead citizen's bare-string name is captured in the Thought's
    // savedPartnerName field since the entity is gone by the time the Thoughts screen renders.
    DIED_RECENTLY            ("bannerbound.thought.died_recently",             -5,  6_000, 6_000),
    FRIEND_DIED              ("bannerbound.thought.friend_died",              -10,  6_000, 6_000),
    CLOSE_FRIEND_DIED        ("bannerbound.thought.close_friend_died",        -15,  6_000, 6_000),
    FRIEND_FOR_LIFE_DIED     ("bannerbound.thought.friend_for_life_died",     -20,  6_000, 6_000),
    FAMILY_DIED              ("bannerbound.thought.family_died",              -30,  6_000, 6_000),

    // Food state — driven by the per-citizen aiStep food-thought dispatch. STARVING is infinite
    // (held while foodStored == 0 and consumption > 0); WAS_STARVING_RECENTLY one-shots on the
    // transition back to "has food" and lasts 2 min. EATING_WELL / EATING_VERY_WELL are also
    // infinite — the poll adds/removes them in lockstep with the current production-vs-
    // consumption rate so they reflect the live state without needing per-tick refreshes.
    // ESCALATING: -10 immediately, plunging to -40 over a single in-game day — empty bellies
    // turn ugly fast, much faster than homelessness or joblessness.
    STARVING                  ("bannerbound.thought.starving",                 -10,    -1,    -1, -40, 24_000),
    WAS_STARVING_RECENTLY     ("bannerbound.thought.was_starving_recently",    -5,  2_400, 2_400),
    EATING_WELL               ("bannerbound.thought.eating_well",              +5,    -1,    -1),
    EATING_VERY_WELL          ("bannerbound.thought.eating_very_well",        +10,    -1,    -1),

    // Health state — driven by the per-citizen aiStep injury-thought poll. Both infinite; the
    // poll keeps the active kind in lockstep with the live HP ratio (<50% → BADLY_INJURED,
    // 50–75% → IN_PAIN, ≥75% → neither). Only one of the two is ever active at a time.
    I_M_BADLY_INJURED         ("bannerbound.thought.badly_injured",            -10,    -1,    -1),
    I_M_IN_PAIN               ("bannerbound.thought.in_pain",                   -3,    -1,    -1),

    // Step 12 — low-compliance refusals. All three suppress work (WorkGoal.canUse() checks
    // for them) while active. NO_WORK_AS_JOB is per-partner so a citizen can refuse Forester
    // jobs while still accepting Farmer assignments later in the same minute.
    NO_WORK_RIGHT_NOW("bannerbound.thought.no_work_right_now", -2, 1_200, 1_200),  // 1 min
    NO_WORK_AS_JOB   ("bannerbound.thought.no_work_as_job",    -2, 1_200, 1_200),  // 1 min per job
    NO_WORK_TODAY    ("bannerbound.thought.no_work_today",     -5, 24_000, 24_000), // 1 in-game day

    // Policy-driven (infinite — the hourly policy tick adds/removes them in lockstep with the
    // policy's active state). NIGHTSHIFT_FATIGUE only on assigned-job citizens; DOMESTICATION_HAPPY
    // on every citizen while Domestication is active.
    NIGHTSHIFT_FATIGUE ("bannerbound.thought.nightshift_fatigue", -10, -1, -1),
    DOMESTICATION_HAPPY("bannerbound.thought.domestication_happy", +15, -1, -1),
    // Night Watch: only GUARDS carry the weary thought while the policy keeps them up all night
    // (PolicyEffects.syncPolicyThoughts + SleepGoal's guard exemption). Sleep-loss = COMFORT.
    NIGHT_WATCH_WEARY  ("bannerbound.thought.night_watch_weary",   -12, -1, -1),

    // Crafter skill-tier promotion (Novice → Apprentice → …, see QualityMath.skillTierKey): a
    // short pride boost when a crafter levels up. Added by CitizenEntity.addJobXp on tier change.
    PROMOTED("bannerbound.thought.promoted", +10, 6_000, 6_000), // 5 min

    // Workplace appeal (carrot-not-stick: the XP multiplier is the real lever, these are the
    // mood echo). Refreshed by CrafterWorkGoal on each completed craft from the workshop's
    // cached beauty tier — LOVELY at ≥ ATTRACTIVE, DREARY at ≤ UNAPPEALING, neither in between.
    LOVELY_WORKPLACE("bannerbound.thought.lovely_workplace", +5, 6_000, 7_200), // 5-6 min
    DREARY_WORKPLACE("bannerbound.thought.dreary_workplace", -5, 6_000, 7_200), // 5-6 min

    // Apostasy (FAITH_PLAN): the settlement forsook its faith — every citizen grieves.
    // APPEND-ONLY position: ThoughtKind persists by ordinal; never insert above this line.
    FORSOOK_THE_GODS("bannerbound.thought.forsook_the_gods", -8, 48_000, 48_000), // 2 in-game days

    // Scripted crises: appended for ordinal stability. These are short-lived settlement-wide mood
    // echoes so the Thoughts tab reacts to crisis start/resolution without becoming permanent lore.
    CRISIS_STARTED("bannerbound.thought.crisis_started", -8, 24_000, 24_000),
    CRISIS_RESOLVED("bannerbound.thought.crisis_resolved", +8, 12_000, 12_000),

    WAR_WEARINESS("bannerbound.thought.war_weariness", -10, -1, -1),
    RALLYING_SPEECHES("bannerbound.thought.rallying_speeches", -10, -1, -1),
    GLORY_TALES("bannerbound.thought.glory_tales", +10, 24_000, 24_000),

    // Poison (POISON_PLAN): infinite — held while the citizen is poisoned (any stage), cleared once
    // cured. APPEND-ONLY for legacy ordinal saves (key "K"); new saves persist by id (key "KID"), so
    // EXPANSION thoughts no longer go here — they register their own ThoughtType via ThoughtTypes
    // (e.g. Antiquity's grog "enjoyed a drink"). Append new CORE built-ins below this line.
    POISONED("bannerbound.thought.poisoned", -12, -1, -1);

    /** Random-pick pool for the per-tick child thought roll. Same ordering as the enum values
     *  above so {@code values()[i]} matches readers' expectations. */
    public static final ThoughtKind[] CHILD_FLAVOUR_THOUGHTS = {
        CHILD_SAW_BIRD, CHILD_CURIOUS, CHILD_MUDDY_PUDDLE, CHILD_FOUND_PEBBLE
    };


    /** Sentinel for {@code minDurationTicks}/{@code maxDurationTicks} meaning "never expires". */
    public static final int INFINITE_DURATION = -1;

    private final String labelKey;
    private final int modifier;
    private final int minDurationTicks;
    private final int maxDurationTicks;
    /** Worst-case modifier an escalating grievance deepens toward as it ages. Equal to
     *  {@link #modifier} for non-escalating kinds (no ramp). */
    private final int escalationFloor;
    /** Ticks from thought creation to reach {@link #escalationFloor}. {@code 0} = no escalation. */
    private final int escalationRampTicks;

    /** Non-escalating kinds: floor == modifier, ramp == 0 (the common case). */
    ThoughtKind(String labelKey, int modifier, int minDurationTicks, int maxDurationTicks) {
        this(labelKey, modifier, minDurationTicks, maxDurationTicks, modifier, 0);
    }

    /** Escalating kinds (NO_HOME, UNEMPLOYED, STARVING): the modifier deepens linearly from
     *  {@code modifier} to {@code escalationFloor} over {@code escalationRampTicks} ticks of
     *  continuous activity. Ignored grievances fester instead of sitting at a flat nudge. */
    ThoughtKind(String labelKey, int modifier, int minDurationTicks, int maxDurationTicks,
                int escalationFloor, int escalationRampTicks) {
        this.labelKey = labelKey;
        this.modifier = modifier;
        this.minDurationTicks = minDurationTicks;
        this.maxDurationTicks = maxDurationTicks;
        this.escalationFloor = escalationFloor;
        this.escalationRampTicks = escalationRampTicks;
    }

    /** True iff this kind's happiness hit deepens the longer it stays active. */
    public boolean escalates() { return escalationRampTicks > 0 && escalationFloor != modifier; }

    /** The happiness modifier for a thought of this kind that has been active for {@code ageTicks}.
     *  For non-escalating kinds this is just {@link #modifier}; for escalating kinds it ramps
     *  linearly from {@code modifier} (age 0) to {@link #escalationFloor} (age ≥ rampTicks). */
    public int modifierAt(long ageTicks) {
        if (!escalates() || ageTicks <= 0) return modifier;
        if (ageTicks >= escalationRampTicks) return escalationFloor;
        double t = ageTicks / (double) escalationRampTicks;
        return (int) Math.round(modifier + t * (escalationFloor - modifier));
    }

    /** Translation key for the thought label. Social thoughts use {@code %s} for the partner
     *  name; trigger code passes the styled name as a positional arg when building the Component. */
    public String labelKey() { return labelKey; }
    /** Signed happiness delta this thought contributes while active. */
    public int modifier() { return modifier; }
    /** True iff this kind never auto-expires (e.g. {@link #UNEMPLOYED} — cleared on workstation assignment). */
    public boolean isInfinite() { return minDurationTicks == INFINITE_DURATION; }
    /** True iff this kind binds to a specific partner citizen — conversation outcomes plus
     *  the per-child {@link #MY_CHILD_BORN} plus the five {@code *_DIED} death thoughts so
     *  multiple deaths in the same settlement stack instead of refreshing one entry. */
    public boolean isPerPartner() {
        return this == ARGUMENT_WITH || this == FIGHT_WITH || this == GREAT_CONVERSATION_WITH
            || this == MY_CHILD_BORN
            || this == DIED_RECENTLY || this == FRIEND_DIED || this == CLOSE_FRIEND_DIED
            || this == FRIEND_FOR_LIFE_DIED || this == FAMILY_DIED
            // Step 12: the per-job refusal — partner UUID is derived from the job-title id
            // (UUID.nameUUIDFromBytes(jobTitleId.getBytes())) so a Forester refusal doesn't
            // block a later Farmer assignment in the same minute.
            || this == NO_WORK_AS_JOB;
    }

    /** Which happiness pillar this built-in thought feeds (Food/Culture/Comfort/Society). Defaults
     *  here, tunable; the Citizen screen groups thoughts by this into one ring each. */
    @Override
    public HappinessCategory category() {
        return switch (this) {
            case STARVING, WAS_STARVING_RECENTLY, EATING_WELL, EATING_VERY_WELL ->
                HappinessCategory.FOOD;
            case LIKE_HERE, LOVE_HERE, UNCOMFORTABLE_HERE, HATE_HERE,
                 MY_CHILD_BORN, NEW_CHILD_IN_SETTLEMENT,
                 CHILD_SAW_BIRD, CHILD_CURIOUS, CHILD_MUDDY_PUDDLE, CHILD_FOUND_PEBBLE,
                 PROGRESSED_RECENTLY, FORSOOK_THE_GODS, GLORY_TALES ->
                HappinessCategory.CULTURE;
            case RECENTLY_RAINED, RECENTLY_STORMED, ROUGH_LODGING,
                 NO_HOME, NICE_HOME, LIKE_HOME, LOVE_HOME, UNCOMFORTABLE_HOME, HATE_HOME,
                 I_M_BADLY_INJURED, I_M_IN_PAIN, POISONED,
                 CRISIS_STARTED, CRISIS_RESOLVED, WAR_WEARINESS, NIGHT_WATCH_WEARY ->
                HappinessCategory.COMFORT;
            case UNEMPLOYED, NO_WORK_RIGHT_NOW, NO_WORK_AS_JOB, NO_WORK_TODAY,
                 NIGHTSHIFT_FATIGUE, DOMESTICATION_HAPPY, PROMOTED,
                 LOVELY_WORKPLACE, DREARY_WORKPLACE,
                 ARGUMENT_WITH, FIGHT_WITH, GREAT_CONVERSATION_WITH,
                 DIED_RECENTLY, FRIEND_DIED, CLOSE_FRIEND_DIED, FRIEND_FOR_LIFE_DIED, FAMILY_DIED,
                 RALLYING_SPEECHES ->
                HappinessCategory.SOCIETY;
        };
    }

    /** Picks the death-thought kind matching a survivor's relationship tier to the deceased.
     *  Order goes from strongest to weakest so an unrecognised top tier (future Lover) still
     *  falls through to the highest-impact baseline. Negative tiers return {@code null} —
     *  enemies / rivals don't get a happiness hit when their target dies. */
    public static ThoughtKind deathThoughtFor(RelationshipTier tier) {
        return switch (tier) {
            case FAMILY            -> FAMILY_DIED;
            case FRIENDS_FOR_LIFE  -> FRIEND_FOR_LIFE_DIED;
            case CLOSE_FRIENDS     -> CLOSE_FRIEND_DIED;
            case FRIENDS           -> FRIEND_DIED;
            case ACQUAINTANCES, STRANGERS, DISLIKED -> DIED_RECENTLY;
            default -> null; // RIVALS, ENEMIES, HATED — no thought
        };
    }

    /** Rolls a random duration in {@code [min, max]} ticks, or returns -1 for infinite thoughts. */
    public int rollDurationTicks(net.minecraft.util.RandomSource rng) {
        if (isInfinite()) return INFINITE_DURATION;
        if (maxDurationTicks <= minDurationTicks) return minDurationTicks;
        return minDurationTicks + rng.nextInt(maxDurationTicks - minDurationTicks + 1);
    }

    private static final ThoughtKind[] VALUES = values();

    /** Built-ins self-register into {@link ThoughtTypes} so the registry is the single source of truth
     *  for both Core and expansion thoughts. Runs once on class init. */
    static {
        for (ThoughtKind k : VALUES) {
            ThoughtTypes.register(k);
        }
    }

    /** No-op whose only job is to force this enum's class-initialisation (and thus the registration
     *  block above) from {@link ThoughtTypes}, so a save-load that resolves an id before any other
     *  reference still finds the built-ins. */
    public static void bootstrap() {}

    /** Legacy NBT id: {@code bannerbound:<lowercased name>}. Lazily computed; the persistence key. */
    private ResourceLocation id;

    @Override
    public ResourceLocation id() {
        if (id == null) {
            id = ResourceLocation.fromNamespaceAndPath("bannerbound", name().toLowerCase(java.util.Locale.ROOT));
        }
        return id;
    }

    /** Safe ordinal-keyed lookup for LEGACY NBT decode (pre-id saves used key "K"). Returns
     *  {@code null} on invalid input so the caller can drop the malformed entry. */
    public static ThoughtKind fromOrdinal(int ord) {
        return (ord >= 0 && ord < VALUES.length) ? VALUES[ord] : null;
    }
}
